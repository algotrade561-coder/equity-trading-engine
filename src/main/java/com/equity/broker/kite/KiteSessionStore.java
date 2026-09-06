package com.equity.broker.kite;

import com.equity.domain.user.UserId;
import com.equity.platform.time.TradingClock;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Holds the live Kite session of each user.
 *
 * <p>In memory only, and that is a deliberate first cut rather than an oversight. A Kite access
 * token is worthless tomorrow, so persisting it buys one thing — surviving a restart within the same
 * session day — at the cost of writing a bearer credential to disk. Until that store is encrypted at
 * rest with a key the application does not also keep next to it, a restart during market hours
 * should demand a fresh login, loudly. The replacement is a persistent encrypted store; the
 * interface here does not change when it arrives.</p>
 *
 * <p>Expiry is evaluated against the trading date from the injected clock, so REPLAY sees the same
 * expiry logic as LIVE instead of quietly passing because the wall clock happens to agree.</p>
 */
@Component
public class KiteSessionStore {

    private final TradingClock clock;
    private final KiteSessionPersistence persistence;
    private final Map<UserId, KiteSession> sessions = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public KiteSessionStore(TradingClock clock, KiteSessionPersistence persistence) {
        this.clock = clock;
        this.persistence = persistence;
    }

    /** Test constructor: memory only. */
    public KiteSessionStore(TradingClock clock) {
        this(clock, KiteSessionPersistence.inMemory());
    }

    public void put(KiteSession session) {
        sessions.put(session.userId(), session);
        persistence.save(new KiteSessionPersistence.StoredSession(
                session.userId(), session.kiteUserId(), session.accessToken(),
                session.tradingDate(), session.issuedAt()));
    }

    /** Returns the session only while it is valid for today; an expired one is evicted, not returned. */
    public Optional<KiteSession> get(UserId userId) {
        KiteSession s = sessions.get(userId);
        if (s == null) {
            // Cold start: the process restarted but the token may still be good for today.
            s = persistence.load(userId, clock.tradingDate())
                    .map(stored -> new KiteSession(stored.userId(), stored.kiteUserId(),
                            stored.accessToken(), null, stored.tradingDate(), stored.issuedAt()))
                    .orElse(null);
            if (s != null) sessions.put(userId, s);
        }
        if (s == null) return Optional.empty();
        LocalDate today = clock.tradingDate();
        if (!s.isValidOn(today)) {
            sessions.remove(userId, s);
            persistence.clear(userId);
            return Optional.empty();
        }
        return Optional.of(s);
    }

    public boolean isAuthenticated(UserId userId) {
        return get(userId).isPresent();
    }

    public void clear(UserId userId) {
        sessions.remove(userId);
        persistence.clear(userId);
    }

    /**
     * Users with a session still valid for today, including any only on disk.
     *
     * <p>Used at startup to bring the feed back without a human signing in again.</p>
     */
    public java.util.List<UserId> restorableUsers() {
        return persistence.loadAll(clock.tradingDate()).stream()
                .map(KiteSessionPersistence.StoredSession::userId).toList();
    }

    /** Users currently holding a valid session. Used to decide who can be traded for today. */
    public java.util.Set<UserId> authenticatedUsers() {
        LocalDate today = clock.tradingDate();
        java.util.Set<UserId> out = new java.util.LinkedHashSet<>();
        sessions.forEach((id, s) -> { if (s.isValidOn(today)) out.add(id); });
        return out;
    }
}
