package com.equity.trading;

import com.equity.broker.BrokerOrder;
import com.equity.broker.BrokerPort;
import com.equity.broker.BrokerPosition;
import com.equity.broker.OrderStatus;
import com.equity.broker.ProductType;
import com.equity.domain.position.Position;
import com.equity.domain.position.PositionStatus;
import com.equity.domain.user.UserId;
import com.equity.platform.time.TradingClock;
import com.equity.user.UserAccount;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Makes the engine's view agree with the broker's, in the broker's favour.
 *
 * <h2>Why</h2>
 * <p>Design note 0.12: the broker is truth, the database is a log, and {@link PositionBook} is a
 * cache that may be wrong after any disconnect. Without this the two views drift silently, and every
 * way they drift costs money:</p>
 * <ul>
 *   <li>An order update missed during a websocket drop leaves a filled entry stuck at PENDING_ENTRY,
 *       so no stop is ever evaluated for shares that are really held.</li>
 *   <li>A submission that timed out may have landed. {@link PositionLifecycle} keeps the tag; this
 *       is what goes and looks.</li>
 *   <li>A position closed manually in Kite stays OPEN here, so the engine keeps a slot occupied, and
 *       may send an exit for shares that are already gone.</li>
 *   <li>A restart mid-session reloads positions from the database, which is a log of what the engine
 *       last believed — not of what is actually held.</li>
 * </ul>
 *
 * <h2>What it will not do</h2>
 * <p>It never adopts a broker position it cannot tie to an engine order. The account is the user's
 * own; a holding this engine did not create may be their manual trade, and taking ownership of it
 * would mean squaring off a position somebody else is managing. Those are reported as orphans and
 * left alone — loudly, because an intraday orphan the engine will not square off is exactly the
 * thing a human needs to know about before 3:20.</p>
 *
 * <p>It also never places an order. Every state change goes through {@link PositionLifecycle}, which
 * is the sole owner of the position lifecycle (design note 0.2).</p>
 */
@Component
public class Reconciler {

    private static final Logger log = LoggerFactory.getLogger(Reconciler.class);

    /**
     * How long an entry may sit unresolved before absence at the broker is taken as proof it never
     * arrived. Long enough to cover a slow acknowledgement, short enough that a position is not left
     * unwatched for most of a session.
     */
    private static final Duration GRACE = Duration.ofSeconds(90);

    /**
     * How long after the engine closes a position the broker may still report it as held.
     *
     * <p>Kite's positions endpoint lags its own fills. Twenty-six seconds after the engine sold TEGA
     * at its target and booked the trade, reconciliation read 91 shares still standing and raised an
     * orphan — telling an operator to go and manually close a position that no longer existed. A
     * false alarm on this path is worse than none: it invites exactly the intervention it should
     * prevent.</p>
     */
    private static final Duration SETTLING = Duration.ofMinutes(2);

    private final BrokerPort broker;
    private final PositionBook book;
    private final PositionLifecycle lifecycle;
    private final TradingClock clock;

    private final Map<UserId, Report> reports = new ConcurrentHashMap<>();

    /**
     * Last traded price per symbol. Supplied as a function so this class keeps no opinion about
     * where market data lives, the same way the position lifecycle takes its ATR.
     */
    private volatile java.util.function.ToDoubleFunction<String> lastPrice = symbol -> 0.0;

    public void setLastPriceSource(java.util.function.ToDoubleFunction<String> source) {
        this.lastPrice = source;
    }

    public Reconciler(BrokerPort broker, PositionBook book, PositionLifecycle lifecycle,
                      TradingClock clock) {
        this.broker = broker;
        this.book = book;
        this.lifecycle = lifecycle;
        this.clock = clock;
    }

    /**
     * What the last pass found.
     *
     * @param orphans     symbols the broker holds intraday that no engine position accounts for
     * @param failed      null unless the pass could not run, in which case it says why — a stale
     *                    clean report would be worse than none, because it reads as "all agreed"
     */
    public record Report(Instant at, int brokerOrders, int brokerPositions, int resolvedFills,
                         int adoptedEntries, int externalCloses, int abandoned,
                         List<String> orphans, String failed) {

        public static Report unavailable(Instant at, String why) {
            return new Report(at, 0, 0, 0, 0, 0, 0, List.of(), why);
        }

        /** True when the engine and the broker agree about everything that matters. */
        public boolean inAgreement() {
            return failed == null && orphans.isEmpty() && abandoned == 0;
        }

        public boolean actedOnAnything() {
            return resolvedFills + adoptedEntries + externalCloses + abandoned > 0;
        }
    }

    public Optional<Report> lastReport(UserId userId) {
        return Optional.ofNullable(reports.get(userId));
    }

