package com.equity.api;

import com.equity.domain.risk.RiskLimits;
import com.equity.domain.user.UserId;
import com.equity.platform.security.CurrentUser;
import com.equity.store.UserProfileService;
import com.equity.strategy.ExitPolicy;
import com.equity.strategy.StrategyThresholds;
import com.equity.user.UserAccount;
import com.equity.user.UserRegistry;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Everything on the settings page, for the signed-in user only.
 *
 * <p>No endpoint here takes a user id. Each acts on whoever is signed in, so there is no request a
 * client can construct that reads or writes somebody else's broker credentials.</p>
 *
 * <p>Writes go to the database <b>and</b> to the live in-memory account, in that order. Persisting
 * without applying would mean settings that take effect on the next restart, which is useless
 * mid-session; applying without persisting is what the engine did before and lost on every restart.</p>
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);

    private final CurrentUser currentUser;
    private final UserProfileService profiles;
    private final UserRegistry users;

    public SettingsController(CurrentUser currentUser, UserProfileService profiles,
                              UserRegistry users) {
        this.currentUser = currentUser;
        this.profiles = profiles;
        this.users = users;
    }

    // ── Broker ───────────────────────────────────────────────────────────────

    /** Presence and a four-character hint. Never the credentials themselves — spec section 37. */
    @GetMapping("/broker")
    public Map<String, Object> broker() {
        UserId id = currentUser.require();
        UserProfileService.BrokerConfigView view = profiles.describeBrokerConfig(id);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("apiKeySet", view.apiKeySet());
        m.put("apiSecretSet", view.apiSecretSet());
        m.put("apiKeyHint", view.apiKeyHint());
        m.put("brokerClientId", view.brokerClientId());
        m.put("tokenTradingDate", view.tokenTradingDate());
        m.put("encryptionAvailable", view.encryptionAvailable());
        return m;
    }

    public record BrokerCredentialsRequest(String apiKey, String apiSecret) {}

    /**
     * Saves this user's Kite application credentials.
     *
     * <p>A blank field leaves the stored value alone, so the form can show "already set" for the
     * secret and be submitted without destroying it. Clearing is {@link #clearBroker()}.</p>
     */
    @PostMapping("/broker")
    public Map<String, Object> saveBroker(@RequestBody BrokerCredentialsRequest request) {
        UserId id = currentUser.require();
        profiles.saveBrokerCredentials(id, request.apiKey(), request.apiSecret());
        return broker();
    }

    @DeleteMapping("/broker")
    public Map<String, Object> clearBroker() {
        UserId id = currentUser.require();
        profiles.clearBrokerCredentials(id);
        return broker();
    }

    // ── Risk ─────────────────────────────────────────────────────────────────

    @GetMapping("/risk")
    public RiskLimits risk() {
        return account().limits();
    }

    @PostMapping("/risk")
    public RiskLimits saveRisk(@RequestBody RiskLimits limits) {
        UserAccount account = account();
        validate(limits);
        profiles.saveRiskLimits(account.userId(), limits);
        account.setLimits(limits);
        log.warn("risk limits changed for user={}: risk/trade {} daily loss {} max positions {}",
                account.userId(), limits.riskPerTradeRupees(), limits.maxDailyLossRupees(),
                limits.maxOpenPositions());
        return limits;
    }

    /**
     * Refuses limits that would disable the protection they exist to provide.
     *
     * <p>Not a style check. A zero or negative daily-loss limit reads as "no limit" to the latch, and
     * a stop range of zero width rejects every trade — both are configurations that look like numbers
     * and behave like switches.</p>
     */
    private static void validate(RiskLimits l) {
        if (!(l.riskPerTradeRupees() > 0)) {
            throw new IllegalArgumentException("risk per trade must be positive");
        }
        if (!(l.maxDailyLossRupees() > 0)) {
            throw new IllegalArgumentException(
                    "daily loss limit must be positive — zero would mean no limit at all");
        }
        if (l.maxOpenPositions() < 1) {
            throw new IllegalArgumentException("max open positions must be at least 1");
        }
        if (!(l.minStopPercent() < l.maxStopPercent())) {
            throw new IllegalArgumentException(
                    "min stop must be below max stop, or every trade is rejected");
        }
        if (!(l.maxPositionValue() > 0)) {
            throw new IllegalArgumentException("position value cap must be positive");
        }
    }

    // ── Strategy thresholds ──────────────────────────────────────────────────

    @GetMapping("/thresholds")
    public StrategyThresholds thresholds() {
        return account().thresholds();
    }

    @PostMapping("/thresholds")
    public StrategyThresholds saveThresholds(@RequestBody StrategyThresholds thresholds) {
        UserAccount account = account();
        if (!thresholds.entryWindowStart().isBefore(thresholds.entryWindowEnd())) {
            throw new IllegalArgumentException("the entry window must start before it ends");
        }
        if (!thresholds.entryWindowEnd().isBefore(thresholds.squareOffTime())) {
            throw new IllegalArgumentException(
                    "square-off must be after the entry window closes, or a position can be opened "
                    + "after the time it would be closed");
        }
        profiles.saveThresholds(account.userId(), thresholds);
        account.setThresholds(thresholds);
        return thresholds;
    }

    // ── Exit policy ──────────────────────────────────────────────────────────

    @GetMapping("/exit-policy")
    public ExitPolicy exitPolicy() {
        return account().exitPolicy();
    }

    @PostMapping("/exit-policy")
    public ExitPolicy saveExitPolicy(@RequestBody ExitPolicy policy) {
        UserAccount account = account();
        profiles.saveExitPolicy(account.userId(), policy);
        account.setExitPolicy(policy);
        log.warn("exit policy changed for user={}: {}", account.userId(), policy);
        return policy;
    }

    /** Market hours, so the UI does not hard-code them separately from the engine. */
    @GetMapping("/session-times")
    public Map<String, String> sessionTimes() {
        StrategyThresholds t = account().thresholds();
        Map<String, String> m = new LinkedHashMap<>();
        m.put("marketOpen", LocalTime.of(9, 15).toString());
        m.put("marketClose", LocalTime.of(15, 30).toString());
        m.put("entryWindowStart", t.entryWindowStart().toString());
        m.put("entryWindowEnd", t.entryWindowEnd().toString());
        m.put("squareOff", t.squareOffTime().toString());
        return m;
    }

    private UserAccount account() {
        return users.ensure(currentUser.require());
    }
}
