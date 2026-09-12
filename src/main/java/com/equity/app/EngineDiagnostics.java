package com.equity.app;

import com.equity.broker.kite.KiteCredentialsProvider;
import com.equity.broker.kite.KiteProperties;
import com.equity.broker.kite.KiteTickerManager;
import com.equity.market.InstrumentFreshness;
import com.equity.market.MarketDataRouter;
import com.equity.market.universe.UniverseProperties;
import com.equity.market.universe.UniverseService;
import com.equity.platform.time.TradingClock;
import com.equity.risk.AccountLedger;
import com.equity.session.SessionOrchestrator;
import com.equity.strategy.RejectionLog;
import com.equity.trading.PositionBook;
import com.equity.trading.PositionLifecycle;
import com.equity.trading.Reconciler;
import com.equity.user.UserAccount;
import com.equity.user.UserRegistry;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The two logs an operator actually reads: what this process was configured to do, and whether it is
 * doing it.
 *
 * <h2>Why this exists separately</h2>
 * <p>Every component here already logs its own events, and that is the problem: on a quiet morning
 * the interesting fact is the <em>absence</em> of events, and absence cannot be grepped. "No entry
 * has been taken" has perhaps a dozen distinct causes — the wrong mode, an unarmed user, a feed that
 * never connected, a universe that resolved to nothing, a latched loss, or a strategy condition that
 * is simply never met — and reading that off a scrolling log means knowing all dozen in advance.</p>
 *
 * <p>So this reports positively, on a timer, and when nothing is being traded it says which check is
 * stopping it. It reads state and writes log lines; it changes nothing and is safe to remove.</p>
 */
@Component
public class EngineDiagnostics {

    private static final Logger log = LoggerFactory.getLogger(EngineDiagnostics.class);

    private static final LocalTime OPEN = LocalTime.of(9, 15);
    private static final LocalTime CLOSE = LocalTime.of(15, 30);

    private final EngineProperties engine;
    private final UniverseProperties universeProps;
    private final UniverseService universe;
    private final KiteProperties kite;
    private final KiteCredentialsProvider credentials;
    private final KiteTickerManager tickers;
    private final MarketDataRouter router;
    private final InstrumentFreshness freshness;
    private final UserRegistry users;
    private final PositionBook positions;
    private final PositionLifecycle lifecycle;
    private final RejectionLog rejections;
    private final AccountLedger ledger;
    private final Reconciler reconciler;
    private final SessionOrchestrator session;
    private final TradingClock clock;
    private final Environment environment;

    /** Previous tick count, so the heartbeat can report a rate rather than a total nobody reads. */
    private final AtomicLong lastTickCount = new AtomicLong();
    private volatile String lastVerdict = "";

    public EngineDiagnostics(EngineProperties engine, UniverseProperties universeProps,
                             UniverseService universe, KiteProperties kite,
                             KiteCredentialsProvider credentials,
                             KiteTickerManager tickers, MarketDataRouter router,
                             InstrumentFreshness freshness,
                             UserRegistry users, PositionBook positions,
                             PositionLifecycle lifecycle, RejectionLog rejections,
                             AccountLedger ledger, Reconciler reconciler,
                             SessionOrchestrator session, TradingClock clock,
                             Environment environment) {
        this.engine = engine;
        this.universeProps = universeProps;
        this.universe = universe;
        this.kite = kite;
        this.credentials = credentials;
        this.tickers = tickers;
        this.router = router;
        this.freshness = freshness;
        this.users = users;
        this.positions = positions;
        this.lifecycle = lifecycle;
        this.rejections = rejections;
        this.ledger = ledger;
        this.reconciler = reconciler;
        this.session = session;
        this.clock = clock;
        this.environment = environment;
    }

    // ── What this process was configured to do ───────────────────────────────

