package com.equity.market.candle;

import com.equity.domain.market.Candle;
import com.equity.domain.market.Tick;
import com.equity.domain.market.Timeframe;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Builds 1-minute candles from ticks and aggregates the higher timeframes from them.
 *
 * <p><b>3m/5m/15m are derived from completed 1m candles, never from ticks directly.</b> Building
 * each timeframe independently lets them disagree — a 5m candle whose high exceeds every 1m high
 * inside it, because a tick arrived while one aggregator had rolled and another had not. Deriving
 * upward makes that impossible by construction.</p>
 *
 * <p>Per-candle volume is computed by <b>differencing the exchange's cumulative day volume</b>,
 * not by summing tick volumes. Feeds drop and redeliver ticks; a running sum drifts over a session
 * whereas a difference of two cumulative readings self-corrects.</p>
 *
 * <p>Only COMPLETED candles are published. A forming candle is never handed to the strategy,
 * because acting on a value that can still change is a standard source of live/replay divergence.</p>
 *
 * <p>Thread-safety: one symbol is only ever advanced by the market-data thread. The published
 * history lists are copy-on-publish so reader threads never see a partially built candle.</p>
 */
public final class CandleEngine {

    /** How many completed candles to retain per timeframe. 15m x 400 still covers many sessions. */
    private static final int HISTORY_LIMIT = 400;

    private final Map<String, SymbolBuilder> builders = new ConcurrentHashMap<>();
    private final List<Consumer<Candle>> listeners = new ArrayList<>();

    /** Register a listener for COMPLETED candles of every timeframe. */
    public void onCandleClosed(Consumer<Candle> listener) {
        listeners.add(listener);
    }

    public void onTick(Tick tick) {
        builders.computeIfAbsent(tick.symbol(), SymbolBuilder::new).accept(tick);
    }

    /**
     * Close any bucket whose window has fully elapsed even though no further tick arrived.
     *
     * <p>Without this a stock that stops trading never closes its last candle, so its strategy
     * state silently freezes at the last active minute instead of registering that momentum
     * stopped. Call it once a second from the session scheduler.</p>
     */
    public void closeStaleBuckets(Instant now) {
        builders.values().forEach(b -> b.closeIfElapsed(now));
    }

    public List<Candle> history(String symbol, Timeframe tf) {
        SymbolBuilder b = builders.get(symbol);
        return b == null ? List.of() : b.history(tf);
    }

    public Optional<Candle> lastClosed(String symbol, Timeframe tf) {
        List<Candle> h = history(symbol, tf);
        return h.isEmpty() ? Optional.empty() : Optional.of(h.get(h.size() - 1));
    }

    private void publish(Candle c) {
        for (Consumer<Candle> l : listeners) {
            try {
                l.accept(c);
            } catch (RuntimeException ex) {
                // A misbehaving listener must not stop the candle stream for every other consumer.
                LoggerHolder.LOG.warn("candle listener failed for {} {}: {}",
                        c.symbol(), c.timeframe(), ex.toString());
            }
        }
    }

    private static final class LoggerHolder {
        static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(CandleEngine.class);
    }

    /** All per-symbol state. Mutated only by the feed thread. */
    private final class SymbolBuilder {
        private final String symbol;
        private final Map<Timeframe, List<Candle>> closed = new EnumMap<>(Timeframe.class);

        private Instant bucketStart;
        private double open, high, low, close;
        private long bucketStartCumulativeVolume = -1;
        private long lastCumulativeVolume = -1;
        private boolean hasBucket;

        SymbolBuilder(String symbol) {
            this.symbol = symbol;
            for (Timeframe tf : Timeframe.values()) closed.put(tf, new ArrayList<>());
        }

        void accept(Tick t) {
            Instant minute = t.exchangeTime().truncatedTo(ChronoUnit.MINUTES);

            if (!hasBucket) {
                start(minute, t);
                return;
            }
            if (minute.isAfter(bucketStart)) {
                closeBucket();
                start(minute, t);
                return;
            }
            if (minute.isBefore(bucketStart)) {
                return;   // late tick for a bucket already closed — dropping beats corrupting it
            }
            high = Math.max(high, t.lastPrice());
            low = Math.min(low, t.lastPrice());
            close = t.lastPrice();
            lastCumulativeVolume = t.cumulativeVolume();
        }

        void closeIfElapsed(Instant now) {
            if (hasBucket && now.isAfter(bucketStart.plus(Duration.ofMinutes(1)))) {
                closeBucket();
                hasBucket = false;
            }
        }

        private void start(Instant minute, Tick t) {
            bucketStart = minute;
            open = high = low = close = t.lastPrice();
            // First bucket of the day has no earlier reading to difference against.
            bucketStartCumulativeVolume = lastCumulativeVolume >= 0
                    ? lastCumulativeVolume : t.cumulativeVolume();
            lastCumulativeVolume = t.cumulativeVolume();
            hasBucket = true;
        }

        private void closeBucket() {
            long volume = Math.max(0, lastCumulativeVolume - bucketStartCumulativeVolume);
            Candle c = new Candle(symbol, Timeframe.M1, bucketStart, open, high, low, close, volume);
            append(Timeframe.M1, c);
            publish(c);
            aggregateUpward(c);
        }

        /**
         * Roll completed 1m candles into 3m/5m/15m. A higher candle closes only when the wall-clock
         * bucket it belongs to is complete AND every constituent minute is present — a gap in the
         * 1m series must not silently produce a short higher-timeframe candle.
         */
        private void aggregateUpward(Candle justClosed) {
            for (Timeframe tf : new Timeframe[]{Timeframe.M3, Timeframe.M5, Timeframe.M15}) {
                long epochMin = justClosed.startTime().getEpochSecond() / 60;
                long bucketIndex = epochMin / tf.minutes();
                boolean lastMinuteOfBucket = (epochMin + 1) % tf.minutes() == 0;
                if (!lastMinuteOfBucket) continue;

                Instant bucketStartTime = Instant.ofEpochSecond(bucketIndex * tf.minutes() * 60L);
                List<Candle> ones = closed.get(Timeframe.M1);
                List<Candle> members = new ArrayList<>();
                for (int i = ones.size() - 1; i >= 0 && members.size() < tf.minutes(); i--) {
                    Candle c = ones.get(i);
                    if (c.startTime().isBefore(bucketStartTime)) break;
                    members.add(0, c);
                }
                if (members.size() != tf.minutes()) continue;   // incomplete bucket — skip it

                double o = members.get(0).open();
                double h = members.stream().mapToDouble(Candle::high).max().orElse(o);
                double l = members.stream().mapToDouble(Candle::low).min().orElse(o);
                double cl = members.get(members.size() - 1).close();
                long v = members.stream().mapToLong(Candle::volume).sum();

                Candle agg = new Candle(symbol, tf, bucketStartTime, o, h, l, cl, v);
                append(tf, agg);
                publish(agg);
            }
        }

        private void append(Timeframe tf, Candle c) {
            List<Candle> list = closed.get(tf);
            list.add(c);
            if (list.size() > HISTORY_LIMIT) list.remove(0);
        }

        List<Candle> history(Timeframe tf) {
            return List.copyOf(closed.get(tf));
        }
    }
}
