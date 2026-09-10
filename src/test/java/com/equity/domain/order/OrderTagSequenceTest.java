package com.equity.domain.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.equity.domain.user.UserId;
import org.junit.jupiter.api.Test;

/**
 * A tag has to identify one order, for the whole day.
 *
 * <p>It did not. The sequence lived in a static counter that began at zero, so each restart reissued
 * numbers the session had already spent. On 10 September three restarts minted {@code ...-000001}
 * for ACUTAAS, ZFCVINDIA and PNBHOUSING; when the broker echoed that tag back on PNBHOUSING's fill
 * the engine matched it to the stale ZFCVINDIA position, PNBHOUSING's own row kept a quantity of
 * zero, and 170 shares sat with no stop while a fictional profit was booked on a position that had
 * never been held.</p>
 */
class OrderTagSequenceTest {

    private static final UserId USER = UserId.of("07926329-989e-4d3c-b229-119b4a6c81dc");

    @Test
    void aTagCarriesTheSequenceThatCanBeReadBackFromIt() {
        OrderTag tag = new OrderTag("e07926329-000042");

        assertThat(tag.sequence())
                .as("the tag is the only thing the broker echoes back, so it has to be "
                        + "self-describing on recovery")
                .isEqualTo(42);
    }

    @Test
    void aTagWithNoUsableSequenceSaysSoRatherThanGuessing() {
        assertThat(new OrderTag("e07926329-abcdef").sequence()).isEqualTo(-1);
        assertThat(new OrderTag("nodash").sequence()).isEqualTo(-1);
        assertThat(new OrderTag("e07926329-").sequence())
                .as("a zero here would silently reset the day's numbering")
                .isEqualTo(-1);
    }

    /**
     * The counter is static and shared, so every assertion here is relative to where it already
     * stands. Asserting absolute numbers made these tests depend on each other's ordering — which
     * is worth avoiding on its own, and would also have hidden the wrap described below.
     */
    @Test
    void resumingCarriesTheSequencePastWhatTheDayAlreadyUsed() {
        long alreadyUsed = OrderTag.currentSequence() + 500;
        OrderTag.resumeAfter(alreadyUsed);

        assertThat(OrderTag.currentSequence())
                .as("the day had reached %d, so this process must continue above it — issuing 1 "
                        + "again is what let a fill be matched to the wrong stock", alreadyUsed)
                .isEqualTo(alreadyUsed);
        assertThat(OrderTag.forEntry(USER).sequence()).isEqualTo(alreadyUsed + 1);
    }

    @Test
    void resumingNeverWalksTheSequenceBackwards() {
        long high = OrderTag.currentSequence() + 10_000;
        OrderTag.resumeAfter(high);

        OrderTag.resumeAfter(5);
        assertThat(OrderTag.currentSequence())
                .as("a partial or out-of-order restore must not send the counter back into numbers "
                        + "it has already issued")
                .isEqualTo(high);
    }

    @Test
    void twoTagsMintedInOneProcessAreNeverEqual() {
        OrderTag first = OrderTag.forEntry(USER);
        OrderTag second = OrderTag.forEntry(USER);
        OrderTag exit = OrderTag.forExit(USER);

        assertThat(first.value()).isNotEqualTo(second.value());
        assertThat(second.value()).isNotEqualTo(exit.value());
    }

    /**
     * Kite truncates past 20 characters silently, so a resumed sequence must not push the tag over.
     *
     * <p>The format holds because the sequence is taken modulo a million and printed to six digits,
     * which fixes the length whatever the counter reaches. That modulo does mean the numbering wraps
     * after a million orders in one process — irrelevant against a cap of twenty attempts a day, and
     * noted here so the next reader does not have to work it out from the format string.</p>
     */
    @Test
    void aResumedTagStillFitsInsideWhatTheBrokerAccepts() {
        OrderTag.resumeAfter(OrderTag.currentSequence() + 500_000);
        OrderTag tag = OrderTag.forEntry(USER);

        assertThat(tag.value().length()).isLessThanOrEqualTo(OrderTag.MAX_LENGTH);
        assertThat(tag.belongsTo(USER))
                .as("an order whose owner cannot be identified is one nobody can safely close")
                .isTrue();
    }
}
