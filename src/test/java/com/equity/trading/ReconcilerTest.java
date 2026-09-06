package com.equity.trading;

import static org.assertj.core.api.Assertions.assertThat;

import com.equity.broker.BrokerException;
import com.equity.broker.BrokerOrder;
import com.equity.broker.BrokerPort;
import com.equity.broker.BrokerPosition;
import com.equity.broker.OrderRequest;
import com.equity.broker.OrderSide;
import com.equity.broker.OrderStatus;
import com.equity.broker.OrderVariety;
import com.equity.broker.ProductType;
import com.equity.domain.Direction;
import com.equity.domain.momentum.EntryPattern;
import com.equity.domain.order.TradeIntent;
import com.equity.domain.position.Position;
import com.equity.domain.position.PositionStatus;
import com.equity.domain.risk.RiskDecision;
import com.equity.domain.risk.RiskLimits;
import com.equity.domain.user.Role;
import com.equity.domain.user.TradingUser;
import com.equity.domain.user.UserId;
import com.equity.domain.user.UserStatus;
import com.equity.platform.time.FixedTradingClock;
import com.equity.strategy.StrategyThresholds;
import com.equity.user.UserAccount;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Making the engine's view agree with the broker's.
 *
 * <p>Every case here is a way the two views drift apart, and each one costs money in a different
 * way: shares held with no stop watching them, a slot occupied by a position that no longer exists,
 * or an intraday holding nothing will square off before the broker does it at the worst price of the
 * day. None of these announce themselves — that is exactly why they need tests.</p>
 */
class ReconcilerTest {

    private static final Instant NOW = Instant.parse("2026-09-04T05:00:00Z");   // 10:30 IST

    private static final class StubBroker implements BrokerPort {
        final List<BrokerOrder> orders = new ArrayList<>();
        final List<BrokerPosition> positions = new ArrayList<>();
        final List<OrderRequest> sent = new ArrayList<>();
        /** Every request that reached this broker, including one it then failed to answer. */
        final List<OrderRequest> attempted = new ArrayList<>();
        boolean authenticated = true;
        boolean failReads;
        /** Non-null makes placeOrder throw it, standing in for a timeout or a refusal. */
        BrokerException placeFailure;
        int sequence;

        @Override public String placeOrder(UserId u, OrderRequest r) {
            attempted.add(r);
            if (placeFailure != null) throw placeFailure;
            sent.add(r);
            return "order-" + (++sequence);
        }
        @Override public void cancelOrder(UserId u, String id) {}
        @Override public void cancelOrder(UserId u, String id, OrderVariety v) {}
        @Override public void modifyOrder(UserId u, String id, int q, double p) {}
        @Override public double availableMargin(UserId u) { return 1_000_000; }
        @Override public boolean isAuthenticated(UserId u) { return authenticated; }

        @Override public List<BrokerOrder> fetchOrders(UserId u) {
            if (failReads) throw new BrokerException("broker down", "NetworkException", true);
            return List.copyOf(orders);
        }
        @Override public List<BrokerPosition> fetchPositions(UserId u) {
            if (failReads) throw new BrokerException("broker down", "NetworkException", true);
            return List.copyOf(positions);
        }
    }

    private StubBroker broker;
    private PositionBook book;
    private FixedTradingClock clock;
    private PositionLifecycle lifecycle;
    private Reconciler reconciler;
    private UserAccount account;

    @BeforeEach
    void setUp() {
        broker = new StubBroker();
        book = new PositionBook();
        clock = new FixedTradingClock(NOW);
        lifecycle = new PositionLifecycle(broker, book, clock);
        reconciler = new Reconciler(broker, book, lifecycle, clock);
        account = new UserAccount(
                new TradingUser(UserId.random(), "test", "", UserStatus.ACTIVE, Set.of(Role.TRADER)),
                RiskLimits.conservative(), StrategyThresholds.defaults());
        account.setEntriesEnabled(true);
    }

    private TradeIntent intent() {
        return new TradeIntent(account.userId(), "RELIANCE", Direction.LONG,
                EntryPattern.PULLBACK_CONTINUATION, 1000, 990, 1020, NOW, "test");
    }

    private RiskDecision approval() {
        return RiskDecision.approve(100, 990, 1000, 100_000, "test");
    }

    private static BrokerOrder order(String tag, OrderStatus status, int qty, int filled,
                                     double price) {
        return new BrokerOrder("o-" + tag, "RELIANCE", OrderSide.BUY, status,
                qty, filled, price, "", NOW, tag);
    }

    private static BrokerPosition held(int qty, double last) {
        return new BrokerPosition("RELIANCE", qty, 1000, last, 0, 0, ProductType.MIS);
    }

    // ── Fills the engine never saw ───────────────────────────────────────────

