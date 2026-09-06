package com.equity.risk;

import com.equity.domain.user.UserId;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Where a user's running day is kept between restarts.
 *
 * <p>An interface with a do-nothing default so {@link AccountLedger} stays a plain object that unit
 * tests can construct without a database, while the running application gets durability. The
 * alternative — a repository injected directly — would make every ledger test a Spring test.</p>
 */
public interface LedgerStore {

    /** A day's accumulated state, as stored. */
    record Snapshot(double realised, int attempts, int fills, boolean latched, String latchReason) {
        public static Snapshot empty() { return new Snapshot(0, 0, 0, false, null); }
    }

    Optional<Snapshot> load(UserId userId, LocalDate tradingDate);

    void save(UserId userId, LocalDate tradingDate, Snapshot snapshot);

    /** Used by tests and by a run with persistence switched off. */
    static LedgerStore inMemory() {
        return new LedgerStore() {
            private final java.util.Map<String, Snapshot> map = new java.util.concurrent.ConcurrentHashMap<>();

            @Override public Optional<Snapshot> load(UserId userId, LocalDate date) {
                return Optional.ofNullable(map.get(userId + "|" + date));
            }

            @Override public void save(UserId userId, LocalDate date, Snapshot snapshot) {
                map.put(userId + "|" + date, snapshot);
            }
        };
    }
}
