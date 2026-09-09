package com.equity.market;

import com.equity.domain.market.Tick;
import com.equity.market.candle.CandleEngine;
import com.equity.market.state.StructureEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The single entry point for market data into the engine.
 *
 * <p>Everything downstream hangs off this one method, in a fixed order: record freshness, build
 * candles, refresh shared structure, then hand the tick to whoever is watching live prices. The
 * order is the point — a stop evaluated before the structure knows the new price would be judging
 * against the previous one.</p>
 *
 * <p>Runs on the feed thread and must stay quick. Consumers that want to do real work register a
 * tick consumer and are expected to return promptly; one slow consumer delays the whole universe,
 * and Kite disconnects a client that falls behind. A consumer that throws is logged and skipped
 * rather than allowed to kill the feed for everyone else.</p>
 */
@Component
public class MarketDataRouter {

    private static final Logger log = LoggerFactory.getLogger(MarketDataRouter.class);

    /**
     * The regular session. Candles are built only between these, and the reason is not tidiness.
     *
     * <p>The feed delivers ticks well outside market hours — the pre-open auction, and stale
     * last-traded prices for hours after the close. Those were being folded into the same 1-minute
     * series the indicators read, and one session produced 29,685 pre-open bars against 187,405
     * session bars. Relative volume is computed against the session average, so tens of thousands of
     * near-empty bars in the denominator inflated it for every stock: {@code minRelativeVolume}
     * refused 17 decisions out of 14,980, a filter doing no filtering. VWAP, the EMA pair and ATR
     * were all skewed by the same bars.</p>
     *
     * <p>Bounded on the tick's own exchange timestamp rather than the clock, so a replay of a taped
     * session draws the same boundary the live session did.</p>
     */
    private static final java.time.LocalTime SESSION_OPEN = java.time.LocalTime.of(9, 15);
    private static final java.time.LocalTime SESSION_CLOSE = java.time.LocalTime.of(15, 30);

    private final AtomicLong ticksOutsideSession = new AtomicLong();

    private final InstrumentFreshness freshness;
    private final CandleEngine candles;
    private final StructureEngine structure;
    private final com.equity.platform.time.TradingClock clock;

    private final List<Consumer<Tick>> tickConsumers = new CopyOnWriteArrayList<>();
    /** The most recent tick per symbol. The only place depth and spread survive after processing. */
    private final java.util.Map<String, Tick> lastTick = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicLong ticksSeen = new AtomicLong();
    private final AtomicLong ticksDropped = new AtomicLong();

    public MarketDataRouter(InstrumentFreshness freshness, CandleEngine candles,
                            StructureEngine structure, com.equity.platform.time.TradingClock clock) {
        this.freshness = freshness;
        this.candles = candles;
        this.structure = structure;
        this.clock = clock;
    }

    /** Register something that reacts to live prices — the exit checks, chiefly. */
    public void onTick(Consumer<Tick> consumer) {
        tickConsumers.add(consumer);
    }

    public void accept(List<Tick> ticks) {
        for (Tick tick : ticks) {
            ticksSeen.incrementAndGet();
            try {
                freshness.record(tick);
                lastTick.put(tick.symbol(), tick);
                // Structure still updates: the day's high, low and previous close come from the
                // exchange's own fields on the tick and are correct whenever they arrive. Only the
                // bar series is bounded, because only it is cumulative.
                structure.onTick(tick);
                if (withinSession(tick)) {
                    candles.onTick(tick);
                } else {
                    ticksOutsideSession.incrementAndGet();
                }
            } catch (RuntimeException e) {
                ticksDropped.incrementAndGet();
                log.error("failed to process tick for {}", tick.symbol(), e);
                continue;
            }
            for (Consumer<Tick> consumer : tickConsumers) {
                try {
                    consumer.accept(tick);
                } catch (RuntimeException e) {
                    log.error("tick consumer {} threw for {}",
                            consumer.getClass().getSimpleName(), tick.symbol(), e);
                }
            }
        }
    }

    /**
     * Closes any candle whose minute has elapsed without a further tick.
     *
     * <p>Called on a timer. Without it an instrument that stops trading never closes its last
     * candle, so its structure silently freezes at the last active minute rather than registering
     * that the move stopped — which reads to the strategy as continuing momentum.</p>
     */
    public void closeStaleBuckets() {
        candles.closeStaleBuckets(clock.now());
    }

    /** The last tick for a symbol, or null. Depth lives here and nowhere else downstream. */
    public Tick lastTick(String symbol) { return lastTick.get(symbol); }

    /**
     * Whether this tick belongs to the regular session.
     *
     * <p>Uses the exchange timestamp, not the local clock: a tick that arrives late still belongs to
     * the minute it was traded in, and a replay must draw the boundary where the live session did.</p>
     */
    private static boolean withinSession(Tick tick) {
        java.time.LocalTime at = tick.exchangeTime()
                .atZone(com.equity.platform.time.TradingClock.IST).toLocalTime();
        return !at.isBefore(SESSION_OPEN) && !at.isAfter(SESSION_CLOSE);
    }

    /** Ticks that arrived outside market hours and were not folded into any bar. */
    public long ticksOutsideSession() { return ticksOutsideSession.get(); }

    public long ticksSeen()    { return ticksSeen.get(); }
    public long ticksDropped() { return ticksDropped.get(); }
    public int consumerCount() { return tickConsumers.size(); }

    /** Snapshot of the registered consumers, for the status endpoint. */
    public List<String> consumerNames() {
        List<String> names = new ArrayList<>(tickConsumers.size());
        for (Consumer<Tick> c : tickConsumers) names.add(c.getClass().getSimpleName());
        return names;
    }
}
