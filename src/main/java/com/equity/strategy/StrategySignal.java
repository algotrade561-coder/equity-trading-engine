package com.equity.strategy;

import com.equity.domain.momentum.RejectionStage;
import com.equity.domain.order.TradeIntent;

/**
 * The outcome of evaluating one symbol for one user.
 *
 * <p>A rejection is a first-class result carrying the stage it died at, not a null with a log line.
 * These are counted: a system that records only what it traded cannot tell a gate that filters
 * noise from one that blocks its best candidates, and a sibling engine had a gate doing exactly the
 * latter for months (README principle 2).</p>
 */
public record StrategySignal(
        Kind kind,
        TradeIntent intent,
        RejectionStage stage,
        String condition,
        String detail) {

    public enum Kind {
        /** Nothing to report — the setup is progressing, or there is nothing here. */
        NONE,
        /** A named condition failed. Counted by stage and condition. */
        REJECTED,
        /** A trade should be attempted, subject to risk and permission. */
        INTENT
    }

    public static final StrategySignal NOTHING =
            new StrategySignal(Kind.NONE, null, null, null, null);

    public static StrategySignal reject(RejectionStage stage, String condition, String detail) {
        return new StrategySignal(Kind.REJECTED, null, stage, condition, detail);
    }

    public static StrategySignal intent(TradeIntent intent) {
        return new StrategySignal(Kind.INTENT, intent, null, null, null);
    }

    public boolean isIntent()   { return kind == Kind.INTENT; }
    public boolean isRejected() { return kind == Kind.REJECTED; }
}