    /**
     * One full pass for one user. Makes two HTTP calls, so it must not run on the feed thread.
     *
     * @param account the account, needed only so a fill that arrives after a halt is disowned the
     *                same way a live update would be (design note 0.3)
     */
    public Report reconcile(UserAccount account) {
        UserId userId = account.userId();
        Instant now = clock.now();

        if (!broker.isAuthenticated(userId)) {
            // Not signed in is the normal resting state before the morning login, not a fault. But
            // it must not leave yesterday's clean report standing as if it were current.
            Report report = Report.unavailable(now, "not connected to the broker");
            reports.put(userId, report);
            return report;
        }

        List<BrokerOrder> orders;
        List<BrokerPosition> brokerPositions;
        try {
            orders = broker.fetchOrders(userId);
            brokerPositions = broker.fetchPositions(userId);
        } catch (RuntimeException e) {
            log.warn("reconciliation for {} could not read broker state: {}", userId, e.getMessage());
            Report report = Report.unavailable(now, e.getMessage());
            reports.put(userId, report);
            return report;
        }

        int adopted = lifecycle.resolveUnconfirmed(userId, orders, GRACE).size();
        int resolved = replayMissedOrderUpdates(userId, orders, account);
        int abandoned = abandonEntriesTheBrokerNeverSaw(userId, orders, now);
        Outcome exposure = reconcileExposure(userId, brokerPositions, orders, now);

        Report report = new Report(now, orders.size(), brokerPositions.size(), resolved, adopted,
                exposure.externalCloses(), abandoned, exposure.orphans(), null);
        reports.put(userId, report);

        if (report.actedOnAnything() || !report.orphans().isEmpty()) {
            log.warn("reconciliation for {}: {} fill(s) recovered, {} entry adopted, {} closed "
                            + "externally, {} abandoned, orphans {}",
                    userId, resolved, adopted, exposure.externalCloses(), abandoned,
                    exposure.orphans());
        } else {
            log.debug("reconciliation for {}: engine and broker agree ({} orders, {} positions)",
                    userId, orders.size(), brokerPositions.size());
        }
        return report;
    }

    /**
     * Re-applies terminal order states the engine never saw.
     *
     * <p>Deliberately routed through {@link PositionLifecycle#onOrderUpdate}, the same entry point a
     * live websocket update uses, rather than transitioning positions here. A recovery path that
     * writes its own transitions is a second lifecycle owner, and it will drift from the real one
     * exactly where it matters — the paths that are only exercised when something has gone wrong.</p>
     */
    private int replayMissedOrderUpdates(UserId userId, List<BrokerOrder> orders,
                                         UserAccount account) {
        Map<String, BrokerOrder> byTag = orders.stream()
                .filter(o -> o.tag() != null && !o.tag().isBlank())
                .collect(Collectors.toMap(BrokerOrder::tag, o -> o, (a, b) ->
                        a.updatedAt() != null && b.updatedAt() != null
                                && a.updatedAt().isAfter(b.updatedAt()) ? a : b));

        int applied = 0;
        for (Position position : book.forUser(userId)) {
            if (position.status().isFinished()) continue;

            String tag = position.status() == PositionStatus.EXIT_PENDING
                    ? tagValue(position.exitTag())
                    : tagValue(position.entryTag());
            if (tag == null) continue;

            BrokerOrder order = byTag.get(tag);
            // UNKNOWN is left alone on purpose: onOrderUpdate refuses to guess, and so does this.
            if (order == null || !order.status().isTerminal()) continue;

            boolean stillUnapplied = position.status() == PositionStatus.PENDING_ENTRY
                    || (position.status() == PositionStatus.EXIT_PENDING
                        && order.status() == OrderStatus.COMPLETE);
            if (!stillUnapplied) continue;

            log.warn("reconciliation found {} {} at {} while the engine still had it {} — "
                            + "applying the update the engine missed",
                    userId, position.symbol(), order.status(), position.status());
            lifecycle.onOrderUpdate(userId, order, account);
            applied++;
        }
        return applied;
    }

    /**
     * Writes off entries the broker has no record of at all.
     *
     * <p>Kite returns every order placed today, rejected ones included, so a tag that is absent after
     * the grace period never reached the exchange. Marked ABANDONED rather than CLOSED — see
     * {@link PositionLifecycle#abandon}.</p>
     */
    private int abandonEntriesTheBrokerNeverSaw(UserId userId, List<BrokerOrder> orders,
                                                Instant now) {
        var tags = orders.stream().map(BrokerOrder::tag).filter(t -> t != null && !t.isBlank())
                .collect(Collectors.toSet());
        Instant cutoff = now.minus(GRACE);

        int abandoned = 0;
        for (Position position : book.pendingEntries(userId)) {
            String tag = tagValue(position.entryTag());
            if (tag == null || tags.contains(tag)) continue;
            if (position.openedAt() == null || position.openedAt().isAfter(cutoff)) continue;

            lifecycle.abandon(position, "no order with tag " + tag + " exists at the broker "
                    + GRACE.toSeconds() + "s after submission");
            abandoned++;
        }
        return abandoned;
    }

