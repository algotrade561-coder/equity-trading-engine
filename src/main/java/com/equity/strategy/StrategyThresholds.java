package com.equity.strategy;

import java.time.LocalTime;

/**
 * The numbers one user applies to the shared market structure.
 *
 * <p><b>No weighted score.</b> The specification is explicit about this and it is the right call: a
 * weighted sum lets a strong reading on one axis buy a failing reading on another, so a trade can
 * be taken for reasons nobody chose. Every field here is a hard threshold on a named condition, and
 * a candidate that fails one is rejected at that named stage — which is also what makes the
 * rejection counts interpretable afterwards.</p>
 *
 * <p>These are starting values, not calibrated ones. Nothing here has been fitted to this market's
 * tape; they are deliberately strict so that being wrong shows up as too few trades rather than as
 * too many.</p>
 *
 * @param minDayChangePercent      a stock that is not up is not a momentum candidate
 * @param maxDayChangePercent      already extended; the move being joined has largely happened
 * @param sanityBandPercent        beyond this, assume an unadjusted corporate action (note 0.9)
 * @param maxDistanceFromHighPct   how far off the day high a candidate may sit
 * @param minImpulseReturn5m       the 5-minute thrust that defines an impulse leg
 * @param minRelativeVolume        the impulse must carry volume, not drift
 * @param requireAboveVwap         price above VWAP, the session's fair value
 * @param requireEmaStack          ema9 above ema20
 * @param requireOutperformIndex   relative strength against NIFTY must be positive
 * @param minPullbackPercent       a pullback shallower than this has not actually paused
 * @param maxPullbackPercent       deeper than this and it is a reversal, not a pullback
 * @param maxConsolidationRangePct a consolidation wider than this is not a coil
 * @param minConsolidationBars     how many 1m bars the coil must hold
 * @param triggerBufferPercent     how far above the setup high price must trade to trigger
 * @param stopAtrMultiple          stop distance as a multiple of ATR
 * @param targetRMultiple          target distance as a multiple of the stop distance
 * @param timeStopMinutes          close a position that has resolved neither way by then
 * @param entryWindowStart         no entries before this — the open is not this strategy's edge
 * @param entryWindowEnd           no new entries after this
 * @param squareOffTime            everything flat by here, ahead of the broker's own square-off
 */
public record StrategyThresholds(
        double minDayChangePercent,
        double maxDayChangePercent,
        double sanityBandPercent,
        double maxDistanceFromHighPct,
        double minImpulseReturn5m,
        double minRelativeVolume,
        boolean requireAboveVwap,
        boolean requireEmaStack,
        boolean requireOutperformIndex,
        double minPullbackPercent,
        double maxPullbackPercent,
        double maxConsolidationRangePct,
        int minConsolidationBars,
        double triggerBufferPercent,
        double stopAtrMultiple,
        double targetRMultiple,
        int timeStopMinutes,
        LocalTime entryWindowStart,
        LocalTime entryWindowEnd,
        LocalTime squareOffTime) {

    public static StrategyThresholds defaults() {
        return new StrategyThresholds(
                1.5,    // min day change %
                12.0,   // max day change %
                25.0,   // corporate-action sanity band %
                1.5,    // max distance from day high %
                0.6,    // min 5m impulse return %
                1.3,    // min relative volume
                true,   // above VWAP
                true,   // ema9 > ema20
                true,   // outperforming the index
                0.2,    // min pullback %
                1.2,    // max pullback %
                0.8,    // max consolidation range %
                3,      // min consolidation bars
                0.05,   // trigger buffer %
                1.5,    // stop = 1.5 x ATR
                2.0,    // target = 2R
                45,     // time stop, minutes
                LocalTime.of(9, 30),
                LocalTime.of(14, 30),
                LocalTime.of(15, 10));
    }
}
