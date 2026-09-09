package com.equity.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.equity.broker.ProductType;
import com.equity.domain.Direction;
import com.equity.domain.momentum.EntryPattern;
import com.equity.domain.order.OrderTag;
import com.equity.domain.position.ExitReason;
import com.equity.domain.position.Position;
import com.equity.domain.user.UserId;
import java.time.Instant;
import com.equity.strategy.ShadowExits.ShadowOutcome;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The counterfactual has to be a real counterfactual.
 *
 * <p>Everything here guards one property: a shadow policy must produce <b>its own</b> answer. The
 * failure mode is silent and total — a shadow that inherits the live stop agrees with the live
 * policy on every trade, the month's data shows five identical columns, and the conclusion drawn
 * from it is that the exit policy does not matter.</p>
 */
class ShadowExitsTest {

    private static final UserId USER = UserId.of("11111111-1111-1111-1111-111111111111");
    private static final Instant AT = Instant.parse("2026-09-09T04:30:00Z");

    /** Entry 100, stop 98, target 104. R is 2.00. */
    private static Position open(int quantity) {
        return Position.pendingEntry(USER, "BHEL", Direction.LONG, EntryPattern.PULLBACK_CONTINUATION,
                        ProductType.MIS, quantity, 100.0, 98.0, 104.0,
                        new OrderTag("t"), "o1", AT)
                .withFill(quantity, 100.0, AT);
    }

    /** Walks a position through prices the way the live tick path does: mark first, then evaluate. */
    private static Position feed(ShadowExits shadows, Position position, double atr, double... prices) {
        for (double price : prices) {
            Position marked = position.withHighWaterMark(price).withLowWaterMark(price);
            shadows.onTick(marked, price, atr);
            // The live policy is breakeven-at-1R, so the live stop moves and the shadows must not
            // be measured against it. This is the exact situation the rewind exists for.
            position = StopAdjuster.adjust(marked, ExitPolicy.breakevenAtOneR(), atr);
        }
        return position;
    }

    @Test
    void aFixedStopIsNotDraggedUpByTheLivePolicysBreakeven() {
        ShadowExits shadows = new ShadowExits();

        // Runs to 1R (102), which arms the live breakeven, then falls back through the entry. The
        // live trade stops at 100 and fills at 99.50; a fixed stop at 98 was never touched.
        Position position = feed(shadows, open(500), 1.0, 100.5, 102.0, 101.0, 99.5);
        assertThat(position.stopPrice())
                .as("the live policy has moved its stop to entry")
                .isEqualTo(100.0);

        Map<String, Double> pnl = shadows.settle(
                position.withClose(99.5, ExitReason.HARD_STOP, AT)).pnl();

        assertThat(pnl.get("be1R"))
                .as("the shadow matching the live policy triggers at its 100.00 stop, and books it "
                        + "there rather than at the 99.50 the live order actually filled at — a "
                        + "shadow fill cannot be observed, so every candidate is treated alike")
                .isCloseTo(0.0, within(0.01));
        assertThat(pnl.get("fixed"))
                .as("98 was never touched, so this policy never triggered and takes the real exit "
                        + "of 99.50: -250. If it had inherited the live 100.00 stop it would report "
                        + "a scratch instead, which is the failure this test exists to catch")
                .isCloseTo(-250.0, within(0.01));
    }

    @Test
    void aFixedStopKeepsRunningWhereBreakevenScratches() {
        ShadowExits shadows = new ShadowExits();

        // 1R, back to 99 (below entry, so breakeven is hit), then away to the 104 target.
        Position position = feed(shadows, open(500), 1.0, 102.0, 99.0, 101.0, 104.0);
        Map<String, Double> pnl = shadows.settle(
                position.withClose(100.0, ExitReason.HARD_STOP, AT)).pnl();

        assertThat(pnl.get("be1R"))
                .as("stopped at entry on the pullback, exactly as the live trade was")
                .isCloseTo(0.0, within(0.01));
        assertThat(pnl.get("fixed"))
                .as("98 was never touched, so it survived the shake-out and reached the 104 target: "
                        + "4.00 x 500. This is the cost of breakeven, measured rather than argued")
                .isCloseTo(2000.0, within(0.01));
    }

    @Test
    void aPolicyThatNeverTriggersTakesTheRealExit() {
        ShadowExits shadows = new ShadowExits();
        Position position = feed(shadows, open(100), 1.0, 100.5, 101.0);

        Map<String, Double> pnl = shadows.settle(
                position.withClose(101.0, ExitReason.SQUARE_OFF, AT)).pnl();

        assertThat(pnl).containsOnlyKeys("fixed", "be1R", "trail1R_1.5atr", "trail2R_2.5atr",
                "be1R_trail2R");
        assertThat(pnl.values())
                .as("nothing reached a stop or a target, so every policy was still holding at the "
                        + "square-off and every one books the square-off price")
                .allSatisfy(v -> assertThat(v).isCloseTo(100.0, within(0.01)));
    }

