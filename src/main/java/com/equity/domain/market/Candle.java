package com.equity.domain.market;

import java.time.Instant;

/**
 * A completed OHLCV candle. {@code startTime} is the inclusive start of the bucket.
 *
 * <p>Only COMPLETED candles reach the strategy. An in-progress candle is deliberately not
 * representable here: acting on a forming candle means acting on a value that can still change,
 * which is the classic source of backtest/live divergence.</p>
 */
public record Candle(String symbol, Timeframe timeframe, Instant startTime,
                     double open, double high, double low, double close, long volume) {

    public Candle {
        if (high < low)  throw new IllegalArgumentException("high < low for " + symbol);
        if (volume < 0)  throw new IllegalArgumentException("negative volume for " + symbol);
    }

    public Instant endTime()   { return startTime.plus(timeframe.duration()); }
    public boolean bullish()   { return close > open; }
    public double range()      { return high - low; }
    public double body()       { return Math.abs(close - open); }

    /** Percent change across the candle. Zero when open is non-positive rather than infinite. */
    public double changePercent() {
        return open > 0 ? (close - open) / open * 100.0 : 0.0;
    }
}