    /**
     * The websocket drop case. An entry filled, the update was lost, and the position sat at
     * PENDING_ENTRY — which means {@code onTick} skipped it and no stop was ever evaluated for shares
     * that were really held.
     */
    @Test
    void recoversAFillWhoseOrderUpdateWasLost() {
        Position pending = lifecycle.open(account, intent(), approval(), account.epoch()).position();
        broker.orders.add(order(pending.entryTag().value(), OrderStatus.COMPLETE, 100, 100, 1002));
        broker.positions.add(held(100, 1002));

        Reconciler.Report report = reconciler.reconcile(account);

        Position recovered = book.byId(pending.id()).orElseThrow();
        assertThat(recovered.status()).isEqualTo(PositionStatus.OPEN);
        assertThat(recovered.entryPrice())
                .as("the actual fill, so the stop distance and risk reflect what really happened")
                .isEqualTo(1002);
        assertThat(report.resolvedFills()).isEqualTo(1);
    }

    @Test
    void recoversARejectionTheEngineNeverSaw() {
        Position pending = lifecycle.open(account, intent(), approval(), account.epoch()).position();
        broker.orders.add(order(pending.entryTag().value(), OrderStatus.REJECTED, 100, 0, 0));

        reconciler.reconcile(account);

        assertThat(book.byId(pending.id()).orElseThrow().status())
                .as("a rejected entry left pending occupies a slot and blocks the symbol forever")
                .isEqualTo(PositionStatus.CLOSED);
    }

    /**
     * An unrecognised status must not be resolved by guessing. Both COMPLETE and CANCELLED authorise
     * the lifecycle to act, and they are opposite actions.
     */
    @Test
    void leavesAnUnknownOrderStatusAlone() {
        Position pending = lifecycle.open(account, intent(), approval(), account.epoch()).position();
        broker.orders.add(order(pending.entryTag().value(), OrderStatus.UNKNOWN, 100, 0, 0));

        reconciler.reconcile(account);

        assertThat(book.byId(pending.id()).orElseThrow().status())
                .isEqualTo(PositionStatus.PENDING_ENTRY);
    }

    // ── Submissions whose outcome was unknown ────────────────────────────────

    /**
     * The worst case in the system: {@code placeOrder} timed out, so no position was recorded, but
     * the order reached the exchange and filled. Without this the engine holds shares it has no
     * record of — no stop, no target, and no square-off before the broker's own.
     */
    @Test
    void adoptsAnEntryThatFilledAfterTheSubmissionTimedOut() {
        broker.placeFailure = new BrokerException("POST /orders/regular failed: SocketTimeout",
                new java.net.SocketTimeoutException("timeout"));

        var outcome = lifecycle.open(account, intent(), approval(), account.epoch());
        assertThat(outcome.succeeded()).isFalse();
        assertThat(book.forUser(account.userId())).isEmpty();
        assertThat(lifecycle.unconfirmedEntries())
                .as("the tag is minted before the wire precisely so this is recoverable")
                .isEqualTo(1);

        // The order did land, and Kite reports it against the tag the engine minted.
        broker.placeFailure = null;
        String tag = broker.attempted.get(0).tag();
        broker.orders.add(order(tag, OrderStatus.COMPLETE, 100, 100, 1003));
        broker.positions.add(held(100, 1003));

        Reconciler.Report report = reconciler.reconcile(account);

        assertThat(report.adoptedEntries()).isEqualTo(1);
        Position adopted = book.withExposure(account.userId()).get(0);
        assertThat(adopted.status()).isEqualTo(PositionStatus.OPEN);
        assertThat(adopted.entryPrice()).isEqualTo(1003);
        assertThat(adopted.stopPrice())
                .as("the stop risk authorised, not one derived from where price is now")
                .isEqualTo(990);
        assertThat(adopted.targetPrice()).isEqualTo(1020);
        assertThat(report.orphans())
                .as("once adopted the shares are accounted for and must not also read as an orphan")
                .isEmpty();
    }

    @Test
    void doesNotAdoptWhenTheTimedOutOrderNeverReachedTheExchange() {
        broker.placeFailure = new BrokerException("POST /orders/regular failed: SocketTimeout",
                new java.net.SocketTimeoutException("timeout"));
        lifecycle.open(account, intent(), approval(), account.epoch());
        broker.placeFailure = null;

        clock.advance(Duration.ofSeconds(120));                 // past the grace period
        reconciler.reconcile(account);

        assertThat(book.forUser(account.userId())).isEmpty();
        assertThat(lifecycle.unconfirmedEntries())
                .as("absent from today's orders after the grace period means it never arrived")
                .isZero();
    }

    @Test
    void anOutrightRefusalIsNotHeldForReconciliation() {
        broker.placeFailure = new BrokerException("Insufficient funds", "MarginException", false);

        lifecycle.open(account, intent(), approval(), account.epoch());

        assertThat(lifecycle.unconfirmedEntries())
                .as("the broker answered and said no; there is nothing at the exchange to find")
                .isZero();
    }

    // ── Positions closed outside the engine ─────────────────────────────────

