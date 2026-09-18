package com.equity.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.equity.domain.Direction;
import com.equity.domain.market.Tick;
import com.equity.domain.momentum.EntryPattern;
import com.equity.domain.order.TradeIntent;
import com.equity.domain.position.ExitReason;
import com.equity.domain.risk.RiskLimits;
import com.equity.domain.user.UserId;
import com.equity.platform.time.FixedTradingClock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A paper position follows an intent to the same conclusions a real one would, and is sized and
 * charged the same way — otherwise a week of paper outcomes is not comparable to a week of fills.
 */
class ShadowTraderTest {

    private static final Instant T0 = Instant.parse("2026-09-18T04:30:00Z");   // 10:00 IST
    private static final UserId USER = UserId.random();

    private FixedTradingClock clock;
    private ShadowTrader shadows;
    private List<ShadowTrader.Outcome> outcomes;

    @BeforeEach
    void setUp() {
        clock = new FixedTradingClock(T0);
        shadows = new ShadowTrader(clock);
        outcomes = new ArrayList<>();
        shadows.onOutcome(outcomes::add);
    }

    private static TradeIntent intent(double entry, double stop, double target) {
        return new TradeIntent(USER, "ABC", Direction.LONG, EntryPattern.CONSOLIDATION_BREAKOUT,
                entry, stop, target, T0, "test");
    }

    private void open(TradeIntent i, boolean armed) {
        shadows.open(i, "CONSOLIDATION_BREAKOUT", armed, RiskLimits.house(), 90, LocalTime.of(15, 10));
    }

    private void tick(double price, Instant at) {
        shadows.onTick(Tick.ltp("ABC", price, 0, at));
    }

    @Test
    void sizedLikeTheRiskEngineAndFilledWithSlippage() {
        open(intent(100.0, 99.0, 101.33), false);
        assertThat(shadows.openCount()).isEqualTo(1);

        tick(101.40, T0.plusSeconds(60));

        assertThat(outcomes).hasSize(1);
        ShadowTrader.Outcome o = outcomes.get(0);
        assertThat(o.paper().quantity).as("3000 rupees of risk over a 1 rupee stop, capped at 2 lakh notional").isEqualTo(2000);
        assertThat(o.paper().entry).as("bought 0.03% above the trigger").isEqualTo(100.03);
        assertThat(o.exit()).as("sold 0.03% below the tick that crossed the target").isEqualTo(101.37);
        assertThat(o.reason()).isEqualTo(ExitReason.TARGET);
        assertThat(o.grossPnl()).isCloseTo((101.37 - 100.03) * 2000, within(0.01));
        assertThat(o.charges()).isGreaterThan(0);
        assertThat(o.netPnl()).isLessThan(o.grossPnl());
        assertThat(o.paper().userWasArmed).isFalse();
    }

    @Test
    void aStopClosesItAndTheExcursionsAreRecordedInR() {
        open(intent(100.0, 99.0, 101.33), true);
        tick(100.50, T0.plusSeconds(10));    // +0.5R
        tick(99.40, T0.plusSeconds(20));     // -0.6R
        tick(98.95, T0.plusSeconds(30));     // through the stop

        assertThat(outcomes).hasSize(1);
        ShadowTrader.Outcome o = outcomes.get(0);
        assertThat(o.reason()).isEqualTo(ExitReason.HARD_STOP);
        assertThat(o.paper().favourableExcursionR()).isCloseTo((100.50 - 100.03) / 1.03, within(0.01));
        assertThat(o.paper().adverseExcursionR()).isCloseTo((100.03 - 98.95) / 1.03, within(0.01));
        assertThat(shadows.openCount()).isZero();
        assertThat(o.paper().userWasArmed).isTrue();
    }

    @Test
    void theTimeStopAndSquareOffComeFromTheClockNotTheTicks() {
        open(intent(100.0, 99.0, 101.33), false);
        tick(100.20, T0.plusSeconds(5));

        clock.setTo(T0.plus(Duration.ofMinutes(89)));
        shadows.checkClocks(s -> 100.20);
        assertThat(outcomes).as("89 minutes: still inside the time stop").isEmpty();

        clock.setTo(T0.plus(Duration.ofMinutes(91)));
        shadows.checkClocks(s -> 100.20);
        assertThat(outcomes).hasSize(1);
        assertThat(outcomes.get(0).reason()).isEqualTo(ExitReason.TIME_STOP);

        // A second paper position later in the day is closed by the square-off.
        clock.setTo(Instant.parse("2026-09-18T09:00:00Z"));   // 14:30 IST
        shadows.open(intent(200.0, 198.0, 202.66), "X", false, RiskLimits.house(), 90, LocalTime.of(15, 10));
        clock.setTo(Instant.parse("2026-09-18T09:41:00Z"));   // 15:11 IST
        shadows.checkClocks(s -> 201.0);
        assertThat(outcomes).hasSize(2);
        assertThat(outcomes.get(1).reason()).isEqualTo(ExitReason.SQUARE_OFF);
    }

    @Test
    void aPositionClosesOnceEvenIfTwoLevelsAreCrossedInTheSameBurst() {
        open(intent(100.0, 99.0, 101.33), false);
        tick(98.0, T0.plusSeconds(1));
        tick(97.0, T0.plusSeconds(2));
        clock.setTo(T0.plus(Duration.ofHours(2)));
        shadows.checkClocks(s -> 97.0);

        assertThat(outcomes).hasSize(1);
    }

    @Test
    void anIntentThatSizesToNothingIsIgnored() {
        // Risk per share of 20,000 against a 3,000 budget rounds to zero shares.
        open(intent(120000.0, 100000.0, 146600.0), false);
        assertThat(shadows.openCount()).isZero();
        // A large but affordable one is fine: 1,000 risk/share → 3 shares.
        open(intent(50000.0, 49000.0, 51330.0), false);
        assertThat(shadows.openCount()).isEqualTo(1);
    }
}
