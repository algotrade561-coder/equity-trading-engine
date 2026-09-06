package com.equity.broker.kite;

import com.equity.domain.user.UserId;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Resolves which Kite application a given user logs in through.
 *
 * <p>Falls back to the application-wide key configured in {@link KiteProperties}. A per-user
 * override exists for the case where a user brings their own Kite subscription, and because having
 * the seam from the start is cheaper than retrofitting it once orders are flowing.</p>
 *
 * <p>No method here returns anything printable. Callers get a {@link KiteCredentials}, whose
 * {@code toString} is redacted, and there is deliberately no accessor that hands out the raw secret
 * as a bare String for something else to log.</p>
 */
@Component
public class KiteCredentialsProvider {

    /** Looks a user's stored credentials up. Null when nothing is persisted (tests, or no DB). */
    public interface StoredCredentials {
        java.util.Optional<KiteCredentials> find(UserId userId);
    }

    private final KiteProperties properties;
    private final StoredCredentials stored;
    private final Map<UserId, KiteCredentials> overrides = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public KiteCredentialsProvider(KiteProperties properties, StoredCredentials stored) {
        this.properties = properties;
        this.stored = stored;
    }

    /** Test constructor: configuration only, nothing persisted. */
    public KiteCredentialsProvider(KiteProperties properties) {
        this(properties, userId -> java.util.Optional.empty());
    }

    public void override(UserId userId, KiteCredentials credentials) {
        overrides.put(userId, credentials);
    }

    /**
     * Resolution order: an in-process override, then this user's own stored credentials, then the
     * application-wide pair from configuration.
     *
     * <p>Per-user first is what makes the settings page meaningful — a user who has entered their own
     * Kite app uses it, and everybody else falls back to the shared one. The shared pair last means
     * adding a user never silently gives them somebody else's app.</p>
     */
    public Optional<KiteCredentials> find(UserId userId) {
        KiteCredentials own = overrides.get(userId);
        if (own != null) return Optional.of(own);

        Optional<KiteCredentials> persisted = stored.find(userId);
        if (persisted.isPresent()) return persisted;

        if (!properties.isConfigured()) return Optional.empty();
        return Optional.of(new KiteCredentials(properties.getApiKey(), properties.getApiSecret()));
    }

    public KiteCredentials require(UserId userId) {
        return find(userId).orElseThrow(() -> new IllegalStateException(
                "no Kite API credentials configured for user " + userId
                        + " (set equity.broker.kite.api-key / api-secret)"));
    }
}