    /**
     * The price a position actually closed at, when the engine did not close it.
     *
     * <p>Preference order, and the order matters. A completed sell for this symbol in today's order
     * list is the broker's own record of the fill and is therefore the truth. Failing that the last
     * traded price is a fair approximation of a close that has just happened. Only with neither does
     * the caller fall back — and it now says so instead of booking the trade flat.</p>
     *
     * <p>Deliberately not matched on tag: this exists for exits the engine did not send, so there is
     * no engine tag to match. Quantity and side are what identify it.</p>
     */
    private double exitPriceFor(Position position, List<BrokerOrder> orders) {
        Optional<BrokerOrder> fill = orders.stream()
                .filter(o -> position.symbol().equals(o.symbol()))
                .filter(o -> o.status() == OrderStatus.COMPLETE)
                .filter(o -> o.side() != sideOfEntry(position))
                .filter(o -> o.filledQuantity() > 0 && o.averagePrice() > 0)
                .max(java.util.Comparator.comparing(
                        o -> o.updatedAt() == null ? Instant.EPOCH : o.updatedAt()));
        if (fill.isPresent()) return fill.get().averagePrice();

        return lastPrice.applyAsDouble(position.symbol());
    }

    private static com.equity.broker.OrderSide sideOfEntry(Position position) {
        return position.direction() == com.equity.domain.Direction.LONG
                ? com.equity.broker.OrderSide.BUY : com.equity.broker.OrderSide.SELL;
    }

    private record Outcome(int externalCloses, List<String> orphans) {}

    /**
     * Compares held shares, symbol by symbol.
     *
     * <p>Only intraday positions are considered. A CNC holding is the user's investment account and
     * has nothing to do with this engine; counting it would make every long-term holding look like
     * an orphan every sixty seconds.</p>
     */
    private Outcome reconcileExposure(UserId userId, List<BrokerPosition> brokerPositions,
                                      List<BrokerOrder> orders, Instant now) {
        Map<String, Integer> heldAtBroker = new HashMap<>();
        Map<String, Double> priceAtBroker = new HashMap<>();
        for (BrokerPosition bp : brokerPositions) {
            if (bp.product() != ProductType.MIS || bp.isFlat()) continue;
            heldAtBroker.merge(bp.symbol(), bp.quantity(), Integer::sum);
            priceAtBroker.put(bp.symbol(), bp.lastPrice());
        }

        int externalCloses = 0;
        Map<String, Integer> claimedByEngine = new HashMap<>();

        for (Position position : book.withExposure(userId)) {
            int held = heldAtBroker.getOrDefault(position.symbol(), 0);

            if (held == 0) {
                // The broker holds nothing here. Whatever closed it, the engine did not, and
                // leaving it OPEN would occupy a position slot and invite an exit for shares that
                // no longer exist.
                lifecycle.adoptExternalClose(position, exitPriceFor(position, orders),
                        "the broker reports no intraday position in " + position.symbol());
                externalCloses++;
                continue;
            }

            claimedByEngine.merge(position.symbol(), position.filledQuantity(), Integer::sum);

            if (Math.abs(held) < position.filledQuantity()) {
                // A partial close outside the engine. Not corrected automatically: adjusting the
                // quantity silently would change the rupees at risk on a position a human is
                // evidently already handling.
                log.error("QUANTITY MISMATCH {} {}: engine believes {} shares, broker holds {}. "
                                + "The stop will be sized wrongly — check the broker terminal.",
                        userId, position.symbol(), position.filledQuantity(), Math.abs(held));
            }
        }

        List<String> orphans = new ArrayList<>();
        for (var entry : heldAtBroker.entrySet()) {
            int unaccounted = Math.abs(entry.getValue())
                    - claimedByEngine.getOrDefault(entry.getKey(), 0);
            if (unaccounted <= 0) continue;

            // A position this engine closed moments ago is not an orphan; the broker's view simply
            // has not caught up. Without this the engine reports its own completed trade as a
            // holding somebody must deal with by hand.
            var closedAt = book.lastClosedAt(userId, entry.getKey());
            if (closedAt.isPresent() && closedAt.get().isAfter(now.minus(SETTLING))) {
                log.debug("{} still shows {} share(s) at the broker {}s after the engine closed it "
                        + "— within the settling window, not an orphan", entry.getKey(),
                        unaccounted, Duration.between(closedAt.get(), now).toSeconds());
                continue;
            }

            orphans.add(entry.getKey() + " x" + unaccounted);
            log.error("ORPHAN POSITION {} {}: the broker holds {} intraday share(s) this engine "
                            + "did not open. It will NOT be squared off by the engine — close it "
                            + "manually or it will be auto-squared by the broker.",
                    userId, entry.getKey(), unaccounted);
        }
        return new Outcome(externalCloses, orphans);
    }

    private static String tagValue(com.equity.domain.order.OrderTag tag) {
        return tag == null ? null : tag.value();
    }
}
