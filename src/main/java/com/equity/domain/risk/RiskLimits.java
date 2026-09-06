package com.equity.domain.risk;

/**
 * One user's risk budget. Per user, never global — the specification requires that users be fully
 * independent, and a shared limit would let one user's losses stop another user trading.
 *
 * @param riskPerTradeRupees   the budget a single trade may lose if its stop is hit; this is what
 *                             sizing divides by the stop distance
 * @param maxDailyLossRupees   realised plus unrealised; once breached it latches for the day
 * @param maxOpenPositions     concurrent positions
 * @param maxDailyAttempts     entry submissions, filled or not — a cap on churn, not on winners
 * @param maxPendingOrders     unfilled entries at once; without it a stalled fill lets the engine
 *                             queue the same idea repeatedly
 * @param maxPositionValue     notional per position, so one cheap stop cannot buy the whole account
 * @param minStopPercent       below this the stop is inside the noise and will be hit by a spread
 * @param maxStopPercent       above this the trade is too wide to be the setup that was detected
 * @param maxSpreadPercent     refuse to cross a book wider than this
 * @param cooldownSeconds      after closing a symbol, how long before re-entering it
 * @param maxStalenessSeconds  how old the last tick for an instrument may be at entry
 */
public record RiskLimits(
        double riskPerTradeRupees,
        double maxDailyLossRupees,
        int maxOpenPositions,
        int maxDailyAttempts,
        int maxPendingOrders,
        double maxPositionValue,
        double minStopPercent,
        double maxStopPercent,
        double maxSpreadPercent,
        long cooldownSeconds,
        long maxStalenessSeconds) {

    /**
     * Conservative starting limits.
     *
     * <p>These are a starting point, not a calibration. Nothing here has been fitted to this
     * market's tape, and the honest thing is to say so rather than to imply the numbers mean
     * something: they are small enough that being wrong is survivable.</p>
     */
    public static RiskLimits conservative() {
        return new RiskLimits(
                1_000,      // risk per trade
                3_000,      // daily loss latch
                3,          // concurrent positions
                12,         // entry attempts per day
                2,          // pending entries
                150_000,    // notional per position
                0.25,       // min stop %
                1.50,       // max stop %
                0.20,       // max spread %
                300,        // 5 minute cooldown per symbol
                15);        // last tick may be 15s old at entry
    }
}
