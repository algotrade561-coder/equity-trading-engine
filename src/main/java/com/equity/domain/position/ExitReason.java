package com.equity.domain.position;

/**
 * Why a position is being closed, and — through {@link #priority()} — which reason wins when more
 * than one fires on the same tick.
 *
 * <p>The ordering is not cosmetic. A hard stop and a target can both be true inside one tick when a
 * bar gaps through both; closing at the target in that case books a profit that never existed. The
 * stop wins, always. A risk halt outranks even the stop because it is a decision about the account
 * rather than about the trade.</p>
 */
public enum ExitReason {

    /** Account-level stop: the daily loss limit latched, or an operator halted the user. */
    RISK_HALT(0),
    /** Price hit the stop. */
    HARD_STOP(1),
    /** End of the intraday session — MIS positions are squared off by the broker otherwise. */
    SQUARE_OFF(2),
    /** An operator pressed the button. */
    MANUAL(3),
    /** Price hit the target. */
    TARGET(4),
    /** The move is over: momentum structure broke down without hitting stop or target. */
    STRUCTURE(5),
    /** Held too long without resolving either way. */
    TIME_STOP(6);

    private final int priority;

    ExitReason(int priority) { this.priority = priority; }

    /** Lower wins. */
    public int priority() { return priority; }

    /**
     * True for reasons that must fire even while the account is otherwise halted.
     *
     * <p>Design note 0.1: exits do not share a permission gate with entries. A halt that also
     * blocked the exit path would convert a bad day into an unmanaged position.</p>
     */
    public boolean isMandatory() {
        return this == RISK_HALT || this == HARD_STOP || this == SQUARE_OFF || this == MANUAL;
    }
}
