package com.equity.trading;

import com.equity.domain.order.TradeIntent;
import com.equity.domain.risk.RiskDecision;
import com.equity.domain.user.UserId;
import com.equity.user.UserAccount;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Sends each user's orders on that user's own thread, and never on the thread that decided them.
 *
 * <h2>What was wrong before</h2>
 * <p>An entry was placed synchronously on the market-data thread: the tick that triggered it did not
 * finish being processed until the broker had answered, about 170 ms from a home line. With one user
 * that cost one tick's latency. With N users evaluated in a loop it cost the last user N round trips
 * and stalled the feed for all of them — and Kite drops a client that falls behind. Exits were
 * queued and sent by a one-second timer, so a stop left on average half a second after the price
 * crossed it, single user or not. Both are fixed here.</p>
 *
 * <h2>The shape</h2>
 * <p>One single-threaded executor per user. Within a user, orders are sequential and in the order
 * they were decided — the same guarantee the old lock gave, and the one that keeps "an exit for a
 * position already exiting is skipped" true. Across users, fully parallel: user B's entry does not
 * wait for user A's broker, and neither waits for the feed. An exit is sent the moment it is
 * queued; the one-second sweep remains only as a safety net for a signal that was somehow missed.</p>
 *
 * <p>Per user rather than a shared pool on purpose. A shared pool lets one user's slow broker call
 * occupy a thread another user needed; a thread per user makes the isolation structural. The cost
 * is one mostly-idle thread per user, which is nothing.</p>
 *
 * <h2>What bounds a hung call</h2>
 * <p>The HTTP client's own timeouts — five seconds to connect, ten to read. A call that hangs holds
 * only its own user's thread, for at most that long, and comes back as a broker exception the
 * lifecycle already knows how to treat as "may have landed". Nothing here retries; retrying an order
 * is how two get sent.</p>
 */
@Component
public class OrderDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OrderDispatcher.class);

    private final PositionLifecycle lifecycle;
    private final Map<UserId, ExecutorService> executors = new ConcurrentHashMap<>();
    /** Whether a drain is already queued for the user, so a burst of signals costs one drain. */
    private final Map<UserId, AtomicBoolean> drainQueued = new ConcurrentHashMap<>();
    private final AtomicLong entriesDispatched = new AtomicLong();
    private final AtomicLong drainsDispatched = new AtomicLong();
    private final AtomicLong rejectedAfterShutdown = new AtomicLong();

    public OrderDispatcher(PositionLifecycle lifecycle) {
        this.lifecycle = lifecycle;
        lifecycle.onExitQueued(this::exitsPending);
    }

    // ── Entries ──────────────────────────────────────────────────────────────

    /**
     * Places an authorised entry on the user's thread and hands the outcome back when it is known.
     *
     * <p>Returns at once. The caller — the feed thread — has already done everything that needs the
     * tick: evaluated, authorised, recorded the attempt. The broker round trip is the only part that
     * remains and the only part that must not happen here.</p>
     */
    public void submitEntry(UserAccount account, TradeIntent intent, RiskDecision decision,
                            long epochAtDecision, Consumer<PositionLifecycle.EntryOutcome> onOutcome) {
        UserId userId = account.userId();
        Runnable task = () -> {
            PositionLifecycle.EntryOutcome outcome;
            try {
                outcome = lifecycle.open(account, intent, decision, epochAtDecision);
            } catch (RuntimeException e) {
                // open() reports broker refusals as outcomes; anything reaching here is a defect in
                // the path itself, and it must not kill the user's thread.
                log.error("entry for {} {} threw unexpectedly: {}", userId, intent.symbol(), e.toString(), e);
                outcome = PositionLifecycle.EntryOutcome.dropped("internal error: " + e.getMessage());
            }
            try {
                onOutcome.accept(outcome);
            } catch (RuntimeException e) {
                log.warn("entry outcome handler for {} {} threw: {}", userId, intent.symbol(), e.toString());
            }
        };
        if (dispatch(userId, task)) entriesDispatched.incrementAndGet();
    }

    // ── Exits ────────────────────────────────────────────────────────────────

    /**
     * An exit has been queued for this user; send it now.
     *
     * <p>Coalesced: a stop and a target raised on the same tick, or three positions stopping on one
     * bar, produce one drain that takes everything pending. The flag is cleared as the drain starts,
     * so a request arriving during the drain queues the next one rather than being missed.</p>
     */
    public void exitsPending(UserId userId) {
        AtomicBoolean queued = drainQueued.computeIfAbsent(userId, id -> new AtomicBoolean());
        if (!queued.compareAndSet(false, true)) return;
        boolean accepted = dispatch(userId, () -> {
            queued.set(false);
            try {
                lifecycle.drainExits(userId);
            } catch (RuntimeException e) {
                log.error("exit drain for {} threw unexpectedly: {} — the sweep will retry", userId, e.toString(), e);
            }
        });
        if (accepted) drainsDispatched.incrementAndGet(); else queued.set(false);
    }

    /**
     * The safety net. Called by the periodic loop: anything still queued is signalled again.
     *
     * <p>In normal operation this finds nothing — every offer already signalled. It exists for the
     * failure that cannot be reasoned away: a signal lost to an exception between the offer and the
     * dispatch. A stop that waits one extra second is a cost; a stop that is never sent is not.</p>
     */
    public int sweep() {
        int signalled = 0;
        for (UserId userId : lifecycle.usersWithQueuedExits()) {
            exitsPending(userId);
            signalled++;
        }
        return signalled;
    }

    // ── Plumbing ─────────────────────────────────────────────────────────────

    private boolean dispatch(UserId userId, Runnable task) {
        try {
            executors.computeIfAbsent(userId, this::newExecutor).execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            rejectedAfterShutdown.incrementAndGet();
            log.error("order dispatcher for {} is shut down; task dropped", userId);
            return false;
        }
    }

    private ExecutorService newExecutor(UserId userId) {
        String name = "orders-" + userId.toString().substring(0, 8);
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            t.setUncaughtExceptionHandler((thread, e) ->
                    log.error("uncaught on {}: {}", thread.getName(), e.toString(), e));
            return t;
        };
        log.info("order dispatcher thread {} started", name);
        return Executors.newSingleThreadExecutor(factory);
    }

    /** Stops accepting work and lets in-flight orders finish. */
    @PreDestroy
    public void shutdown() {
        executors.values().forEach(ExecutorService::shutdown);
        executors.values().forEach(ex -> {
            try {
                if (!ex.awaitTermination(15, TimeUnit.SECONDS)) ex.shutdownNow();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ex.shutdownNow();
            }
        });
    }

    public int activeUsers()              { return executors.size(); }
    public long entriesDispatched()       { return entriesDispatched.get(); }
    public long drainsDispatched()        { return drainsDispatched.get(); }
    public long rejectedAfterShutdown()   { return rejectedAfterShutdown.get(); }
}
