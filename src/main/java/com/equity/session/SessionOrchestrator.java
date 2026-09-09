package com.equity.session;

import com.equity.broker.FeedStatusListener;
import com.equity.broker.MarketDataPort;
import com.equity.broker.OrderUpdatePort;
import com.equity.domain.market.Candle;
import com.equity.domain.market.SharedInstrumentState;
import com.equity.domain.market.Tick;
import com.equity.domain.market.Timeframe;
import com.equity.domain.position.ExitReason;
import com.equity.domain.risk.RiskDecision;
import com.equity.market.InstrumentFreshness;
import com.equity.market.MarketDataRouter;
import com.equity.market.candle.CandleEngine;
import com.equity.market.state.StructureEngine;
import com.equity.market.universe.IndexConstituentSource;
import com.equity.market.universe.UniverseProperties;
import com.equity.market.universe.UniverseService;
import com.equity.platform.time.TradingClock;
import com.equity.risk.AccountLedger;
import com.equity.broker.ProductType;
import com.equity.risk.MarginCache;
import com.equity.risk.MarginRequirements;
import com.equity.risk.RiskEngine;
import com.equity.strategy.MomentumStrategy;
import com.equity.strategy.DecisionJournal;
import com.equity.strategy.RejectionLog;
import com.equity.strategy.StrategySignal;
import com.equity.trading.PositionBook;
import com.equity.trading.Reconciler;
import com.equity.trading.PositionLifecycle;
import com.equity.user.UserAccount;
import com.equity.user.UserRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.time.LocalTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Wires the engine together and drives it on a clock.
 *
 * <p>Every component here is independently testable and knows nothing about the others; this class
 * is the only place that says what happens in what order. That is deliberate — the sequencing is
 * the part most likely to change, and keeping it in one readable method beats spreading it across
 * the components as implicit assumptions.</p>
 *
 * <h2>The two paths</h2>
 * <ul>
 *   <li><b>Candle path</b> — a completed 1m candle advances each armed user's setup for that symbol.
 *       Slow, expensive, and correct only on completed bars.</li>
 *   <li><b>Tick path</b> — runs on the feed thread and must stay cheap. It does two things: check
 *       stops for open positions, and check triggers for armed setups. No I/O, no ranking, no
 *       indicator maths.</li>
 * </ul>
 *
 * <h2>Shadow evaluation</h2>
 * <p>Both paths evaluate every ACTIVE user, armed or not; only an armed user's intent reaches risk
 * and the broker. A disarmed user's trigger is recorded as a shadow intent and discarded.</p>
 *
 * <p>That distinction is deliberate and was got wrong once here: gating evaluation on
 * {@code users.armed()} meant a session with nobody armed produced no setups, no rejections and no
 * counts, leaving no way to tell "the strategy found nothing" from "the strategy never ran". A
 * disarmed session has to be observable or there is no point running one.</p>
 */
