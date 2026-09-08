package com.equity.trading;

import static org.assertj.core.api.Assertions.assertThat;

import com.equity.broker.BrokerException;
import com.equity.broker.BrokerOrder;
import com.equity.broker.BrokerPort;
import com.equity.broker.BrokerPosition;
import com.equity.broker.OrderRequest;
import com.equity.broker.OrderSide;
import com.equity.broker.OrderStatus;
import com.equity.domain.Direction;
import com.equity.domain.market.Tick;
import com.equity.domain.momentum.EntryPattern;
import com.equity.domain.position.ExitReason;
import com.equity.domain.position.Position;
import com.equity.domain.position.PositionStatus;
import com.equity.domain.risk.RiskDecision;
import com.equity.domain.risk.RiskLimits;
import com.equity.domain.user.Role;
import com.equity.domain.user.TradingUser;
import com.equity.domain.user.UserId;
import com.equity.domain.user.UserStatus;
import com.equity.domain.order.TradeIntent;
import com.equity.platform.time.FixedTradingClock;
import com.equity.strategy.StrategyThresholds;
import com.equity.user.UserAccount;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The single owner of position transitions.
 *
 * <p>These are the tests that matter most in the codebase. Every one of them corresponds to a
 * failure that actually happened in the sibling options engine, or to a race the design notes
 * identified and this class is supposed to close.</p>
 */
class PositionLifecycleTest {

    private static final Instant NOW = Instant.parse("2026-09-04T05:00:00Z");   // 10:30 IST

    /** Records what was sent and answers with whatever the test wants. */
    private static final class RecordingBroker implements BrokerPort {
        final List<OrderRequest> sent = new ArrayList<>();
        boolean refuse;
        int sequence;

        @Override public String placeOrder(UserId userId, OrderRequest request) {
            if (refuse) throw new BrokerException("refused by test", "InputException", false);
            sent.add(request);
            return "order-" + (++sequence);
        }
        @Override public void cancelOrder(UserId userId, String brokerOrderId) {}
        @Override public void cancelOrder(UserId u, String id, com.equity.broker.OrderVariety v) {}
        @Override public void modifyOrder(UserId u, String id, int q, double p) {}
        @Override public List<BrokerOrder> fetchOrders(UserId userId) { return List.of(); }
        @Override public List<BrokerPosition> fetchPositions(UserId userId) { return List.of(); }
        @Override public double availableMargin(UserId userId) { return 1_000_000; }
        @Override public boolean isAuthenticated(UserId userId) { return true; }
    }

    private RecordingBroker broker;
    private PositionBook book;
    private FixedTradingClock clock;
    private PositionLifecycle lifecycle;
    private UserAccount account;

