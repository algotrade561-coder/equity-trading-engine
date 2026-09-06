package com.equity.broker;

import java.time.Instant;

/**
 * The view the broker holds of an order. This is the authority — design note 0.12: the broker is
 * truth, the database is a log, and memory is a cache that may be wrong after any disconnect.
 *
 * @param averagePrice actual fill price; carried alongside {@code filledQuantity} rather than the
 *                     requested values because a slipped fill invalidates the risk sizing that
 *                     authorised the order (design note 0.8)
 */
public record BrokerOrder(
        String brokerOrderId,
        String symbol,
        OrderSide side,
        OrderStatus status,
        int quantity,
        int filledQuantity,
        double averagePrice,
        String statusMessage,
        Instant updatedAt,
        String tag) {

    public int pendingQuantity() { return Math.max(0, quantity - filledQuantity); }

    public boolean isPartiallyFilled() { return filledQuantity > 0 && filledQuantity < quantity; }
}
