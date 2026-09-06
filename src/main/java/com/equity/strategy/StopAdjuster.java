package com.equity.strategy;

import com.equity.domain.Direction;
import com.equity.domain.position.Position;

/**
 * Where a position's stop should sit now, given how far the trade has run.
 *
 * <p>A pure function of the position, the policy and the current ATR — no clock, no broker, no
 * state. That is deliberate: this decides when to give up on a live trade, and the only way to have
 * any confidence in it is to be able to drive it through every case in a unit test rather than
 * waiting to observe it in a market.</p>
 *
 * <h2>The one invariant</h2>
 * <p><b>A stop only ever moves in the favourable direction.</b> Every path here goes through
 * {@link Position#withStop(double)}, which refuses to loosen — a stop that can widen is not a stop,
 * it is a hope. The two policies therefore compose safely: whichever asks for the tighter level
 * wins, and neither can undo the other.</p>
 */
public final class StopAdjuster {

    private StopAdjuster() {}

    /**
     * @param atr current ATR for the symbol; NaN or zero disables trailing rather than defaulting to
     *            some fixed distance, because a trailing stop with an invented width is worse than
     *            no trailing stop — it looks calibrated and is not
     * @return the position, with its stop moved if the policy calls for it
     */
    public static Position adjust(Position position, ExitPolicy policy, double atr) {
        if (!position.hasExposure() || !policy.movesTheStop()) return position;
        if (!(position.riskPerShare() > 0)) return position;

        double reachedR = position.favourableExcursionR();
        Position adjusted = position;

        if (policy.breakevenEnabled() && reachedR >= policy.breakevenArmAtR()) {
            // Entry exactly, not entry plus a tick: moving the stop through the fill would make a
            // scratch trade impossible and turn ordinary noise into a guaranteed small loss.
            adjusted = adjusted.withStop(position.entryPrice());
        }

        if (policy.trailingEnabled()
                && reachedR >= policy.trailingArmAtR()
                && atr > 0 && !Double.isNaN(atr)) {
            double distance = policy.trailingAtrMultiple() * atr;
            double trailed = position.direction() == Direction.LONG
                    ? position.highWaterMark() - distance
                    : position.highWaterMark() + distance;
            adjusted = adjusted.withStop(trailed);
        }

        return adjusted;
    }

    /**
     * Whether the adjustment actually changed anything.
     *
     * <p>Used to decide whether the move is worth a log line. A trailing stop that logs on every
     * tick buries everything else in the file.</p>
     */
    public static boolean moved(Position before, Position after) {
        return Double.compare(before.stopPrice(), after.stopPrice()) != 0;
    }
}
