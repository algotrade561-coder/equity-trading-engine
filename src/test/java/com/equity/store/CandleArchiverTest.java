package com.equity.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.equity.domain.market.Candle;
import com.equity.domain.market.Timeframe;
import com.equity.platform.time.FixedTradingClock;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * A session leaves the database only after it is verifiably in the archive — and not before, not
 * partially, and not twice.
 *
 * <p>In-memory database on purpose: pointed at the configured file this would archive and delete
 * the real sessions.</p>
 */
@SpringBootTest(classes = com.equity.app.EquityApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:candle-archive;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "equity.security.secret-key=test-key-not-a-real-one",
        "equity.auth.google.enabled=false",
        "equity.candles.persist=false",
})
class CandleArchiverTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 12);
    /** 08:30 IST: before the open, when the archiver is allowed to run. */
    private static final Instant NOW = TODAY.atTime(8, 30).atZone(IST).toInstant();

    @Autowired private CandleRepository repository;
    @Autowired private PlatformTransactionManager transactions;

    @TempDir Path temp;

    /** Records every store and can be told to fail, so the ordering can be observed. */
    private final class RecordingArchive implements CandleArchive {
        final List<LocalDate> stored = new ArrayList<>();
        final List<Long> rowsReported = new ArrayList<>();
        final LocalDirectoryCandleArchive real = new LocalDirectoryCandleArchive(temp.resolve("archive"));
        boolean fail;

        @Override public String store(LocalDate tradingDate, Path file, long rows) throws IOException {
            if (fail) throw new IOException("bucket unreachable (test)");
            stored.add(tradingDate);
            rowsReported.add(rows);
            return real.store(tradingDate, file, rows);
        }
        @Override public String describe() { return "recording"; }
    }

    private RecordingArchive archive;
    private FixedTradingClock clock;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        archive = new RecordingArchive();
        clock = new FixedTradingClock(NOW);
    }

    @AfterEach
    void tearDown() {
        repository.deleteAll();
    }

    private CandleArchiver archiver(int retentionDays, int hardLimitDays) {
        return new CandleArchiver(repository, archive, transactions, clock,
                temp.resolve("work"), retentionDays, hardLimitDays);
    }

    /** {@code bars} one-minute bars for {@code symbol} on {@code date}, from 09:15 IST. */
    private void session(LocalDate date, String symbol, int bars) {
        Instant open = date.atTime(9, 15).atZone(IST).toInstant();
        List<CandleEntity> rows = new ArrayList<>();
        for (int i = 0; i < bars; i++) {
            double base = 1000 + i;
            rows.add(new CandleEntity(new Candle(symbol, Timeframe.M1, open.plus(Duration.ofMinutes(i)),
                    base, base + 0.5, base - 0.5, base + 0.25, 1_000L * (i + 1)), date));
        }
        repository.saveAll(rows);
    }

    private static List<String> linesOf(Path gz) throws IOException {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(gz)), StandardCharsets.UTF_8))) {
            return in.lines().toList();
        }
    }

    private Path archived(LocalDate date) {
        return temp.resolve("archive").resolve(String.valueOf(date.getYear())).resolve(CandleArchiver.fileName(date));
    }

    // ── What is archived, and what is left alone ─────────────────────────────

    @Test
    void sessionsOlderThanTheWindowAreArchivedAndRemoved_recentOnesStay() throws IOException {
        LocalDate old = TODAY.minusDays(10);
        LocalDate recent = TODAY.minusDays(3);
        session(old, "RELIANCE", 30);
        session(old, "TCS", 20);
        session(recent, "RELIANCE", 15);
        session(TODAY, "RELIANCE", 5);

        int done = archiver(7, 60).archiveNow();

        assertThat(done).isEqualTo(1);
        assertThat(archive.stored).containsExactly(old);
        assertThat(archive.rowsReported).containsExactly(50L);
        assertThat(repository.countByTradingDate(old)).as("archived session is gone from the table").isZero();
        assertThat(repository.countByTradingDate(recent)).as("inside the window: untouched").isEqualTo(15);
        assertThat(repository.countByTradingDate(TODAY)).as("today: untouched").isEqualTo(5);

        List<String> lines = linesOf(archived(old));
        assertThat(lines.get(0)).isEqualTo(CandleArchiver.HEADER);
        assertThat(lines).hasSize(51);
        // Ordered by symbol then time, so a reader can walk one stock's day without sorting.
        assertThat(lines.get(1)).startsWith("RELIANCE," + old + ",");
        assertThat(lines.get(31)).startsWith("TCS," + old + ",");
        // Every field, exactly as stored.
        assertThat(lines.get(1)).isEqualTo("RELIANCE," + old + ","
                + old.atTime(9, 15).atZone(IST).toInstant() + ",1000.0,1000.5,999.5,1000.25,1000");
    }

    @Test
    void severalOldSessionsGoOldestFirstAndEachIsItsOwnFile() {
        session(TODAY.minusDays(20), "A", 3);
        session(TODAY.minusDays(9), "A", 3);
        session(TODAY.minusDays(8), "A", 3);

        assertThat(archiver(7, 60).archiveNow()).isEqualTo(3);
        assertThat(archive.stored).containsExactly(TODAY.minusDays(20), TODAY.minusDays(9), TODAY.minusDays(8));
        assertThat(repository.count()).isZero();
        assertThat(archived(TODAY.minusDays(20))).exists();
        assertThat(archived(TODAY.minusDays(9))).exists();
        assertThat(archived(TODAY.minusDays(8))).exists();
    }

    @Test
    void theBoundaryDayStaysUntilItIsStrictlyOlderThanTheWindow() {
        session(TODAY.minusDays(7), "A", 3);   // exactly retention days old: still inside
        session(TODAY.minusDays(8), "A", 3);   // one more: out

        archiver(7, 60).archiveNow();

        assertThat(archive.stored).containsExactly(TODAY.minusDays(8));
        assertThat(repository.countByTradingDate(TODAY.minusDays(7))).isEqualTo(3);
    }

    // ── Nothing is deleted unless the archive has it ─────────────────────────

    @Test
    void whenTheArchiveFailsTheRowsAreUntouched() {
        session(TODAY.minusDays(10), "A", 25);
        archive.fail = true;

        CandleArchiver archiver = archiver(7, 60);
        assertThat(archiver.archiveNow()).isZero();

        assertThat(repository.countByTradingDate(TODAY.minusDays(10)))
                .as("a failed upload must leave every row where it was")
                .isEqualTo(25);
        assertThat(archiver.lastOutcome()).contains("failed on " + TODAY.minusDays(10));
        assertThat(temp.resolve("work")).satisfies(w ->
                assertThat(Files.exists(w) ? Files.list(w).toList() : List.of())
                        .as("no temporary file is left behind").isEmpty());

        // And it succeeds on the next run once the archive is back, with nothing lost.
        archive.fail = false;
        assertThat(archiver.archiveNow()).isEqualTo(1);
        assertThat(repository.countByTradingDate(TODAY.minusDays(10))).isZero();
        assertThat(archive.rowsReported).containsExactly(25L);
    }

    @Test
    void aFailureStopsTheRunSoLaterSessionsAreNotAttemptedAgainstABrokenArchive() {
        session(TODAY.minusDays(12), "A", 2);
        session(TODAY.minusDays(11), "A", 2);
        archive.fail = true;

        archiver(7, 60).archiveNow();

        assertThat(archive.stored).isEmpty();
        assertThat(repository.count()).isEqualTo(4);
    }

    @Test
    void runningTwiceDoesNotArchiveTwice() {
        session(TODAY.minusDays(10), "A", 4);
        CandleArchiver archiver = archiver(7, 60);

        assertThat(archiver.archiveNow()).isEqualTo(1);
        assertThat(archiver.archiveNow()).isZero();
        assertThat(archive.stored).hasSize(1);
        assertThat(archiver.lastOutcome()).startsWith("nothing older than");
    }

    // ── The hard limit ───────────────────────────────────────────────────────

    @Test
    void pastTheHardLimitSessionsAreDeletedEvenIfTheArchiveIsBroken() {
        session(TODAY.minusDays(70), "A", 3);   // beyond the hard limit
        session(TODAY.minusDays(10), "A", 3);   // beyond retention, inside the hard limit
        archive.fail = true;

        archiver(7, 60).archiveNow();

        assertThat(repository.countByTradingDate(TODAY.minusDays(70)))
                .as("the disk is protected even when the archive is not").isZero();
        assertThat(repository.countByTradingDate(TODAY.minusDays(10)))
                .as("still inside the hard limit: kept for the archive to retry").isEqualTo(3);
    }

    @Test
    void theHardLimitMustBeLongerThanTheRetentionWindow() {
        assertThatThrownBy(() -> archiver(7, 7)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> archiver(7, 3)).isInstanceOf(IllegalArgumentException.class);
    }

    // ── When it runs ─────────────────────────────────────────────────────────

    @Test
    void theScheduledRunDeclinesDuringMarketHours() {
        session(TODAY.minusDays(10), "A", 3);
        clock.setTo(TODAY.atTime(LocalTime.of(11, 0)).atZone(IST).toInstant());

        archiver(7, 60).scheduled();
        assertThat(archive.stored).as("no bulk delete next to the live session").isEmpty();

        clock.setTo(TODAY.atTime(LocalTime.of(15, 40)).atZone(IST).toInstant());
        archiver(7, 60).scheduled();
        assertThat(archive.stored).containsExactly(TODAY.minusDays(10));
    }

    // ── The local archive's own promise ──────────────────────────────────────

    @Test
    void theLocalArchiveNeverLeavesAPartialFileUnderTheFinalName() throws IOException {
        LocalDirectoryCandleArchive local = new LocalDirectoryCandleArchive(temp.resolve("local"));
        Path src = temp.resolve("src.csv.gz");
        Files.write(src, new byte[]{1, 2, 3, 4, 5});

        String where = local.store(TODAY.minusDays(9), src, 1);

        Path target = Path.of(where);
        assertThat(target).exists();
        assertThat(Files.readAllBytes(target)).containsExactly(1, 2, 3, 4, 5);
        assertThat(Files.list(target.getParent()).map(p -> p.getFileName().toString()))
                .as("the .part is renamed away, not left beside the file")
                .containsExactly(CandleArchiver.fileName(TODAY.minusDays(9)));
    }
}
