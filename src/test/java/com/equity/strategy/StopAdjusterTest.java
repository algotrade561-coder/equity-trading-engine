package com.equity.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.equity.broker.ProductType;
import com.equity.domain.Direction;
import com.equity.domain.momentum.EntryPattern;
import com.equity.domain.order.OrderTag;
import com.equity.domain.position.Position;
import com.equity.domain.user.UserId;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Where the stop sits as a trade runs.
 *
 * <p>Entry 1000, stop 990 — so one R is 10 rupees and every level below is in units anyone can check
 * by hand.</p>
 */
class StopAdjusterTest {

    private static final Instant NOW = Instant.parse("2026-09-04T05:00:00Z");
    private static final UserId USER = UserId.random();
    private static final double ATR = 4.0;

    /** Open at 1000 with a 990 stop, having since traded up to {@code high}. */
    private static Position openAt(double high) {
        return Position.pendingEntry(USER, "RELIANCE", Direction.LONG,
                        EntryPattern.PULLBACK_CONTINUATION, ProductType.MIS, 100,
                        1000, 990, 1020, OrderTag.forEntry(USER), "o1", NOW)
                .withFill(100, 1000, NOW)
                .withHighWaterMark(high);
    }

    // ── The high-water mark ──────────────────────────────────────────────────

    @Test
    void theMarkOnlyEverMovesInTheFavourableDirection() {
        Position p = openAt(1015).withHighWaterMark(1004);

        assertThat(p.highWaterMark())
                .as("a mark that follows price back down would let a trailing stop loosen")
                .isEqualTo(1015);
    }

    @Test
    void progressIsMeasuredFromTheMarkNotTheCurrentPrice() {
        Position p = openAt(1015).withHighWaterMark(1002);

        assertThat(p.favourableExcursionR())
                .as("reached 1.5R must stay true after a pullback, or trailing arms and disarms")
                .isEqualTo(1.5);
    }

    // ── Nothing happens under the shipped policy ─────────────────────────────

    @Test
    void theShippedPolicyNeverTouchesTheStop() {
        Position p = openAt(1050);

        Position after = StopAdjuster.adjust(p, ExitPolicy.fixed(), ATR);

        assertThat(after.stopPrice()).isEqualTo(990);
        assertThat(StopAdjuster.moved(p, after)).isFalse();
    }

    // ── Breakeven ────────────────────────────────────────────────────────────

    @Test
    void breakevenDoesNothingBeforeItIsArmed() {
        ExitPolicy policy = new ExitPolicy(true, 1.0, false, 1.0, 1.5, false);

        assertThat(StopAdjuster.adjust(openAt(1009), policy, ATR).stopPrice())
                .as("0.9R is not 1R")
                .isEqualTo(990);
    }

    @Test
    void breakevenMovesTheStopToTheFillExactly() {
        ExitPolicy policy = new ExitPolicy(true, 1.0, false, 1.0, 1.5, false);

        assertThat(StopAdjuster.adjust(openAt(1010), policy, ATR).stopPrice())
                .as("through the fill would make a scratch impossible and guarantee a small loss")
                .isEqualTo(1000);
    }

    // ── Trailing ─────────────────────────────────────────────────────────────

    @Test
    void trailingDoesNothingBeforeItIsArmed() {
        ExitPolicy policy = new ExitPolicy(false, 1.0, true, 1.0, 1.5, false);

        assertThat(StopAdjuster.adjust(openAt(1005), policy, ATR).stopPrice()).isEqualTo(990);
    }

    @Test
    void trailingFollowsTheMarkAtTheConfiguredAtrDistance() {
        ExitPolicy policy = new ExitPolicy(false, 1.0, true, 1.0, 1.5, false);

        // 1.5 x ATR 4.0 = 6 below a 1030 mark
        assertThat(StopAdjuster.adjust(openAt(1030), policy, ATR).stopPrice()).isEqualTo(1024);
    }

    @Test
    void trailingNeverLoosensWhenPriceFallsBack() {
        ExitPolicy policy = new ExitPolicy(false, 1.0, true, 1.0, 1.5, false);
        Position trailed = StopAdjuster.adjust(openAt(1030), policy, ATR);
        assertThat(trailed.stopPrice()).isEqualTo(1024);

        Position later = StopAdjuster.adjust(trailed.withHighWaterMark(1012), policy, ATR);

        assertThat(later.stopPrice())
                .as("the mark did not move, so neither may the stop")
                .isEqualTo(1024);
    }

    @Test
    void aMissingAtrDisablesTrailingRatherThanInventingADistance() {
        ExitPolicy policy = new ExitPolicy(false, 1.0, true, 1.0, 1.5, false);

        assertThat(StopAdjuster.adjust(openAt(1050), policy, Double.NaN).stopPrice())
                .as("a trailing stop with an invented width looks calibrated and is not")
                .isEqualTo(990);
        assertThat(StopAdjuster.adjust(openAt(1050), policy, 0).stopPrice()).isEqualTo(990);
    }

    // ── The two together ─────────────────────────────────────────────────────

    @Test
    void whicheverPolicyAsksForTheTighterStopWins() {
        ExitPolicy both = ExitPolicy.trailing();

        // At 1012 the trail wants 1006, breakeven wants 1000 — the trail is tighter.
        assertThat(StopAdjuster.adjust(openAt(1012), both, ATR).stopPrice()).isEqualTo(1006);

        // At 1010 exactly, the trail wants 1004 and breakeven 1000; still the trail.
        assertThat(StopAdjuster.adjust(openAt(1010), both, ATR).stopPrice()).isEqualTo(1004);
    }

    @Test
    void aWideAtrCannotDragTheStopBackDown() {
        ExitPolicy both = ExitPolicy.trailing();

        // Trailing 1.5 x ATR 40 below a 1015 mark would be 955 — far below the original stop.
        assertThat(StopAdjuster.adjust(openAt(1015), both, 40.0).stopPrice())
                .as("a stop that can widen is not a stop; breakeven still applies")
                .isEqualTo(1000);
    }

    @Test
    void aPositionWithNoExposureIsLeftAlone() {
        Position pending = Position.pendingEntry(USER, "RELIANCE", Direction.LONG,
                EntryPattern.PULLBACK_CONTINUATION, ProductType.MIS, 100,
                1000, 990, 1020, OrderTag.forEntry(USER), "o1", NOW);

        assertThat(StopAdjuster.adjust(pending, ExitPolicy.trailing(), ATR).stopPrice())
                .as("nothing is held yet, so there is nothing to protect")
                .isEqualTo(990);
    }
}
