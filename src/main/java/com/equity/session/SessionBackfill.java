package com.equity.session;

import com.equity.broker.HistoricalDataPort;
import com.equity.domain.market.Candle;
import com.equity.domain.market.Timeframe;
import com.equity.domain.user.UserId;
import com.equity.market.candle.CandleEngine;
import com.equity.market.state.StructureEngine;
import com.equity.market.universe.UniverseService;
import com.equity.platform.time.TradingClock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Loads the session's completed bars after a restart, so the engine does not begin the day over.
 *
 * <h2>The problem this removes</h2>
 * <p>Indicators are derived from the in-memory 1-minute series, which is built from live ticks. A
 * process started at 10:30 therefore believes the session began at 10:30. The obvious cost is a
 * twenty-bar warm-up. The real one is that <b>VWAP is session-cumulative</b>: computed from a series
 * that starts at 10:30 it is not VWAP, it never becomes VWAP, and {@code requireAboveVwap} is a
 * mandatory entry gate — so every decision for the rest of the day is judged against the wrong
 * level, silently. That made restarting during market hours something to avoid, which is a bad
 * property for the operation most likely to follow a configuration change.</p>
 *
 * <h2>How it runs</h2>
 * <p>On its own thread, never on startup's critical path and never on the feed thread. The engine is
 * fully live while this works through the universe; symbols are simply more accurate as it reaches
 * them, and the discovery set goes first because those are the only ones that can produce an entry.</p>
 *
 * <p>Requests are paced. Kite allows three historical calls a second and answers a burst with 429s,
 * so five hundred symbols take a few minutes — which is why the ordering matters more than the
 * total. Seeded bars never republish, so nothing here can run the strategy over stale prices.</p>
 *
 * <p>Historical data is a paid Kite add-on. Without it every request is refused, so availability is
 * probed once and the whole pass is skipped with one clear line rather than five hundred failures.</p>
 */
@Component
public class SessionBackfill {

    private static final Logger log = LoggerFactory.getLogger(SessionBackfill.class);

    /** Kite permits three historical requests a second; this leaves headroom for everything else. */
    private static final long REQUEST_SPACING_MS = 400;

    private final HistoricalDataPort history;
    private final CandleEngine candles;
    private final StructureEngine structure;
    private final UniverseService universe;
    private final TradingClock clock;
    private final boolean enabled;

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicInteger symbolsSeeded = new AtomicInteger();
    private final AtomicInteger barsSeeded = new AtomicInteger();
    private volatile String status = "not started";

    public SessionBackfill(HistoricalDataPort history, CandleEngine candles,
                           StructureEngine structure, UniverseService universe, TradingClock clock,
                           @Value("${equity.backfill.enabled:true}") boolean enabled) {
        this.history = history;
        this.candles = candles;
        this.structure = structure;
        this.universe = universe;
        this.clock = clock;
        this.enabled = enabled;
    }

    public String status()      { return status; }
    public int symbolsSeeded()  { return symbolsSeeded.get(); }
    public boolean isRunning()  { return running.get(); }

    /**
     * Starts a pass for this user, if one is not already running.
     *
     * <p>Called when the feed comes up, which is both after a login and after a restart. Returns
     * immediately; the work happens on a daemon thread so a shutdown mid-pass does not hang.</p>
     */
    public void startFor(UserId userId) {
        if (!enabled) {
            status = "disabled";
            return;
        }
        if (!running.compareAndSet(false, true)) return;

        Thread worker = new Thread(() -> {
            try {
                run(userId);
            } catch (RuntimeException e) {
                status = "failed: " + e.getMessage();
                log.error("session backfill failed: {}", e.toString());
            } finally {
                running.set(false);
            }
        }, "session-backfill");
        worker.setDaemon(true);
        worker.start();
    }

    private void run(UserId userId) {
        LocalDate today = clock.tradingDate();

        if (!history.isHistoryAvailable(userId, today)) {
            status = "unavailable";
            log.warn("historical data is not available for this Kite account, so the session cannot "
                    + "be backfilled. Indicators will be built from live ticks only — which means "
                    + "roughly 20 minutes before any entry can be evaluated, and a VWAP measured "
                    + "from this process's start rather than from the open. Historical data is a "
                    + "paid Kite Connect add-on; enable it to make a restart free.");
            return;
        }

        // The discovery set first: those are the only symbols that can produce an entry, so their
        // accuracy is worth more than the rest of the board's put together.
        Set<String> ordered = new LinkedHashSet<>(universe.discoverySet());
        ordered.addAll(universe.subscribedSymbols());
        ordered.remove(StructureEngine.INDEX_SYMBOL);
        List<String> symbols = new ArrayList<>(ordered);

        log.info("session backfill starting for {} symbol(s), discovery set first", symbols.size());
        status = "running";
        long started = System.nanoTime();

        for (String symbol : symbols) {
            try {
                List<Candle> minutes = history.intradayMinutes(userId, symbol, today);
                if (!minutes.isEmpty()) {
                    int added = candles.seed(symbol, Timeframe.M1, minutes);
                    if (added > 0) {
                        // Seeding is silent by design, so nothing else would recompute the
                        // indicators and the state would still describe the truncated series.
                        structure.rebuild(symbol);
                        symbolsSeeded.incrementAndGet();
                        barsSeeded.addAndGet(added);
                    }
                }
            } catch (RuntimeException e) {
                log.debug("backfill skipped {}: {}", symbol, e.getMessage());
            }

            try {
                Thread.sleep(REQUEST_SPACING_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                status = "interrupted after " + symbolsSeeded.get() + " symbol(s)";
                return;
            }
        }

        long seconds = (System.nanoTime() - started) / 1_000_000_000L;
        status = String.format("complete: %d symbols, %d bars, %ds",
                symbolsSeeded.get(), barsSeeded.get(), seconds);
        log.info("session backfill complete — {} symbol(s) seeded with {} bar(s) in {}s. Indicators "
                + "now reflect the whole session, not just this process's lifetime.",
                symbolsSeeded.get(), barsSeeded.get(), seconds);
    }
}
