package com.equity.provisioning;

import com.equity.domain.user.UserId;
import java.util.List;
import java.util.Optional;

/**
 * Where allocations are kept. Implemented over JPA in the store package, in memory for tests.
 *
 * <p>Rows are never deleted. A released allocation stays as history, because "which address did
 * this user trade from in August" is a question a regulator can ask.</p>
 */
public interface IpAllocationStore {

    /** Persists and returns the row with its identity set. */
    IpAllocation save(IpAllocation allocation);

    /** The user's current allocation — the one that is not RELEASED — if any. */
    Optional<IpAllocation> current(UserId userId);

    /** Every allocation that is not RELEASED, across users. What counts against capacity. */
    List<IpAllocation> live();

    static IpAllocationStore inMemory() {
        return new IpAllocationStore() {
            private final java.util.Map<Long, IpAllocation> rows = new java.util.concurrent.ConcurrentHashMap<>();
            private final java.util.concurrent.atomic.AtomicLong ids = new java.util.concurrent.atomic.AtomicLong();

            @Override public IpAllocation save(IpAllocation a) {
                IpAllocation withId = a.id() == null ? a.withId(ids.incrementAndGet()) : a;
                rows.put(withId.id(), withId);
                return withId;
            }
            @Override public Optional<IpAllocation> current(UserId userId) {
                return rows.values().stream()
                        .filter(a -> a.userId().equals(userId) && !a.status().isFinished())
                        .max(java.util.Comparator.comparing(IpAllocation::createdAt));
            }
            @Override public List<IpAllocation> live() {
                return rows.values().stream().filter(a -> !a.status().isFinished()).toList();
            }
        };
    }
}