    @Test
    void trailingArmsOnlyAfterItsThresholdAndFollowsTheHighWaterMark() {
        ShadowExits shadows = new ShadowExits();

        // ATR 1.0. trail1R sits 1.5 behind the high once 1R (102) is reached; at a high of 103 that
        // is 101.5, which the fall to 101 breaches. trail2R does not arm until 104 and never does.
        Position position = feed(shadows, open(200), 1.0, 102.0, 103.0, 101.0);
        Map<String, Double> pnl = shadows.settle(
                position.withClose(101.0, ExitReason.SQUARE_OFF, AT)).pnl();

        assertThat(pnl.get("trail1R_1.5atr"))
                .as("armed at 102, trailed to 101.50, stopped there: 1.50 x 200")
                .isCloseTo(300.0, within(0.01));
        assertThat(pnl.get("trail2R_2.5atr"))
                .as("never reached 2R, so it never armed and took the square-off")
                .isCloseTo(200.0, within(0.01));
    }

    @Test
    void aTrailingStopWithNoAtrDoesNothingRatherThanInventingAWidth() {
        ShadowExits shadows = new ShadowExits();
        Position position = feed(shadows, open(100), Double.NaN, 102.0, 103.0, 101.0);

        Map<String, Double> pnl = shadows.settle(
                position.withClose(101.0, ExitReason.SQUARE_OFF, AT)).pnl();

        assertThat(pnl.get("trail1R_1.5atr"))
                .as("a trailing stop with a made-up distance looks calibrated and is not")
                .isCloseTo(100.0, within(0.01));
    }

    @Test
    void settlingReleasesTheMemoryForThatPosition() {
        ShadowExits shadows = new ShadowExits();
        Position position = feed(shadows, open(100), 1.0, 101.0);
        assertThat(shadows.tracked()).isEqualTo(1);

        shadows.settle(position.withClose(101.0, ExitReason.SQUARE_OFF, AT));
        assertThat(shadows.tracked())
                .as("a day of trades must not accumulate maps for positions that are long gone")
                .isZero();
    }

    @Test
    void anUnfilledPositionYieldsNothingToCompare() {
        ShadowExits shadows = new ShadowExits();
        Position pending = Position.pendingEntry(USER, "BHEL", Direction.LONG,
                EntryPattern.PULLBACK_CONTINUATION, ProductType.MIS, 100, 100.0, 98.0, 104.0,
                new OrderTag("t"), "o1", AT);

        shadows.onTick(pending, 101.0, 1.0);
        assertThat(shadows.settle(pending).pnl()).isEmpty();
    }

    /**
     * A trade the engine did not watch from its fill must say so.
     *
     * <p>This is the position adopted at startup or reconciled in from the broker. Its shadows begin
     * from wherever the day has already reached, so a policy that would have exited during the
     * unseen part cannot know it — and the row would otherwise look identical to a clean one.</p>
     */
    @Test
    void aTradePickedUpMidFlightIsMarkedAsSuch() {
        ShadowExits shadows = new ShadowExits();

        // First tick already 1.5R ahead: the fill and the move to it were never seen.
        Position position = feed(shadows, open(100), 1.0, 103.0, 103.5);
        ShadowOutcome outcome = shadows.settle(
                position.withClose(103.5, ExitReason.SQUARE_OFF, AT));

        assertThat(outcome.watchedFromR())
                .as("1.5R had already been given away before anything was recorded")
                .isCloseTo(1.5, within(0.01));
        assertThat(outcome.fromEntry())
                .as("pooling this with trades watched from entry would bias every policy upwards")
                .isFalse();
    }

    /**
     * The first tick after a fill has already moved, and that must still count as watched.
     *
     * <p>100.20 on a 2.00 R is 0.1R gone before anything was recorded. An exact-zero test would
     * call this trade unwatched, and since every live trade looks like this, the whole month would
     * be discarded by a filter that appeared to be working.</p>
     */
    @Test
    void aTradeWatchedFromItsFillIsCleanToPoolEvenIfTheFirstTickHasMoved() {
        ShadowExits shadows = new ShadowExits();
        Position position = feed(shadows, open(100), 1.0, 100.2, 101.0);

        ShadowOutcome outcome = shadows.settle(
                position.withClose(101.0, ExitReason.SQUARE_OFF, AT));
        assertThat(outcome.watchedFromR()).isCloseTo(0.1, within(0.001));
        assertThat(outcome.fromEntry()).isTrue();
    }

    @Test
    void aPositionNeverTickedAtAllIsNotClaimedAsWatched() {
        ShadowExits shadows = new ShadowExits();
        Position position = open(100);

        ShadowOutcome outcome = shadows.settle(
                position.withClose(101.0, ExitReason.SQUARE_OFF, AT));
        assertThat(outcome.watchedFromR())
                .as("no ticks were seen, which is not the same as having seen it from the start")
                .isNaN();
        assertThat(outcome.fromEntry()).isFalse();
    }
}
