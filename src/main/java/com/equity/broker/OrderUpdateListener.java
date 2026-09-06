package com.equity.broker;

import com.equity.domain.user.UserId;

/**
 * Receives order state changes pushed by the broker.
 *
 * <p>These arrive on the same socket as market data but belong to one user, so the user id is
 * explicit rather than implied by which connection delivered it.</p>
 *
 * <p>Push is an optimisation, never the whole story: a postback can be missed across a reconnect.
 * Reconciliation against {@link BrokerPort#fetchOrders} remains the authority, and this callback only
 * makes the engine react sooner than the next poll would.</p>
 */
@FunctionalInterface
public interface OrderUpdateListener {
    void onOrderUpdate(UserId userId, BrokerOrder order);
}
