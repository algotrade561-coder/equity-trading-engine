package com.equity.domain.momentum;

/**
 * Where a candidate died. Persisted for every rejection (spec section 59) because a system that
 * only records what it traded cannot tell a gate that filters noise from one that blocks its best
 * candidates — a distinction that was measured, and got the wrong answer, in a sibling engine.
 */
public enum RejectionStage {
    UNIVERSE,
    LIQUIDITY,
    DISCOVERY,
    IMPULSE,
    PULLBACK,
    CONSOLIDATION,
    ARMING,
    TRIGGER,
    RISK,
    PERMISSION,
    EXECUTION
}
