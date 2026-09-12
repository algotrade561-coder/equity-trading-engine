package com.equity.user;

import com.equity.domain.risk.RiskLimits;
import com.equity.domain.user.Role;
import com.equity.domain.user.TradingUser;
import com.equity.domain.user.UserId;
import com.equity.domain.user.UserStatus;
import com.equity.strategy.StrategyThresholds;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The users the engine trades for.
 *
 * <p>In memory. Postgres-backed tenancy is a later phase, and holding it here first keeps the
 * seam visible: everything downstream already takes a {@link UserId} and reads its configuration
 * through this registry, so the storage swap does not reach into the trading code.</p>
 *
 * <p>A user is created disarmed — {@code entriesEnabled} false — so registering somebody is never
 * the same act as letting them trade.</p>
 */
@Component
public class UserRegistry {

    /**
     * Supplies a user's stored configuration. Returns empty when nothing is persisted, in which case
     * the shipped defaults are used.
     */
    public interface SettingsSource {
        java.util.Optional<Loaded> load(UserId userId);

        record Loaded(RiskLimits limits, StrategyThresholds thresholds,
                      com.equity.strategy.ExitPolicy exitPolicy) {}
    }

    private final SettingsSource settingsSource;
    private final Map<UserId, UserAccount> accounts = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public UserRegistry(SettingsSource settingsSource) {
        this.settingsSource = settingsSource;
    }

    /** Test constructor: everyone gets the shipped defaults. */
    public UserRegistry() {
        this(userId -> java.util.Optional.empty());
    }

    /**
     * Builds an account, preferring stored settings over defaults.
     *
     * <p>The entry permission is <b>not</b> restored. Arming is a decision about right now, and a
     * process that came back armed because it was armed yesterday is precisely the surprise nobody
     * wants after a restart.</p>
     */
    private UserAccount build(UserId userId, TradingUser user) {
        var stored = settingsSource.load(userId);
        UserAccount account = new UserAccount(user,
                stored.map(SettingsSource.Loaded::limits).orElseGet(RiskLimits::house),
                stored.map(SettingsSource.Loaded::thresholds).orElseGet(StrategyThresholds::house));
        stored.map(SettingsSource.Loaded::exitPolicy).ifPresent(account::setExitPolicy);
        return account;
    }

    public UserAccount register(UserId userId, String displayName, String email) {
        UserAccount account = build(userId, new TradingUser(userId, displayName, email,
                UserStatus.ACTIVE, Set.of(Role.TRADER)));
        accounts.put(userId, account);
        return account;
    }

    /**
     * Returns the account, creating a disarmed one if this user is new.
     *
     * <p>Auto-creation exists because the console identifies a user by a generated id and there is
     * no sign-up flow yet. It is safe precisely because a new account cannot trade: the worst case
     * is an idle row, not an unauthorised order.</p>
     */
    public UserAccount ensure(UserId userId) {
        return accounts.computeIfAbsent(userId,
                id -> build(id, new TradingUser(id, id.toString().substring(0, 8), "",
                        UserStatus.ACTIVE, Set.of(Role.TRADER))));
    }

    /**
     * Drops a user from the live registry. Only ever called after the store has refused to find any
     * trading history for them — a user with positions is disabled, not forgotten.
     */
    public void forget(UserId userId) {
        accounts.remove(userId);
    }

    public Optional<UserAccount> find(UserId userId) {
        return Optional.ofNullable(accounts.get(userId));
    }

    public Collection<UserAccount> all() { return accounts.values(); }

    /** Users who may currently open positions. Only these can produce an order. */
    public Collection<UserAccount> armed() {
        return accounts.values().stream().filter(UserAccount::mayOpen).toList();
    }

    /**
     * Users whose candidates are worth evaluating, armed or not.
     *
     * <p>Wider than {@link #armed()} on purpose. Evaluating only armed users means a session with
     * nobody armed records nothing at all — no setups, no rejections, no counts — and the operator
     * cannot tell the difference between "the strategy found nothing" and "the strategy never ran".
     * Evaluating disarmed users costs a little CPU and buys a shadow record of what the engine would
     * have done, which is the whole point of running it disarmed.</p>
     *
     * <p>A halted or disabled user is excluded: they are stopped, not merely unarmed.</p>
     */
    public Collection<UserAccount> evaluable() {
        return accounts.values().stream()
                .filter(a -> a.status() == com.equity.domain.user.UserStatus.ACTIVE)
                .toList();
    }

    public int size() { return accounts.size(); }
}
