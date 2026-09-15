package com.equity.market.state;

import com.equity.domain.market.Candle;
import com.equity.domain.market.SharedInstrumentState;
import com.equity.domain.market.Timeframe;
import com.equity.market.candle.CandleEngine;
import com.equity.market.indicator.Indicators;
import com.equity.platform.time.TradingClock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * What kind of day it is: the index's move and the market's breadth, as one snapshot.
 *
 * <h2>Why this exists</h2>
 * <p>The strategy judges every stock against the index over fifteen minutes and against its own
 * VWAP, day high and volume — and knows nothing about the tape as a whole. On 15 September 2026 the
 * index fell one per cent from the open in a straight line, a fifth of the universe was green, and
 * the engine took three longs and lost all three. Each passed every gate. Nothing in the journal
 * recorded that four out of five stocks were red when they were taken, so the question "does this
 * strategy work on a down day" could not be answered from the record. This is the record.</p>
 *
 * <h2>What it is not</h2>
 * <p>A gate. Nothing reads it to decide anything; it is written into the journal beside every
 * decision so the decision can later be judged against the day it was made on. If a market gate is
 * ever added, this is the input it will read, and the journal will already say what it would have
 * done.</p>
 *
 * <h2>Cost</h2>
 * <p>A pass over the five hundred instrument states, at most once a second — the snapshot is cached
 * and re-used within the second, because the journal asks for it on every rejection and a busy
 * minute has hundreds.</p>
 */
@Component
public class MarketContext {

    /** The market as of {@code at}. NaN means "not known" throughout; a caller must not read 0 as flat. */
    public record Snapshot(
            Instant at,
            /** The index's last price, or NaN before its first tick. */
            double indexLast,
            /** The index's open — the first bar's open — or NaN before the first bar closes. */
            double indexOpen,
            /** Index change from its open, per cent. */
            double indexChangeFromOpenPct,
            /** Index return over the last fifteen completed minutes, per cent. */
            double indexReturn15mPct,
            /** Index return over the last sixty completed minutes, per cent (NaN in the first hour). */
            double indexReturn60mPct,
            /** Instruments with a known previous close — the denominator of the breadth figures. */
            int universe,
            /** Share of the universe trading above its previous close, per cent. */
            double pctUpOnDay,
            /** Share of the universe trading above its own session VWAP, per cent. */
            double pctAboveVwap,
            /** Share of the universe up more than one per cent on the day. */
            double pctUpOver1Pct,
            /** Share of the universe down more than one per cent on the day. */
            double pctDownOver1Pct) {

        public static final Snapshot NONE = new Snapshot(null, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                Double.NaN, 0, Double.NaN, Double.NaN, Double.NaN, Double.NaN);

        /** JSON fields, without braces, for the journal. */
        public String toJsonFields() {
            return "\"niftyLast\":" + num(indexLast)
                    + ",\"niftyOpen\":" + num(indexOpen)
                    + ",\"niftyFromOpenPct\":" + num(indexChangeFromOpenPct)
                    + ",\"niftyRet15m\":" + num(indexReturn15mPct)
                    + ",\"niftyRet60m\":" + num(indexReturn60mPct)
                    + ",\"breadthUniverse\":" + universe
                    + ",\"pctUpOnDay\":" + num(pctUpOnDay)
                    + ",\"pctAboveVwap\":" + num(pctAboveVwap)
                    + ",\"pctUpOver1\":" + num(pctUpOver1Pct)
                    + ",\"pctDownOver1\":" + num(pctDownOver1Pct);
        }

        private static String num(double v) {
            return Double.isNaN(v) || Double.isInfinite(v) ? "null" : String.format(java.util.Locale.ROOT, "%.4f", v);
        }
    }

    private final StructureEngine structure;
    private final CandleEngine candles;
    private final TradingClock clock;
    private volatile Snapshot cached = Snapshot.NONE;
    private volatile long cachedEpochSecond = Long.MIN_VALUE;

    public MarketContext(StructureEngine structure, CandleEngine candles, TradingClock clock) {
        this.structure = structure;
        this.candles = candles;
        this.clock = clock;
    }

    /** The current snapshot, recomputed at most once per second of trading time. */
    public Snapshot snapshot() {
        Instant now = clock.now();
        long second = now.getEpochSecond();
        Snapshot s = cached;
        if (second == cachedEpochSecond && s.at() != null) return s;
        s = compute(now);
        cached = s;
        cachedEpochSecond = second;
        return s;
    }

    private Snapshot compute(Instant now) {
        List<Candle> index = candles.history(StructureEngine.INDEX_SYMBOL, Timeframe.M1);
        double indexOpen = index.isEmpty() ? Double.NaN : index.get(0).open();
        double indexLast = structure.state(StructureEngine.INDEX_SYMBOL)
                .map(SharedInstrumentState::lastPrice).filter(p -> p > 0).orElse(Double.NaN);
        double fromOpen = indexOpen > 0 && !Double.isNaN(indexLast) ? (indexLast - indexOpen) / indexOpen * 100.0 : Double.NaN;

        int universe = 0, up = 0, aboveVwap = 0, upOver1 = 0, downOver1 = 0;
        for (SharedInstrumentState s : structure.all()) {
            if (s.previousClose() <= 0 || s.lastPrice() <= 0) continue;
            universe++;
            double change = s.changeFromPreviousClosePercent();
            if (change > 0) up++;
            if (change > 1.0) upOver1++;
            if (change < -1.0) downOver1++;
            if (s.aboveVwap()) aboveVwap++;
        }
        double n = universe;
        return new Snapshot(now, indexLast, indexOpen, fromOpen,
                Indicators.returnPercent(index, 15), Indicators.returnPercent(index, 60),
                universe,
                universe == 0 ? Double.NaN : up / n * 100.0,
                universe == 0 ? Double.NaN : aboveVwap / n * 100.0,
                universe == 0 ? Double.NaN : upOver1 / n * 100.0,
                universe == 0 ? Double.NaN : downOver1 / n * 100.0);
    }
}
