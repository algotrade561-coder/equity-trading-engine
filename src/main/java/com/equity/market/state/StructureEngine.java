package com.equity.market.state;

import com.equity.domain.market.Candle;
import com.equity.domain.market.SharedInstrumentState;
import com.equity.domain.market.Tick;
import com.equity.domain.market.Timeframe;
import com.equity.market.candle.CandleEngine;
import com.equity.market.indicator.Indicators;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Assembles the shared, user-independent view of each instrument.
 *
 * <p>Design note 0.4. Geometry — VWAP, ATR, EMAs, returns, day range — is a property of the market,
 * not of a user, so it is computed once per symbol and read by everybody. Only thresholds applied to
 * these numbers are per user. A per-(user × symbol) state machine would multiply this work by the
 * number of users for identical answers, and 200 symbols × N users is where that stops scaling.</p>
 *
 * <p>Two update paths, deliberately different in cost:</p>
 * <ul>
 *   <li><b>every tick</b> — swap the price-derived fields. Cheap, and keeps stops honest.</li>
 *   <li><b>every completed 1m candle</b> — recompute the indicators. Expensive, and correct only on
 *       completed candles: an indicator built from a forming bar changes under the strategy's feet
 *       and is a standard source of live/replay divergence.</li>
 * </ul>
 *
 * <p>Each symbol's state is published by reference swap, so a reader sees one coherent snapshot for
 * a whole evaluation pass rather than a record being written field by field underneath it.</p>
 */
@Component
public class StructureEngine {

    /** NIFTY 50, for relative strength. Subscribed as an index, which ticks without volume. */
    public static final String INDEX_SYMBOL = "NIFTY 50";

    private final CandleEngine candles;
    private final Map<String, AtomicReference<SharedInstrumentState>> states = new ConcurrentHashMap<>();

    public StructureEngine(CandleEngine candles) {
        this.candles = candles;
        candles.onCandleClosed(this::onCandleClosed);
    }

    /** Fast path. Keeps price, volume and the running day range current between candle closes. */
    public void onTick(Tick tick) {
        AtomicReference<SharedInstrumentState> ref = states.computeIfAbsent(
                tick.symbol(), s -> new AtomicReference<>(empty(s)));

        ref.updateAndGet(prev -> new SharedInstrumentState(
                tick.symbol(),
                // Never overwrite a known previous close with a zero: LTP-mode ticks carry no OHLC,
                // and a single one of those would erase the denominator of the day-change figure.
                tick.previousClose() > 0 ? tick.previousClose() : prev.previousClose(),
                tick.dayOpen() > 0 ? tick.dayOpen() : prev.open(),
                Math.max(prev.dayHigh(), Math.max(tick.dayHigh(), tick.lastPrice())),
                lowOf(prev.dayLow(), tick),
                tick.lastPrice(),
                Math.max(prev.cumulativeVolume(), tick.cumulativeVolume()),
                prev.vwap(), prev.ema9(), prev.ema20(), prev.atr(),
                prev.return1m(), prev.return3m(), prev.return5m(), prev.return15m(),
                prev.relativeVolume(),
                prev.currentGainerRank(), prev.previousGainerRank(),
                prev.niftyRelativeStrength(), prev.sectorRelativeStrength(),
                tick.receivedAt()));
    }

    /**
     * Recomputes a symbol's indicators from whatever history it now has.
     *
     * <p>For after a backfill: seeded candles arrive silently, so nothing would otherwise recompute
     * VWAP, the EMAs or ATR, and the state would still describe the truncated series the process
     * started with.</p>
     */
    public void rebuild(String symbol) {
        recompute(symbol);
    }

    /** Slow path. Only 1m closes drive it; higher timeframes are read from history when needed. */
    private void onCandleClosed(Candle candle) {
        if (candle.timeframe() != Timeframe.M1) return;
        recompute(candle.symbol());
    }

