package com.equity.store;

import com.equity.domain.market.Candle;
import com.equity.domain.market.Timeframe;
import com.equity.market.candle.CandleEngine;
import com.equity.market.state.StructureEngine;
import com.equity.platform.time.TradingClock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the session's 1-minute bars, so a restart resumes rather than starts over.
 *
 * <h2>Why this and not the broker</h2>
 * <p>The obvious fix for the warm-up is to fetch the session's history from Kite, and the engine
 * will do that when the add-on is present. But the data was already here: every one of these bars
 * was built by this process from the tick stream and then discarded at shutdown. Paying a broker to
 * sell back something we had and threw away is the wrong trade, and it leaves the engine unable to
 * recover on an account without the subscription — which is the account it is running on.</p>
 *
 * <p>It is also strictly better in one respect. Broker history needs an authenticated session, so it
 * cannot run until after the login; these bars are on local disk and are restored at startup, before
 * the feed is even connected.</p>
 *
 * <h2>What a restart gets back</h2>
 * <p>Only what this engine saw. A process starting for the first time at 11:00 has no earlier bars
 * to restore and still warms up — that case genuinely needs broker history. But the case that
 * actually happens, restarting a process that has been running since the open, is fully covered:
 * indicators come back complete, VWAP included, and the twenty-bar wait disappears.</p>
 *
 * <h2>Off the feed thread</h2>
 * <p>Closed bars are queued and written by the scheduler. A database write on the tick path would
 * stall every instrument behind it, and Kite drops a client that falls behind.</p>
 */
@Component
public class SessionCandleStore {

    private static final Logger log = LoggerFactory.getLogger(SessionCandleStore.class);

    private final CandleRepository repository;
    private final CandleEngine candles;
    private final StructureEngine structure;
    private final TradingClock clock;
    private final boolean enabled;
    private final int retentionDays;

    private final ConcurrentLinkedQueue<Candle> pending = new ConcurrentLinkedQueue<>();
    private final AtomicInteger saved = new AtomicInteger();
    /**
     * Bars already on disk, as {@code symbol|epochSecond}.
     *
     * <p>Duplicates are ordinary, not exceptional: a restart restores a minute that then closes
     * again live, and a bucket can be closed by either the feed thread or the stale sweep. A unique
     * constraint is the right guard, but it must not be the mechanism — one collision failed the
     * whole batch and lost five hundred good bars with it.</p>
     */
    private final java.util.Set<String> onDisk = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile int restored;

    public SessionCandleStore(CandleRepository repository, CandleEngine candles,
                              StructureEngine structure, TradingClock clock,
                              @Value("${equity.candles.persist:true}") boolean enabled,
                              @Value("${equity.candles.retention-days:90}") int retentionDays) {
        this.repository = repository;
        this.candles = candles;
        this.structure = structure;
        this.clock = clock;
        this.enabled = enabled;
        this.retentionDays = retentionDays;

        if (enabled) {
            // Registered here rather than in the orchestrator so persistence cannot be forgotten
            // when the wiring is next rearranged.
            candles.onCandleClosed(this::record);
        }
    }

    public int restoredCount() { return restored; }
    public int savedCount()    { return saved.get(); }

    /** Queued, never written here — this runs on the feed thread. */
    private void record(Candle candle) {
        if (candle.timeframe() == Timeframe.M1) pending.add(candle);
    }

    private static String key(String symbol, java.time.Instant startTime) {
        return symbol + "|" + startTime.getEpochSecond();
    }

    // ── Restore ──────────────────────────────────────────────────────────────

