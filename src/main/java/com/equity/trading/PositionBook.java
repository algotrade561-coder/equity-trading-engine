package com.equity.trading;

import com.equity.domain.position.Position;
import com.equity.domain.position.PositionStatus;
import com.equity.domain.user.UserId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.ToDoubleFunction;
import org.springframework.stereotype.Component;

/**
 * Every position the engine believes it holds, per user.
 *
 * <p>A cache, not the truth. Design note 0.12: the broker is truth, the database is a log, and this
 * is memory — it can be wrong after any disconnect, and reconciliation resolves disagreements in
 * the broker's favour. It exists because the exit checks run on every tick and cannot make an HTTP
 * call to find out what is open.</p>
 *
 * <p>Positions are stored per user with no shared index by symbol, deliberately: two users holding
 * the same stock are two unrelated positions, and any structure that merged them would let one
 * user's exit close another user's shares.</p>
 */
@Component
public class PositionBook {

    private final PositionStore store;
    private final Map<UUID, Position> positions = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastClosedBySymbol = new ConcurrentHashMap<>();
    private final List<Consumer<Position>> closedListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<Position>> openedListeners = new CopyOnWriteArrayList<>();

    @org.springframework.beans.factory.annotation.Autowired
    public PositionBook(PositionStore store) {
        this.store = store;
    }

    /** Test constructor: memory only. */
    public PositionBook() {
        this(PositionStore.inMemory());
    }

    /**
     * Reloads positions that still held shares when the process stopped.
     *
     * <p>Called once at startup, before the feed is connected, so the exit machinery sees them on
     * the very first tick rather than after the first reconciliation poll.</p>
     */
    @jakarta.annotation.PostConstruct
    public void restore() {
        List<Position> open = store.loadOpen();
        open.forEach(p -> positions.put(p.id(), p));
        if (!open.isEmpty()) {
            org.slf4j.LoggerFactory.getLogger(PositionBook.class).warn(
                    "restored {} position(s) still holding shares from a previous run: {}",
                    open.size(), open.stream().map(Position::symbol).toList());
        }
    }

    /** Notified once per position when it reaches CLOSED. Used by the ledger and the strategy. */
    public void onClosed(Consumer<Position> listener) {
        closedListeners.add(listener);
    }

    /**
     * Notified once per position when its entry fills.
     *
     * <p>Fires only on the transition out of PENDING_ENTRY, so an exit that failed and put the
     * position back to OPEN does not read as a second fill.</p>
     */
    public void onOpened(Consumer<Position> listener) {
        openedListeners.add(listener);
    }

    public void put(Position position) {
        Position previous = positions.put(position.id(), position);
        store.save(position);
        boolean justOpened = position.status() == PositionStatus.OPEN
                && previous != null && previous.status() == PositionStatus.PENDING_ENTRY;
        if (justOpened) openedListeners.forEach(l -> l.accept(position));

        boolean justClosed = position.status() == PositionStatus.CLOSED
                && (previous == null || previous.status() != PositionStatus.CLOSED);
        if (justClosed) {
            // Guarded because this runs on the order-update thread: a position that reached CLOSED
            // without a timestamp used to throw here, turning a broker rejection into a lost update
            // and leaving the position stuck. The cooldown is worth less than the transition.
            if (position.closedAt() != null) {
                lastClosedBySymbol.put(key(position.userId(), position.symbol()),
                        position.closedAt());
            }
            closedListeners.forEach(l -> l.accept(position));
        }
    }

    public Optional<Position> byId(UUID id) {
        return Optional.ofNullable(positions.get(id));
    }

    public List<Position> forUser(UserId userId) {
        return positions.values().stream().filter(p -> p.userId().equals(userId)).toList();
    }

    /** Positions holding shares right now — OPEN or with an exit already working. */
    public List<Position> withExposure(UserId userId) {
        return positions.values().stream()
                .filter(p -> p.userId().equals(userId) && p.hasExposure()).toList();
    }

    public List<Position> pendingEntries(UserId userId) {
        return positions.values().stream()
                .filter(p -> p.userId().equals(userId) && p.status() == PositionStatus.PENDING_ENTRY)
                .toList();
    }

    /**
     * True if this user already has anything live in the symbol.
     *
     * <p>Includes a pending entry. Without that, a second signal arriving before the first fills
     * would double the intended size in a single move — which is exactly when a stock is moving
     * fast enough to produce two signals.</p>
     */
    public boolean hasLiveInterest(UserId userId, String symbol) {
        return positions.values().stream().anyMatch(p ->
                p.userId().equals(userId) && p.symbol().equals(symbol)
                        && (p.hasExposure() || p.status() == PositionStatus.PENDING_ENTRY));
    }

    public Optional<Instant> lastClosedAt(UserId userId, String symbol) {
        return Optional.ofNullable(lastClosedBySymbol.get(key(userId, symbol)));
    }

    /** Mark-to-market across everything this user holds, using the supplied price source. */
    public double unrealised(UserId userId, ToDoubleFunction<String> priceBySymbol) {
        double total = 0;
        for (Position p : withExposure(userId)) {
            double price = priceBySymbol.applyAsDouble(p.symbol());
            if (price > 0) total += p.unrealisedPnl(price);
        }
        return total;
    }

    public Collection<Position> all() { return new ArrayList<>(positions.values()); }

    public int openCount(UserId userId) { return withExposure(userId).size(); }

    private static String key(UserId userId, String symbol) { return userId + "|" + symbol; }
}