    @Test
    void closesAPositionTheBrokerNoLongerHolds() {
        Position open = openAndFill();
        // Squared off by hand in Kite: the broker reports nothing intraday.
        broker.sent.clear();

        Reconciler.Report report = reconciler.reconcile(account);

        Position closed = book.byId(open.id()).orElseThrow();
        assertThat(closed.status())
                .as("left OPEN it occupies a slot and invites an exit for shares that are gone")
                .isEqualTo(PositionStatus.CLOSED);
        assertThat(report.externalCloses()).isEqualTo(1);
        assertThat(broker.sent)
                .as("reconciliation must never place an order — it only records what is already true")
                .isEmpty();
    }

    @Test
    void leavesAPositionAloneWhenTheBrokerAgreesItIsHeld() {
        Position open = openAndFill();
        broker.positions.add(held(100, 1010));

        Reconciler.Report report = reconciler.reconcile(account);

        assertThat(book.byId(open.id()).orElseThrow().status()).isEqualTo(PositionStatus.OPEN);
        assertThat(report.inAgreement()).isTrue();
        assertThat(report.actedOnAnything()).isFalse();
    }

    // ── Orphans ──────────────────────────────────────────────────────────────

    /**
     * A holding the engine did not create is reported, never adopted. It may be the user's own manual
     * trade, and taking ownership would mean squaring off a position somebody else is managing.
     */
    @Test
    void reportsAnIntradayHoldingTheEngineDidNotOpenWithoutTouchingIt() {
        broker.positions.add(held(50, 1010));

        Reconciler.Report report = reconciler.reconcile(account);

        assertThat(report.orphans()).containsExactly("RELIANCE x50");
        assertThat(broker.sent).isEmpty();
        assertThat(report.inAgreement()).isFalse();
        assertThat(broker.sent).isEmpty();
        assertThat(book.forUser(account.userId())).isEmpty();
    }

    @Test
    void aDeliveryHoldingIsNotAnOrphan() {
        broker.positions.add(new BrokerPosition("RELIANCE", 50, 1000, 1010, 0, 0, ProductType.CNC));

        Reconciler.Report report = reconciler.reconcile(account);

        assertThat(report.orphans())
                .as("a CNC holding is the user's investment account and has nothing to do with this "
                        + "engine; flagging it would fire every sixty seconds forever")
                .isEmpty();
    }

    @Test
    void reportsOnlyTheSharesBeyondWhatTheEngineAccountsFor() {
        openAndFill();                                   // engine holds 100
        broker.positions.add(held(130, 1010));           // broker holds 130

        assertThat(reconciler.reconcile(account).orphans()).containsExactly("RELIANCE x30");
    }

    // ── Entries the broker never saw ────────────────────────────────────────

    @Test
    void abandonsRatherThanClosesAnEntryTheBrokerHasNoRecordOf() {
        Position pending = lifecycle.open(account, intent(), approval(), account.epoch()).position();

        clock.advance(Duration.ofSeconds(120));
        Reconciler.Report report = reconciler.reconcile(account);

        assertThat(book.byId(pending.id()).orElseThrow().status())
                .as("CLOSED would imply the engine knows the trade is over; it knows the opposite")
                .isEqualTo(PositionStatus.ABANDONED);
        assertThat(report.abandoned()).isEqualTo(1);
        assertThat(report.inAgreement()).isFalse();
    }

    @Test
    void doesNotAbandonAnEntryStillInsideTheGracePeriod() {
        Position pending = lifecycle.open(account, intent(), approval(), account.epoch()).position();

        clock.advance(Duration.ofSeconds(30));
        reconciler.reconcile(account);

        assertThat(book.byId(pending.id()).orElseThrow().status())
                .as("a slow acknowledgement is not a missing order")
                .isEqualTo(PositionStatus.PENDING_ENTRY);
    }

    // ── When the broker cannot be read ──────────────────────────────────────

    @Test
    void aFailedPassSaysSoRatherThanReportingAgreement() {
        openAndFill();
        broker.failReads = true;

        Reconciler.Report report = reconciler.reconcile(account);

        assertThat(report.failed()).isNotNull();
        assertThat(report.inAgreement())
                .as("a stale clean report is worse than none — it reads as 'the views agree'")
                .isFalse();
        assertThat(book.withExposure(account.userId()))
                .as("an unreadable broker must never be taken as 'the broker holds nothing'")
                .hasSize(1);
    }

    @Test
    void doesNotTouchAnythingBeforeTheMorningLogin() {
        openAndFill();
        broker.authenticated = false;

        Reconciler.Report report = reconciler.reconcile(account);

        assertThat(report.failed()).contains("not connected");
        assertThat(book.withExposure(account.userId())).hasSize(1);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Position openAndFill() {
        Position position = lifecycle.open(account, intent(), approval(), account.epoch()).position();
        lifecycle.onOrderUpdate(account.userId(),
                order(position.entryTag().value(), OrderStatus.COMPLETE, 100, 100, 1000), account);
        return book.byId(position.id()).orElseThrow();
    }

}
