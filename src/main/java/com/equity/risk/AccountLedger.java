package com.equity.risk;

import com.equity.domain.position.Position;
import com.equity.domain.user.UserId;
import com.equity.platform.time.TradingClock;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Each user's running day: realised profit, entry attempts, and whether the loss limit has latched.
 *
 * <h2>Why the limit latches</h2>
 * <p>Design note 0.7. Daily loss is realised <b>plus unrealised</b> — a limit that counted only
 * closed trades would let an account sit far beyond it as long as nothing was booked. And once
 * breached it stays breached for the session, even if the market comes back and the number
 * improves. A limit that un-breaches is not a limit: it hands the account back to the same
 * conditions that just took it through the line, at the worst possible moment to be re-entering.</p>
 *
 * <p>Everything resets on a trading-date change, read from the injected clock rather than the wall
 * clock, so a replay of yesterday behaves exactly as the live session did.</p>
 */
@Component
public class AccountLedger {

    private static final Logger log = LoggerFactory.getLogger(AccountLedger.class);

    private final TradingClock clock;
    private final LedgerStore store;
    private final Map<UserId, DayState> days = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public AccountLedger(TradingClock clock, LedgerStore store) {
        this.clock = clock;
        this.store = store;
    }

    /** Test constructor: keeps the day in memory only. */
    public AccountLedger(TradingClock clock) {
        this(clock, LedgerStore.inMemory());
    }

    private static final class DayState {
        LocalDate date;
        double realised;
        int attempts;
        int fills;
        boolean latched;
        String latchReason;
    }

    /**
     * The day's state, reloaded from storage on a date change or a cold start.
     *
     * <p>The reload is the point. A latch held only in memory is escaped by restarting the process —
     * which is exactly what somebody would do while trying to fix whatever was going wrong.</p>
     */
    private DayState today(UserId userId) {
        DayState state = days.computeIfAbsent(userId, id -> new DayState());
        LocalDate date = clock.tradingDate();
        synchronized (state) {
            if (!date.equals(state.date)) {
                state.date = date;
                LedgerStore.Snapshot stored = store.load(userId, date)
                        .orElseGet(LedgerStore.Snapshot::empty);
                state.realised = stored.realised();
                state.attempts = stored.attempts();
                state.fills = stored.fills();
                state.latched = stored.latched();
                state.latchReason = stored.latchReason();
                if (state.latched) {
                    log.warn("user={} resumed with the daily loss latch already set: {}",
                            userId, state.latchReason);
                }
            }
        }
        return state;
    }

    /** Called inside the caller's synchronized block, so the snapshot is consistent. */
    private void persist(UserId userId, DayState state) {
        store.save(userId, state.date, new LedgerStore.Snapshot(
                state.realised, state.attempts, state.fills, state.latched, state.latchReason));
    }

    /** Counted at submission, not at fill: an attempt consumes capacity whether or not it works. */
    public void recordAttempt(UserId userId) {
        DayState state = today(userId);
        synchronized (state) { state.attempts++; persist(userId, state); }
    }

    public void recordFill(UserId userId) {
        DayState state = today(userId);
        synchronized (state) { state.fills++; persist(userId, state); }
    }

    public void recordClosed(Position position) {
        DayState state = today(position.userId());
        synchronized (state) {
            state.realised += position.realisedPnl();
            persist(position.userId(), state);
        }
    }

    public double realised(UserId userId) {
        DayState state = today(userId);
        synchronized (state) { return state.realised; }
    }

    public int attempts(UserId userId) {
        DayState state = today(userId);
        synchronized (state) { return state.attempts; }
    }

    public int fills(UserId userId) {
        DayState state = today(userId);
        synchronized (state) { return state.fills; }
    }

    /**
     * Evaluates the daily loss against the limit and latches if it has been breached.
     *
     * @param unrealised mark-to-market on everything currently open
     * @return true if the user is latched — either now or from earlier in the session
     */
    public boolean checkAndLatch(UserId userId, double unrealised, double maxDailyLossRupees) {
        DayState state = today(userId);
        synchronized (state) {
            if (state.latched) return true;
            double total = state.realised + unrealised;
            if (total <= -Math.abs(maxDailyLossRupees)) {
                state.latched = true;
                state.latchReason = String.format(
                        "daily loss %.0f (realised %.0f + open %.0f) breached limit %.0f",
                        total, state.realised, unrealised, maxDailyLossRupees);
                log.error("DAILY LOSS LATCHED for user={}: {}", userId, state.latchReason);
                persist(userId, state);
                return true;
            }
            return false;
        }
    }

    public boolean isLatched(UserId userId) {
        DayState state = today(userId);
        synchronized (state) { return state.latched; }
    }

    public String latchReason(UserId userId) {
        DayState state = today(userId);
        synchronized (state) { return state.latchReason; }
    }

    /**
     * Clears a latch. Manual, and logged loudly — an operator un-latching an account mid-session is
     * overriding the one control that stopped a losing day, and that should be visible afterwards.
     */
    public void clearLatch(UserId userId) {
        DayState state = today(userId);
        synchronized (state) {
            if (!state.latched) return;
            log.warn("daily loss latch MANUALLY CLEARED for user={} (was: {})", userId, state.latchReason);
            state.latched = false;
            state.latchReason = null;
            persist(userId, state);
        }
    }
}
