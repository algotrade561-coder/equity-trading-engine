package com.equity.market.indicator;

import com.equity.domain.market.Candle;
import java.util.List;

/**
 * Stateless indicator maths. Kept as pure functions over candle history so the same code runs
 * unchanged under LIVE and REPLAY, and so each one is testable without any engine around it.
 *
 * <p>Every method returns {@code Double.NaN} when there is not enough history rather than 0.
 * A zero ATR would silently make an ATR-scaled stop distance zero, and a zero RVOL would silently
 * fail a "RVOL >= 1.5" test — both look like a decision when they are actually missing data.
 * NaN propagates and forces the caller to handle it.</p>
 */
public final class Indicators {

    private Indicators() {}

    /**
     * Session VWAP from typical price. Accumulated across the whole session, not a rolling window —
     * intraday VWAP is a session-anchored value and a rolling variant would give a different, and
     * much less meaningful, line to trade against.
     */
    public static double vwap(List<Candle> session) {
        double pv = 0, v = 0;
        for (Candle c : session) {
            double typical = (c.high() + c.low() + c.close()) / 3.0;
            pv += typical * c.volume();
            v += c.volume();
        }
        return v > 0 ? pv / v : Double.NaN;
    }

    /**
     * Wilder's ATR over {@code period} candles. Needs {@code period + 1} candles because the first
     * true range requires a previous close.
     */
    public static double atr(List<Candle> candles, int period) {
        if (candles == null || candles.size() < period + 1) return Double.NaN;
        int from = candles.size() - period;
        double sum = 0;
        for (int i = from; i < candles.size(); i++) {
            Candle cur = candles.get(i);
            double prevClose = candles.get(i - 1).close();
            double tr = Math.max(cur.high() - cur.low(),
                    Math.max(Math.abs(cur.high() - prevClose), Math.abs(cur.low() - prevClose)));
            sum += tr;
        }
        return sum / period;
    }

    /** Exponential moving average of closes. */
    public static double ema(List<Candle> candles, int period) {
        if (candles == null || candles.size() < period) return Double.NaN;
        double k = 2.0 / (period + 1);
        double ema = candles.get(candles.size() - period).close();
        for (int i = candles.size() - period + 1; i < candles.size(); i++) {
            ema = candles.get(i).close() * k + ema * (1 - k);
        }
        return ema;
    }

    /**
     * Relative volume: volume so far today against the same elapsed portion of an average day.
     *
     * <p>Compared like-for-like against {@code averageVolumeAtSameTime}, NOT against a full-day
     * average. Measuring 10:00 volume against a whole-day average makes every stock look quiet in
     * the morning and busy in the afternoon, which would systematically shift entries later in the
     * session.</p>
     */
    public static double relativeVolume(long volumeSoFarToday, long averageVolumeAtSameTime) {
        if (averageVolumeAtSameTime <= 0) return Double.NaN;
        return (double) volumeSoFarToday / averageVolumeAtSameTime;
    }

    /** Percentage return between the close {@code lookback} candles ago and the latest close. */
    public static double returnPercent(List<Candle> candles, int lookback) {
        if (candles == null || candles.size() < lookback + 1) return Double.NaN;
        double then = candles.get(candles.size() - 1 - lookback).close();
        double now = candles.get(candles.size() - 1).close();
        return then > 0 ? (now - then) / then * 100.0 : Double.NaN;
    }

    /**
     * Relative strength against a reference index over the same window: simply the difference of
     * the two returns.
     *
     * <p>Deliberately a subtraction and not a normalised score. The specification forbids weighted
     * scores, and "stock +1.8% while NIFTY +0.3%" is directly interpretable in a rejection reason,
     * where "relative strength 0.72" is not.</p>
     */
    public static double relativeStrength(double stockReturnPct, double indexReturnPct) {
        if (Double.isNaN(stockReturnPct) || Double.isNaN(indexReturnPct)) return Double.NaN;
        return stockReturnPct - indexReturnPct;
    }
}
