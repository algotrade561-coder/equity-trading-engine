package com.equity.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.equity.domain.Direction;
import com.equity.domain.market.Candle;
import com.equity.domain.market.SharedInstrumentState;
import com.equity.domain.market.Tick;
import com.equity.domain.market.Timeframe;
import com.equity.domain.momentum.MomentumState;
import com.equity.domain.momentum.RejectionStage;
import com.equity.domain.risk.RiskLimits;
import com.equity.domain.user.Role;
import com.equity.domain.user.TradingUser;
import com.equity.domain.user.UserId;
import com.equity.domain.user.UserStatus;
import com.equity.platform.time.FixedTradingClock;
import com.equity.user.UserAccount;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The setup state machine.
 *
 * <p>Built from explicit candle series rather than from recorded tape, because the point of these
 * tests is that each named condition does what its name says. Tape tests come later and answer a
 * different question — whether the conditions are worth anything.</p>
 */
class MomentumStrategyTest {

    private static final String SYMBOL = "RELIANCE";
    /** 10:30 IST, inside the default entry window. */
    private static final Instant NOW = Instant.parse("2026-09-04T05:00:00Z");

    private FixedTradingClock clock;
    private MomentumStrategy strategy;
    private UserAccount account;

    @BeforeEach
    void setUp() {
        clock = new FixedTradingClock(NOW);
        strategy = new MomentumStrategy(clock);
        account = new UserAccount(
                new TradingUser(UserId.random(), "test", "", UserStatus.ACTIVE, Set.of(Role.TRADER)),
                RiskLimits.conservative(), StrategyThresholds.defaults());
        account.setEntriesEnabled(true);
    }

    /** A healthy candidate: up 3% on the day, above VWAP, EMAs stacked, leading the index. */
    private SharedInstrumentState healthy(double lastPrice, double dayHigh,
                                          double return5m, double relativeVolume) {
        return new SharedInstrumentState(SYMBOL, 1000, 1010, dayHigh, 1005, lastPrice, 5_000_000,
                lastPrice - 3, lastPrice - 1, lastPrice - 4, 4.0,
                0.1, 0.3, return5m, 1.4, relativeVolume, 5, 9, 0.8, 0, NOW);
    }

