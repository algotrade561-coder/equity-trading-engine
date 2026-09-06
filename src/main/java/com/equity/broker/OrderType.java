package com.equity.broker;

/**
 * Order types this engine is willing to send.
 *
 * <p>Deliberately a short list. Every additional type is another set of broker-side validation rules
 * and another way for an order to sit unfilled while the strategy believes it has a position.</p>
 */
public enum OrderType {
    /** Immediate fill at whatever the book offers. Used for exits, where certainty beats price. */
    MARKET,
    /** Priced entry. Never used for an exit, because an unfilled exit is an unbounded loss. */
    LIMIT,
    /** Stop-loss market: triggers at triggerPrice, then executes at market. */
    SL_M
}
