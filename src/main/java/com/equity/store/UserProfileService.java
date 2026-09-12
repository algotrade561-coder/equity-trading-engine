package com.equity.store;

import com.equity.domain.risk.RiskLimits;
import com.equity.domain.user.UserId;
import com.equity.platform.security.SecretCipher;
import com.equity.strategy.ExitPolicy;
import com.equity.strategy.StrategyThresholds;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one place that reads and writes a user's stored configuration.
 *
 * <p>It owns encryption, so exactly one class can get that wrong: credentials are encrypted on the
 * way in and decrypted on the way out, and nothing above this layer ever holds ciphertext or has to
 * remember to encrypt. Equally, nothing below it ever holds plaintext.</p>
 *
 * <p>The read side is deliberately asymmetric. {@link #brokerCredentials} returns real secrets and
 * is called only by the broker layer; {@link #describeBrokerConfig} returns what a settings page may
 * see — whether a key is present, not what it is. A UI that can display a secret is a UI that can
 * leak one, and spec section 37 forbids credentials in API responses.</p>
 */
@Service
public class UserProfileService {

    private static final Logger log = LoggerFactory.getLogger(UserProfileService.class);

    private final ConfigChangeRepository configChanges;
    private final PositionRepository positions;
    private final com.equity.platform.time.TradingClock clock;
    private final AppUserRepository users;
    private final BrokerConfigRepository brokerConfigs;
    private final UserSettingsRepository settings;
    private final SecretCipher cipher;

    public UserProfileService(AppUserRepository users, BrokerConfigRepository brokerConfigs,
                              UserSettingsRepository settings, ConfigChangeRepository configChanges,
                              PositionRepository positions,
                              com.equity.platform.time.TradingClock clock, SecretCipher cipher) {
        this.users = users;
        this.brokerConfigs = brokerConfigs;
        this.settings = settings;
        this.configChanges = configChanges;
        this.positions = positions;
        this.clock = clock;
        this.cipher = cipher;
    }

    // ── Broker credentials ───────────────────────────────────────────────────

    /** The real API key and secret. Broker layer only. */
    public record BrokerCredentials(String apiKey, String apiSecret, String sourceIp) {}

    @Transactional(readOnly = true)
    public Optional<BrokerCredentials> brokerCredentials(UserId userId) {
        return brokerConfigs.findByTradingUserId(userId.toString())
                .filter(BrokerConfigEntity::hasCredentials)
                .map(c -> {
                    String key = cipher.decrypt(c.getApiKeyEncrypted());
                    String secret = cipher.decrypt(c.getApiSecretEncrypted());
                    if (key == null || secret == null) {
                        log.error("stored Kite credentials for user={} could not be decrypted — "
                                + "the encryption key has changed and they must be re-entered", userId);
                        return null;
                    }
                    return new BrokerCredentials(key, secret, c.getSourceIp());
                });
    }

    /**
     * Stores a user's Kite application credentials.
     *
     * <p>A blank value leaves the existing one alone rather than clearing it, so a settings form can
     * show "already set" for the secret and submit an empty field without destroying it. Clearing is
     * an explicit act — see {@link #clearBrokerCredentials}.</p>
     */
    @Transactional
    public void saveBrokerCredentials(UserId userId, String apiKey, String apiSecret) {
        if (!cipher.isConfigured()) {
            throw new IllegalStateException(
                    "cannot save broker credentials: EQUITY_SECRET_KEY is not set, and they will "
                    + "not be stored in clear text");
        }
        BrokerConfigEntity config = brokerConfigs.findByTradingUserId(userId.toString())
                .orElseGet(() -> new BrokerConfigEntity(userId.toString()));

        if (apiKey != null && !apiKey.isBlank()) {
            config.setApiKeyEncrypted(cipher.encrypt(apiKey.trim()));
        }
        if (apiSecret != null && !apiSecret.isBlank()) {
            config.setApiSecretEncrypted(cipher.encrypt(apiSecret.trim()));
        }
        brokerConfigs.save(config);
        log.warn("broker credentials updated for user={}", userId);
    }

    @Transactional
    public void clearBrokerCredentials(UserId userId) {
        brokerConfigs.findByTradingUserId(userId.toString()).ifPresent(c -> {
            c.setApiKeyEncrypted(null);
            c.setApiSecretEncrypted(null);
            c.clearSession();
            brokerConfigs.save(c);
            log.warn("broker credentials cleared for user={}", userId);
        });
    }

    /** What a settings page is allowed to know: presence, not content. */
    public record BrokerConfigView(boolean apiKeySet, boolean apiSecretSet, String apiKeyHint,
                                   String brokerClientId, String tokenTradingDate,
                                   boolean encryptionAvailable, String sourceIp) {}

    @Transactional(readOnly = true)
    public BrokerConfigView describeBrokerConfig(UserId userId) {
        return brokerConfigs.findByTradingUserId(userId.toString())
                .map(c -> new BrokerConfigView(
                        c.getApiKeyEncrypted() != null && !c.getApiKeyEncrypted().isBlank(),
                        c.getApiSecretEncrypted() != null && !c.getApiSecretEncrypted().isBlank(),
                        // The last four characters only — enough to answer "is this the key I think
                        // it is" without being enough to use.
                        hint(cipher.decrypt(c.getApiKeyEncrypted())),
                        c.getBrokerClientId(),
                        c.getTokenTradingDate() == null ? null : c.getTokenTradingDate().toString(),
                        cipher.isConfigured(),
                        c.getSourceIp()))
                .orElse(new BrokerConfigView(false, false, null, null, null, cipher.isConfigured(), null));
    }

    /**
     * Pins this user's broker traffic to a local address, or clears the pin.
     *
     * <p>Accepts an IP literal only, never a hostname. A hostname is resolved at bind time, on the
     * broker call path, by whatever the resolver says that second — which is exactly the kind of
     * silent indirection that ends with an order leaving from an address the broker has not
     * registered. The operator has the address in front of them; they can type it.</p>
     *
     * @return the address that was set before, so the caller can drop any client bound to it
     */
    @Transactional
    public String setSourceIp(UserId userId, String sourceIp) {
        String cleaned = sourceIp == null || sourceIp.isBlank() ? null : sourceIp.trim();
        if (cleaned != null && !isIpLiteral(cleaned)) {
            throw new IllegalArgumentException("source IP must be an IPv4 or IPv6 address, not '"
                    + cleaned + "'");
        }
        BrokerConfigEntity config = brokerConfigs.findByTradingUserId(userId.toString())
                .orElseGet(() -> new BrokerConfigEntity(userId.toString()));
        String previous = config.getSourceIp();
        config.setSourceIp(cleaned);
        brokerConfigs.save(config);
        log.warn("source IP for user={} changed {} -> {}", userId,
                previous == null ? "default" : previous, cleaned == null ? "default" : cleaned);
        return previous;
    }

    /** Whether the user currently holds, or is waiting on, a position. Deletion is refused while they do. */
    @Transactional(readOnly = true)
    public boolean hasExposure(UserId userId) {
        return positions.existsByTradingUserIdAndStatusIn(userId.toString(),
                java.util.List.of("PENDING_ENTRY", "OPEN", "EXIT_PENDING"));
    }

    /**
     * Removes a user's account: their login, their settings, their broker configuration.
     *
     * <p>Their trading records are not touched. Positions, the daily ledger and the configuration
     * audit are keyed by the trading id, not by the account row, and they stay exactly where they
     * are — history a regulator can still ask for, now under an id with no login attached. What
     * goes is the ability to sign in and everything that only exists to let them trade.</p>
     *
     * <p>Refused while the user holds a position. The exit machinery would still manage it, but
     * nobody would be able to see it: the dashboard reads positions through the account. Close
     * first, then delete.</p>
     */
    @Transactional
    public String deleteAccount(UserId userId) {
        String id = userId.toString();
        if (hasExposure(userId)) {
            throw new IllegalStateException("this user holds a position; close it before deleting them");
        }
        AppUserEntity account = users.findByTradingUserId(id)
                .orElseThrow(() -> new IllegalStateException("no user " + id));
        brokerConfigs.deleteByTradingUserId(id);
        settings.deleteByTradingUserId(id);
        users.delete(account);
        return account.getEmail();
    }

    /** Whether a string is an address literal — digits and dots, or hex and colons — rather than a name. */
    static boolean isIpLiteral(String value) {
        if (value.indexOf(':') >= 0) {
            return value.matches("[0-9A-Fa-f:.]+");
        }
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3 || !part.chars().allMatch(Character::isDigit)) return false;
            if (Integer.parseInt(part) > 255) return false;
        }
        return true;
    }

    private static String hint(String value) {
        if (value == null || value.length() < 4) return null;
        return "…" + value.substring(value.length() - 4);
    }

    // ── Settings ─────────────────────────────────────────────────────────────

    @Transactional
    public UserSettingsEntity settingsFor(UserId userId) {
        return settings.findByTradingUserId(userId.toString())
                .orElseGet(() -> settings.save(UserSettingsEntity.defaults(userId.toString())));
    }

    @Transactional
    public void saveRiskLimits(UserId userId, RiskLimits limits) {
        UserSettingsEntity e = settingsFor(userId);
        e.apply(limits);
        settings.save(e);
        recordChange(userId, "RISK_LIMITS", limits.toString());
        log.warn("risk limits updated for user={}: {}", userId, limits);
    }

    @Transactional
    public void saveThresholds(UserId userId, StrategyThresholds thresholds) {
        UserSettingsEntity e = settingsFor(userId);
        e.apply(thresholds);
        settings.save(e);
        recordChange(userId, "THRESHOLDS", thresholds.toString());
        log.warn("strategy thresholds updated for user={}", userId);
    }

    @Transactional
    public void saveExitPolicy(UserId userId, ExitPolicy policy) {
        UserSettingsEntity e = settingsFor(userId);
        e.apply(policy);
        settings.save(e);
        recordChange(userId, "EXIT_POLICY", policy.toString());
        log.warn("exit policy updated for user={}: {}", userId, policy);
    }

    /**
     * Writes the configuration into the audit trail.
     *
     * <p>user_settings is overwritten in place, so it can say what the configuration is and never
     * what it was. Without this a month of results is a month of trades taken under unknown and
     * varying rules — and the rules did vary: in three sessions the risk budget tripled, the daily
     * loss limit more than tripled, and breakeven went from off to on.</p>
     *
     * <p>Never allowed to fail a settings save. Losing an audit row costs a later comparison;
     * refusing the change would cost the operator control of a live engine.</p>
     */
    private void recordChange(UserId userId, String kind, String settings) {
        try {
            configChanges.save(new ConfigChangeEntity(userId.toString(), clock.now(),
                    clock.tradingDate(), kind, settings));
        } catch (RuntimeException e) {
            log.warn("could not record the {} change for audit: {}", kind, e.getMessage());
        }
    }

    // ── Users ────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<AppUserEntity> allUsers() {
        return users.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<AppUserEntity> user(UserId userId) {
        return users.findByTradingUserId(userId.toString());
    }
}
