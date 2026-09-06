package com.equity.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.equity.app.EngineProperties;
import com.equity.app.ExecutionMode;
import com.equity.domain.Direction;
import com.equity.domain.market.SharedInstrumentState;
import com.equity.domain.market.Tick;
import com.equity.domain.momentum.EntryPattern;
import com.equity.domain.order.TradeIntent;
import com.equity.domain.risk.DenialReason;
import com.equity.domain.risk.RiskDecision;
import com.equity.domain.risk.RiskLimits;
import com.equity.domain.user.Role;
import com.equity.domain.user.TradingUser;
import com.equity.domain.user.UserId;
import com.equity.domain.user.UserStatus;
import com.equity.market.InstrumentFreshness;
import com.equity.platform.time.FixedTradingClock;
import com.equity.strategy.StrategyThresholds;
import com.equity.trading.PositionBook;
import com.equity.user.UserAccount;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The permission sequence and the sizing.
 *
 * <p>Every denial has a named reason and every one is asserted here, because the distribution of
 * those reasons over a session is the only evidence available about whether a limit is protecting
 * the account or quietly preventing it from trading at all.</p>
 */
class RiskEngineTest {

    /** 10:30 IST — inside the default entry window. */
    private static final Instant NOW = Instant.parse("2026-09-04T05:00:00Z");

    private EngineProperties engine;
    private PositionBook positions;
    private AccountLedger ledger;
    private InstrumentFreshness freshness;
    private FixedTradingClock clock;
    private MarginRequirements requirements;
    private StubMarginBroker broker;
    private RiskEngine risk;
    private UserAccount account;

    @BeforeEach
    void setUp() {
        clock = new FixedTradingClock(NOW);
        engine = new EngineProperties();
        engine.setMode(ExecutionMode.LIVE);
        engine.setTradingEnabled(true);
        positions = new PositionBook();
        ledger = new AccountLedger(clock);
        freshness = new InstrumentFreshness(clock);
        broker = new StubMarginBroker();
        requirements = new MarginRequirements(broker, clock);
        risk = new RiskEngine(engine, positions, ledger, freshness, clock, requirements);

        account = new UserAccount(
                new TradingUser(UserId.random(), "test", "", UserStatus.ACTIVE, Set.of(Role.TRADER)),
                RiskLimits.conservative(), StrategyThresholds.defaults());
        account.setEntriesEnabled(true);
        freshness.record(tick(1000));
    }

    private static Tick tick(double price) {
        return new Tick("RELIANCE", price, 1_000_000, price - 0.5, price + 0.5,
                980, 1005, 975, 980, NOW, NOW);
    }

    private static SharedInstrumentState state() {
        return new SharedInstrumentState("RELIANCE", 980, 985, 1005, 975, 1000, 1_000_000,
                995, 998, 994, 5.0, 0.1, 0.4, 0.8, 1.2, 1.5, 5, 9, 0.5, 0, NOW);
    }

    /** Stop 10 below a 1000 entry: 1% stop, 1000-rupee budget, so 100 shares. */
    private TradeIntent intent() {
        return new TradeIntent(account.userId(), "RELIANCE", Direction.LONG,
                EntryPattern.PULLBACK_CONTINUATION, 1000, 990, 1020, NOW, "test");
    }

    private RiskDecision authorise() {
        return risk.authorise(account, intent(), state(), tick(1000), 0, 1_000_000, account.epoch());
    }

    // ── The happy path and the sizing ────────────────────────────────────────

    @Test
    void sizesFromTheRiskBudgetDividedByTheStopDistance() {
        RiskDecision decision = authorise();

        assertThat(decision.approved()).isTrue();
        assertThat(decision.quantity())
                .as("1000 rupees of budget over a 10-rupee stop is 100 shares")
                .isEqualTo(100);
        assertThat(decision.riskRupees()).isEqualTo(1000.0);
        assertThat(decision.positionValue()).isEqualTo(100_000.0);
    }