@Component
public class SessionOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SessionOrchestrator.class);

    private final MarketDataPort marketData;
    private final OrderUpdatePort orderUpdates;
    private final MarketDataRouter router;
    private final CandleEngine candles;
    private final StructureEngine structure;
    private final UniverseService universe;
    private final UniverseProperties universeProperties;
    private final IndexConstituentSource constituents;
    private final InstrumentFreshness freshness;
    private final MomentumStrategy strategy;
    private final RejectionLog rejections;
    private final DecisionJournal journal;
    private final RiskEngine risk;
    private final AccountLedger ledger;
    private final MarginCache margins;
    private final MarginRequirements requirements;
    private final Reconciler reconciler;
    private final SessionBackfill backfill;
    private final PositionBook positions;
    private final PositionLifecycle lifecycle;
    private final UserRegistry users;
    private final TradingClock clock;

    private final AtomicBoolean started = new AtomicBoolean();
    private volatile boolean feedUp;
    private volatile Instant squaredOffOn;

    public SessionOrchestrator(MarketDataPort marketData, OrderUpdatePort orderUpdates,
                               MarketDataRouter router, CandleEngine candles,
                               StructureEngine structure, UniverseService universe,
                               UniverseProperties universeProperties,
                               IndexConstituentSource constituents,
                               InstrumentFreshness freshness, MomentumStrategy strategy,
                               RejectionLog rejections, DecisionJournal journal,
                               RiskEngine risk, AccountLedger ledger,
                               MarginCache margins, MarginRequirements requirements,
                               Reconciler reconciler, SessionBackfill backfill,
                               PositionBook positions,
                               PositionLifecycle lifecycle, UserRegistry users, TradingClock clock) {
        this.marketData = marketData;
        this.orderUpdates = orderUpdates;
        this.router = router;
        this.candles = candles;
        this.structure = structure;
        this.universe = universe;
        this.universeProperties = universeProperties;
        this.constituents = constituents;
        this.freshness = freshness;
        this.strategy = strategy;
        this.rejections = rejections;
        this.journal = journal;
        this.risk = risk;
        this.ledger = ledger;
        this.margins = margins;
        this.requirements = requirements;
        this.reconciler = reconciler;
        this.backfill = backfill;
        this.positions = positions;
        this.lifecycle = lifecycle;
        this.users = users;
        this.clock = clock;
    }

    @PostConstruct
    public void wire() {
        if (!started.compareAndSet(false, true)) return;

        marketData.addTickListener(router::accept);
        marketData.addFeedStatusListener(new FeedListener());
        orderUpdates.addOrderUpdateListener((userId, order) ->
                lifecycle.onOrderUpdate(userId, order, users.find(userId).orElse(null)));

        // The ledger has counted fills since it was written and nothing ever told it about one, so
        // every hit rate derived from it read zero. The book already announces a fill by moving a
        // position to OPEN; this is simply the wire that was missing.
        positions.onOpened(position -> ledger.recordFill(position.userId()));
        reconciler.setLastPriceSource(symbol -> {
            Tick last = router.lastTick(symbol);
            return last == null ? 0 : last.lastPrice();
        });

        router.onTick(this::onTick);
        candles.onCandleClosed(this::onCandleClosed);

        positions.onClosed(position -> {
            ledger.recordClosed(position);
            // A slot just freed; anything deferred only for want of one may trigger again.
            strategy.onCapacityFreed(position.userId());
            // Do not re-enter a symbol immediately after being taken out of it: the conditions that
            // produced the exit are usually still present a minute later.
            strategy.onPositionClosed(position.userId(), position.symbol(),
                    Duration.ofSeconds(users.find(position.userId())
                            .map(a -> a.limits().cooldownSeconds()).orElse(300L)));
            margins.refresh(position.userId());
        });

        lifecycle.onEntryFailure(strategy::onEntryAbandoned);

        // The trailing stop needs the instrument's ATR and the user's policy. Supplied as lookups
        // so the lifecycle keeps no opinion about where either lives.
        lifecycle.setAtrSource(symbol -> structure.state(symbol)
                .map(SharedInstrumentState::atr).orElse(Double.NaN));
        lifecycle.onOutcome(journal::tradeOutcome);
        lifecycle.setExitPolicySource(userId -> users.find(userId)
                .map(UserAccount::exitPolicy).orElseGet(com.equity.strategy.ExitPolicy::fixed));

        log.info("session orchestrator wired: market data -> candles -> structure -> strategy "
                + "-> risk -> lifecycle");
    }

    // ── Tick path ────────────────────────────────────────────────────────────

    /**
     * Runs on the feed thread for every tick. Kept to two jobs and no I/O.
     *
     * <p>Stops are checked first, for every user, before any entry logic. An engine that evaluates
     * entries before exits spends its budget on new risk in exactly the tick where existing risk
     * needed attention.</p>
     */
    private void onTick(Tick tick) {
        lifecycle.onTick(tick);

        if (!universe.isCandidate(tick.symbol())) return;
        Optional<SharedInstrumentState> state = structure.state(tick.symbol());
        if (state.isEmpty()) return;

        for (UserAccount account : users.evaluable()) {
            StrategySignal signal = strategy.onTick(account, state.get(), tick);
            if (signal.isRejected()) {
                rejections.recordStrategyRejection(account.userId(), tick.symbol(), signal);
                journal.rejection(account.userId(), state.get(), signal,
                        strategy.setupFor(account.userId(), tick.symbol()));
                continue;
            }
            if (!signal.isIntent()) continue;

            journal.intent(account.userId(), state.get(),
                    strategy.setupFor(account.userId(), tick.symbol()),
                    signal.intent().referencePrice(), signal.intent().stopPrice(),
                    signal.intent().targetPrice(), account.mayOpen(), tick.book());

            if (account.mayOpen()) {
                attemptEntry(account, signal, state.get(), tick);
            } else {
                // Shadow: the setup triggered but this user is not armed. Recorded, never sent.
                // This is what makes a disarmed session worth running — without it the operator
                // cannot tell "the strategy found nothing" from "the strategy never ran".
                rejections.recordShadowIntent(account.userId(), tick.symbol(),
                        signal.intent().rationale());
                log.info("SHADOW ENTRY {} {} at {} stop {} target {} — user not armed",
                        account.userId(), tick.symbol(), signal.intent().referencePrice(),
                        signal.intent().stopPrice(), signal.intent().targetPrice());
                strategy.onShadowIntent(account.userId(), tick.symbol(),
                        Duration.ofSeconds(account.limits().cooldownSeconds()));
            }
        }
    }

    private void attemptEntry(UserAccount account, StrategySignal signal,
                              SharedInstrumentState state, Tick tick) {
        rejections.recordIntent();
        long epoch = account.epoch();

        double unrealised = positions.unrealised(account.userId(), symbol -> {
            Tick last = router.lastTick(symbol);
            return last == null ? 0 : last.lastPrice();
        });

        RiskDecision decision = risk.authorise(account, signal.intent(), state, tick,
                unrealised, margins.available(account.userId()), epoch);

        if (!decision.approved()) {
            rejections.recordRiskDenial(account.userId(), state.symbol(),
                    decision.denialReason(), decision.note());
            journal.riskDenial(account.userId(), state,
                    strategy.setupFor(account.userId(), state.symbol()),
                    decision.denialReason().name(), decision.note());
            if (decision.denialReason().isCapacityLimited()) {
                // Waiting on a slot, not on time. Released the moment a position closes.
                strategy.onEntryDeferredForCapacity(account.userId(), state.symbol());
            } else {
                strategy.onEntryAbandoned(account.userId(), state.symbol(),
                        decision.denialReason().isTerminalForSession());
            }
            return;
        }

        ledger.recordAttempt(account.userId());
        var outcome = lifecycle.open(account, signal.intent(), decision, epoch);
        if (!outcome.succeeded()) {
            // The broker's own words, not a guess at which of two things went wrong.
            rejections.recordExecutionFailure(account.userId(), state.symbol(), outcome.message());
        }
    }

    // ── Candle path ──────────────────────────────────────────────────────────

    private void onCandleClosed(Candle candle) {
        if (candle.timeframe() != Timeframe.M1) return;
        if (!universe.isCandidate(candle.symbol())) return;

        Optional<SharedInstrumentState> state = structure.state(candle.symbol());
        if (state.isEmpty()) return;
        var minutes = candles.history(candle.symbol(), Timeframe.M1);

        for (UserAccount account : users.evaluable()) {
            checkStructureExit(account, state.get());

            // Captured either side of the evaluation so the journal records a funnel rather than a
            // tally: without transitions, one setup dying repeatedly is indistinguishable from many
            // setups failing once, and those imply opposite things about a threshold.
            var setup = strategy.setupFor(account.userId(), candle.symbol());
            String before = setup.state().name();

            StrategySignal signal = strategy.onCandleClosed(account, state.get(), minutes);

            String after = setup.state().name();
            if (!before.equals(after)) {
                journal.transition(account.userId(), state.get(), setup, before, after);
                log.info("SETUP {} {} {} -> {}{}", account.userId(), candle.symbol(), before, after,
                        setup.pattern() == null ? "" : " (" + setup.pattern() + ")");
            }

            if (signal.isRejected()) {
                rejections.recordStrategyRejection(account.userId(), candle.symbol(), signal);
                journal.rejection(account.userId(), state.get(), signal, setup);
            }
        }
    }

    /**
     * Closes a position whose move has broken down, before the stop is reached.
     *
     * <p>Candle-driven, not tick-driven: VWAP is crossed and re-crossed constantly inside a minute,
     * and acting on that would exit on noise. A <b>completed</b> bar closing below it is a different
     * statement — the move this trade was joining is no longer intact.</p>
     *
     * <p>Off unless the user's policy enables it. It is the exit most likely to clip a winner, and
     * in a sibling engine cutting early cost more than it saved.</p>
     */
    private void checkStructureExit(UserAccount account, SharedInstrumentState state) {
        if (!account.exitPolicy().structureExitEnabled()) return;
        if (state.vwap() <= 0 || Double.isNaN(state.vwap()) || state.aboveVwap()) return;

        positions.withExposure(account.userId()).stream()
                .filter(p -> p.symbol().equals(state.symbol()))
                .forEach(p -> lifecycle.requestExit(p, ExitReason.STRUCTURE,
                        String.format("closed below vwap: %.2f vs %.2f",
                                state.lastPrice(), state.vwap())));
    }

    // ── Scheduled work ───────────────────────────────────────────────────────

    /**
     * The fast loop. Closes candles whose minute elapsed without a tick, and sends queued exits.
     *
     * <p>Exits are drained here rather than on the feed thread on purpose: placing an order is an
     * HTTP call, and doing it inline would stall every other instrument behind it.</p>
     */
    @Scheduled(fixedDelay = 1000)
    public void fastLoop() {
        router.closeStaleBuckets();
        lifecycle.drainExits();
        // Off the feed thread on purpose: a slow disk here would stall every instrument behind one
        // write, and Kite drops a client that falls behind.
        journal.flush();
    }

    /** Re-ranks the board and moves the depth subscription to follow it. */
    @Scheduled(fixedDelay = 15_000)
    public void refreshUniverse() {
        if (!feedUp) return;
        universe.refreshDiscoverySet();
    }

    /**
     * Keeps the margin picture current without putting an HTTP call on the tick path.
     *
     * <p>Two halves: how much the account has, and how much each candidate would block. The second
     * is what makes intraday leverage visible to the risk check — without it every entry is measured
     * against the full price of the shares, which refuses trades the broker would accept. Only the
     * discovery set is asked about, and only once per symbol per day.</p>
     */
    @Scheduled(fixedDelay = 30_000)
    public void refreshMargins() {
        users.all().forEach(a -> margins.refresh(a.userId()));

        var candidates = universe.discoverySet();
        if (candidates.isEmpty()) return;
        users.all().stream().findFirst().ifPresent(a ->
                requirements.prime(a.userId(), ProductType.MIS, candidates));
    }

    /**
     * Makes the engine's view agree with the broker's.
     *
     * <p>Every sixty seconds, and it is the only thing that notices a fill lost to a websocket drop,
     * an order that timed out but landed, or a position closed by hand in Kite. A run costs two HTTP
     * calls per signed-in user, which is why it is here on the scheduler and not on the tick path.</p>
     *
     * <p>It runs whether or not the feed is up, deliberately: a dropped feed is precisely when the
     * engine's view is most likely to be stale, so stopping reconciliation then would disable it in
     * the one situation it exists for.</p>
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 10_000)
    public void reconcile() {
        for (UserAccount account : users.all()) {
            try {
                reconciler.reconcile(account);
            } catch (RuntimeException e) {
                // One user's broker problem must not stop the others being reconciled.
                log.error("reconciliation failed for user={}: {}", account.userId(), e.toString());
            }
        }
    }

    /**
     * Time stops and the end-of-day square-off.
     *
     * <p>The square-off runs ahead of the broker's own so the engine chooses the exit rather than
     * discovering it. It fires once per trading date; without that guard it would re-request an exit
     * every ten seconds for the rest of the session.</p>
     */
    @Scheduled(fixedDelay = 10_000)
    public void sessionGuards() {
        LocalTime now = clock.timeOfDay();
        for (UserAccount account : users.all()) {
            lifecycle.checkTimeStops(account.userId(), account.thresholds().timeStopMinutes());

            if (!now.isBefore(account.thresholds().squareOffTime())) {
                Instant today = clock.tradingDate().atStartOfDay(TradingClock.IST).toInstant();
                if (!today.equals(squaredOffOn)) {
                    log.warn("square-off time reached — closing everything for user={}", account.userId());
                    lifecycle.requestExitAll(account.userId(), ExitReason.SQUARE_OFF,
                            "session square-off at " + now.withNano(0));
                }
            }
        }
        if (!now.isBefore(LocalTime.of(15, 10))) {
            squaredOffOn = clock.tradingDate().atStartOfDay(TradingClock.IST).toInstant();
        }
    }

    public boolean isFeedUp() { return feedUp; }

    /** The user whose broker session carries the feed, if any is registered. */
    private java.util.Optional<com.equity.domain.user.UserId> sessions() {
        return users.all().stream().map(UserAccount::userId).findFirst();
    }

    /** Reacts to the feed going up and down. */
    private final class FeedListener implements FeedStatusListener {

        @Override
        public void onConnected(Instant at) {
            feedUp = true;
            log.info("market data feed up at {}", at);
            // Subscribing here rather than at startup: the instrument master is only loaded after
            // somebody logs in, and a subscription built before it exists resolves no tokens at all.
            if (universeProperties.isAutoSubscribe()) {
                // An explicit list wins; otherwise the index is read from the exchange. Resolved
                // here rather than at startup because the instrument master only exists after a
                // login, and a subscription built before it resolves no tokens at all.
                List<String> symbols = universeProperties.getSymbols().isEmpty()
                        ? constituents.constituents()
                        : universeProperties.getSymbols();

                universe.subscribeUniverse(symbols);

                // Load the session's completed bars behind the live feed. Without it a process
                // started mid-session computes VWAP from its own start time, which is not VWAP and
                // never becomes it — and VWAP is a mandatory entry gate.
                sessions().ifPresent(backfill::startFor);

                universe.recordUnresolved(
                        new java.util.ArrayList<>(marketData.unresolvedSymbols()),
                        marketData::suggestionsFor);
            } else {
                log.warn("auto-subscribe is off — the engine is watching nothing");
            }
        }

        @Override
        public void onDisconnected(Instant at, String reason, boolean willRetry) {
            feedUp = false;
            // Everything known is now of unknown age. Leaving the old timestamps in place would let
            // entries pass a freshness check against prices that stopped arriving.
            freshness.invalidateAll();
            log.error("market data feed DOWN at {} ({}), retrying={} — entries will fail the "
                    + "freshness check until it returns", at, reason, willRetry);
        }
    }
}
