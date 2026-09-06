package com.equity.domain.position;

import com.equity.domain.user.UserId;
import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;

/**
 * A request that a position be closed.
 *
 * <p>Nothing closes a position directly. Every source — the stop check, the target check, the
 * structure detector, the square-off timer, an operator — raises one of these, and a single owner
 * drains the queue. Design note 0.2: in the sibling options engine five code paths could each close
 * a position, and 29% of one strategy's trades were exited by a path that did not own them, which
 * made the exit logic impossible to reason about and occasionally double-sent.</p>
 */
public record ExitRequest(
        UUID positionId,
        UserId userId,
        String symbol,
        ExitReason reason,
        Instant requestedAt,
        String note) {

    /**
     * Highest-priority reason first; ties broken by whichever was raised earlier.
     *
     * <p>The tie-break matters: without it the order of two same-priority requests depends on hash
     * iteration, and a replay stops being reproducible.</p>
     */
    public static final Comparator<ExitRequest> BY_URGENCY =
            Comparator.comparingInt((ExitRequest r) -> r.reason().priority())
                    .thenComparing(ExitRequest::requestedAt)
                    .thenComparing(r -> r.positionId().toString());

    public static ExitRequest of(Position position, ExitReason reason, Instant at, String note) {
        return new ExitRequest(position.id(), position.userId(), position.symbol(), reason, at, note);
    }
}
