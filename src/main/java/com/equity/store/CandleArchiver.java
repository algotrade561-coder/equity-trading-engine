package com.equity.store;

import com.equity.platform.time.TradingClock;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Moves finished sessions out of the database and into the archive, one session at a time.
 *
 * <h2>Why</h2>
 * <p>The engine keeps the session's 1-minute bars in the database so a restart resumes rather than
 * warms up. That needs today's bars and nothing older. But the bars are also the only record of the
 * price path, which every question about a stop or a target replays — so they are worth keeping,
 * just not in the trading database, where thirty megabytes a session becomes two gigabytes a
 * quarter sitting next to the positions and the ledger on an eight-gigabyte disk.</p>
 *
 * <h2>The order of operations, and why it is this order</h2>
 * <ol>
 *   <li>Count the session's rows.</li>
 *   <li>Stream them to a gzipped CSV on local disk, counting as they are written.</li>
 *   <li>Refuse to go on if the two counts differ.</li>
 *   <li>Hand the file to the archive, which returns only once the bytes are verifiably at the
 *       destination.</li>
 *   <li>Only now, delete the rows — in one statement, in its own transaction.</li>
 *   <li>Confirm nothing is left for that date.</li>
 * </ol>
 * <p>A failure anywhere before step 5 leaves the database exactly as it was and the session is
 * retried next run. A failure after step 5 cannot lose data because step 4 already succeeded.
 * The run stops at the first session that fails rather than moving on to the next: whatever is
 * wrong is likely wrong for all of them, and one clear error a day beats a hundred.</p>
 *
 * <h2>The hard limit</h2>
 * <p>If the archive is broken for long enough — a revoked permission, a deleted bucket — the
 * table would grow without bound, and the disk filling is a worse outcome than the history being
 * lost. So there is a second, much longer window past which sessions are deleted unarchived, with
 * an ERROR each time. The gap between the two windows is how long there is to notice.</p>
 *
 * <h2>When it runs</h2>
 * <p>Shortly after start-up and then every few hours, but never during market hours: a bulk delete
 * of a hundred and eighty thousand rows is not something to put next to the flush that writes the
 * live session. On the trading box that means once each morning before the open.</p>
 */
public class CandleArchiver {

    private static final Logger log = LoggerFactory.getLogger(CandleArchiver.class);
    private static final LocalTime MARKET_QUIET_FROM = LocalTime.of(9, 10);
    private static final LocalTime MARKET_QUIET_UNTIL = LocalTime.of(15, 35);
    static final String HEADER = "symbol,trading_date,start_time_utc,open,high,low,close,volume";

    private final CandleRepository repository;
    private final CandleArchive archive;
    private final TransactionTemplate readOnly;
    private final TransactionTemplate readWrite;
    private final TradingClock clock;
    private final Path workDirectory;
    private final int retentionDays;
    private final int hardLimitDays;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile String lastOutcome = "not run yet";

    public CandleArchiver(CandleRepository repository, CandleArchive archive,
                          PlatformTransactionManager transactions, TradingClock clock,
                          Path workDirectory, int retentionDays, int hardLimitDays) {
        if (hardLimitDays <= retentionDays) {
            throw new IllegalArgumentException("hard-limit-days (" + hardLimitDays
                    + ") must be greater than retention-days (" + retentionDays + ")");
        }
        this.repository = repository;
        this.archive = archive;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
        this.readWrite = new TransactionTemplate(transactions);
        this.clock = clock;
        this.workDirectory = workDirectory;
        this.retentionDays = retentionDays;
        this.hardLimitDays = hardLimitDays;
        log.info("candle archive: sessions older than {} days go to {}; older than {} days are deleted regardless",
                retentionDays, archive.describe(), hardLimitDays);
    }

    static String fileName(LocalDate tradingDate) {
        return "candles-m1-" + tradingDate + ".csv.gz";
    }

    /** Skips market hours; otherwise {@link #archiveNow()}. What the scheduler calls. */
    public void scheduled() {
        LocalTime now = clock.timeOfDay();
        if (!now.isBefore(MARKET_QUIET_FROM) && now.isBefore(MARKET_QUIET_UNTIL)) return;
        archiveNow();
    }

