package com.equity.broker;

import static org.assertj.core.api.Assertions.assertThat;

import com.equity.domain.user.UserId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The connectivity check must never leave an order behind without saying so.
 *
 * <p>An after-market order that survives the check acts at the next open with nobody watching. The
 * report's {@code orderStillLive} flag is the one output that matters, and each test here is a
 * different way the broker can behave between "placed" and "gone". The check is exercised against a
 * fake rather than the real adapter for the same reason the provisioner is: the real thing costs
 * money or moves a market, and the failure paths are exactly the ones that cannot be rehearsed live.</p>
 */
class ConnectivityCheckTest {

    private static final UserId USER = UserId.of("07926329-989e-4d3c-b229-119b4a6c81dc");
    private static final Instant NOW = Instant.parse("2026-09-12T06:00:00Z");

    /** A broker whose order book and cancel behaviour a test can script. */
    private static final class ScriptedBroker implements BrokerPort {
        final List<OrderRequest> placed = new ArrayList<>();
        final List<String> cancelled = new ArrayList<>();
        BrokerException refusePlaceWith;
        BrokerException refuseCancelWith;
        boolean cancelSilentlyIgnored;     // accepts the cancel, order stays OPEN — the nasty case
        boolean neverListsTheOrder;

        @Override public String placeOrder(UserId u, OrderRequest r) {
            if (refusePlaceWith != null) throw refusePlaceWith;
            placed.add(r);
            return "amo-" + placed.size();
        }
        @Override public void cancelOrder(UserId u, String id) { cancelOrder(u, id, OrderVariety.REGULAR); }
        @Override public void cancelOrder(UserId u, String id, OrderVariety v) {
            if (refuseCancelWith != null) throw refuseCancelWith;
            cancelled.add(v + ":" + id);
        }
        @Override public void modifyOrder(UserId u, String id, int q, double p) {}
        @Override public List<BrokerOrder> fetchOrders(UserId u) {
            if (neverListsTheOrder) return List.of();
            List<BrokerOrder> out = new ArrayList<>();
            for (int i = 0; i < placed.size(); i++) {
                String id = "amo-" + (i + 1);
                boolean gone = cancelled.stream().anyMatch(c -> c.endsWith(":" + id)) && !cancelSilentlyIgnored;
                out.add(new BrokerOrder(id, placed.get(i).symbol(), OrderSide.BUY,
                        gone ? OrderStatus.CANCELLED : OrderStatus.OPEN, 1, 0, 0, "", NOW, placed.get(i).tag()));
            }
            return out;
        }
        @Override public List<BrokerPosition> fetchPositions(UserId u) { return List.of(); }
        @Override public double availableMargin(UserId u) { return 0; }
        @Override public boolean isAuthenticated(UserId u) { return true; }
    }

    @Test
    void theHappyPathPlacesOneHarmlessOrderAndCancelsItThroughTheAmoBook() {
        ScriptedBroker broker = new ScriptedBroker();
        ConnectivityCheck.Report r = new ConnectivityCheck(broker).run(USER, "ITC", 380.0, "10.0.1.4");

        assertThat(r.connected()).isTrue();
        assertThat(r.orderStillLive()).isFalse();
        assertThat(r.sourceIp()).isEqualTo("10.0.1.4");

        OrderRequest sent = broker.placed.get(0);
        assertThat(sent.variety()).as("outside hours only an AMO is accepted").isEqualTo(OrderVariety.AMO);
        assertThat(sent.quantity()).as("the worst case is one share").isEqualTo(1);
        assertThat(sent.type()).isEqualTo(OrderType.LIMIT);
        assertThat(sent.limitPrice()).isEqualTo(380.0);
        assertThat(sent.product()).as("a fill would be a share in the demat, not a leveraged position")
                .isEqualTo(ProductType.CNC);
        assertThat(broker.cancelled)
                .as("an AMO cannot be cancelled through the regular path; the variety must travel")
                .containsExactly("AMO:amo-1");
        assertThat(r.steps()).extracting(ConnectivityCheck.Step::ok).containsExactly(true, true, true, true);
    }

    @Test
    void aRefusedOrderIsNotConnectedAndNothingIsLeftBehind() {
        ScriptedBroker broker = new ScriptedBroker();
        broker.refusePlaceWith = new BrokerException(
                "Request not allowed from this IP", "TokenException", false);

        ConnectivityCheck.Report r = new ConnectivityCheck(broker).run(USER, "ITC", 380.0, null);

        assertThat(r.connected()).isFalse();
        assertThat(r.orderStillLive()).isFalse();
        assertThat(r.brokerOrderId()).isNull();
        assertThat(r.steps()).hasSize(1);
        assertThat(r.steps().get(0).detail())
                .as("the broker's own words are the diagnosis — an IP message says the key is not registered here")
                .contains("TokenException").contains("not allowed from this IP");
    }

    @Test
    void aCancelTheBrokerRejectsIsReportedAsAnOrderStillLive() {
        ScriptedBroker broker = new ScriptedBroker();
        broker.refuseCancelWith = new BrokerException("Order cannot be cancelled", "OrderException", false);

        ConnectivityCheck.Report r = new ConnectivityCheck(broker).run(USER, "ITC", 380.0, null);

        assertThat(r.connected()).as("the order was accepted, so the link works").isTrue();
        assertThat(r.orderStillLive())
                .as("but the order is at the broker and will act at the open — the operator must be told")
                .isTrue();
        assertThat(r.brokerOrderId()).isEqualTo("amo-1");
    }

    @Test
    void aCancelTheBrokerAcceptsButDoesNotApplyIsCaughtByTheConfirmStep() {
        // The nasty case: cancel returns 200, the book still says OPEN. Trusting the cancel response
        // alone would report success while the order sat there until Monday.
        ScriptedBroker broker = new ScriptedBroker();
        broker.cancelSilentlyIgnored = true;

        ConnectivityCheck.Report r = new ConnectivityCheck(broker).run(USER, "ITC", 380.0, null);

        assertThat(r.orderStillLive()).isTrue();
        assertThat(r.steps()).filteredOn(s -> s.name().equals("confirm")).first()
                .satisfies(s -> {
                    assertThat(s.ok()).isFalse();
                    assertThat(s.detail()).contains("OPEN");
                });
    }

    @Test
    void anOrderTheBookNeverListsIsStillCancelledAndNotReportedAsLive() {
        // Kite's book can lag. If the order never appears but the cancel was accepted, the honest
        // answer is "cancelled, unconfirmed" — not an alarm, and not a false all-clear either.
        ScriptedBroker broker = new ScriptedBroker();
        broker.neverListsTheOrder = true;

        ConnectivityCheck.Report r = new ConnectivityCheck(broker).run(USER, "ITC", 380.0, null);

        assertThat(broker.cancelled).containsExactly("AMO:amo-1");
        assertThat(r.orderStillLive()).isFalse();
        assertThat(r.steps()).filteredOn(s -> s.name().equals("order book")).first()
                .satisfies(s -> assertThat(s.ok()).isFalse());
    }
}
