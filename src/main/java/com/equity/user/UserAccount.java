package com.equity.user;

import com.equity.domain.risk.RiskLimits;
import com.equity.domain.user.TradingUser;
import com.equity.domain.user.UserId;
import com.equity.domain.user.UserStatus;
import com.equity.strategy.ExitPolicy;
import com.equity.strategy.StrategyThresholds;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Everything the engine holds for one user: who they are, what they are allowed to risk, what
 * thresholds they trade, and whether they are currently permitted to open anything.
 *
 * <p>Two switches, not one, and they are not interchangeable:</p>
 * <ul>
 *   <li>{@code entriesEnabled} — this user may open positions. Turning it off is routine.</li>
 *   <li>{@link UserStatus#HALTED} — emergency stop, which also bumps the epoch.</li>
 * </ul>
 *
 * <p><b>Neither switch touches exits.</b> Design note 0.1. A user who is halted still has their
 * positions managed and closed; the alternative leaves live exposure with nothing watching it,
 * which is strictly worse than the situation the halt was called for.</p>
 *
 * <h2>The epoch</h2>
 * <p>Design note 0.3. Every order submission captures the epoch it was authorised under, and a halt
 * bumps it. A fill that arrives for a stale epoch is one the operator has already decided against —
 * it is closed immediately rather than adopted. This closes the window between "operator pressed
 * halt" and "the entry order we sent 200ms ago comes back filled", which a permission checklist
 * evaluated before submission cannot close on its own.</p>
 */
public class UserAccount {

    private final TradingUser user;
    private final AtomicLong epoch = new AtomicLong(1);

    private volatile RiskLimits limits;
    private volatile StrategyThresholds thresholds;
    private volatile ExitPolicy exitPolicy = ExitPolicy.fixed();
    private volatile boolean entriesEnabled;
    private volatile UserStatus status;

    public UserAccount(TradingUser user, RiskLimits limits, StrategyThresholds thresholds) {
        this.user = user;
        this.limits = limits;
        this.thresholds = thresholds;
        this.status = user.status();
        // Ships off. A user who has never been switched on should not start trading because the
        // process restarted.
        this.entriesEnabled = false;
    }

    public UserId userId()                  { return user.userId(); }
    public TradingUser user()               { return user; }
    public RiskLimits limits()              { return limits; }
    public StrategyThresholds thresholds()  { return thresholds; }

    /**
     * How this user's open positions are managed. Per user so two users can run the same entries
     * with different exits and the difference is attributable to the exit alone.
     */
    public ExitPolicy exitPolicy()          { return exitPolicy; }
    public void setExitPolicy(ExitPolicy p) { this.exitPolicy = p; }
    public UserStatus status()              { return status; }
    public long epoch()                     { return epoch.get(); }

    public void setLimits(RiskLimits limits)             { this.limits = limits; }
    public void setThresholds(StrategyThresholds t)      { this.thresholds = t; }

    public boolean entriesEnabled() { return entriesEnabled; }

    public void setEntriesEnabled(boolean enabled) {
        this.entriesEnabled = enabled;
    }

    /** May this user open a new position? Says nothing about closing one. */
    public boolean mayOpen() {
        return entriesEnabled && status == UserStatus.ACTIVE;
    }

    /**
     * Emergency stop. Bumps the epoch so anything already in flight is disowned on arrival.
     *
     * @return the new epoch
     */
    public long halt() {
        status = UserStatus.HALTED;
        entriesEnabled = false;
        return epoch.incrementAndGet();
    }

    /** Clears a halt. Entries stay off — resuming them is a separate, deliberate act. */
    public void resume() {
        if (status == UserStatus.HALTED) status = UserStatus.ACTIVE;
    }

    public void setStatus(UserStatus status) {
        this.status = status;
        if (status != UserStatus.ACTIVE) entriesEnabled = false;
    }

    /** True if the given epoch is the one currently in force. */
    public boolean isCurrentEpoch(long candidate) {
        return candidate == epoch.get();
    }
}
