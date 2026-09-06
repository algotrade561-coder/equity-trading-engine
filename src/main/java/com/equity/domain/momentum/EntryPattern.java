package com.equity.domain.momentum;

/**
 * Supported continuation setups. An enum from day one so SHORT variants can be added without a
 * schema migration — the LONG/SHORT axis lives on {@link com.equity.domain.Direction}, not here.
 */
public enum EntryPattern {
    PULLBACK_CONTINUATION,
    CONSOLIDATION_BREAKOUT
}
