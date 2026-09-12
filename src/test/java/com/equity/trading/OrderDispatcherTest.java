package com.equity.trading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The dispatcher's two promises: one user's broker never delays another user's order, and no
 * order is ever sent twice because two threads noticed the same thing.
 */
class OrderDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-09-04T05:00:00Z");

    /**
     * A broker whose calls can be held open until the test releases them, and which records the
     * thread each call arrived on. That is what lets a test say "B did not wait for A".
     */
    private static final class GatedBroker implements BrokerPort {
        final List<OrderRequest> sent = new CopyOnWriteArrayList<>();
        final List<String> threads = new CopyOnWriteArrayList<>();
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger peakInFlight = new AtomicInteger();
        volatile CountDownLatch gate = new CountDownLatch(0);
        volatile boolean refuse;
        private final AtomicInteger sequence = new AtomicInteger();

        @Override public String placeOrder(UserId userId, OrderRequest request) {
            int now = inFlight.incrementAndGet();
            peakInFlight.accumulateAndGet(now, Math::max);
            threads.add(Thread.currentThread().getName());
            try {
                if (!gate.await(5, TimeUnit.SECONDS)) throw new AssertionError("gate never opened");
                if (refuse) throw new BrokerException("refused by test", "InputException", false);
                sent.add(request);
                return "order-" + sequence.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BrokerException("interrupted", "test", false);
            } finally {
                inFlight.decrementAndGet();
            }
        }
        @Override public void cancelOrder(UserId userId, String brokerOrderId) {}
        @Override public void cancelOrder(UserId u, String id, com.equity.broker.OrderVariety v) {}
        @Override public void modifyOrder(UserId u, String id, int q, double p) {}
        @Override public List<BrokerOrder> fetchOrders(UserId userId) { return List.of(); }
        @Override public List<BrokerPosition> fetchPositions(UserId userId) { return List.of(); }
        @Override public double availableMargin(UserId userId) { return 1_000_000; }
        @Override public boolean isAuthenticated(UserId userId) { return true; }
    }

    private GatedBroker broker;
    private PositionBook book;
    private PositionLifecycle lifecycle;
    private OrderDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        broker = new GatedBroker();
        book = new PositionBook();
        lifecycle = new PositionLifecycle(broker, book, new FixedTradingClock(NOW));
        dispatcher = new OrderDispatcher(lifecycle);
    }

    @AfterEach
    void tearDown() {
        broker.gate.countDown();
        dispatcher.shutdown();
    }

    private static UserAccount account() {
        UserAccount account = new UserAccount(
                new TradingUser(UserId.random(), "test", "", UserStatus.ACTIVE, Set.of(Role.TRADER)),
                RiskLimits.conservative(), StrategyThresholds.defaults());
        account.setEntriesEnabled(true);
        return account;
    }

    private static TradeIntent intent(UserAccount account, String symbol) {
        return new TradeIntent(account.userId(), symbol, Direction.LONG,
                EntryPattern.PULLBACK_CONTINUATION, 1000, 990, 1020, NOW, "test");
    }

    private static RiskDecision approval() {
        return RiskDecision.approve(100, 990, 1000, 100_000, "test");
    }

    private void submit(UserAccount account, String symbol, List<PositionLifecycle.EntryOutcome> into) {
        dispatcher.submitEntry(account, intent(account, symbol), approval(), account.epoch(), into::add);
    }

    private Position openAndFill(UserAccount account, String symbol) {
        Position position = lifecycle.open(account, intent(account, symbol), approval(), account.epoch()).position();
        lifecycle.onOrderUpdate(account.userId(), new BrokerOrder("o", symbol, OrderSide.BUY,
                OrderStatus.COMPLETE, 100, 100, 1000, "", NOW, position.entryTag().value()), account);
        return book.byId(position.id()).orElseThrow();
    }

    // ── Entries ──────────────────────────────────────────────────────────────

    @Test
    void anEntryIsPlacedOffTheCallingThreadAndTheOutcomeComesBack() {
        UserAccount user = account();
        List<PositionLifecycle.EntryOutcome> outcomes = new CopyOnWriteArrayList<>();

        submit(user, "RELIANCE", outcomes);

        await().atMost(Duration.ofSeconds(2)).until(() -> outcomes.size() == 1);
        assertThat(outcomes.get(0).succeeded()).isTrue();
        assertThat(broker.threads.get(0))
                .as("the broker call must not run on the thread that decided the entry")
                .isNotEqualTo(Thread.currentThread().getName())
                .startsWith("orders-");
        assertThat(book.pendingEntries(user.userId())).hasSize(1);
    }

    @Test
    void twoUsersEntriesAreInFlightAtTheBrokerAtTheSameTime() {
        UserAccount a = account();
        UserAccount b = account();
        broker.gate = new CountDownLatch(1);
        List<PositionLifecycle.EntryOutcome> outcomes = new CopyOnWriteArrayList<>();

        submit(a, "RELIANCE", outcomes);
        submit(b, "RELIANCE", outcomes);

        // Both calls reach the broker and wait at the gate together. Under the old single lock the
        // second could not have started until the first had returned.
        await().atMost(Duration.ofSeconds(2)).until(() -> broker.inFlight.get() == 2);
        assertThat(outcomes).isEmpty();

        broker.gate.countDown();
        await().atMost(Duration.ofSeconds(2)).until(() -> outcomes.size() == 2);
        assertThat(broker.peakInFlight.get()).isEqualTo(2);
        assertThat(broker.threads.stream().distinct().count())
                .as("each user has their own thread")
                .isEqualTo(2);
    }

    @Test
    void oneUsersEntriesStaySequentialAndInOrder() {
        UserAccount a = account();
        broker.gate = new CountDownLatch(1);
        List<PositionLifecycle.EntryOutcome> outcomes = new CopyOnWriteArrayList<>();

        submit(a, "RELIANCE", outcomes);
        submit(a, "TCS", outcomes);
        submit(a, "INFY", outcomes);

        await().atMost(Duration.ofSeconds(2)).until(() -> broker.inFlight.get() == 1);
        // Give the others every chance to start if they were going to.
        await().pollDelay(Duration.ofMillis(200)).atMost(Duration.ofSeconds(1))
                .until(() -> broker.inFlight.get() == 1);
        assertThat(broker.peakInFlight.get())
                .as("within a user orders are one at a time — that is what keeps the book consistent")
                .isEqualTo(1);

        broker.gate.countDown();
        await().atMost(Duration.ofSeconds(2)).until(() -> outcomes.size() == 3);
        assertThat(broker.sent.stream().map(OrderRequest::symbol).toList())
                .containsExactly("RELIANCE", "TCS", "INFY");
        assertThat(broker.threads.stream().distinct().count()).isEqualTo(1);
    }

    @Test
    void aSlowBrokerForOneUserDoesNotHoldUpAnotherUsersEntry() {
        UserAccount slow = account();
        UserAccount quick = account();
        broker.gate = new CountDownLatch(1);
        List<PositionLifecycle.EntryOutcome> slowOutcomes = new CopyOnWriteArrayList<>();
        List<PositionLifecycle.EntryOutcome> quickOutcomes = new CopyOnWriteArrayList<>();

        submit(slow, "RELIANCE", slowOutcomes);
        await().atMost(Duration.ofSeconds(2)).until(() -> broker.inFlight.get() == 1);

        // The gate is a per-call wait; open it only for calls that start after this point by
        // swapping the latch. The slow user's call is still parked on the old one.
        CountDownLatch slowGate = broker.gate;
        broker.gate = new CountDownLatch(0);

        submit(quick, "TCS", quickOutcomes);
        await().atMost(Duration.ofSeconds(2)).until(() -> quickOutcomes.size() == 1);
        assertThat(slowOutcomes).as("the slow user is still waiting on their broker").isEmpty();
        assertThat(quickOutcomes.get(0).succeeded()).isTrue();

        slowGate.countDown();
        await().atMost(Duration.ofSeconds(2)).until(() -> slowOutcomes.size() == 1);
    }

    @Test
    void aBrokerRefusalIsReportedAsAnOutcomeAndTheThreadSurvives() {
        UserAccount a = account();
        broker.refuse = true;
        List<PositionLifecycle.EntryOutcome> outcomes = new CopyOnWriteArrayList<>();

        submit(a, "RELIANCE", outcomes);
        await().atMost(Duration.ofSeconds(2)).until(() -> outcomes.size() == 1);
        assertThat(outcomes.get(0).succeeded()).isFalse();
        assertThat(outcomes.get(0).message()).contains("refused by test");

        broker.refuse = false;
        submit(a, "TCS", outcomes);
        await().atMost(Duration.ofSeconds(2)).until(() -> outcomes.size() == 2);
        assertThat(outcomes.get(1).succeeded()).as("the user's thread is still working").isTrue();
    }

    @Test
    void anOutcomeHandlerThatThrowsDoesNotKillTheUsersThread() {
        UserAccount a = account();
        AtomicInteger calls = new AtomicInteger();

        dispatcher.submitEntry(a, intent(a, "RELIANCE"), approval(), a.epoch(), outcome -> {
            calls.incrementAndGet();
            throw new IllegalStateException("handler bug");
        });
        dispatcher.submitEntry(a, intent(a, "TCS"), approval(), a.epoch(), outcome -> calls.incrementAndGet());

        await().atMost(Duration.ofSeconds(2)).until(() -> calls.get() == 2);
        assertThat(broker.sent).hasSize(2);
    }

    // ── Exits ────────────────────────────────────────────────────────────────

    @Test
    void aStopIsSentAsSoonAsItIsQueuedWithoutWaitingForTheSweep() {
        UserAccount a = account();
        Position position = openAndFill(a, "RELIANCE");

        lifecycle.onTick(new Tick("RELIANCE", 985, 0, 984, 986, 0, 0, 0, 0, NOW, NOW));

        // No call to sweep() or drainExits() anywhere here: the offer itself signalled the dispatcher.
        await().atMost(Duration.ofSeconds(2)).until(() -> broker.sent.size() == 2);
        assertThat(broker.sent.get(1).side()).isEqualTo(OrderSide.SELL);
        assertThat(book.byId(position.id()).orElseThrow().status()).isEqualTo(PositionStatus.EXIT_PENDING);
        assertThat(lifecycle.queuedExits()).isZero();
    }

    @Test
    void aBurstOfTicksThroughTheStopSendsExactlyOneExit() {
        UserAccount a = account();
        openAndFill(a, "RELIANCE");
        broker.gate = new CountDownLatch(1);

        for (int i = 0; i < 25; i++) {
            lifecycle.onTick(new Tick("RELIANCE", 985 - i, 0, 984, 986, 0, 0, 0, 0, NOW, NOW));
        }
        broker.gate.countDown();

        await().atMost(Duration.ofSeconds(2)).until(() -> broker.sent.size() == 2);
        await().pollDelay(Duration.ofMillis(200)).atMost(Duration.ofSeconds(1))
                .until(() -> broker.sent.size() == 2);
        assertThat(broker.sent.stream().filter(r -> r.side() == OrderSide.SELL).count())
                .as("twenty-five ticks through the stop, one order")
                .isEqualTo(1);
    }

    @Test
    void twoThreadsRaisingExitsForTheSamePositionProduceOneOrder() throws Exception {
        UserAccount a = account();
        openAndFill(a, "RELIANCE");
        broker.gate = new CountDownLatch(1);

        CountDownLatch start = new CountDownLatch(1);
        List<Thread> raisers = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            Thread raiser = new Thread(() -> {
                try { start.await(); } catch (InterruptedException e) { return; }
                for (int i = 0; i < 50; i++) {
                    lifecycle.onTick(new Tick("RELIANCE", 985, 0, 984, 986, 0, 0, 0, 0, NOW, NOW));
                }
            }, "raiser-" + t);
            raisers.add(raiser);
            raiser.start();
        }
        start.countDown();
        for (Thread raiser : raisers) raiser.join(2000);
        broker.gate.countDown();

        await().atMost(Duration.ofSeconds(2)).until(() -> broker.sent.size() == 2);
        await().pollDelay(Duration.ofMillis(200)).atMost(Duration.ofSeconds(1))
                .until(() -> broker.sent.size() == 2);
        assertThat(broker.sent.stream().filter(r -> r.side() == OrderSide.SELL).count()).isEqualTo(1);
    }

    @Test
    void twoUsersStopsLeaveAtTheSameTimeOnTwoThreads() {
        UserAccount a = account();
        UserAccount b = account();
        openAndFill(a, "RELIANCE");
        openAndFill(b, "RELIANCE");
        broker.threads.clear();
        broker.gate = new CountDownLatch(1);

        // One tick, two users' positions. The lifecycle checks both and signals both.
        lifecycle.onTick(new Tick("RELIANCE", 985, 0, 984, 986, 0, 0, 0, 0, NOW, NOW));

        await().atMost(Duration.ofSeconds(2)).until(() -> broker.inFlight.get() == 2);
        broker.gate.countDown();
        await().atMost(Duration.ofSeconds(2)).until(() -> broker.sent.size() == 4);
        assertThat(broker.threads.stream().distinct().count())
                .as("each user's exit went on that user's thread")
                .isEqualTo(2);
    }

    @Test
    void aFailedExitIsRequeuedAndTheSweepSendsIt() {
        UserAccount a = account();
        Position position = openAndFill(a, "RELIANCE");
        broker.refuse = true;

        lifecycle.onTick(new Tick("RELIANCE", 985, 0, 984, 986, 0, 0, 0, 0, NOW, NOW));
        await().atMost(Duration.ofSeconds(2)).until(() -> broker.threads.size() == 2);
        await().pollDelay(Duration.ofMillis(100)).until(() -> lifecycle.queuedExits() == 1);
        assertThat(book.byId(position.id()).orElseThrow().status()).isEqualTo(PositionStatus.OPEN);

        broker.refuse = false;
        int resignalled = dispatcher.sweep();

        assertThat(resignalled).isEqualTo(1);
        await().atMost(Duration.ofSeconds(2)).until(() -> broker.sent.size() == 2);
        assertThat(book.byId(position.id()).orElseThrow().status()).isEqualTo(PositionStatus.EXIT_PENDING);
    }

    @Test
    void theSweepFindsNothingWhenEverythingWasSignalled() {
        UserAccount a = account();
        openAndFill(a, "RELIANCE");

        lifecycle.onTick(new Tick("RELIANCE", 985, 0, 984, 986, 0, 0, 0, 0, NOW, NOW));
        await().atMost(Duration.ofSeconds(2)).until(() -> broker.sent.size() == 2);

        assertThat(dispatcher.sweep()).isZero();
    }

    @Test
    void repeatedSignalsBeforeTheDrainRunsCoalesceIntoOneDrain() {
        UserAccount a = account();
        openAndFill(a, "RELIANCE");
        broker.gate = new CountDownLatch(1);

        // Park the user's thread on an entry so nothing can drain yet, then signal many times.
        submit(a, "TCS", new ArrayList<>());
        await().atMost(Duration.ofSeconds(2)).until(() -> broker.inFlight.get() == 1);
        long before = dispatcher.drainsDispatched();
        for (int i = 0; i < 20; i++) dispatcher.exitsPending(a.userId());

        assertThat(dispatcher.drainsDispatched() - before)
                .as("a burst of signals is one queued drain, not twenty")
                .isEqualTo(1);
        broker.gate.countDown();
    }

    @Test
    void afterShutdownNothingIsAcceptedAndNothingThrows() {
        UserAccount a = account();
        submit(a, "RELIANCE", new CopyOnWriteArrayList<>());
        await().atMost(Duration.ofSeconds(2)).until(() -> broker.sent.size() == 1);

        dispatcher.shutdown();
        submit(a, "TCS", new CopyOnWriteArrayList<>());

        assertThat(dispatcher.rejectedAfterShutdown()).isEqualTo(1);
        assertThat(broker.sent).hasSize(1);
    }
}
