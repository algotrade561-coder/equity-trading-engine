package com.equity.domain.market;

import java.time.Duration;

/**
 * Strategy timeframes. 1m is primary; 3m/5m/15m are context and are AGGREGATED FROM COMPLETED 1m
 * candles rather than built independently from ticks, so every timeframe is guaranteed consistent
 * with the one below it.
 */
public enum Timeframe {
    M1(1), M3(3), M5(5), M15(15);

    private final int minutes;

    Timeframe(int minutes) { this.minutes = minutes; }

    public int minutes()        { return minutes; }
    public Duration duration()  { return Duration.ofMinutes(minutes); }
    /** How many completed 1m candles make one candle of this timeframe. */
    public int oneMinuteCandles() { return minutes; }
}
