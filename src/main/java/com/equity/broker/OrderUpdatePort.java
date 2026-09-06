package com.equity.broker;

/**
 * Subscription to order state pushed by the broker.
 *
 * <p>Separate from {@link MarketDataPort} because these are different things that happen to share a
 * socket at Kite: market data is shared by every user, order updates belong to one. Merging them
 * would put a user id on a market-data interface that has no business knowing about users.</p>
 */
public interface OrderUpdatePort {

    void addOrderUpdateListener(OrderUpdateListener listener);
}
