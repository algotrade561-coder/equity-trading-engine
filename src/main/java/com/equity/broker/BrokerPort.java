package com.equity.broker;

import com.equity.domain.user.UserId;
import java.util.List;

/**
 * Everything the engine may ask a broker to do, per user.
 *
 * <p>Every method takes a {@link UserId}. There is no ambient current user, deliberately: the
 * sibling options engine authenticated one client against a globally held primary token, and when a
 * second user made requests through it the broker answered for the wrong account until the 403s made
 * it visible. Passing identity explicitly turns that class of bug into a compile error.</p>
 *
 * <p>Implementations are expected to be safe to call from multiple threads.</p>
 */
public interface BrokerPort {

    /**
     * Submit an order.
     *
     * @return the order id assigned by the broker
     * @throws BrokerException on refusal; a timeout is reported as non-retryable because the order
     *                         may well have landed — resolve it with {@link #fetchOrders(UserId)}
     */
    String placeOrder(UserId userId, OrderRequest request);

    /** Cancels a regular order. */
    void cancelOrder(UserId userId, String brokerOrderId);

    /**
     * Cancels an order placed under a specific variety.
     *
     * <p>An AMO cannot be cancelled through the regular path — the broker routes varieties to
     * different endpoints — so the caller has to remember which one it used.</p>
     */
    void cancelOrder(UserId userId, String brokerOrderId, OrderVariety variety);

    /** Modify a resting order. Only valid while the order is live. */
    void modifyOrder(UserId userId, String brokerOrderId, int quantity, double limitPrice);

    /** Orders for today, in their newest state. This is the reconciliation source of truth. */
    List<BrokerOrder> fetchOrders(UserId userId);

    List<BrokerPosition> fetchPositions(UserId userId);

    /** Available intraday cash. Sizing that ignores it produces margin rejections at the exchange. */
    double availableMargin(UserId userId);

    /**
     * What the broker will actually block to hold this order.
     *
     * <p>Not the notional. An intraday product is leveraged — a 1322-rupee share can need around 264
     * to hold under MIS — and the multiple differs per stock and changes without notice, so it has to
     * be asked rather than assumed. Comparing notional against cash refuses trades the broker would
     * happily accept; assuming a fixed multiple does the opposite and is worse.
     *
     * @return the required margin, or {@code NaN} when it cannot be determined — callers must then
     *         fall back to the notional, which errs towards refusing rather than over-committing
     */
    default double requiredMargin(UserId userId, OrderRequest request) {
        return Double.NaN;
    }

    /** False when the user has no valid session, in which case every call above would fail. */
    boolean isAuthenticated(UserId userId);
}