    /** Flat bars, enough of them to clear the history requirement. */
    private List<Candle> flatHistory(int count, double price) {
        List<Candle> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(new Candle(SYMBOL, Timeframe.M1, NOW.minusSeconds(60L * (count - i)),
                    price, price + 0.5, price - 0.5, price, 10_000));
        }
        return out;
    }

    private static void append(List<Candle> history, double open, double high,
                               double low, double close, long volume) {
        history.add(new Candle(SYMBOL, Timeframe.M1,
                history.get(history.size() - 1).startTime().plusSeconds(60),
                open, high, low, close, volume));
    }

    // ── Mandatory conditions ─────────────────────────────────────────────────

    @Test
    void refusesASymbolWithNoPreviousClose() {
        SharedInstrumentState noClose = new SharedInstrumentState(SYMBOL, 0, 1010, 1040, 1005,
                1030, 5_000_000, 1027, 1029, 1026, 4.0, 0.1, 0.3, 0.8, 1.4, 1.5, 5, 9, 0.8, 0, NOW);

        StrategySignal signal = strategy.onCandleClosed(account, noClose, flatHistory(30, 1030));

        assertThat(signal.stage()).isEqualTo(RejectionStage.UNIVERSE);
        assertThat(signal.condition()).isEqualTo("noPreviousClose");
    }

    @Test
    void refusesAnUnadjustedCorporateAction() {
        // 1030 against a 690 previous close is a 49% "gain" — a split, not a move.
        SharedInstrumentState split = new SharedInstrumentState(SYMBOL, 690, 1010, 1040, 1005,
                1030, 5_000_000, 1027, 1029, 1026, 4.0, 0.1, 0.3, 0.8, 1.4, 1.5, 1, 9, 0.8, 0, NOW);

        StrategySignal signal = strategy.onCandleClosed(account, split, flatHistory(30, 1030));

        assertThat(signal.condition())
                .as("design note 0.9: this would otherwise rank as the strongest gainer on the board")
                .isEqualTo("suspectCorporateAction");
    }

    @Test
    void refusesAStockThatIsNotActuallyUp() {
        SharedInstrumentState flat = new SharedInstrumentState(SYMBOL, 1000, 1010, 1012, 995,
                1002, 5_000_000, 1001, 1002, 1000, 4.0, 0, 0, 0, 0, 1.0, 40, 41, 0.1, 0, NOW);

        assertThat(strategy.onCandleClosed(account, flat, flatHistory(30, 1002)).condition())
                .isEqualTo("dayChangeTooSmall");
    }

    @Test
    void refusesAStockThatHasAlreadyMadeItsMove() {
        SharedInstrumentState extended = new SharedInstrumentState(SYMBOL, 1000, 1010, 1160, 1005,
                1155, 5_000_000, 1100, 1140, 1120, 4.0, 0.1, 0.3, 0.8, 1.4, 1.5, 1, 2, 0.8, 0, NOW);

        assertThat(strategy.onCandleClosed(account, extended, flatHistory(30, 1155)).condition())
                .isEqualTo("dayChangeExtended");
    }

    @Test
    void refusesBelowVwap() {
        SharedInstrumentState belowVwap = new SharedInstrumentState(SYMBOL, 1000, 1010, 1040, 1005,
                1030, 5_000_000, 1035, 1029, 1026, 4.0, 0.1, 0.3, 0.8, 1.4, 1.5, 5, 9, 0.8, 0, NOW);

        assertThat(strategy.onCandleClosed(account, belowVwap, flatHistory(30, 1030)).condition())
                .isEqualTo("belowVwap");
    }

    @Test
    void refusesWhenTheEmasAreNotStacked() {
        SharedInstrumentState crossed = new SharedInstrumentState(SYMBOL, 1000, 1010, 1040, 1005,
                1030, 5_000_000, 1027, 1020, 1029, 4.0, 0.1, 0.3, 0.8, 1.4, 1.5, 5, 9, 0.8, 0, NOW);

        assertThat(strategy.onCandleClosed(account, crossed, flatHistory(30, 1030)).condition())
                .isEqualTo("emaNotStacked");
    }

    @Test
    void refusesAStockLaggingTheIndex() {
        SharedInstrumentState lagging = new SharedInstrumentState(SYMBOL, 1000, 1010, 1040, 1005,
                1030, 5_000_000, 1027, 1029, 1026, 4.0, 0.1, 0.3, 0.8, 1.4, 1.5, 5, 9, -0.4, 0, NOW);

        assertThat(strategy.onCandleClosed(account, lagging, flatHistory(30, 1030)).condition())
                .isEqualTo("notOutperformingIndex");
    }

    @Test
    void refusesACandidateFarBelowItsDayHigh() {
        assertThat(strategy.onCandleClosed(account, healthy(1030, 1080, 0.8, 1.5),
                flatHistory(30, 1030)).condition()).isEqualTo("farFromDayHigh");
    }

    @Test
    void refusesOutsideTheEntryWindow() {
        clock.setTo(Instant.parse("2026-09-04T09:30:00Z"));   // 15:00 IST

        assertThat(strategy.onCandleClosed(account, healthy(1030, 1040, 0.8, 1.5),
                flatHistory(30, 1030)).condition()).isEqualTo("outsideEntryWindow");
    }

    @Test
    void refusesUntilThereIsEnoughHistoryToJudge() {
        assertThat(strategy.onCandleClosed(account, healthy(1030, 1040, 0.8, 1.5),
                flatHistory(5, 1030)).condition()).isEqualTo("insufficientHistory");
    }

    // ── Setup progression ────────────────────────────────────────────────────

    @Test
    void refusesAThrustWithNoVolumeBehindIt() {
        StrategySignal signal = strategy.onCandleClosed(account,
                healthy(1030, 1040, 0.9, 0.4), flatHistory(30, 1030));

        assertThat(signal.stage()).isEqualTo(RejectionStage.IMPULSE);
        assertThat(signal.condition())
                .as("a move on no participation is the one most likely to hand itself straight back")
                .isEqualTo("noVolumeBehindThrust");
    }

    @Test
    void refusesADriftThatIsNotAThrust() {
        assertThat(strategy.onCandleClosed(account, healthy(1030, 1040, 0.1, 1.8),
                flatHistory(30, 1030)).condition()).isEqualTo("thrustTooWeak");
    }

    @Test
    void aThrustOnVolumeBecomesAnImpulse() {
        strategy.onCandleClosed(account, healthy(1030, 1040, 0.9, 1.8), flatHistory(30, 1030));

        assertThat(strategy.setupFor(account.userId(), SYMBOL).state())
                .isEqualTo(MomentumState.IMPULSE);
    }

    @Test
    void aShallowPauseAfterAnImpulseArmsTheSetup() {
        List<Candle> history = flatHistory(30, 1030);
        strategy.onCandleClosed(account, healthy(1035, 1036, 0.9, 1.8), history);
        assertThat(strategy.setupFor(account.userId(), SYMBOL).state()).isEqualTo(MomentumState.IMPULSE);

        // Three tight bars holding just under the impulse high.
        for (int i = 0; i < 4; i++) {
            append(history, 1034, 1035, 1033.5, 1034.5, 5_000);
            strategy.onCandleClosed(account, healthy(1034.5, 1036, 0.5, 1.5), history);
        }

        SetupState setup = strategy.setupFor(account.userId(), SYMBOL);
        assertThat(setup.state()).isEqualTo(MomentumState.ARMED);
        assertThat(setup.triggerLevel())
                .as("the trigger sits just above the pause high")
                .isGreaterThan(1035.0);
        assertThat(setup.structureLow()).isGreaterThan(0.0);
    }

    @Test
    void aDeepRetracementInvalidatesRatherThanWaiting() {
        List<Candle> history = flatHistory(30, 1030);
        strategy.onCandleClosed(account, healthy(1035, 1036, 0.9, 1.8), history);

        append(history, 1030, 1031, 1013, 1022, 40_000);   // wick gives the whole thrust back
        StrategySignal signal = strategy.onCandleClosed(account, healthy(1022, 1032, 0.2, 1.5), history);

        assertThat(signal.stage()).isEqualTo(RejectionStage.PULLBACK);
        assertThat(signal.condition()).isEqualTo("retracedTooDeep");
        assertThat(strategy.setupFor(account.userId(), SYMBOL).state()).isEqualTo(MomentumState.IDLE);
    }

    @Test
    void anInvalidatedSetupWaitsBeforeRebuilding() {
        List<Candle> history = flatHistory(30, 1030);
        strategy.onCandleClosed(account, healthy(1035, 1036, 0.9, 1.8), history);
        append(history, 1030, 1031, 1013, 1022, 40_000);
        strategy.onCandleClosed(account, healthy(1022, 1032, 0.2, 1.5), history);
        assertThat(strategy.setupFor(account.userId(), SYMBOL).state()).isEqualTo(MomentumState.IDLE);

        assertThat(strategy.onCandleClosed(account, healthy(1035, 1036, 0.9, 1.8), history).condition())
                .isEqualTo("symbolCooldown");
    }

    // ── The trigger ──────────────────────────────────────────────────────────

    @Test
    void anArmedSetupSaysNothingUntilPriceTradesThroughTheTrigger() {
        SetupState setup = arm();

        StrategySignal signal = strategy.onTick(account, healthy(setup.triggerLevel() - 1, 1040, 0.5, 1.5),
                tickAt(setup.triggerLevel() - 1));

        assertThat(signal.kind())
                .as("not yet is silence, not a rejection — it would swamp the rejection counts")
                .isEqualTo(StrategySignal.Kind.NONE);
    }

    @Test
    void tradingThroughTheTriggerProducesAnIntentWithAStopAtTheStructureLow() {
        SetupState setup = arm();
        double trigger = setup.triggerLevel();
        double structureLow = setup.structureLow();

        StrategySignal signal = strategy.onTick(account, healthy(trigger + 0.5, 1040, 0.5, 1.5),
                tickAt(trigger + 0.5));

        assertThat(signal.isIntent()).isTrue();
        var intent = signal.intent();
        assertThat(intent.direction()).isEqualTo(Direction.LONG);
        assertThat(intent.symbol()).isEqualTo(SYMBOL);
        // The pause in this scenario is tighter than the stock's own noise (ATR 4.0), so the ATR
        // floor widens the stop past the structure low. That is the floor working, not a defect.
        double atrFloor = intent.referencePrice()
                - account.thresholds().stopAtrMultiple() * 4.0;
        assertThat(intent.stopPrice())
                .as("the stop is the wider of the structure low and the instrument's own noise")
                .isEqualTo(Math.min(structureLow, atrFloor));
        // The target is a multiple of the risk actually taken, so a stop widened by the ATR floor
        // moves the target out with it. Deriving it from the structure low instead would promise a
        // 2R target on a trade whose R is larger than that.
        assertThat(intent.targetPrice())
                .isEqualTo(intent.referencePrice()
                        + (intent.referencePrice() - intent.stopPrice())
                          * account.thresholds().targetRMultiple());
        assertThat(strategy.setupFor(account.userId(), SYMBOL).state())
                .isEqualTo(MomentumState.TRIGGERED);
    }

    @Test
    void theStructureLowIsKeptWhenItIsAlreadyWiderThanTheAtrFloor() {
        SetupState setup = arm();
        double trigger = setup.triggerLevel();

        // A quiet stock: ATR 0.2, so 1.5 x ATR is well inside the pause.
        SharedInstrumentState quiet = new SharedInstrumentState(SYMBOL, 1000, 1010, 1040, 1005,
                trigger + 0.5, 5_000_000, trigger - 3, trigger - 1, trigger - 4, 0.2,
                0.1, 0.3, 0.5, 1.4, 1.5, 5, 9, 0.8, 0, NOW);

        StrategySignal signal = strategy.onTick(account, quiet, tickAt(trigger + 0.5));

        assertThat(signal.intent().stopPrice())
                .as("the floor may only widen a stop, never tighten one onto the setup")
                .isEqualTo(setup.structureLow());
    }

    // ── The gap between arming and triggering ────────────────────────────────

    @Test
    void aStockThatBecomesOverExtendedBeforeTriggeringIsRefused() {
        SetupState setup = arm();
        double trigger = setup.triggerLevel();

        // Armed a minute ago at an acceptable day change. Against a 900 previous close the price
        // that triggers this entry is +15%, past the 12% ceiling — and the breakout itself is what
        // pushed it there, which is the normal direction of travel, not a corner case.
        SharedInstrumentState extended = new SharedInstrumentState(SYMBOL, 900, 1010, 1200, 1005,
                trigger + 0.5, 5_000_000, trigger - 3, trigger - 1, trigger - 4, 4.0,
                0.1, 0.3, 0.5, 1.4, 1.5, 5, 9, 0.8, 0, NOW);

        StrategySignal signal = strategy.onTick(account, extended, tickAt(trigger + 0.5));

        assertThat(signal.stage()).isEqualTo(RejectionStage.TRIGGER);
        assertThat(signal.condition())
                .as("mandatory conditions were last checked at the candle close, up to a minute ago")
                .isEqualTo("dayChangeExtendedAtTrigger");
    }

    @Test
    void aStockThatSlipsBelowVwapBeforeTriggeringIsRefused() {
        SetupState setup = arm();
        double trigger = setup.triggerLevel();

        SharedInstrumentState belowVwap = new SharedInstrumentState(SYMBOL, 1000, 1010, 1040, 1005,
                trigger + 0.5, 5_000_000, trigger + 10, trigger - 1, trigger - 4, 4.0,
                0.1, 0.3, 0.5, 1.4, 1.5, 5, 9, 0.8, 0, NOW);

        assertThat(strategy.onTick(account, belowVwap, tickAt(trigger + 0.5)).condition())
                .isEqualTo("belowVwapAtTrigger");
    }

    @Test
    void aTriggerRejectionIsNamedApartFromItsCandlePathEquivalent() {
        SetupState setup = arm();
        double trigger = setup.triggerLevel();
        SharedInstrumentState split = new SharedInstrumentState(SYMBOL, 690, 1010, 1040, 1005,
                trigger + 0.5, 5_000_000, trigger - 3, trigger - 1, trigger - 4, 4.0,
                0.1, 0.3, 0.5, 1.4, 1.5, 1, 9, 0.8, 0, NOW);

        assertThat(strategy.onTick(account, split, tickAt(trigger + 0.5)).condition())
                .as("'failed a minute after it passed' is a different fact from 'never passed', and "
                        + "collapsing them would hide how often a setup goes stale before it fires")
                .isEqualTo("suspectCorporateActionAtTrigger");
    }

    @Test
    void anUnarmedSymbolIgnoresTicksEntirely() {
        assertThat(strategy.onTick(account, healthy(1030, 1040, 0.5, 1.5), tickAt(1030)).kind())
                .isEqualTo(StrategySignal.Kind.NONE);
    }

    @Test
    void anArmedSetupThatNeverTriggersGoesStaleRatherThanWaitingForever() {
        List<Candle> history = flatHistory(30, 1030);
        strategy.onCandleClosed(account, healthy(1035, 1036, 0.9, 1.8), history);
        for (int i = 0; i < 4; i++) {
            append(history, 1034, 1035, 1033.5, 1034.5, 5_000);
            strategy.onCandleClosed(account, healthy(1034.5, 1036, 0.5, 1.5), history);
        }
        assertThat(strategy.setupFor(account.userId(), SYMBOL).isArmed()).isTrue();

        StrategySignal last = StrategySignal.NOTHING;
        for (int i = 0; i < 12 && !last.isRejected(); i++) {
            append(history, 1034, 1035, 1033.5, 1034.5, 5_000);
            last = strategy.onCandleClosed(account, healthy(1034.5, 1036, 0.5, 1.5), history);
        }

        assertThat(last.condition())
                .as("a breakout that has not happened in ten minutes is a different trade")
                .isEqualTo("armedTooLong");
    }

    @Test
    void closingAPositionClearsTheSetupAndStartsACooldown() {
        arm();

        strategy.onPositionClosed(account.userId(), SYMBOL, Duration.ofMinutes(5));

        SetupState setup = strategy.setupFor(account.userId(), SYMBOL);
        assertThat(setup.state()).isEqualTo(MomentumState.IDLE);
        assertThat(setup.inCooldown(clock.now())).isTrue();
    }

    @Test
    void setupsAreSeparatePerUser() {
        UserAccount other = new UserAccount(
                new TradingUser(UserId.random(), "other", "", UserStatus.ACTIVE, Set.of(Role.TRADER)),
                RiskLimits.conservative(), StrategyThresholds.defaults());
        arm();

        assertThat(strategy.setupFor(other.userId(), SYMBOL).state())
                .as("two users must not share one state machine")
                .isEqualTo(MomentumState.IDLE);
    }

    private SetupState arm() {
        List<Candle> history = flatHistory(30, 1030);
        strategy.onCandleClosed(account, healthy(1035, 1036, 0.9, 1.8), history);
        for (int i = 0; i < 4; i++) {
            append(history, 1034, 1035, 1033.5, 1034.5, 5_000);
            strategy.onCandleClosed(account, healthy(1034.5, 1036, 0.5, 1.5), history);
        }
        SetupState setup = strategy.setupFor(account.userId(), SYMBOL);
        assertThat(setup.isArmed()).isTrue();
        return setup;
    }

    private static Tick tickAt(double price) {
        return new Tick(SYMBOL, price, 5_000_000, price - 0.2, price + 0.2,
                1010, 1040, 1005, 1000, NOW, NOW);
    }

    /**
     * The retry storm from the first live morning.
     *
     * <p>An authorised entry that fails to become a position calls {@code onEntryAbandoned}, which
     * re-armed the setup and nothing more. The price was still above the trigger, so the next tick
     * fired again — twelve orders for one stock in under seven seconds, stopped only because the
     * daily attempt cap ran out, followed by five hundred further intents the cap then refused.</p>
     *
     * <p>The setup is genuinely still valid, so re-arming is right. Firing again on the very next
     * tick is not: whatever refused the entry needs time to change.</p>
     */
    @Test
    void anAbandonedEntryDoesNotRetriggerOnTheNextTick() {
        SetupState setup = arm();
        double trigger = setup.triggerLevel();
        SharedInstrumentState state = healthy(trigger + 0.5, trigger + 2, 0.5, 1.5);

        assertThat(strategy.onTick(account, state, tickAt(trigger + 0.5)).isIntent())
                .as("the first trigger is the trade")
                .isTrue();

        strategy.onEntryAbandoned(account.userId(), SYMBOL);

        for (int i = 0; i < 50; i++) {
            assertThat(strategy.onTick(account, state, tickAt(trigger + 0.5)).isIntent())
                    .as("tick %d after the abandoned entry still submitted an order", i)
                    .isFalse();
        }
    }

    @Test
    void theSetupBecomesTradeableAgainOnceTheHoldOffPasses() {
        SetupState setup = arm();
        double trigger = setup.triggerLevel();
        SharedInstrumentState state = healthy(trigger + 0.5, trigger + 2, 0.5, 1.5);

        strategy.onTick(account, state, tickAt(trigger + 0.5));
        strategy.onEntryAbandoned(account.userId(), SYMBOL);
        clock.advance(java.time.Duration.ofMinutes(3));

        assertThat(strategy.onTick(account, state, tickAt(trigger + 0.5)).isIntent())
                .as("a hold-off that never expires would silently drop a valid setup for the day")
                .isTrue();
    }

    /**
     * An armed setup must not trigger when the last candle close refused it.
     *
     * <p>The trigger-time recheck deliberately covers only the price-derived conditions, because
     * relative strength and the EMA pair are computed from completed candles and cannot change
     * between them. That reasoning is right, and it is exactly what made ignoring them wrong: the
     * close had already said no, and the tick path never asked.</p>
     *
     * <p>Seen live — a stock sat ARMED for fourteen minutes failing notOutperformingIndex on every
     * close, and would have entered the moment it broke out.</p>
     */
    @Test
    void anArmedSetupDoesNotTriggerWhileTheLastCloseRefusedIt() {
        SetupState setup = arm();
        double trigger = setup.triggerLevel();

        // A close where the stock has stopped leading the index. Everything else still holds.
        SharedInstrumentState lagging = new SharedInstrumentState(SYMBOL, 1000, 1010,
                trigger + 2, 1005, trigger + 0.5, 5_000_000,
                trigger - 3, trigger - 1, trigger - 4, 4.0,
                0.1, 0.3, 0.5, 1.4, 1.5, 5, 9, -0.4, 0, NOW);
        strategy.onCandleClosed(account, lagging, flatHistory(25, trigger));

        StrategySignal signal = strategy.onTick(account, lagging, tickAt(trigger + 0.5));

        assertThat(signal.isIntent())
                .as("the most recent close refused this stock on a mandatory condition")
                .isFalse();
        assertThat(signal.condition()).isEqualTo("mandatoryFailedAtLastClose");
    }

    /**
     * An armed setup ages on every close, not only on the ones that reach the state machine.
     *
     * <p>Ageing used to sit after the mandatory check, which returns early — so a setup whose
     * mandatory conditions were failing never aged, and could wait indefinitely on a level set long
     * before. One was found still ARMED fourteen bars past a ten-bar limit.</p>
     */
    @Test
    void anArmedSetupExpiresEvenWhileMandatoryConditionsAreFailing() {
        SetupState setup = arm();
        double trigger = setup.triggerLevel();
        SharedInstrumentState lagging = new SharedInstrumentState(SYMBOL, 1000, 1010,
                trigger + 2, 1005, trigger + 0.5, 5_000_000,
                trigger - 3, trigger - 1, trigger - 4, 4.0,
                0.1, 0.3, 0.5, 1.4, 1.5, 5, 9, -0.4, 0, NOW);

        // Collected rather than sampled at the end: once it expires the setup is IDLE and every
        // later close refuses it on the mandatory condition again, hiding the expiry.
        List<String> conditions = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            conditions.add(strategy.onCandleClosed(account, lagging, flatHistory(25, trigger))
                    .condition());
        }

        assertThat(conditions)
                .as("a stale armed setup must not survive its bar limit by failing a check earlier")
                .contains("armedTooLong");
        assertThat(strategy.setupFor(account.userId(), SYMBOL).state())
                .isEqualTo(MomentumState.IDLE);
    }

    /**
     * A refusal that cannot change today stops the setup for the day.
     *
     * <p>On the first live session the attempt cap was reached at 09:35 and armed setups went on
     * re-triggering every two minutes until the close — one stock 140 times. Nine distinct
     * opportunities were recorded as 264 intents, which buried the journal meant to explain the day.
     * Retrying a spent budget or a latched loss can only ever produce the same answer.</p>
     */
    @Test
    void aRefusalThatCannotChangeTodayStopsTheSetupForTheDay() {
        SetupState setup = arm();
        double trigger = setup.triggerLevel();
        SharedInstrumentState state = healthy(trigger + 0.5, trigger + 2, 0.5, 1.5);

        strategy.onTick(account, state, tickAt(trigger + 0.5));
        strategy.onEntryAbandoned(account.userId(), SYMBOL, true);

        clock.advance(java.time.Duration.ofHours(4));   // the rest of a session

        assertThat(strategy.onTick(account, state, tickAt(trigger + 0.5)).isIntent())
                .as("the attempt budget cannot refill before tomorrow")
                .isFalse();
    }

    @Test
    void aMomentaryRefusalStillRetriesAfterTheShortHoldOff() {
        SetupState setup = arm();
        double trigger = setup.triggerLevel();
        SharedInstrumentState state = healthy(trigger + 0.5, trigger + 2, 0.5, 1.5);

        strategy.onTick(account, state, tickAt(trigger + 0.5));
        strategy.onEntryAbandoned(account.userId(), SYMBOL, false);

        clock.advance(java.time.Duration.ofMinutes(3));

        assertThat(strategy.onTick(account, state, tickAt(trigger + 0.5)).isIntent())
                .as("a wide spread or a margin shortfall clears; the setup must come back")
                .isTrue();
    }

    /**
     * A full position book is not a judgement about this trade.
     *
     * <p>Capacity refusals were held off for two minutes like any other, so a valid setup simply
     * re-fired at the cap for as long as the book stayed full. PARADEEP triggered eight times in one
     * session and became a position none of them. The hold is now lifted by a position closing, not
     * by the clock.</p>
     */
    @Test
    void aSetupDeferredForCapacityWaitsForASlotRatherThanTheClock() {
        SetupState setup = arm();
        double trigger = setup.triggerLevel();
        SharedInstrumentState state = healthy(trigger + 0.5, trigger + 2, 0.5, 1.5);

        strategy.onTick(account, state, tickAt(trigger + 0.5));
        strategy.onEntryDeferredForCapacity(account.userId(), SYMBOL);

        clock.advance(java.time.Duration.ofMinutes(30));
        assertThat(strategy.onTick(account, state, tickAt(trigger + 0.5)).isIntent())
                .as("half an hour later the book may still be full; time is not the signal")
                .isFalse();

        strategy.onCapacityFreed(account.userId());

        assertThat(strategy.onTick(account, state, tickAt(trigger + 0.5)).isIntent())
                .as("a slot freed, and the setup was valid the whole time")
                .isTrue();
    }

    /**
     * Freeing a slot must not resurrect a setup that was held off for a different reason — a broker
     * refusal or a spent attempt budget is unaffected by a position closing.
     */
    @Test
    void freeingASlotDoesNotLiftAHoldOffTakenForAnotherReason() {
        SetupState setup = arm();
        double trigger = setup.triggerLevel();
        SharedInstrumentState state = healthy(trigger + 0.5, trigger + 2, 0.5, 1.5);

        strategy.onTick(account, state, tickAt(trigger + 0.5));
        strategy.onEntryAbandoned(account.userId(), SYMBOL, true);   // spent for the session

        strategy.onCapacityFreed(account.userId());

        assertThat(strategy.onTick(account, state, tickAt(trigger + 0.5)).isIntent())
                .as("the attempt budget cannot refill because a position closed")
                .isFalse();
    }
}
