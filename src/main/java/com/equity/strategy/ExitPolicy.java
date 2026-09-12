package com.equity.strategy;

/**
 * How a position is managed after it is filled, per user.
 *
 * <p>Separate from {@link StrategyThresholds} because it answers a different question — that record
 * decides what to enter, this one decides what to do afterwards — and because it is the thing most
 * worth A/B testing between users on the same signal. Two users can run identical entry thresholds
 * and different exit policies, and the difference in outcome is then attributable.</p>
 *
 * <h2>What ships, and why it changed</h2>
 * <p>{@link #fixed()} shipped first: the stop and target set at entry never move. That was an
 * admission rather than a recommendation — nothing here had been measured on this market's tape.</p>
 *
 * <p>The first session that traded measured it. Ten closed positions gave back <b>Rs 9,616</b>
 * between their peak and their exit. Two of them reached a full R of profit and then finished
 * negative: BEML ran to +1.30R and closed at -1.35R, a 2.66R round trip. Moving the stop to entry
 * once a trade is 1R ahead would have turned those two into scratches and lifted the day's realised
 * result from Rs 331 to Rs 1,384 — while costing the winners nothing, because all three of them ran
 * past 2R without ever returning to entry.</p>
 *
 * <p>So {@link #breakevenAtOneR()} is now the default. It is one session of evidence, which is thin,
 * and the asymmetry is what makes it defensible rather than the sample size: the rule can only ever
 * convert a loss into a scratch, and it cannot act at all until the trade has already paid for its
 * own risk. Trailing and structure exit stay off — those can clip a winner, and nothing has
 * measured them.</p>
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

    /** Stop and target fixed at entry, nothing moves. The other arm of the comparison. */
    public static ExitPolicy fixed() {
        return new ExitPolicy(false, 1.0, false, 1.0, 1.5, false);
    }

    /**
     * What ships: the stop moves to entry once the trade is 1R ahead, and nothing else moves.
     *
     * <p>Deliberately the smallest intervention that addresses what was measured. Trailing would
     * have clipped ZENTEC, TEGA and BANDHANBNK, each of which needed room to reach 2R.</p>
     */
    public static ExitPolicy breakevenAtOneR() {
        return new ExitPolicy(true, 1.0, false, 1.0, 1.5, false);
    }

    /** A starting point for the other arm of an A/B. Not calibrated — nothing here has been measured. */
    public static ExitPolicy trailing() {
        return new ExitPolicy(true, 1.0, true, 1.0, 1.5, true);
    }

    /**
     * What a new user starts with. The first live account runs the fixed policy and measures the
     * alternatives as shadows, so that is the default: a new user's live exits are the ones with a
     * record, and the shadow comparison keeps running for them too.
     */
    public static ExitPolicy house() {
        return fixed();
    }

    public boolean movesTheStop() { return breakevenEnabled || trailingEnabled; }
}
