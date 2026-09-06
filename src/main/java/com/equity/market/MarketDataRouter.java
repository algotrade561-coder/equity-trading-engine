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
                candles.onTick(tick);
                structure.onTick(tick);
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
