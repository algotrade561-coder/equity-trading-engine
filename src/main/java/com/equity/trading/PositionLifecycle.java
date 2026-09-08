package com.equity.trading;

import com.equity.broker.BrokerException;
import com.equity.broker.BrokerOrder;
import com.equity.broker.BrokerPort;
import com.equity.broker.OrderRequest;
import com.equity.broker.OrderSide;
import com.equity.broker.OrderStatus;
import com.equity.broker.OrderType;
import com.equity.broker.OrderVariety;
import com.equity.broker.ProductType;
import com.equity.domain.Direction;
import com.equity.domain.market.Tick;
import com.equity.domain.order.OrderTag;
import com.equity.domain.order.TradeIntent;
import com.equity.domain.position.ExitReason;
import com.equity.domain.position.ExitRequest;
import com.equity.domain.position.Position;
import com.equity.domain.position.PositionStatus;
import com.equity.domain.risk.RiskDecision;
import com.equity.domain.user.UserId;
import com.equity.platform.time.TradingClock;
import com.equity.strategy.ExitPolicy;
import com.equity.strategy.StopAdjuster;
import com.equity.user.UserAccount;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The <b>only</b> component that opens or closes a position.
 *
 * <p>Design note 0.2, and the single most important boundary in this codebase. In the sibling
 * options engine five different code paths could close a position; 29% of one strategy's trades were
 * exited by a path that did not own them, which made the exit logic impossible to reason about and
 * occasionally sent two exit orders for the same shares. Here, every other component can only
 * <i>request</i> an exit; this class alone acts.</p>
 *
 * <h2>Exits are not gated like entries</h2>
 * <p>Design note 0.1. Nothing in this class consults the master trading switch, the user's entry
 * permission, or the risk engine when closing. A halt stops new risk; it must never strand existing
 * risk with nothing watching it.</p>
 *
 * <h2>The epoch</h2>
 * <p>Design note 0.3. An entry is authorised under an epoch and re-checks it immediately before
 * submission. If a fill still arrives for an epoch that has since been bumped — the operator halted
 * during the round trip — the position is not adopted: it is closed at once, which is the
 * compensating action a pre-submission checklist alone cannot provide.</p>
 */
