package com.equity.domain.market;

import java.time.Instant;

/**
 * Everything the platform knows about one instrument, shared by every user.
 *
 * <p><b>Immutable, replaced by reference swap.</b> A user evaluation reads one snapshot for its
 * entire pass, so it can never observe a half-updated market — one field from before a tick and
 * another from after. That property is what removes the need for locking on the read path.</p>
 *
 * <p>Deliberately contains <b>no</b> user-specific strategy state. Different users apply different
 * thresholds to these same numbers; the numbers themselves are not user-specific. Putting a
 * {@code MomentumState} in here would force one shared state per symbol, which the specification
 * explicitly forbids.</p>
 */
public record SharedInstrumentState(
        String symbol,
        double previousClose,
        double open,
        double dayHigh,
        double dayLow,
        double lastPrice,
        long cumulativeVolume,
        double vwap,
        double ema9,
        double ema20,
        double atr,
        double return1m,
        double return3m,
        double return5m,
        double return15m,
        double relativeVolume,
        int currentGainerRank,
        int previousGainerRank,
        double niftyRelativeStrength,
        double sectorRelativeStrength,
        Instant lastUpdated
) {

    /** Day change vs previous close, the ranking metric. Guarded against a missing previous close. */
    public double changeFromPreviousClosePercent() {
        return previousClose > 0 ? (lastPrice - previousClose) / previousClose * 100.0 : 0.0;
    }

    public double changeFromOpenPercent() {
        return open > 0 ? (lastPrice - open) / open * 100.0 : 0.0;
    }

    /** How far below the day high, as a percentage. 0 when at the high. */
    public double distanceFromDayHighPercent() {
        return dayHigh > 0 ? (dayHigh - lastPrice) / dayHigh * 100.0 : 0.0;
    }

    /** Signed: positive above VWAP, negative below. */
    public double distanceFromVwapPercent() {
        return vwap > 0 ? (lastPrice - vwap) / vwap * 100.0 : 0.0;
    }

    public boolean aboveVwap() { return vwap > 0 && lastPrice > vwap; }

    /** Negative means the stock climbed the ranking (rank 41 -> 12 gives -29). */
    public int rankChange() {
        if (currentGainerRank <= 0 || previousGainerRank <= 0) return 0;
        return currentGainerRank - previousGainerRank;
    }

    public boolean rankImproving() { return rankChange() < 0; }

    public boolean outperformingNifty()  { return niftyRelativeStrength > 0; }
    public boolean outperformingSector() { return sectorRelativeStrength > 0; }

    /**
     * A day change beyond any plausible intraday move almost certainly means the previous close was
     * not adjusted for a split or bonus. Such a symbol must be refused rather than traded — see
     * design note 0.9.
     */
    public boolean suspectCorporateAction(double sanityBandPercent) {
        return Math.abs(changeFromPreviousClosePercent()) > sanityBandPercent;
    }
}