    @Test
    void trimsToTheNotionalCapRatherThanRefusingTheTrade() {
        // A very tight stop would otherwise buy an enormous position for the same rupee risk.
        TradeIntent tight = new TradeIntent(account.userId(), "RELIANCE", Direction.LONG,
                EntryPattern.PULLBACK_CONTINUATION, 1000, 997, 1010, NOW, "tight");

        RiskDecision decision = risk.authorise(account, tight, state(), tick(1000), 0,
                1_000_000, account.epoch());

        assertThat(decision.approved()).isTrue();
        assertThat(decision.quantity())
                .as("150,000 notional cap over a 1000 rupee share")
                .isEqualTo(150);
        assertThat(decision.riskRupees()).isLessThan(account.limits().riskPerTradeRupees());
    }

    // ── Is the engine trading at all ─────────────────────────────────────────

    @Test
    void refusesInReplayMode() {
        engine.setMode(ExecutionMode.REPLAY);
        assertThat(authorise().denialReason()).isEqualTo(DenialReason.NOT_LIVE_MODE);
    }

    @Test
    void refusesWhenTheMasterSwitchIsOff() {
        engine.setTradingEnabled(false);
        assertThat(authorise().denialReason()).isEqualTo(DenialReason.TRADING_DISABLED);
    }

    @Test
    void refusesForAUserWhoHasNotBeenArmed() {
        account.setEntriesEnabled(false);
        assertThat(authorise().denialReason()).isEqualTo(DenialReason.USER_ENTRIES_DISABLED);
    }

    @Test
    void refusesUnderAStaleEpoch() {
        long stale = account.epoch();
        account.halt();
        account.resume();
        account.setEntriesEnabled(true);

        RiskDecision decision = risk.authorise(account, intent(), state(), tick(1000), 0,
                1_000_000, stale);

        assertThat(decision.denialReason()).isEqualTo(DenialReason.STALE_EPOCH);
    }

    @Test
    void refusesOutsideTheEntryWindow() {
        clock.setTo(Instant.parse("2026-09-04T09:30:00Z"));   // 15:00 IST, past the window
        assertThat(authorise().denialReason()).isEqualTo(DenialReason.OUTSIDE_ENTRY_WINDOW);
    }

    // ── Account capacity ─────────────────────────────────────────────────────

    @Test
    void refusesOnceTheDailyLossHasLatched() {
        RiskDecision decision = risk.authorise(account, intent(), state(), tick(1000),
                -4000, 1_000_000, account.epoch());

        assertThat(decision.denialReason()).isEqualTo(DenialReason.DAILY_LOSS_LATCHED);
    }

    @Test
    void refusesPastTheDailyAttemptCap() {
        for (int i = 0; i < account.limits().maxDailyAttempts(); i++) {
            ledger.recordAttempt(account.userId());
        }
        assertThat(authorise().denialReason()).isEqualTo(DenialReason.MAX_DAILY_ATTEMPTS);
    }

    @Test
    void refusesASecondPositionInTheSameSymbol() {
        positions.put(com.equity.domain.position.Position.pendingEntry(
                account.userId(), "RELIANCE", Direction.LONG, EntryPattern.PULLBACK_CONTINUATION,
                com.equity.broker.ProductType.MIS, 10, 1000, 990, 1020,
                com.equity.domain.order.OrderTag.forEntry(account.userId()), "o1", NOW));

        assertThat(authorise().denialReason())
                .as("a pending entry counts: two signals in a fast move would double the size")
                .isEqualTo(DenialReason.ALREADY_IN_SYMBOL);
    }

    // ── Is there a tradeable price ───────────────────────────────────────────

    @Test
    void refusesOnAStalePrice() {
        clock.advance(Duration.ofSeconds(60));
        assertThat(authorise().denialReason()).isEqualTo(DenialReason.STALE_PRICE);
    }