@Component
public class PositionLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PositionLifecycle.class);

    private final BrokerPort broker;
    private final PositionBook book;
    private final TradingClock clock;
    private final ExitQueue exits = new ExitQueue();

    /**
     * Entries whose submission failed in a way that does not prove the order never landed.
     *
     * <p>A timeout is not a rejection. The request may have reached the exchange and filled, and the
     * engine would then hold shares it has no record of — no stop, no target, no square-off. Keyed by
     * the tag, which was minted before the wire precisely so it can be recognised afterwards.</p>
     */
    private final Map<String, UnconfirmedEntry> unconfirmed = new ConcurrentHashMap<>();

    private final AtomicLong entriesSubmitted = new AtomicLong();
    private final AtomicLong exitsSubmitted = new AtomicLong();
    private final AtomicLong disownedFills = new AtomicLong();
    private final AtomicLong stopsTightened = new AtomicLong();
    private final AtomicLong adoptedOrphans = new AtomicLong();

    /** Notified when an entry attempt fails, so the strategy can release the setup. */
    private volatile BiConsumer<UserId, String> entryFailureListener = (u, s) -> {};

    /**
     * Current ATR per symbol, and each user's exit policy.
     *
     * <p>Supplied as functions rather than injected collaborators so this class keeps no opinion
     * about where either comes from, and so the tick path stays free of lookups it cannot control.
     * Both default to "no adjustment", which is the shipped behaviour.</p>
     */
    private volatile java.util.function.ToDoubleFunction<String> atrSource = symbol -> Double.NaN;
    private volatile java.util.function.Function<UserId, ExitPolicy> exitPolicySource =
            userId -> ExitPolicy.fixed();

    public PositionLifecycle(BrokerPort broker, PositionBook book, TradingClock clock) {
        this.broker = broker;
        this.book = book;
        this.clock = clock;
    }

    public void onEntryFailure(BiConsumer<UserId, String> listener) {
        this.entryFailureListener = listener;
    }

    public void setAtrSource(java.util.function.ToDoubleFunction<String> atrSource) {
        this.atrSource = atrSource;
    }

    public void setExitPolicySource(java.util.function.Function<UserId, ExitPolicy> source) {
        this.exitPolicySource = source;
    }

    /**
     * What happened to an entry attempt.
     *
     * <p>Replaces an {@code Optional<Position>}, which could say only "no position" — leaving the
     * caller unable to distinguish a broker refusal from an epoch that moved, and unable to show
     * anyone the broker's own words. For a rejection those words are the whole diagnostic value.</p>
     */
    public record EntryOutcome(Position position, BrokerException failure, String reason) {

        public static EntryOutcome opened(Position position) {
            return new EntryOutcome(position, null, "opened");
        }

        public static EntryOutcome refused(BrokerException failure) {
            return new EntryOutcome(null, failure, "broker refused");
        }

        public static EntryOutcome dropped(String reason) {
            return new EntryOutcome(null, null, reason);
        }

        public boolean succeeded() { return position != null; }

        /** The broker's own message, or our reason when the order never left. */
        public String message() {
            return failure != null ? failure.getMessage() : reason;
        }
    }

    // ── Opening ──────────────────────────────────────────────────────────────

    /**
     * Submits an authorised entry.
     *
     * @param epochAtDecision the epoch risk approved under; re-checked here because time passed
     * @return the new position, or empty if the entry was refused or failed
     */
    public synchronized EntryOutcome open(UserAccount account, TradeIntent intent,
                                          RiskDecision decision, long epochAtDecision) {
        if (!decision.approved()) {
            return EntryOutcome.dropped("risk did not approve the entry");
        }

        // Re-check immediately before the wire. This is a narrower window than the risk check, and
        // narrowing it is the whole point — it cannot be closed, only made small and compensated.
        if (!account.isCurrentEpoch(epochAtDecision)) {
            log.warn("dropping entry for {} {}: epoch moved {} -> {} after authorisation",
                    account.userId(), intent.symbol(), epochAtDecision, account.epoch());
            entryFailureListener.accept(account.userId(), intent.symbol());
            return EntryOutcome.dropped("the user was stopped between authorisation and submission");
        }

        OrderTag tag = OrderTag.forEntry(account.userId());
        // One shape, always: intraday, at market, in the current session. This is an intraday
        // engine — a delivery product would not be squared off, and an after-market entry would
        // open a position with nobody watching it.
        OrderRequest request = new OrderRequest(
                intent.symbol(), "NSE", OrderSide.BUY, decision.quantity(),
                OrderType.MARKET, ProductType.MIS, 0, 0, OrderVariety.REGULAR, tag.value());

        String orderId;
        try {
            orderId = broker.placeOrder(account.userId(), request);
        } catch (BrokerException e) {
            // Not isRetryable(): a timeout is deliberately non-retryable precisely because it may
            // have landed, so the two answer opposite questions. Remember enough to recognise and
            // adopt the fill if it did land; otherwise the shares would be held with no stop and
            // nothing arranged to square them off.
            if (e.mayHaveReachedExchange()) {
                unconfirmed.put(tag.value(), new UnconfirmedEntry(account.userId(), tag,
                        intent, decision, clock.now()));
                log.error("entry submission for {} {} did not complete cleanly: {} — the order may "
                        + "still have landed; holding tag {} for reconciliation",
                        account.userId(), intent.symbol(), e.getMessage(), tag);
            } else {
                log.error("entry rejected for {} {}: {}",
                        account.userId(), intent.symbol(), e.getMessage());
            }
            entryFailureListener.accept(account.userId(), intent.symbol());
            return EntryOutcome.refused(e);
        }

        Position position = Position.pendingEntry(account.userId(), intent.symbol(), Direction.LONG,
                intent.pattern(), ProductType.MIS, decision.quantity(), intent.referencePrice(),
                decision.stopPrice(), intent.targetPrice(), tag, orderId, clock.now());
        book.put(position);
        entriesSubmitted.incrementAndGet();

        log.info("ENTRY {} {} x{} MIS stop {} target {} tag {} order {}",
                account.userId(), intent.symbol(), decision.quantity(),
                decision.stopPrice(), intent.targetPrice(), tag, orderId);
        return EntryOutcome.opened(position);
    }

    // ── Exit detection ───────────────────────────────────────────────────────

    /**
     * Evaluates every exposed position in this symbol against the live price.
     *
     * <p>Runs on the feed thread for every tick, so it does no I/O. It only queues; the drain does
     * the work.</p>
     */
    public void onTick(Tick tick) {
        Instant now = clock.now();
        for (Position current : book.all()) {
            Position position = current;
            if (!position.hasExposure() || !position.symbol().equals(tick.symbol())) continue;
            if (position.status() == PositionStatus.EXIT_PENDING) continue;

            // Mark first, then move the stop, then test it — in that order. Testing a stop before
            // this tick's price has been folded into the high-water mark would judge the trade
            // against a level that is one tick out of date.
            Position marked = position.withHighWaterMark(tick.lastPrice());
            Position adjusted = StopAdjuster.adjust(marked, exitPolicySource.apply(position.userId()),
                    atrSource.applyAsDouble(position.symbol()));
            if (adjusted != position) {
                book.put(adjusted);
                if (StopAdjuster.moved(position, adjusted)) {
                    stopsTightened.incrementAndGet();
                    log.info("STOP MOVED {} {} {} -> {} after {}R",
                            position.userId(), position.symbol(),
                            String.format("%.2f", position.stopPrice()),
                            String.format("%.2f", adjusted.stopPrice()),
                            String.format("%.2f", adjusted.favourableExcursionR()));
                }
                position = adjusted;
            }

            // Both can be true when a bar gaps through the pair. The queue's priority ordering
            // resolves it in favour of the stop; raising both and letting it decide is deliberate.
            if (position.stopBreached(tick.lastPrice())) {
                exits.offer(ExitRequest.of(position, ExitReason.HARD_STOP, now,
                        String.format("%.2f through %.2f", tick.lastPrice(), position.stopPrice())));
            }
            if (position.targetReached(tick.lastPrice())) {
                exits.offer(ExitRequest.of(position, ExitReason.TARGET, now,
                        String.format("%.2f reached %.2f", tick.lastPrice(), position.targetPrice())));
            }
        }
    }

    /** Raises an exit from any other source — structure breakdown, an operator, the square-off timer. */
    public void requestExit(Position position, ExitReason reason, String note) {
        if (!position.hasExposure()) return;
        exits.offer(ExitRequest.of(position, reason, clock.now(), note));
    }

    /** Closes everything this user holds. Used by the halt path and the end-of-day square-off. */
    public void requestExitAll(UserId userId, ExitReason reason, String note) {
        book.withExposure(userId).forEach(p -> requestExit(p, reason, note));
    }

    /** Positions that have been held past their time stop and have resolved neither way. */
    public void checkTimeStops(UserId userId, int timeStopMinutes) {
        Instant cutoff = clock.now().minus(Duration.ofMinutes(timeStopMinutes));
        for (Position p : book.withExposure(userId)) {
            if (p.openedAt() != null && p.openedAt().isBefore(cutoff)) {
                requestExit(p, ExitReason.TIME_STOP, timeStopMinutes + " minutes without resolution");
            }
        }
    }

    // ── Exit execution ───────────────────────────────────────────────────────

    /**
     * Sends the queued exits, most urgent first.
     *
     * <p>A failure here is logged and the request re-queued rather than dropped. An exit that cannot
     * be sent is the one situation where giving up silently is unacceptable: the position stays
     * open with nothing arranged to close it.</p>
     */
    public synchronized int drainExits() {
        List<ExitRequest> requests = exits.drain();
        int sent = 0;
        for (ExitRequest request : requests) {
            Optional<Position> found = book.byId(request.positionId());
            if (found.isEmpty()) continue;
            Position position = found.get();
            if (!position.hasExposure() || position.status() == PositionStatus.EXIT_PENDING) continue;

            OrderTag tag = OrderTag.forExit(position.userId());
            OrderRequest order = new OrderRequest(
                    position.symbol(), "NSE",
                    position.direction() == Direction.LONG ? OrderSide.SELL : OrderSide.BUY,
                    position.filledQuantity(),
                    // Always MARKET. An unfilled exit is an unbounded loss, and a limit exit that
                    // does not fill is exactly what happens in the move you most need out of.
                    //
                    // The product is whatever the entry used. Squaring a CNC holding off with an MIS
                    // sell does not close it — it opens an intraday short alongside it.
                    // Exits are always REGULAR: an after-market exit would leave the position open
                    // overnight, which is the opposite of what every exit reason here means.
                    OrderType.MARKET, position.product(), 0, 0, OrderVariety.REGULAR, tag.value());

            try {
                String orderId = broker.placeOrder(position.userId(), order);
                book.put(position.withExitPending(request.reason(), tag, orderId));
                exitsSubmitted.incrementAndGet();
                sent++;
                log.info("EXIT {} {} x{} {} reason {} ({}) order {}",
                        position.userId(), position.symbol(), position.filledQuantity(),
                        position.product(), request.reason(), request.note(), orderId);
            } catch (BrokerException e) {
                log.error("EXIT FAILED for {} {} reason {}: {} — requeued",
                        position.userId(), position.symbol(), request.reason(), e.getMessage());
                exits.offer(request);
            }
        }
        return sent;
    }

    // ── Reconciliation ───────────────────────────────────────────────────────

    /** An entry that was sent but whose fate the submission call did not establish. */
    private record UnconfirmedEntry(UserId userId, OrderTag tag, TradeIntent intent,
                                    RiskDecision decision, Instant at) {}

    /**
     * Adopts, or discards, entries whose submission never returned an order id.
     *
     * <p>The tag is what makes this possible: it was minted before the wire, the broker echoes it
     * back on every order, and it encodes the user — so an order found at the broker can be matched
     * to the intent that created it even across a restart.</p>
     *
     * <p>An adopted position keeps the stop and target the risk decision authorised, not levels
     * derived from wherever price is now. It filled as the trade that was approved, so it is managed
     * as that trade.</p>
     *
     * @param age how long an unconfirmed entry may go unmatched before it is written off as never
     *            having reached the exchange
     * @return the positions adopted
     */
    public synchronized List<Position> resolveUnconfirmed(UserId userId, List<BrokerOrder> orders,
                                                          Duration age) {
        if (unconfirmed.isEmpty()) return List.of();
        List<Position> adopted = new java.util.ArrayList<>();

        for (UnconfirmedEntry entry : List.copyOf(unconfirmed.values())) {
            if (!entry.userId().equals(userId)) continue;

            Optional<BrokerOrder> match = orders.stream()
                    .filter(o -> entry.tag().value().equals(o.tag()))
                    .findFirst();

            if (match.isEmpty()) {
                // Absent from today's order list after a grace period means it never arrived. Kite
                // returns every order placed today, rejected ones included, so absence is
                // informative once enough time has passed for it to have appeared.
                if (entry.at().isBefore(clock.now().minus(age))) {
                    unconfirmed.remove(entry.tag().value());
                    log.warn("unconfirmed entry {} for {} {} never appeared at the broker — "
                                    + "treating it as never sent",
                            entry.tag(), userId, entry.intent().symbol());
                }
                continue;
            }

            BrokerOrder order = match.get();
            if (!order.status().isTerminal()) continue;      // still working; look again next pass

            unconfirmed.remove(entry.tag().value());

            if (order.status() != OrderStatus.COMPLETE || order.filledQuantity() <= 0) {
                log.warn("unconfirmed entry {} for {} {} resolved as {} — nothing was filled",
                        entry.tag(), userId, entry.intent().symbol(), order.status());
                continue;
            }

            Position position = Position.pendingEntry(userId, entry.intent().symbol(),
                            Direction.LONG, entry.intent().pattern(), ProductType.MIS,
                            order.quantity(), entry.intent().referencePrice(),
                            entry.decision().stopPrice(), entry.intent().targetPrice(),
                            entry.tag(), order.brokerOrderId(), entry.at())
                    .withFill(order.filledQuantity(), order.averagePrice(), clock.now());
            book.put(position);
            adopted.add(position);
            adoptedOrphans.incrementAndGet();
            log.warn("ADOPTED {} {} x{} at {} — the entry filled despite the submission failing; "
                            + "stop {} target {} are now being watched",
                    userId, position.symbol(), position.filledQuantity(), position.entryPrice(),
                    position.stopPrice(), position.targetPrice());
        }
        return adopted;
    }

    /**
     * Records that the broker no longer holds a position the engine believed was open.
     *
     * <p>Something outside this engine closed it — a manual square-off in Kite, the broker's own
     * auto-square-off, or a fill the engine never saw. Design note 0.12: the broker is truth, so
     * this is not a disagreement to average out. The position is closed at the price the broker last
     * reported, and the reason says plainly that the engine did not do it.</p>
     */
    public synchronized void adoptExternalClose(Position position, double price, String note) {
        if (!position.hasExposure()) return;

        // Falling back to the entry price books the trade at exactly zero, which is the one answer
        // guaranteed to be wrong. It happened: a position closed by hand in the terminal was
        // recorded flat, and its real result vanished from the day's realised total. The caller
        // supplies the best price it can find — the broker's own fill for the exit if it can be
        // identified, otherwise the last traded price — and only a complete absence of any price
        // falls back, now saying so rather than passing it off as a result.
        boolean known = price > 0;
        double at = known ? price : position.entryPrice();
        book.put(position.withClose(at, ExitReason.MANUAL, clock.now()));

        if (known) {
            log.warn("EXTERNALLY CLOSED {} {} at {} pnl {} — {}. The engine did not send this exit.",
                    position.userId(), position.symbol(), at,
                    String.format("%.2f", position.withClose(at, ExitReason.MANUAL,
                            clock.now()).realisedPnl()), note);
        } else {
            log.error("EXTERNALLY CLOSED {} {} but no exit price could be established, so it is "
                    + "booked flat at the entry of {}. THE REALISED P&L FOR THIS TRADE IS WRONG — "
                    + "take it from the broker's contract note. {}",
                    position.userId(), position.symbol(), at, note);
        }
    }

    /**
     * Gives up on an entry the broker has no record of.
     *
     * <p>{@link PositionStatus#ABANDONED}, never CLOSED: closing implies the engine knows the trade
     * is over, and this is the opposite — a position whose fate could not be established. Flattening
     * that into a clean close is how a real holding becomes invisible.</p>
     */
    public synchronized void abandon(Position position, String note) {
        if (position.status().isFinished()) return;
        book.put(position.withStatus(PositionStatus.ABANDONED, clock.now()));
        log.error("ABANDONED {} {} ({}) — {}. This needs a human: check the broker terminal.",
                position.userId(), position.symbol(), position.status(), note);
        entryFailureListener.accept(position.userId(), position.symbol());
    }

    /** Tags submitted whose fate is unknown. Non-zero here means real money is unaccounted for. */
    public int unconfirmedEntries() { return unconfirmed.size(); }

    // ── Broker feedback ──────────────────────────────────────────────────────

    /**
     * Applies an order update from the broker.
     *
     * <p>Matched on the tag, not on the symbol: a user can hold the same stock through two separate
     * positions over a session, and matching on symbol would apply one order's fill to the other.</p>
     */
    public synchronized void onOrderUpdate(UserId userId, BrokerOrder order, UserAccount account) {
        Optional<Position> match = book.forUser(userId).stream()
                .filter(p -> matchesTag(p, order.tag()))
                .findFirst();
        if (match.isEmpty()) return;

        Position position = match.get();
        boolean isEntry = position.entryTag() != null
                && position.entryTag().value().equals(order.tag());

        if (isEntry) {
            applyEntryUpdate(position, order, account);
        } else {
            applyExitUpdate(position, order);
        }
    }

    private void applyEntryUpdate(Position position, BrokerOrder order, UserAccount account) {
        switch (order.status()) {
            case COMPLETE -> {
                Position filled = position.withFill(order.filledQuantity(),
                        order.averagePrice(), clock.now());
                book.put(filled);
                log.info("FILLED {} {} x{} at {}", filled.userId(), filled.symbol(),
                        filled.filledQuantity(), filled.entryPrice());

                // Design note 0.3's compensating action: the operator halted while this was in
                // flight, so the position is closed rather than adopted.
                if (account != null && !account.mayOpen()) {
                    disownedFills.incrementAndGet();
                    log.warn("fill arrived for {} {} after the user was stopped — closing immediately",
                            filled.userId(), filled.symbol());
                    requestExit(filled, ExitReason.RISK_HALT, "filled after halt");
                }
            }
            case REJECTED, CANCELLED -> {
                book.put(position.withStatus(PositionStatus.CLOSED, clock.now()));
                log.warn("entry {} for {} {} was {} — {}", order.brokerOrderId(),
                        position.userId(), position.symbol(), order.status(), order.statusMessage());
                entryFailureListener.accept(position.userId(), position.symbol());
            }
            case UNKNOWN -> log.warn("entry {} for {} {} reported an unrecognised status; leaving "
                            + "it pending for reconciliation rather than guessing",
                    order.brokerOrderId(), position.userId(), position.symbol());
            default -> { /* still working */ }
        }
    }

    private void applyExitUpdate(Position position, BrokerOrder order) {
        if (order.status() == OrderStatus.COMPLETE) {
            Position closed = position.withClose(order.averagePrice(),
                    position.exitReason(), clock.now());
            book.put(closed);
            log.info("CLOSED {} {} at {} reason {} pnl {}", closed.userId(), closed.symbol(),
                    closed.exitPrice(), closed.exitReason(),
                    String.format("%.2f", closed.realisedPnl()));
        } else if (order.status() == OrderStatus.REJECTED || order.status() == OrderStatus.CANCELLED) {
            // Put it back to OPEN so the next tick can raise the exit again. An exit that failed
            // must not leave the position permanently marked as exiting and therefore ignored.
            log.error("EXIT ORDER {} for {} {} was {} — position is still open",
                    order.brokerOrderId(), position.userId(), position.symbol(), order.status());
            book.put(position.withStatus(PositionStatus.OPEN));
        }
    }

    private static boolean matchesTag(Position position, String tag) {
        if (tag == null || tag.isBlank()) return false;
        return (position.entryTag() != null && position.entryTag().value().equals(tag))
                || (position.exitTag() != null && position.exitTag().value().equals(tag));
    }

    public int queuedExits()        { return exits.size(); }
    public long entriesSubmitted()  { return entriesSubmitted.get(); }
    public long exitsSubmitted()    { return exitsSubmitted.get(); }
    public long disownedFills()     { return disownedFills.get(); }
    public long stopsTightened()    { return stopsTightened.get(); }
    public long adoptedOrphans()    { return adoptedOrphans.get(); }
}