    /**
     * Archives every session older than the retention window, oldest first.
     *
     * @return the number of sessions archived and deleted in this run
     */
    public int archiveNow() {
        if (!running.compareAndSet(false, true)) return 0;
        try {
            LocalDate today = clock.tradingDate();
            LocalDate cutoff = today.minusDays(retentionDays);
            List<LocalDate> due = repository.tradingDatesBefore(cutoff);
            int done = 0;
            String failure = null;
            for (LocalDate date : due) {
                failure = archiveOne(date);
                if (failure != null) break;
                done++;
            }
            enforceHardLimit(today.minusDays(hardLimitDays));
            if (failure != null) {
                lastOutcome = done + " of " + due.size() + " session(s) archived; " + failure;
            } else if (!due.isEmpty()) {
                lastOutcome = done + " of " + due.size() + " session(s) archived";
            } else {
                lastOutcome = "nothing older than " + cutoff;
            }
            log.info("candle archive run: {}", lastOutcome);
            return done;
        } finally {
            running.set(false);
        }
    }

    public String lastOutcome() { return lastOutcome; }

    /** One session, start to finish. Returns null on success, or why it failed — and the run stops. */
    private String archiveOne(LocalDate date) {
        Path file = null;
        try {
            Files.createDirectories(workDirectory);
            file = workDirectory.resolve(fileName(date) + ".tmp");

            long expected = repository.countByTradingDate(date);
            if (expected == 0) return null;

            long written = writeCsv(date, file);
            if (written != expected) {
                throw new IOException("wrote " + written + " rows for " + date + " but the table holds " + expected);
            }

            String where = archive.store(date, file, written);

            Integer deleted = readWrite.execute(status -> repository.deleteSession(date));
            long left = repository.countByTradingDate(date);
            if (left != 0) {
                // The archive holds the session, so nothing is lost; the rows just come round again.
                log.error("archived {} to {} but {} row(s) remain after deleting {} — will retry",
                        date, where, left, deleted);
                return "rows remain for " + date + " after delete";
            }
            log.info("archived session {}: {} bars ({} KB) -> {}; rows deleted from the database",
                    date, written, Files.size(file) / 1024, where);
            return null;
        } catch (IOException | RuntimeException e) {
            log.error("could not archive session {}: {} — the rows are untouched and will be retried",
                    date, e.toString());
            return "failed on " + date + ": " + e.getMessage();
        } finally {
            if (file != null) {
                try { Files.deleteIfExists(file); } catch (IOException ignored) { /* a stale tmp is harmless */ }
            }
        }
    }

    /** Streams one session to a gzipped CSV. Returns the number of data rows written. */
    private long writeCsv(LocalDate date, Path file) throws IOException {
        long[] count = {0};
        IOException[] failure = {null};
        readOnly.executeWithoutResult(status -> {
            try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(
                         new GZIPOutputStream(Files.newOutputStream(file)), StandardCharsets.UTF_8), 1 << 16);
                 Stream<CandleRepository.CandleRow> rows =
                         repository.streamByTradingDateOrderBySymbolAscStartTimeAsc(date)) {
                out.write(HEADER);
                out.newLine();
                for (var it = rows.iterator(); it.hasNext(); ) {
                    var r = it.next();
                    out.write(r.getSymbol()); out.write(',');
                    out.write(r.getTradingDate().toString()); out.write(',');
                    out.write(r.getStartTime().toString()); out.write(',');
                    out.write(Double.toString(r.getOpen())); out.write(',');
                    out.write(Double.toString(r.getHigh())); out.write(',');
                    out.write(Double.toString(r.getLow())); out.write(',');
                    out.write(Double.toString(r.getClose())); out.write(',');
                    out.write(Long.toString(r.getVolume()));
                    out.newLine();
                    count[0]++;
                }
            } catch (IOException e) {
                failure[0] = e;
            }
        });
        if (failure[0] != null) throw failure[0];
        return count[0];
    }

    private void enforceHardLimit(LocalDate hardCutoff) {
        try {
            List<LocalDate> stale = repository.tradingDatesBefore(hardCutoff);
            if (stale.isEmpty()) return;
            Integer removed = readWrite.execute(status -> repository.deleteSessionsBefore(hardCutoff));
            log.error("HARD LIMIT: deleted {} unarchived bar(s) from {} session(s) older than {} days ({}) "
                            + "because the archive has been failing — that history is gone. Fix the archive.",
                    removed, stale.size(), hardLimitDays, stale);
        } catch (RuntimeException e) {
            log.error("hard-limit prune failed: {}", e.toString());
        }
    }
}
