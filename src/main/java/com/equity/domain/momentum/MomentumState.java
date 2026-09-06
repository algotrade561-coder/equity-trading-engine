package com.equity.domain.momentum;

/**
 * Per (user, symbol) strategy state. Transitions are driven by COMPLETED 1m candles, except
 * ARMED -> TRIGGERED and any stop exit, which are driven by live ticks.
 */
public enum MomentumState {
    IDLE,
    MOMENTUM_DETECTED,
    MOMENTUM_CANDIDATE,
    IMPULSE,
    PULLBACK,
    CONSOLIDATION,
    ARMED,
    TRIGGERED,
    ENTRY_ORDER_PENDING,
    POSITION_OPEN,
    TRENDING,
    MOMENTUM_WEAKENING,
    EXIT_PENDING,
    CLOSED,
    COOLDOWN,
    INVALIDATED;

    /** True once the user has capital at risk — used to forbid discovery-driven transitions. */
    public boolean hasExposure() {
        return this == POSITION_OPEN || this == TRENDING
                || this == MOMENTUM_WEAKENING || this == EXIT_PENDING;
    }

    public boolean isTerminal() {
        return this == CLOSED || this == INVALIDATED;
    }
}