    private void recompute(String symbol) {

        List<Candle> minutes = candles.history(symbol, Timeframe.M1);
        double vwap = Indicators.vwap(minutes);
        double atr = Indicators.atr(minutes, 14);
        double ema9 = Indicators.ema(minutes, 9);
        double ema20 = Indicators.ema(minutes, 20);
        double r1 = Indicators.returnPercent(minutes, 1);
        double r3 = Indicators.returnPercent(minutes, 3);
        double r5 = Indicators.returnPercent(minutes, 5);
        double r15 = Indicators.returnPercent(minutes, 15);
        double relativeVolume = sessionRelativeVolume(minutes);
        double indexReturn5m = indexReturn();

        AtomicReference<SharedInstrumentState> ref = states.computeIfAbsent(
                symbol, s -> new AtomicReference<>(empty(s)));

        ref.updateAndGet(prev -> new SharedInstrumentState(
                prev.symbol(), prev.previousClose(), prev.open(), prev.dayHigh(), prev.dayLow(),
                prev.lastPrice(), prev.cumulativeVolume(),
                vwap, ema9, ema20, atr, r1, r3, r5, r15, relativeVolume,
                prev.currentGainerRank(), prev.previousGainerRank(),
                Indicators.relativeStrength(r5, indexReturn5m),
                prev.sectorRelativeStrength(),
                prev.lastUpdated()));
    }

    /** Applies a fresh ranking pass. Kept separate so ranking can run on its own cadence. */
    public void applyRank(String symbol, int currentRank, int previousRank) {
        AtomicReference<SharedInstrumentState> ref = states.get(symbol);
        if (ref == null) return;
        ref.updateAndGet(p -> new SharedInstrumentState(
                p.symbol(), p.previousClose(), p.open(), p.dayHigh(), p.dayLow(), p.lastPrice(),
                p.cumulativeVolume(), p.vwap(), p.ema9(), p.ema20(), p.atr(),
                p.return1m(), p.return3m(), p.return5m(), p.return15m(), p.relativeVolume(),
                currentRank, previousRank, p.niftyRelativeStrength(), p.sectorRelativeStrength(),
                p.lastUpdated()));
    }

    public Optional<SharedInstrumentState> state(String symbol) {
        AtomicReference<SharedInstrumentState> ref = states.get(symbol);
        return ref == null ? Optional.empty() : Optional.of(ref.get());
    }

    /** Every tradeable symbol seen so far. The index is excluded — it is context, not a candidate. */
    public Collection<SharedInstrumentState> all() {
        return states.entrySet().stream()
                .filter(e -> !INDEX_SYMBOL.equals(e.getKey()))
                .map(e -> e.getValue().get())
                .toList();
    }

    public int size() { return states.size(); }

    private double indexReturn() {
        List<Candle> index = candles.history(INDEX_SYMBOL, Timeframe.M1);
        return index.isEmpty() ? 0.0 : Indicators.returnPercent(index, 5);
    }

    /**
     * Volume in the last minute against this session's own average minute.
     *
     * <p>This is <b>not</b> the usual relative volume, which compares against the same minute on
     * previous days. That needs a historical intraday baseline this engine does not yet store, and
     * inventing one from a single session would produce a number that looks like the real thing and
     * is not. Named and documented as a within-session measure until the baseline exists.</p>
     */
    private static double sessionRelativeVolume(List<Candle> minutes) {
        if (minutes.size() < 5) return Double.NaN;
        long last = minutes.get(minutes.size() - 1).volume();
        long total = minutes.stream().mapToLong(Candle::volume).sum();
        double average = (double) total / minutes.size();
        return average > 0 ? last / average : Double.NaN;
    }

    private static double lowOf(double previousLow, Tick tick) {
        double candidate = tick.dayLow() > 0 ? tick.dayLow() : tick.lastPrice();
        return previousLow > 0 ? Math.min(previousLow, candidate) : candidate;
    }

    private static SharedInstrumentState empty(String symbol) {
        return new SharedInstrumentState(symbol, 0, 0, 0, 0, 0, 0,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                0, 0, Double.NaN, Double.NaN, null);
    }
}