    @BeforeEach
    void setUp() {
        broker = new RecordingBroker();
        book = new PositionBook();
        clock = new FixedTradingClock(NOW);
        lifecycle = new PositionLifecycle(broker, book, clock);
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

    private Position openAndFill() {
        Position position = lifecycle.open(account, intent(), approval(), account.epoch()).position();
        lifecycle.onOrderUpdate(account.userId(), fill(position.entryTag().value(), 100, 1000), account);
        return book.byId(position.id()).orElseThrow();
    }

    private static BrokerOrder fill(String tag, int qty, double price) {
        return new BrokerOrder("o1", "RELIANCE", OrderSide.BUY, OrderStatus.COMPLETE,
                qty, qty, price, "", NOW, tag);
    }

    /**
     * A broker rejection of an entry, which used to throw.
     *
     * <p>{@code withStatus(CLOSED)} left {@code closedAt} null, and the per-symbol cooldown is keyed
     * on it, so recording the transition raised a NullPointerException — on the order-update thread,
     * while handling the rejection. The update was lost and the position stayed PENDING_ENTRY
     * forever, holding a slot and blocking the symbol. Nothing covered this path until reconciliation
     * replayed a rejection through it.</p>
     */
    @Test
    void aRejectedEntryIsClosedAndTimestampedRatherThanThrowing() {
        Position position = lifecycle.open(account, intent(), approval(), account.epoch()).position();

        lifecycle.onOrderUpdate(account.userId(), new BrokerOrder("o1", "RELIANCE", OrderSide.BUY,
                OrderStatus.REJECTED, 100, 0, 0, "margin exceeded", NOW,
                position.entryTag().value()), account);

        Position closed = book.byId(position.id()).orElseThrow();
        assertThat(closed.status()).isEqualTo(PositionStatus.CLOSED);
        assertThat(closed.closedAt())
                .as("the transition that ends a position must carry the time it ended")
                .isEqualTo(NOW);
        assertThat(book.pendingEntries(account.userId()))
                .as("a rejection left pending blocks the symbol for the rest of the session")
                .isEmpty();
    }

    // ── Opening ──────────────────────────────────────────────────────────────

    @Test
    void submitsAnEntryAndTracksItAsPending() {
        Position position = lifecycle.open(account, intent(), approval(), account.epoch()).position();

        assertThat(broker.sent).hasSize(1);
        assertThat(broker.sent.get(0).side()).isEqualTo(OrderSide.BUY);
        assertThat(broker.sent.get(0).quantity()).isEqualTo(100);
        assertThat(position.status()).isEqualTo(PositionStatus.PENDING_ENTRY);
        assertThat(book.pendingEntries(account.userId())).hasSize(1);
    }

    @Test
    void aFillRecordsTheActualPriceNotTheIntendedOne() {
        Position position = lifecycle.open(account, intent(), approval(), account.epoch()).position();

        lifecycle.onOrderUpdate(account.userId(), fill(position.entryTag().value(), 100, 1004.5), account);

        Position filled = book.byId(position.id()).orElseThrow();
        assertThat(filled.status()).isEqualTo(PositionStatus.OPEN);
        assertThat(filled.entryPrice()).isEqualTo(1004.5);
        assertThat(filled.riskAtStop())
                .as("design note 0.8: a slipped fill risks more than the sizing allowed for")
                .isEqualTo(1450.0);
    }

    @Test
    void aBrokerRefusalLeavesNoPositionBehind() {
        broker.refuse = true;

        var outcome = lifecycle.open(account, intent(), approval(), account.epoch());
        assertThat(outcome.succeeded()).isFalse();
        assertThat(outcome.message())
                .as("the caller must be able to show the broker's own words, not a guess")
                .contains("refused by test");
        assertThat(book.all()).isEmpty();
    }

    @Test
    void aDeniedRiskDecisionNeverReachesTheBroker() {
        var denial = RiskDecision.deny(com.equity.domain.risk.DenialReason.SPREAD_TOO_WIDE);

        assertThat(lifecycle.open(account, intent(), denial, account.epoch()).succeeded()).isFalse();
        assertThat(broker.sent).isEmpty();
    }

    // ── The epoch race (design note 0.3) ─────────────────────────────────────

    @Test
    void anEntryAuthorisedBeforeAHaltIsNotSentAfterIt() {
        long epochAtDecision = account.epoch();
        account.halt();

        var outcome = lifecycle.open(account, intent(), approval(), epochAtDecision);
        assertThat(outcome.succeeded()).isFalse();
        assertThat(outcome.message())
                .as("an epoch drop and a broker refusal must be distinguishable")
                .contains("stopped between authorisation and submission");
        assertThat(broker.sent)
                .as("the operator stopped this user between authorisation and submission")
                .isEmpty();
    }

    @Test
    void aFillArrivingAfterAHaltIsClosedImmediatelyRatherThanAdopted() {
        Position position = lifecycle.open(account, intent(), approval(), account.epoch()).position();
        account.halt();   // the operator halts while the order is in flight

        lifecycle.onOrderUpdate(account.userId(), fill(position.entryTag().value(), 100, 1000), account);

        assertThat(lifecycle.disownedFills()).isEqualTo(1);
        assertThat(lifecycle.queuedExits())
                .as("a pre-submission checklist cannot close this window; a compensating exit can")
                .isEqualTo(1);

        lifecycle.drainExits();
        assertThat(book.byId(position.id()).orElseThrow().status())
                .isEqualTo(PositionStatus.EXIT_PENDING);
    }

    // ── Exits ────────────────────────────────────────────────────────────────

    @Test
    void aTickThroughTheStopQueuesExactlyOneExit() {
        Position position = openAndFill();

        for (int i = 0; i < 10; i++) {
            lifecycle.onTick(new Tick("RELIANCE", 985, 0, 984, 986, 0, 0, 0, 0, NOW, NOW));
        }

        assertThat(lifecycle.queuedExits()).isEqualTo(1);
        assertThat(lifecycle.drainExits()).isEqualTo(1);
        assertThat(broker.sent).hasSize(2);
        assertThat(broker.sent.get(1).side())
                .as("closing a long is a sell")
                .isEqualTo(OrderSide.SELL);
        assertThat(book.byId(position.id()).orElseThrow().exitReason()).isEqualTo(ExitReason.HARD_STOP);
    }

    @Test
    void aTickThroughBothStopAndTargetExitsOnTheStop() {
        Position position = openAndFill();

        // One tick that is simultaneously below the stop and above the target is impossible, but a
        // gap that trips both checks within a tick batch is not — and that is the dangerous case.
        lifecycle.onTick(new Tick("RELIANCE", 1025, 0, 1024, 1026, 0, 0, 0, 0, NOW, NOW));
        lifecycle.onTick(new Tick("RELIANCE", 985, 0, 984, 986, 0, 0, 0, 0, NOW, NOW));

        lifecycle.drainExits();
        assertThat(book.byId(position.id()).orElseThrow().exitReason())
                .as("booking the target here would record a profit that never existed")
                .isEqualTo(ExitReason.HARD_STOP);
    }

    @Test
    void exitsStillWorkWhileTheUserIsHalted() {
        Position position = openAndFill();
        account.halt();

        lifecycle.onTick(new Tick("RELIANCE", 985, 0, 984, 986, 0, 0, 0, 0, NOW, NOW));
        lifecycle.drainExits();

        assertThat(book.byId(position.id()).orElseThrow().status())
                .as("design note 0.1: a halt must never strand live exposure")
                .isEqualTo(PositionStatus.EXIT_PENDING);
    }

    @Test
    void aPositionAlreadyExitingIsNotSentASecondExit() {
        openAndFill();
        lifecycle.onTick(new Tick("RELIANCE", 985, 0, 984, 986, 0, 0, 0, 0, NOW, NOW));
        lifecycle.drainExits();
        int afterFirst = broker.sent.size();

        lifecycle.onTick(new Tick("RELIANCE", 980, 0, 979, 981, 0, 0, 0, 0, NOW, NOW));
        lifecycle.drainExits();

        assertThat(broker.sent).hasSize(afterFirst);
    }

    @Test
    void anExitThatCouldNotBeSentIsRequeuedNotDropped() {
        Position position = openAndFill();
        broker.refuse = true;

        lifecycle.onTick(new Tick("RELIANCE", 985, 0, 984, 986, 0, 0, 0, 0, NOW, NOW));
        assertThat(lifecycle.drainExits()).isZero();

        assertThat(lifecycle.queuedExits())
                .as("silently dropping an exit leaves a position open with nothing arranged to close it")
                .isEqualTo(1);

        broker.refuse = false;
        assertThat(lifecycle.drainExits()).isEqualTo(1);
        assertThat(book.byId(position.id()).orElseThrow().status()).isEqualTo(PositionStatus.EXIT_PENDING);
    }

    @Test
    void aRejectedExitOrderPutsThePositionBackToOpenSoItCanBeRetried() {
        Position position = openAndFill();
        lifecycle.onTick(new Tick("RELIANCE", 985, 0, 984, 986, 0, 0, 0, 0, NOW, NOW));
        lifecycle.drainExits();
        Position exiting = book.byId(position.id()).orElseThrow();

        lifecycle.onOrderUpdate(account.userId(), new BrokerOrder("o2", "RELIANCE", OrderSide.SELL,
                OrderStatus.REJECTED, 100, 0, 0, "margin", NOW, exiting.exitTag().value()), account);

        assertThat(book.byId(position.id()).orElseThrow().status())
                .as("leaving it EXIT_PENDING would make the tick check ignore it forever")
                .isEqualTo(PositionStatus.OPEN);
    }

    @Test
    void aCompletedExitClosesThePositionAndBooksTheResult() {
        Position position = openAndFill();
        lifecycle.onTick(new Tick("RELIANCE", 985, 0, 984, 986, 0, 0, 0, 0, NOW, NOW));
        lifecycle.drainExits();
        Position exiting = book.byId(position.id()).orElseThrow();

        lifecycle.onOrderUpdate(account.userId(), new BrokerOrder("o2", "RELIANCE", OrderSide.SELL,
                OrderStatus.COMPLETE, 100, 100, 984.5, "", NOW, exiting.exitTag().value()), account);

        Position closed = book.byId(position.id()).orElseThrow();
        assertThat(closed.status()).isEqualTo(PositionStatus.CLOSED);
        assertThat(closed.realisedPnl()).isEqualTo((984.5 - 1000) * 100);
    }

    @Test
    void anUnrecognisedOrderStatusLeavesThePositionPendingRatherThanGuessing() {
        Position position = lifecycle.open(account, intent(), approval(), account.epoch()).position();

        lifecycle.onOrderUpdate(account.userId(), new BrokerOrder("o1", "RELIANCE", OrderSide.BUY,
                OrderStatus.UNKNOWN, 100, 0, 0, "?", NOW, position.entryTag().value()), account);

        assertThat(book.byId(position.id()).orElseThrow().status())
                .isEqualTo(PositionStatus.PENDING_ENTRY);
    }

    @Test
    void anOrderUpdateForAnotherPositionInTheSameSymbolIsNotApplied() {
        Position position = openAndFill();

        // Same symbol, a tag this engine never minted — a manual order placed in the Kite app, say.
        lifecycle.onOrderUpdate(account.userId(), new BrokerOrder("manual", "RELIANCE",
                OrderSide.SELL, OrderStatus.COMPLETE, 100, 100, 900, "", NOW, "someone-else"), account);

        assertThat(book.byId(position.id()).orElseThrow().status())
                .as("matching on symbol rather than tag would apply a stranger's fill to our position")
                .isEqualTo(PositionStatus.OPEN);
    }

    @Test
    void theStrategyPathAlwaysTradesIntradayInTheCurrentSession() {
        lifecycle.open(account, intent(), approval(), account.epoch());

        assertThat(broker.sent.get(0).product())
                .as("this is an intraday engine; everything it opens is squared off the same session")
                .isEqualTo(com.equity.broker.ProductType.MIS);
        assertThat(broker.sent.get(0).variety())
                .as("an after-market entry would open a position with nobody watching it")
                .isEqualTo(com.equity.broker.OrderVariety.REGULAR);
    }

    @Test
    void anExitIsNeverSentAfterMarket() {
        Position position = openAndFill();
        lifecycle.requestExit(position, ExitReason.MANUAL, "test");
        lifecycle.drainExits();

        assertThat(broker.sent.get(1).variety())
                .as("an AMO exit would leave the position open overnight, which is the opposite "
                        + "of what every exit reason means")
                .isEqualTo(com.equity.broker.OrderVariety.REGULAR);
    }

    /**
     * The engine only ever opens MIS today, so this position is built by hand. The invariant is
     * still worth holding: a position adopted from the broker during reconciliation can be CNC, and
     * squaring a CNC holding off with an MIS sell does not close it — it opens an intraday short
     * alongside it.
     */
    @Test
    void theExitUsesTheProductThePositionHolds() {
        Position cnc = Position.pendingEntry(account.userId(), "RELIANCE", Direction.LONG,
                        EntryPattern.PULLBACK_CONTINUATION, com.equity.broker.ProductType.CNC,
                        100, 1000, 990, 1020,
                        com.equity.domain.order.OrderTag.forEntry(account.userId()), "o9", NOW)
                .withFill(100, 1000, NOW);
        book.put(cnc);

        lifecycle.requestExit(cnc, ExitReason.MANUAL, "test");
        lifecycle.drainExits();

        assertThat(broker.sent.get(0).product())
                .as("an MIS sell against a CNC holding opens a short instead of closing it")
                .isEqualTo(com.equity.broker.ProductType.CNC);
    }

    @Test
    void aTimeStopClosesAPositionThatResolvedNeitherWay() {
        openAndFill();
        clock.advance(java.time.Duration.ofMinutes(46));

        lifecycle.checkTimeStops(account.userId(), 45);

        assertThat(lifecycle.queuedExits()).isEqualTo(1);
    }

    @Test
    void requestExitAllClosesEverythingTheUserHolds() {
        openAndFill();

        lifecycle.requestExitAll(account.userId(), ExitReason.SQUARE_OFF, "end of session");
        lifecycle.drainExits();

        assertThat(book.withExposure(account.userId()))
                .allMatch(p -> p.status() == PositionStatus.EXIT_PENDING);
    }

    /**
     * A fill has to be announced, or nothing downstream can count it.
     *
     * <p>{@code AccountLedger.recordFill} existed, was tested, and was called from nowhere in the
     * engine. After a session of eight filled positions the ledger still read {@code fills 0}, so
     * every hit rate derived from it was zero. The book already knows the moment an entry fills; it
     * simply never said so.</p>
     */
    @Test
    void theBookAnnouncesAFillSoTheLedgerCanCountIt() {
        java.util.List<Position> filled = new java.util.ArrayList<>();
        book.onOpened(filled::add);

        Position position = lifecycle.open(account, intent(), approval(), account.epoch()).position();
        assertThat(filled).as("submitting is not filling").isEmpty();

        lifecycle.onOrderUpdate(account.userId(), fill(position.entryTag().value(), 100, 1000), account);

        assertThat(filled).hasSize(1);
        assertThat(filled.get(0).symbol()).isEqualTo("RELIANCE");
    }

    /** An exit that failed puts the position back to OPEN; that is not a second fill. */
    @Test
    void aFailedExitDoesNotCountAsAnotherFill() {
        java.util.List<Position> filled = new java.util.ArrayList<>();
        Position open = openAndFill();
        book.onOpened(filled::add);

        lifecycle.requestExit(open, ExitReason.MANUAL, "test");
        lifecycle.drainExits();
        Position exiting = book.byId(open.id()).orElseThrow();
        lifecycle.onOrderUpdate(account.userId(), new BrokerOrder("x1", "RELIANCE", OrderSide.SELL,
                OrderStatus.REJECTED, 100, 0, 0, "no", NOW, exiting.exitTag().value()), account);

        assertThat(book.byId(open.id()).orElseThrow().status()).isEqualTo(PositionStatus.OPEN);
        assertThat(filled).as("only the transition out of PENDING_ENTRY is a fill").isEmpty();
    }
}
