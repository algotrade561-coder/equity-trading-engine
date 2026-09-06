package com.equity.broker;

/**
 * Normalised order status.
 *
 * <p>{@link #UNKNOWN} is a first-class value, not a failure to map. A status the engine does not
 * recognise must never be collapsed into COMPLETE or CANCELLED: both of those authorise the position
 * lifecycle to act, and acting on a guess about the fate of an order is how a position gets closed
 * twice or abandoned open.</p>
 */
public enum OrderStatus {
    PENDING,
    OPEN,
    COMPLETE,
    CANCELLED,
    REJECTED,
    UNKNOWN;

    /** True once the broker will send no further updates for this order. */
    public boolean isTerminal() {
        return this == COMPLETE || this == CANCELLED || this == REJECTED;
    }

    /** True while the order can still fill, and therefore still consumes margin and risk budget. */
    public boolean isLive() {
        return this == PENDING || this == OPEN;
    }
}
