package com.equity.domain.market;

import java.time.Instant;

/**
 * One normalized market update.
 *
 * <p>{@code cumulativeVolume} is the exchange's running day volume, not a delta — per-candle volume
 * is derived by differencing, because feeds routinely drop and redeliver ticks and a summed delta
 * would drift over a session.</p>
 *
 * <p>bestBid/bestAsk are 0 when the subscription mode carries no depth. Entry must then be denied
 * with NO_DEPTH rather than assuming a spread — see design note 0.10.</p>
 *
 * <p><b>previousClose is carried on the tick itself</b>, not fetched separately. The exchange sends
 * it in the OHLC block of every quote and full packet, and it is the denominator of the day-change
 * figure the entire top-gainer ranking is built on. Deriving it from our own first candle instead
 * would silently rank on change-from-open, which is a different and much noisier quantity.
 * It is 0 in LTP mode, where the packet carries no OHLC at all.</p>
 */
public record Tick(String symbol, double lastPrice, long cumulativeVolume,
                   double bestBid, double bestAsk,
                   double dayOpen, double dayHigh, double dayLow, double previousClose,
                   Instant exchangeTime, Instant receivedAt) {

    /** A tick with no depth and no OHLC — what LTP mode delivers, and what tests usually want. */
    public static Tick ltp(String symbol, double lastPrice, long cumulativeVolume, Instant at) {
        return new Tick(symbol, lastPrice, cumulativeVolume, 0, 0, 0, 0, 0, 0, at, at);
    }

    public boolean hasDepth() { return bestBid > 0 && bestAsk > 0 && bestAsk >= bestBid; }

    public boolean hasDayOhlc() { return previousClose > 0; }

    /** Spread as a percentage of mid. Returns NaN without depth, so callers cannot silently get 0. */
    public double spreadPercent() {
        if (!hasDepth()) return Double.NaN;
        double mid = (bestBid + bestAsk) / 2.0;
        return mid > 0 ? (bestAsk - bestBid) / mid * 100.0 : Double.NaN;
    }

    /** Day change against the previous close. NaN without it — never silently 0. */
    public double changeFromPreviousClosePercent() {
        if (!hasDayOhlc()) return Double.NaN;
        return (lastPrice - previousClose) / previousClose * 100.0;
    }
}
