package com.equity.broker;

/**
 * Which order book an order goes into.
 *
 * <p>Kite routes these to different endpoints, not to different fields, so the variety has to
 * travel with the order rather than being a flag inside it — and a cancel has to name the same
 * variety, because an AMO cannot be cancelled through the regular path.</p>
 */
public enum OrderVariety {

    /** A normal order for the current session. Everything the strategy sends. */
    REGULAR,

    /**
     * After-market order: accepted while the exchange is shut and released at the next open.
     *
     * <p>The engine never uses this. It exists for the connectivity check, which has to be able to
     * put an order in the book outside trading hours. <b>An accepted AMO acts at the next open</b>
     * unless cancelled, which makes it the one variety here that can trade without anybody watching.
     */
    AMO;

    /** The path segment Kite expects: {@code /orders/regular}, {@code /orders/amo}. */
    public String path() { return name().toLowerCase(); }
}