    @Test
    void refusesASymbolThatHasNeverTicked() {
        TradeIntent unseen = new TradeIntent(account.userId(), "NEVERTRADED", Direction.LONG,
                EntryPattern.PULLBACK_CONTINUATION, 1000, 990, 1020, NOW, "test");

        RiskDecision decision = risk.authorise(account, unseen, state(), tick(1000), 0,
                1_000_000, account.epoch());

        assertThat(decision.denialReason())
                .as("absence of data is not freshness")
                .isEqualTo(DenialReason.STALE_PRICE);
    }

    @Test
    void refusesWithoutDepthRatherThanAssumingAZeroSpread() {
        Tick noDepth = new Tick("RELIANCE", 1000, 1_000_000, 0, 0, 980, 1005, 975, 980, NOW, NOW);

        RiskDecision decision = risk.authorise(account, intent(), state(), noDepth, 0,
                1_000_000, account.epoch());

        assertThat(decision.denialReason())
                .as("design note 0.10: a spread check that assumes zero always passes")
                .isEqualTo(DenialReason.NO_DEPTH);
    }

    @Test
    void refusesAWideSpread() {
        Tick wide = new Tick("RELIANCE", 1000, 1_000_000, 995, 1005, 980, 1005, 975, 980, NOW, NOW);

        RiskDecision decision = risk.authorise(account, intent(), state(), wide, 0,
                1_000_000, account.epoch());

        assertThat(decision.denialReason()).isEqualTo(DenialReason.SPREAD_TOO_WIDE);
    }

    @Test
    void refusesASuspectCorporateAction() {
        // A 45% "day change" is an unadjusted split, not the strongest gainer on the board.
        SharedInstrumentState split = new SharedInstrumentState("RELIANCE", 690, 985, 1005, 975,
                1000, 1_000_000, 995, 998, 994, 5.0, 0.1, 0.4, 0.8, 1.2, 1.5, 1, 9, 0.5, 0, NOW);

        RiskDecision decision = risk.authorise(account, intent(), split, tick(1000), 0,
                1_000_000, account.epoch());

        assertThat(decision.denialReason()).isEqualTo(DenialReason.SUSPECT_CORPORATE_ACTION);
    }

    // ── Does it fit the budget ───────────────────────────────────────────────

    @Test
    void refusesAStopInsideTheNoise() {
        TradeIntent tooTight = new TradeIntent(account.userId(), "RELIANCE", Direction.LONG,
                EntryPattern.PULLBACK_CONTINUATION, 1000, 999.5, 1010, NOW, "test");

        assertThat(risk.authorise(account, tooTight, state(), tick(1000), 0, 1_000_000,
                account.epoch()).denialReason())
                .as("a 0.05% stop is hit by the spread, not by the thesis being wrong")
                .isEqualTo(DenialReason.STOP_TOO_TIGHT);
    }

    @Test
    void refusesAStopTooWideForTheSetupThatWasDetected() {
        TradeIntent tooWide = new TradeIntent(account.userId(), "RELIANCE", Direction.LONG,
                EntryPattern.PULLBACK_CONTINUATION, 1000, 970, 1060, NOW, "test");

        assertThat(risk.authorise(account, tooWide, state(), tick(1000), 0, 1_000_000,
                account.epoch()).denialReason()).isEqualTo(DenialReason.STOP_TOO_WIDE);
    }

    @Test
    void refusesWhenThereIsNotEnoughMargin() {
        RiskDecision decision = risk.authorise(account, intent(), state(), tick(1000), 0,
                50_000, account.epoch());

        assertThat(decision.denialReason()).isEqualTo(DenialReason.INSUFFICIENT_MARGIN);
    }

