package com.equity.strategy;

/**
 * How a position is managed after it is filled, per user.
 *
 * <p>Separate from {@link StrategyThresholds} because it answers a different question — that record
 * decides what to enter, this one decides what to do afterwards — and because it is the thing most
 * worth A/B testing between users on the same signal. Two users can run identical entry thresholds
 * and different exit policies, and the difference in outcome is then attributable.</p>
 *
 * <h2>Everything is off by default</h2>
 * <p>{@link #fixed()} is the shipped policy: the stop and target set at entry never move. That is
 * not a recommendation, it is an admission — none of the options below have been measured on this
 * market's tape, and a trailing stop that has not been measured is a preference, not an edge.</p>
 *
 * <p>In a sibling options engine the exit was the part that gave back what the entries earned, and
 * the fixes that looked obvious mostly failed: cutting losers early clipped winners, holding through
 * the stop recovered 76% of the time but doubled the tail. So these exist to be switched on for one
 * user, run against the other, and kept only if the difference survives leave-one-day-out.</p>
 *
 * @param breakevenEnabled    move the stop to entry once the trade is far enough ahead
 * @param breakevenArmAtR     how far ahead, in units of the original risk
 * @param trailingEnabled     follow the high-water mark with an ATR-based stop
 * @param trailingArmAtR      trailing does nothing until the trade has reached this many R
 * @param trailingAtrMultiple how far behind the high-water mark the trailing stop sits
 * @param structureExitEnabled close when the move breaks down without reaching stop or target
 */
public record ExitPolicy(
        boolean breakevenEnabled,
        double breakevenArmAtR,
        boolean trailingEnabled,
        double trailingArmAtR,
        double trailingAtrMultiple,
        boolean structureExitEnabled) {

    /** What ships: stop and target fixed at entry, nothing moves. */
    public static ExitPolicy fixed() {
        return new ExitPolicy(false, 1.0, false, 1.0, 1.5, false);
    }

    /** A starting point for the other arm of an A/B. Not calibrated — nothing here has been measured. */
    public static ExitPolicy trailing() {
        return new ExitPolicy(true, 1.0, true, 1.0, 1.5, true);
    }

    public boolean movesTheStop() { return breakevenEnabled || trailingEnabled; }
}
