package com.equity.broker.kite;

import com.equity.domain.user.UserId;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Where a Kite session is kept between restarts.
 *
 * <p>Earlier this store was memory-only and said so: a token is worthless tomorrow, so persisting it
 * bought only survival across a same-day restart at the cost of writing a bearer credential to disk.
 * That trade changes once the token is encrypted at rest — and the cost of the old behaviour turned
 * out to be real. Every restart during market hours dropped the login, which meant no feed, no
 * margin, and no way to manage open positions until somebody noticed and signed in again.</p>
 *
 * <p>Implementations must encrypt. This interface deals in plaintext because the caller has none of
 * the key material; the implementation is where the boundary is.</p>
 */
public interface KiteSessionPersistence {

    record StoredSession(UserId userId, String kiteUserId, String accessToken,
                         LocalDate tradingDate, java.time.Instant issuedAt) {}

    void save(StoredSession session);

    Optional<StoredSession> load(UserId userId, LocalDate tradingDate);

    /** Everything valid for the given date — what a restart uses to bring the feed back by itself. */
    List<StoredSession> loadAll(LocalDate tradingDate);

    void clear(UserId userId);

    /** Test and no-persistence default. */
    static KiteSessionPersistence inMemory() {
        return new KiteSessionPersistence() {
            private final java.util.Map<UserId, StoredSession> map =
                    new java.util.concurrent.ConcurrentHashMap<>();

            @Override public void save(StoredSession s) { map.put(s.userId(), s); }

            @Override public Optional<StoredSession> load(UserId userId, LocalDate date) {
                return Optional.ofNullable(map.get(userId))
                        .filter(s -> s.tradingDate().equals(date));
            }

            @Override public List<StoredSession> loadAll(LocalDate date) {
                return map.values().stream().filter(s -> s.tradingDate().equals(date)).toList();
            }

            @Override public void clear(UserId userId) { map.remove(userId); }
        };
    }
}