    /**
     * The configuration actually in effect, printed once.
     *
     * <p>Settings arrive from a tracked file, a local override and the environment, and the question
     * that matters at 09:00 is not what any one of them says but what won. Printing the resolved
     * values removes a whole category of morning confusion — the override that was edited but never
     * reloaded, the environment variable that was never exported.</p>
     *
     * <p>Secrets are reported as present or absent and never printed, per the credential rule.</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void reportConfiguration() {
        boolean canTrade = engine.getMode() == ExecutionMode.LIVE && engine.isTradingEnabled();

        List<String> lines = new ArrayList<>();
        lines.add("");
        lines.add("═══════════════════════════════════════════════════════════════════════");
        lines.add("  EquityEngine — NSE Intraday Momentum — configuration in effect");
        lines.add("═══════════════════════════════════════════════════════════════════════");
        lines.add(String.format("  mode                  %s", engine.getMode()));
        lines.add(String.format("  trading-enabled       %s", engine.isTradingEnabled()));
        lines.add(String.format("  → orders can be sent  %s", canTrade
                ? "YES — both switches are on; a user still has to be armed"
                : "NO — this process cannot send an order in this configuration"));
        lines.add("");
        lines.add(String.format("  google sign-in        %s",
                property("equity.auth.google.enabled", "false")));
        lines.add(String.format("  seed user             %s",
                describe(property("equity.auth.seed-email", ""))));
        lines.add(String.format("  encryption key        %s",
                present(property("equity.security.secret-key", ""))
                        ? "set — credentials will be stored encrypted"
                        : "ABSENT — the engine will refuse to store broker credentials"));
        lines.add("");
        // Not kite.isConfigured(): that reads the application-wide key, which is empty by design
        // now that credentials are stored per user and encrypted. Report what is actually resolvable.
        long withCredentials = users.all().stream()
                .filter(a -> credentials.find(a.userId()).isPresent()).count();
        lines.add(String.format("  broker                %s", kite.isEnabled()
                ? (withCredentials > 0
                        ? "kite, credentials stored for " + withCredentials + " user(s)"
                        : "kite, no stored credentials yet — enter them on the Settings page")
                : "DISABLED"));
        lines.add(String.format("  kite redirect         %s", kite.getRedirectUrl()));
        lines.add("");
        lines.add(String.format("  universe              %s", universeProps.getSymbols().isEmpty()
                ? universeProps.getIndexName() + ", fetched from the exchange"
                : universeProps.getSymbols().size() + " symbol(s) pinned by configuration"));
        if (universeProps.getSymbols().isEmpty()) {
            lines.add(String.format("    source              %s", universeProps.getIndexUrl()));
            lines.add(String.format("    fallback cache      %s", universeProps.getCacheFile()));
        }
        lines.add(String.format("    depth strategy      %s", universeProps.isEvaluateAll()
                ? "every symbol in full depth"
                : "quotes for all, full depth for ranks 1-" + universeProps.getEnterRank()
                  + " (demoted past " + universeProps.getExitRank() + ")"));
        lines.add("");
        lines.add(String.format("  database              %s",
                property("spring.datasource.url", "?")));
        lines.add(String.format("  h2 console            %s",
                property("spring.h2.console.enabled", "false")));
        lines.add(String.format("  log file              %s",
                property("logging.file.name", "console only")));
        lines.add("");
        lines.add("  To trace one stock end to end for a session, restart with:");
        lines.add("    --logging.level.com.equity.strategy=DEBUG");
        lines.add("  For every broker request and response:");
        lines.add("    --logging.level.com.equity.broker.kite=DEBUG");
        lines.add("═══════════════════════════════════════════════════════════════════════");

        log.info(String.join(System.lineSeparator(), lines));
    }

    // ── Whether it is doing it ───────────────────────────────────────────────

    /**
     * One line a minute saying what the engine is doing, and why it is not trading if it is not.
     *
     * <p>A minute is chosen so that a whole session fits in a readable scroll — roughly 375 lines —
     * while still being frequent enough that "when did the feed drop" has a minute-level answer.</p>
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 20_000)
    public void heartbeat() {
        long ticks = router.ticksSeen();
        long perMinute = ticks - lastTickCount.getAndSet(ticks);
        LocalTime now = clock.timeOfDay();
        java.time.DayOfWeek day = clock.tradingDate().getDayOfWeek();
        boolean weekend = day == java.time.DayOfWeek.SATURDAY || day == java.time.DayOfWeek.SUNDAY;
        // Exchange holidays are not known here, so a holiday still reads as market hours; a weekend
        // at least does not — a Saturday labelled MARKET-HOURS with zero ticks looks like an outage.
        boolean marketHours = !weekend && !now.isBefore(OPEN) && !now.isAfter(CLOSE);

        long armed = users.armed().size();
        long open = positions.all().stream().filter(p -> p.hasExposure()).count();
        long pending = positions.all().stream()
                .filter(p -> p.status() == com.equity.domain.position.PositionStatus.PENDING_ENTRY)
                .count();

        int subscribed = universe.subscribedSymbols().size();
        int ticking = freshness.trackedSymbols();

        log.info("HEARTBEAT {} {} | feed {} | broker {} | {} subscribed, {} ticking, {} in depth | "
                        + "ticks {} (+{}/min, {} dropped) | users {}/{} armed | "
                        + "positions {} open, {} pending | entries {} exits {} | "
                        + "intents {} shadow {} rejections {}",
                now.withNano(0), marketHours ? "MARKET-HOURS" : weekend ? "weekend" : "outside-hours",
                session.isFeedUp() ? "UP" : "DOWN",
                tickers.isConnected() ? "CONNECTED" : "no session",
                subscribed, ticking, universe.fullModeCount(),
                ticks, perMinute, router.ticksDropped(),
                armed, users.size(),
                open, pending,
                lifecycle.entriesSubmitted(), lifecycle.exitsSubmitted(),
                rejections.intentCount(), rejections.shadowIntentCount(),
                totalRejections());

        // A total tick count hides a partly-dead feed entirely: 499 subscribed and 3 ticking looks
        // healthy by every other measure, and the 496 silent ones simply never produce a signal.
        if (marketHours && subscribed > 0 && ticking < subscribed * 0.5) {
            log.warn("  only {} of {} subscribed symbol(s) have ever ticked. The rest are silent — "
                    + "check the unresolved-symbol list on /api/universe.", ticking, subscribed);
        }

        reportAnythingUnaccountedFor();

        // Only worth explaining during the hours something is supposed to happen; before the open
        // "nothing has traded" is the correct state and saying so every minute is noise.
        if (marketHours && lifecycle.entriesSubmitted() == 0) {
            explainWhyNothingHasTraded();
        }
    }

    /** Money the engine cannot account for. Silence here is the normal and desirable state. */
    private void reportAnythingUnaccountedFor() {
        int unconfirmed = lifecycle.unconfirmedEntries();
        if (unconfirmed > 0) {
            log.error("  {} entry order(s) sent whose outcome is unknown. Reconciliation is looking "
                    + "for them; if one filled it will be adopted with its original stop.", unconfirmed);
        }
        for (UserAccount account : users.all()) {
            reconciler.lastReport(account.userId()).ifPresent(report -> {
                if (!report.orphans().isEmpty()) {
                    log.error("  ORPHANS for {}: {} — the engine did not open these and will NOT "
                                    + "square them off. Close them in the broker terminal.",
                            account.userId(), report.orphans());
                }
                if (report.failed() != null) {
                    log.warn("  reconciliation for {} could not run: {} — open positions shown are "
                            + "what the engine believes, not what the broker confirms",
                            account.userId(), report.failed());
                }
            });
        }
    }