    /**
     * Loads today's bars back into the engine, before the feed connects.
     *
     * <p>Seeding is silent, so nothing is replayed through the strategy — these bars describe
     * decisions that were already made or already missed. The indicators are then recomputed per
     * symbol, because a seeded bar publishes no event and nothing else would.</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional(readOnly = true)
    public void restoreToday() {
        if (!enabled) return;
        LocalDate today = clock.tradingDate();

        List<CandleEntity> rows;
        try {
            rows = repository.findByTradingDateOrderByStartTimeAsc(today);
        } catch (RuntimeException e) {
            log.error("could not read stored candles: {} — the session will warm up from ticks",
                    e.getMessage());
            return;
        }
        if (rows.isEmpty()) {
            log.info("no stored bars for {} — this is the first run of the session, so indicators "
                    + "will build from live ticks", today);
            return;
        }

        Map<String, List<Candle>> bySymbol = new LinkedHashMap<>();
        for (CandleEntity row : rows) {
            bySymbol.computeIfAbsent(row.symbol(), s -> new ArrayList<>()).add(row.toCandle());
        }

        int seeded = 0;
        for (var entry : bySymbol.entrySet()) {
            entry.getValue().forEach(c -> onDisk.add(key(entry.getKey(), c.startTime())));
            int added = candles.seed(entry.getKey(), Timeframe.M1, entry.getValue());
            if (added > 0) {
                structure.rebuild(entry.getKey());
                seeded++;
            }
        }
        restored = seeded;

        int deepest = bySymbol.values().stream().mapToInt(List::size).max().orElse(0);
        log.info("restored {} bar(s) across {} symbol(s) from this session — up to {} minutes of "
                        + "history per symbol, so indicators and VWAP resume rather than restart",
                rows.size(), seeded, deepest);
        if (deepest < 20) {
            log.warn("the deepest symbol has only {} bar(s); the strategy needs 20 before it will "
                    + "evaluate anything, so a short wait remains", deepest);
        }
    }

    // ── Write ────────────────────────────────────────────────────────────────

    /**
     * Appends whatever closed since the last pass.
     *
     * <p>Deliberately not {@code @Transactional}. Under one transaction a single duplicate marks the
     * whole thing rollback-only, so catching the exception achieves nothing — the commit throws
     * anyway and every bar in the batch is lost. That is what happened: five hundred good bars
     * discarded because one symbol's minute was already stored.</p>
     */
    @Scheduled(fixedDelay = 5_000)
    public void flush() {
        if (!enabled || pending.isEmpty()) return;
        LocalDate today = clock.tradingDate();

        List<CandleEntity> batch = new ArrayList<>();
        for (Candle c = pending.poll(); c != null && batch.size() < 5_000; c = pending.poll()) {
            // Filtered here rather than left to the constraint: a bar already stored is the normal
            // case after a restart, not an error worth a round trip to find out.
            if (onDisk.add(key(c.symbol(), c.startTime()))) batch.add(new CandleEntity(c, today));
        }
        if (batch.isEmpty()) return;

        try {
            saveBatch(batch);
            saved.addAndGet(batch.size());
        } catch (RuntimeException e) {
            // The batch failed as a unit, so retry it one bar at a time: whatever collided is one
            // row, and the rest are perfectly good.
            int written = saveIndividually(batch);
            saved.addAndGet(written);
            log.warn("batch insert of {} bar(s) failed ({}); {} written individually",
                    batch.size(), firstLine(e), written);
        }
    }

    /** Constraint violations arrive as a page of SQL; the first line is the part worth logging. */
    private static String firstLine(RuntimeException e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) return e.getClass().getSimpleName();
        int newline = message.indexOf(System.lineSeparator());
        return newline < 0 ? message : message.substring(0, newline);
    }

    @Transactional
    protected void saveBatch(List<CandleEntity> batch) {
        repository.saveAll(batch);
    }

    /**
     * Writes each bar in its own transaction so one collision cannot take the others with it.
     *
     * <p>Slow, and meant to be — it runs only after a batch has already failed, which should be
     * rare now that duplicates are filtered before they get here.</p>
     */
    private int saveIndividually(List<CandleEntity> batch) {
        int written = 0;
        for (CandleEntity row : batch) {
            try {
                saveOne(row);
                written++;
            } catch (RuntimeException ignored) {
                // Already stored. The row on disk is as good as the one being written.
            }
        }
        return written;
    }

    @Transactional
    protected void saveOne(CandleEntity row) {
        repository.save(row);
    }

    /**
     * Old sessions are pruned, but far later than a restart needs them.
     *
     * <p>Five days was the original window, chosen for what a restart requires — which is a day.
     * That is the wrong criterion: every question worth asking of this engine a month from now needs
     * the price path. Whether a stop was too tight, whether a target left money behind, what a
     * different exit policy would have returned — all of them replay the bars, and none of them can
     * be answered from a position row. At roughly 25MB a session, ninety days costs about two
     * gigabytes and buys the only evidence there is.</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void prune() {
        if (!enabled) return;
        try {
            long removed = repository.deleteByTradingDateBefore(
                    clock.tradingDate().minusDays(retentionDays));
            if (removed > 0) log.info("pruned {} bar(s) older than {} days", removed, retentionDays);
        } catch (RuntimeException e) {
            log.warn("could not prune old candles: {}", e.getMessage());
        }
    }
}