    /**
     * Intraday leverage, which the gate used to ignore entirely.
     *
     * <p>100 shares at 1000 is 100,000 of stock, but MIS blocks a fifth of that. An account with
     * 50,000 can carry the position; comparing the notional refused it and called the refusal
     * insufficient margin, which reads as a funding problem rather than an arithmetic one.</p>
     */
    @Test
    void anIntradayPositionIsMeasuredAgainstTheMarginItBlocksNotItsFullPrice() {
        broker.perShare = 200;
        requirements.prime(account.userId(), com.equity.broker.ProductType.MIS, Set.of("RELIANCE"));

        RiskDecision decision = risk.authorise(account, intent(), state(), tick(1000), 0,
                50_000, account.epoch());

        assertThat(decision.approved())
                .as("20,000 blocked against 50,000 available")
                .isTrue();
        assertThat(decision.quantity())
                .as("leverage decides whether the position can be held, never how big it is — "
                        + "size is still the rupee risk budget divided by the stop distance")
                .isEqualTo(100);
        assertThat(decision.positionValue())
                .as("position value stays the notional; only the gate uses the margin figure")
                .isEqualTo(100_000);
    }

    @Test
    void leverageDoesNotRescueAPositionTheAccountStillCannotCarry() {
        broker.perShare = 200;
        requirements.prime(account.userId(), com.equity.broker.ProductType.MIS, Set.of("RELIANCE"));

        RiskDecision decision = risk.authorise(account, intent(), state(), tick(1000), 0,
                5_000, account.epoch());

        assertThat(decision.denialReason()).isEqualTo(DenialReason.INSUFFICIENT_MARGIN);
        assertThat(decision.note()).contains("MIS margin");
    }

    @Test
    void anUnknownLeverageFallsBackToTheFullNotional() {
        RiskDecision decision = risk.authorise(account, intent(), state(), tick(1000), 0,
                50_000, account.epoch());

        assertThat(decision.denialReason())
                .as("guessing a multiple would authorise a position the exchange then rejects")
                .isEqualTo(DenialReason.INSUFFICIENT_MARGIN);
        assertThat(decision.note())
                .as("an operator has to be able to tell this apart from a real margin shortfall")
                .contains("leverage unknown");
    }

    @Test
    void refusesWhenMarginIsUnknown() {
        RiskDecision decision = risk.authorise(account, intent(), state(), tick(1000), 0,
                0, account.epoch());

        assertThat(decision.denialReason())
                .as("an unfetched margin must read as zero, not as unlimited")
                .isEqualTo(DenialReason.INSUFFICIENT_MARGIN);
    }

    // ── Slippage (design note 0.8) ───────────────────────────────────────────

    @Test
    void aFillThatSlippedBeyondToleranceIsNoLongerTheTradeThatWasApproved() {
        assertThat(risk.fillStillWithinBudget(account, 1_100)).isTrue();
        assertThat(risk.fillStillWithinBudget(account, 1_600))
                .as("the stop did not move with the fill, so the rupees at risk grew silently")
                .isFalse();
    }

    /** Answers margin probes; nothing else here reaches the broker. */
    private static final class StubMarginBroker implements com.equity.broker.BrokerPort {
        double perShare = Double.NaN;

        @Override public String placeOrder(UserId u, com.equity.broker.OrderRequest r) {
            throw new AssertionError("the risk engine must not place orders");
        }
        @Override public void cancelOrder(UserId u, String id) {}
        @Override public void cancelOrder(UserId u, String id, com.equity.broker.OrderVariety v) {}
        @Override public void modifyOrder(UserId u, String id, int q, double p) {}
        @Override public java.util.List<com.equity.broker.BrokerOrder> fetchOrders(UserId u) { return java.util.List.of(); }
        @Override public java.util.List<com.equity.broker.BrokerPosition> fetchPositions(UserId u) { return java.util.List.of(); }
        @Override public double availableMargin(UserId u) { return 0; }
        @Override public boolean isAuthenticated(UserId u) { return true; }

        @Override public double requiredMargin(UserId u, com.equity.broker.OrderRequest r) {
            return perShare * r.quantity();
        }
    }
}
