package com.equity.trading;

import static org.assertj.core.api.Assertions.assertThat;

import com.equity.domain.position.ExitReason;
import com.equity.domain.position.ExitRequest;
import com.equity.domain.user.UserId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExitQueueTest {

    private static final UserId USER = UserId.random();
    private static final Instant T0 = Instant.parse("2026-09-04T05:00:00Z");

    private static ExitRequest request(UUID id, ExitReason reason, Instant at) {
        return new ExitRequest(id, USER, "RELIANCE", reason, at, reason.name());
    }

    @Test
    void theStopWinsWhenAStopAndATargetFireOnTheSameTick() {
        UUID position = UUID.randomUUID();
        ExitQueue queue = new ExitQueue();

        queue.offer(request(position, ExitReason.TARGET, T0));
        queue.offer(request(position, ExitReason.HARD_STOP, T0));

        List<ExitRequest> drained = queue.drain();
        assertThat(drained).hasSize(1);
        assertThat(drained.get(0).reason())
                .as("a bar that gaps through both would otherwise book a profit that never existed")
                .isEqualTo(ExitReason.HARD_STOP);
    }

    @Test
    void aLessUrgentReasonDoesNotDisplaceAMoreUrgentOne() {
        UUID position = UUID.randomUUID();
        ExitQueue queue = new ExitQueue();

        queue.offer(request(position, ExitReason.HARD_STOP, T0));
        queue.offer(request(position, ExitReason.STRUCTURE, T0.plusSeconds(1)));

        assertThat(queue.drain().get(0).reason()).isEqualTo(ExitReason.HARD_STOP);
    }

    @Test
    void aRiskHaltOutranksEvenTheStop() {
        UUID position = UUID.randomUUID();
        ExitQueue queue = new ExitQueue();

        queue.offer(request(position, ExitReason.HARD_STOP, T0));
        queue.offer(request(position, ExitReason.RISK_HALT, T0));

        assertThat(queue.drain().get(0).reason())
                .as("a halt is a decision about the account, not about the trade")
                .isEqualTo(ExitReason.RISK_HALT);
    }

    @Test
    void onePositionCanOnlyEverBeQueuedOnce() {
        UUID position = UUID.randomUUID();
        ExitQueue queue = new ExitQueue();

        for (int i = 0; i < 20; i++) {
            queue.offer(request(position, ExitReason.HARD_STOP, T0.plusSeconds(i)));
        }

        assertThat(queue.size())
                .as("twenty ticks through the stop must not send twenty exit orders")
                .isEqualTo(1);
    }

    @Test
    void drainsMostUrgentFirstAcrossPositions() {
        ExitQueue queue = new ExitQueue();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();

        queue.offer(request(a, ExitReason.TARGET, T0));
        queue.offer(request(b, ExitReason.RISK_HALT, T0));
        queue.offer(request(c, ExitReason.HARD_STOP, T0));

        assertThat(queue.drain()).extracting(ExitRequest::reason)
                .containsExactly(ExitReason.RISK_HALT, ExitReason.HARD_STOP, ExitReason.TARGET);
    }

    @Test
    void equalUrgencyIsOrderedByTimeSoAReplayIsReproducible() {
        ExitQueue queue = new ExitQueue();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        queue.offer(request(second, ExitReason.HARD_STOP, T0.plusSeconds(5)));
        queue.offer(request(first, ExitReason.HARD_STOP, T0));

        assertThat(queue.drain()).extracting(ExitRequest::positionId)
                .as("without a deterministic tie-break the order depends on hash iteration")
                .containsExactly(first, second);
    }

    @Test
    void drainingEmptiesTheQueue() {
        ExitQueue queue = new ExitQueue();
        queue.offer(request(UUID.randomUUID(), ExitReason.TARGET, T0));

        assertThat(queue.drain()).hasSize(1);
        assertThat(queue.drain()).isEmpty();
        assertThat(queue.size()).isZero();
    }

    @Test
    void mandatoryReasonsAreTheOnesThatMustRunWhileHalted() {
        assertThat(ExitReason.RISK_HALT.isMandatory()).isTrue();
        assertThat(ExitReason.HARD_STOP.isMandatory()).isTrue();
        assertThat(ExitReason.SQUARE_OFF.isMandatory()).isTrue();
        assertThat(ExitReason.TARGET.isMandatory()).isFalse();
        assertThat(ExitReason.STRUCTURE.isMandatory()).isFalse();
    }
}