    /**
     * Names the first check that is stopping an entry, in the order the engine applies them.
     *
     * <p>Ordered deliberately from "this process cannot trade at all" outwards to "the market is not
     * offering the setup". Reporting the first failing gate rather than all of them is the point: the
     * later ones are not reached, so listing them invites fixing something that was never the
     * problem.</p>
     */
    private void explainWhyNothingHasTraded() {
        String verdict = diagnose();
        // Repeating an unchanged diagnosis every minute buries the moment it changes.
        if (verdict.equals(lastVerdict)) return;
        lastVerdict = verdict;
        log.warn("  no entry has been taken — {}", verdict);

        Map<String, Long> byCondition = rejections.countsByCondition();
        if (!byCondition.isEmpty()) {
            String top = byCondition.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                    .limit(5)
                    .map(e -> e.getKey() + " " + e.getValue())
                    .reduce((a, b) -> a + ", " + b).orElse("");
            log.warn("  most common refusals so far: {}", top);
        }
    }

    private String diagnose() {
        if (engine.getMode() != ExecutionMode.LIVE) {
            return "mode is " + engine.getMode() + ", so no order can be sent. Set engine.mode=LIVE.";
        }
        if (!engine.isTradingEnabled()) {
            return "the master switch engine.trading-enabled is false.";
        }
        if (!kite.isEnabled()) {
            return "the broker is disabled (equity.broker.kite.enabled=false).";
        }
        if (!tickers.isConnected()) {
            return "there is no broker session — nobody has completed the Kite login today. "
                    + "The access token is issued per day and yesterday's does not carry over.";
        }
        if (!session.isFeedUp()) {
            return "the market data feed is down, so every entry fails the freshness check.";
        }
        if (universe.subscribedSymbols().isEmpty()) {
            return "the universe is empty — no constituents were resolved, so nothing is watched.";
        }
        if (router.ticksSeen() == 0) {
            return "subscribed to " + universe.subscribedSymbols().size()
                    + " symbol(s) but not one tick has arrived.";
        }
        if (users.armed().isEmpty()) {
            return users.size() == 0
                    ? "no user is registered, so there is nobody to trade for."
                    : "no user is armed. Signals are being evaluated and recorded as shadow "
                      + "intents, but nothing will be sent until a user is armed.";
        }
        for (UserAccount account : users.all()) {
            if (ledger.isLatched(account.userId())) {
                return "the daily loss limit is latched for " + account.userId() + ": "
                        + ledger.latchReason(account.userId())
                        + ". It stays latched for the rest of the session.";
            }
        }
        if (universe.discoverySet().isEmpty()) {
            return "no symbol has entered the discovery set yet, so nothing holds a depth "
                    + "subscription and every entry would be refused for want of an order book.";
        }
        if (rejections.intentCount() == 0) {
            return "the strategy has produced no trade intent — no stock has met the entry "
                    + "conditions yet. The refusal counts below say which condition is binding.";
        }
        return "intents were produced but every one was refused; the counts below say why.";
    }

    private long totalRejections() {
        return rejections.countsByStage().values().stream().mapToLong(Long::longValue).sum();
    }

    private String property(String key, String fallback) {
        String value = environment.getProperty(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static String describe(String value) {
        return present(value) ? value : "none configured — nobody can sign in";
    }
}
