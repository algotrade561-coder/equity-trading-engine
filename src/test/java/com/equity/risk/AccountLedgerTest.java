package com.equity.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.equity.broker.ProductType;
import com.equity.domain.Direction;
import com.equity.domain.momentum.EntryPattern;
import com.equity.domain.order.OrderTag;
import com.equity.domain.position.ExitReason;
import com.equity.domain.position.Position;
import com.equity.domain.user.UserId;
import com.equity.platform.time.FixedTradingClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AccountLedgerTest {

    private static final Instant NOW = Instant.parse("2026-09-04T05:00:00Z");
    private static final UserId USER = UserId.random();
    private static final UserId OTHER = UserId.random();

    private FixedTradingClock clock;
    private AccountLedger ledger;

    @BeforeEach
    void setUp() {
        clock = new FixedTradingClock(NOW);
        ledger = new AccountLedger(clock);
    }

    private Position closedWithPnl(UserId user, double pnl) {
        Position position = Position.pendingEntry(user, "RELIANCE", Direction.LONG,
                        EntryPattern.PULLBACK_CONTINUATION, ProductType.MIS, 100, 1000, 990, 1020,
                        OrderTag.forEntry(user), "o1", NOW)
                .withFill(100, 1000, NOW);
        return position.withClose(1000 + pnl / 100.0, ExitReason.TARGET, NOW);
    }

    @Test
    void countsRealisedProfitPerUser() {
        ledger.recordClosed(closedWithPnl(USER, -500));
        ledger.recordClosed(closedWithPnl(USER, 200));
        ledger.recordClosed(closedWithPnl(OTHER, -9999));

        assertThat(ledger.realised(USER)).isEqualTo(-300.0);
        assertThat(ledger.realised(OTHER))
                .as("one user's losses must not touch another's ledger")
                .isEqualTo(-9999.0);
    }

    @Test
    void theLimitCountsOpenLossesNotJustClosedOnes() {
        ledger.recordClosed(closedWithPnl(USER, -1_000));

        // Realised alone is inside the limit; with the open position it is not.
        assertThat(ledger.checkAndLatch(USER, 0, 3_000)).isFalse();
        assertThat(ledger.checkAndLatch(USER, -2_500, 3_000))
                .as("a limit that ignores open losses lets an account sit far beyond it")
                .isTrue();
    }

    @Test
    void onceLatchedItStaysLatchedEvenIfTheMarketComesBack() {
        assertThat(ledger.checkAndLatch(USER, -3_500, 3_000)).isTrue();

        assertThat(ledger.checkAndLatch(USER, 5_000, 3_000))
                .as("a limit that un-latches hands the account back to the conditions that broke it")
                .isTrue();
        assertThat(ledger.isLatched(USER)).isTrue();
    }

    @Test
    void latchingIsPerUser() {
        ledger.checkAndLatch(USER, -5_000, 3_000);

        assertThat(ledger.isLatched(USER)).isTrue();
        assertThat(ledger.isLatched(OTHER)).isFalse();
    }

    @Test
    void theLatchReasonSaysWhatBrokeIt() {
        ledger.recordClosed(closedWithPnl(USER, -2_000));
        ledger.checkAndLatch(USER, -1_500, 3_000);

        assertThat(ledger.latchReason(USER))
                .contains("realised").contains("open").contains("3000");
    }

    @Test
    void attemptsAreCountedAtSubmissionNotAtFill() {
        ledger.recordAttempt(USER);
        ledger.recordAttempt(USER);
        ledger.recordFill(USER);

        assertThat(ledger.attempts(USER))
                .as("an attempt consumes daily capacity whether or not it worked")
                .isEqualTo(2);
        assertThat(ledger.fills(USER)).isEqualTo(1);
    }

    @Test
    void everythingResetsOnANewTradingDate() {
        ledger.recordClosed(closedWithPnl(USER, -5_000));
        ledger.recordAttempt(USER);
        ledger.checkAndLatch(USER, 0, 3_000);
        assertThat(ledger.isLatched(USER)).isTrue();

        clock.advance(Duration.ofHours(24));

        assertThat(ledger.isLatched(USER)).isFalse();
        assertThat(ledger.realised(USER)).isZero();
        assertThat(ledger.attempts(USER)).isZero();
    }

    @Test
    void aLatchCanBeClearedDeliberately() {
        ledger.checkAndLatch(USER, -5_000, 3_000);

        ledger.clearLatch(USER);

        assertThat(ledger.isLatched(USER)).isFalse();
        assertThat(ledger.latchReason(USER)).isNull();
    }

    @Test
    void theLimitSignIsIgnoredSoAPositiveConfigStillMeansALoss() {
        // Someone will eventually configure 3000 meaning "three thousand of loss". Both readings
        // must behave the same; the alternative is a limit that never fires.
        assertThat(ledger.checkAndLatch(USER, -3_100, 3_000)).isTrue();

        AccountLedger other = new AccountLedger(clock);
        assertThat(other.checkAndLatch(USER, -3_100, -3_000)).isTrue();
    }
}
