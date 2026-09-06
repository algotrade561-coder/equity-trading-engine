package com.equity.trading;

import com.equity.domain.position.ExitRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pending requests to close positions, at most one per position.
 *
 * <h2>Why a queue rather than closing on the spot</h2>
 * <p>Design note 0.2. Several checks can decide, on the same tick, that the same position should be
 * closed — the stop, the target, the structure detector, the square-off timer. If each acted
 * directly, the position would be sent two exit orders. Collecting requests and draining them in one
 * place makes double-sending impossible by construction rather than by care.</p>
 *
 * <h2>Merging</h2>
 * <p>A second request for a position already queued does not replace it blindly: the more urgent
 * reason wins. A bar that gaps through both stop and target raises both, and booking the target in
 * that case would record a profit that never existed.</p>
 */
public class ExitQueue {

    private final Map<UUID, ExitRequest> pending = new ConcurrentHashMap<>();

    /**
     * Adds a request, keeping the most urgent reason for that position.
     *
     * @return true if this request changed what is queued
     */
    public boolean offer(ExitRequest request) {
        ExitRequest[] winner = new ExitRequest[1];
        pending.merge(request.positionId(), request, (existing, incoming) ->
                ExitRequest.BY_URGENCY.compare(incoming, existing) < 0 ? incoming : existing);
        winner[0] = pending.get(request.positionId());
        return winner[0] == request;
    }

    /** Everything queued, most urgent first. Draining is the caller's job. */
    public List<ExitRequest> drain() {
        List<ExitRequest> all = new ArrayList<>(pending.values());
        all.sort(ExitRequest.BY_URGENCY);
        all.forEach(r -> pending.remove(r.positionId(), r));
        return all;
    }

    public Optional<ExitRequest> peek(UUID positionId) {
        return Optional.ofNullable(pending.get(positionId));
    }

    public boolean isQueued(UUID positionId) { return pending.containsKey(positionId); }

    public void remove(UUID positionId) { pending.remove(positionId); }

    public int size() { return pending.size(); }
}
