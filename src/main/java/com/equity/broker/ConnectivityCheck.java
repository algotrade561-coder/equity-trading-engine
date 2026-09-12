package com.equity.broker;

import com.equity.domain.order.OrderTag;
import com.equity.domain.user.UserId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Proves, end to end, that this user can put an order in front of the exchange.
 *
 * <h2>Why an order and not a ping</h2>
 * <p>Everything short of an order can succeed while the one thing that matters fails. A profile
 * call works with a valid token from any address; the order path is the only one the broker checks
 * against the address the API key was registered to. After giving a user an Elastic IP, or moving
 * the instance, or renewing a key, the question is not "is the token valid" but "will an order from
 * here be accepted" — and only an order answers it.</p>
 *
 * <h2>Why it is safe to run</h2>
 * <p>One share, a limit price well below the market, delivery product, after-market variety, and
 * <b>cancelled in the same call</b> once the order book confirms it landed. Four things protect
 * against the one real hazard, which is an accepted after-market order acting at the next open with
 * nobody watching: the price is set where it cannot fill on any ordinary day, the quantity makes the
 * worst case one share, the product means a fill would be a share in the demat rather than a
 * leveraged position, and the cancel is not optional. If the cancel fails the report says so in
 * capitals and names the order, because that is the moment an operator has to act.</p>
 *
 * <p>Lives in the broker package because only the position lifecycle and the broker layer may call
 * {@code placeOrder}, and that rule is enforced. This is not a trading path; it is the broker layer
 * checking itself.</p>
 */
public class ConnectivityCheck {

    private static final Logger log = LoggerFactory.getLogger(ConnectivityCheck.class);

    private final BrokerPort broker;

    public ConnectivityCheck(BrokerPort broker) {
        this.broker = broker;
    }

    /** One line of the report. Every step says what it tried and what came back. */
    public record Step(String name, boolean ok, String detail) {}

    /**
     * The outcome. {@code orderStillLive} is the field that matters: true means an after-market order
     * exists at the broker that this code could not cancel, and it will act at the next open.
     */
    public record Report(boolean connected, List<Step> steps, String brokerOrderId,
                         boolean orderStillLive, String sourceIp) {}

    /**
     * Places and cancels one after-market order.
     *
     * @param limitPrice must be below the current price by a margin the caller has chosen; this
     *                   method does not second-guess it beyond requiring it to be positive
     */
    public Report run(UserId userId, String symbol, double limitPrice, String sourceIp) {
        List<Step> steps = new ArrayList<>();
        String orderId = null;

        OrderRequest request = new OrderRequest(symbol, "NSE", OrderSide.BUY, 1, OrderType.LIMIT,
                ProductType.CNC, limitPrice, 0, OrderVariety.AMO, OrderTag.forEntry(userId).value());

        // 1. Place. This is the step the IP whitelist gates.
        try {
            orderId = broker.placeOrder(userId, request);
            steps.add(new Step("place", true, "AMO LIMIT BUY 1 x " + symbol + " @ " + limitPrice
                    + " accepted as order " + orderId));
        } catch (BrokerException e) {
            steps.add(new Step("place", false, e.errorType() + ": " + e.getMessage()));
            log.warn("connectivity check for user={} from {}: order REFUSED — {}", userId,
                    sourceIp == null ? "default interface" : sourceIp, e.getMessage());
            return new Report(false, steps, null, false, sourceIp);
        }

        // 2. Confirm it is in the book. Proves the read path, and that the id we hold is real.
        Optional<BrokerOrder> placed = find(userId, orderId);
        steps.add(new Step("order book", placed.isPresent(),
                placed.map(o -> "visible as " + o.status() + (o.statusMessage() == null || o.statusMessage().isBlank()
                                ? "" : " — " + o.statusMessage()))
                        .orElse("order " + orderId + " not yet listed (the book can lag a moment)")));

        // 3. Cancel. Not optional.
        boolean cancelled;
        try {
            broker.cancelOrder(userId, orderId, OrderVariety.AMO);
            cancelled = true;
            steps.add(new Step("cancel", true, "cancel accepted for " + orderId));
        } catch (BrokerException e) {
            cancelled = false;
            steps.add(new Step("cancel", false, e.errorType() + ": " + e.getMessage()));
        }

        // 4. Confirm the cancel took. The broker can accept a cancel request and still hold the order.
        Optional<BrokerOrder> after = find(userId, orderId);
        boolean confirmedGone = after.map(o -> o.status() == OrderStatus.CANCELLED
                || o.status() == OrderStatus.REJECTED).orElse(false);
        steps.add(new Step("confirm", confirmedGone || (cancelled && after.isEmpty()),
                after.map(o -> "order is now " + o.status())
                        .orElse(cancelled ? "order no longer listed" : "order not found after a failed cancel")));

        boolean stillLive = !(confirmedGone || (cancelled && after.isEmpty()));
        if (stillLive) {
            log.error("CONNECTIVITY CHECK LEFT AN ORDER LIVE: user={} order={} {} x1 @ {} AMO. "
                    + "It WILL act at the next market open unless cancelled in the Kite terminal.",
                    userId, orderId, symbol, limitPrice);
        } else {
            log.info("connectivity check for user={} from {}: order placed and cancelled cleanly ({})",
                    userId, sourceIp == null ? "default interface" : sourceIp, orderId);
        }
        return new Report(true, steps, orderId, stillLive, sourceIp);
    }

    private Optional<BrokerOrder> find(UserId userId, String orderId) {
        try {
            // Kite's book is eventually consistent by a beat; one short retry covers the common case.
            for (int attempt = 0; attempt < 3; attempt++) {
                Optional<BrokerOrder> hit = broker.fetchOrders(userId).stream()
                        .filter(o -> orderId.equals(o.brokerOrderId()))
                        .reduce((a, b) -> b);           // newest state wins
                if (hit.isPresent()) return hit;
                Thread.sleep(400);
            }
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (BrokerException e) {
            return Optional.empty();
        }
    }
}
