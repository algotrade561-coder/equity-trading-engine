package com.equity.broker.kite;

import com.equity.broker.BrokerOrder;
import com.equity.broker.FeedStatusListener;
import com.equity.broker.MarketDataPort;
import com.equity.broker.OrderUpdateListener;
import com.equity.broker.OrderUpdatePort;
import com.equity.broker.SubscriptionMode;
import com.equity.broker.TickListener;
import com.equity.domain.market.Tick;
import com.equity.domain.user.UserId;
import com.equity.platform.time.TradingClock;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Owns every Kite streaming connection and presents one shared quote feed to the engine.
 *
 * <h2>Why one feed and several sockets</h2>
 * <p>Market data is user-independent: the specification says so, and paying for N copies of the same
 * 200-symbol stream would multiply bandwidth and decode cost by the number of users for no
 * information gain. Order postbacks are the opposite — they are tied to an access token and only
 * reach the socket that token opened.</p>
 *
 * <p>So exactly one connection, the primary, carries quote subscriptions and feeds the whole engine.
 * Every other authenticated user gets a connection subscribed to nothing, held open purely to
 * receive their own order updates. If the primary user's session goes away, another authenticated
 * user is promoted and the subscription set is replayed onto their socket, because a shared feed
 * that dies with one user's session is not shared.</p>
 */
@Component
public class KiteTickerManager implements MarketDataPort, OrderUpdatePort {

    private static final Logger log = LoggerFactory.getLogger(KiteTickerManager.class);

    private final KiteProperties properties;
    private final KiteHttp http;
    private final KiteSessionStore sessions;
    private final KiteCredentialsProvider credentials;
    private final KiteInstrumentMaster instruments;
    private final TradingClock clock;

    private final Map<UserId, KiteTickerClient> clients = new ConcurrentHashMap<>();
    private final List<TickListener> tickListeners = new CopyOnWriteArrayList<>();
    private final List<FeedStatusListener> feedListeners = new CopyOnWriteArrayList<>();
    private final List<OrderUpdateListener> orderListeners = new CopyOnWriteArrayList<>();

    /** The subscription set the engine asked for, kept so it can be replayed onto a new primary. */
    private final Map<String, SubscriptionMode> desired = new LinkedHashMap<>();

    private volatile UserId primary;
    private final Set<String> unresolved = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public KiteTickerManager(KiteProperties properties, KiteHttp http, KiteSessionStore sessions,
                             KiteCredentialsProvider credentials, KiteInstrumentMaster instruments,
                             TradingClock clock) {
        this.properties = properties;
        this.http = http;
        this.sessions = sessions;
        this.credentials = credentials;
        this.instruments = instruments;
        this.clock = clock;
    }

    /**
     * Opens a streaming connection for a user who has just logged in.
     *
     * <p>The first caller becomes the primary and inherits the quote subscriptions.</p>
     */
    public synchronized void connect(UserId userId) {
        if (!properties.isEnabled()) {
            log.info("kite integration disabled — not opening a ticker for user={}", userId);
            return;
        }
        KiteSession session = sessions.get(userId).orElseThrow(() ->
                new IllegalStateException("cannot open a ticker for user " + userId + " without a session"));
        KiteCredentials creds = credentials.require(userId);

        clients.computeIfPresent(userId, (id, existing) -> { existing.stop(); return null; });

        // The socket this user's postbacks arrive on must leave from the same address their REST
        // calls do — it is the same API key, registered to the same public IP. A ticker opened from
        // the default interface for a user whose key is pinned elsewhere is refused at the handshake.
        KiteTickerClient client = new KiteTickerClient(
                userId, properties, http.clientFor(creds), clock, instruments.symbolResolver(), new EventBridge());
        clients.put(userId, client);
        client.start(creds.apiKey(), session.accessToken());

        if (primary == null) {
            primary = userId;
            log.info("user={} is now the primary market-data connection", userId);
            replayDesiredSubscriptions();
        }
    }

    /**
     * Rebinds a user after their source address changed.
     *
     * <p>Two things hold the old address: the cached REST client, and the open ticker socket. Both
     * are replaced here, together, because replacing one and not the other is worse than replacing
     * neither — REST calls would leave from the new address while postbacks still arrived on a
     * socket bound to the old one, and the broker would see a key speaking from two places.</p>
     *
     * <p>The ticker is only reopened if it was open. A user with no session gets no socket, exactly
     * as before; the next {@link #connect} picks up the new address on its own.</p>
     */
    public synchronized void sourceIpChanged(UserId userId, String previousIp) {
        http.forgetClientFor(previousIp);
        if (clients.containsKey(userId)) {
            log.warn("source IP changed for user={} while their ticker was open — reconnecting it "
                    + "from the new address", userId);
            disconnect(userId);
            try {
                connect(userId);
            } catch (RuntimeException e) {
                log.error("could not reopen the ticker for user={} from the new address: {}. "
                        + "Their postbacks will not arrive until the next login.", userId, e.getMessage());
            }
        }
    }

    /** Closes a user's connection, promoting a replacement primary if this was the one carrying quotes. */
    public synchronized void disconnect(UserId userId) {
        KiteTickerClient client = clients.remove(userId);
        if (client != null) client.stop();
        if (userId.equals(primary)) {
            primary = clients.keySet().stream().findFirst().orElse(null);
            if (primary != null) {
                log.warn("primary market-data connection moved to user={}", primary);
                replayDesiredSubscriptions();
            } else {
                log.error("no authenticated user left — the market data feed is down");
            }
        }
    }

    @Override
    public synchronized void subscribe(Collection<String> symbols, SubscriptionMode mode) {
        symbols.forEach(s -> desired.put(s, mode));
        withPrimary(client -> client.subscribe(tokensFor(symbols), mode));
    }

    @Override
    public synchronized void setMode(Collection<String> symbols, SubscriptionMode mode) {
        symbols.forEach(s -> { if (desired.containsKey(s)) desired.put(s, mode); });
        withPrimary(client -> client.setMode(tokensFor(symbols), mode));
    }

    @Override
    public synchronized void unsubscribe(Collection<String> symbols) {
        symbols.forEach(desired::remove);
        withPrimary(client -> client.unsubscribe(tokensFor(symbols)));
    }

    @Override
    public void addTickListener(TickListener listener) { tickListeners.add(listener); }

    @Override
    public void addFeedStatusListener(FeedStatusListener listener) { feedListeners.add(listener); }

    @Override
    public void addOrderUpdateListener(OrderUpdateListener listener) { orderListeners.add(listener); }

    @Override
    public boolean isConnected() {
        KiteTickerClient client = primaryClient();
        return client != null && client.isConnected();
    }

    @Override
    public java.util.Collection<String> unresolvedSymbols() {
        return java.util.List.copyOf(unresolved);
    }

    /** Delegates to the instrument master, which is the only thing holding the NSE catalogue. */
    @Override
    public java.util.List<String> suggestionsFor(String tradingSymbol) {
        return instruments.suggestionsFor(tradingSymbol);
    }

    public UserId primaryUser() { return primary; }

    /** How many symbols the engine has asked for. Zero means the feed is up and carrying nothing. */
    public int subscribedSymbolCount() { return desired.size(); }

    /** How many consumers are attached. Zero means decoded ticks are being discarded. */
    public int tickListenerCount() { return tickListeners.size(); }

    @PreDestroy
    public synchronized void shutdown() {
        clients.values().forEach(KiteTickerClient::stop);
        clients.clear();
        primary = null;
    }

    private KiteTickerClient primaryClient() {
        UserId p = primary;
        return p == null ? null : clients.get(p);
    }

    private void withPrimary(java.util.function.Consumer<KiteTickerClient> action) {
        KiteTickerClient client = primaryClient();
        if (client == null) {
            // Not an error: the engine may build its watchlist before anyone has logged in. The
            // request is remembered in `desired` and applied the moment a primary appears.
            log.debug("no primary market-data connection yet — subscription recorded only");
            return;
        }
        action.accept(client);
    }

    private void replayDesiredSubscriptions() {
        if (desired.isEmpty()) return;
        Map<SubscriptionMode, List<String>> byMode = new java.util.EnumMap<>(SubscriptionMode.class);
        desired.forEach((symbol, mode) ->
                byMode.computeIfAbsent(mode, m -> new ArrayList<>()).add(symbol));
        byMode.forEach((mode, symbols) -> withPrimary(client -> client.subscribe(tokensFor(symbols), mode)));
    }

    /**
     * Resolves symbols to tokens, complaining loudly about any that are unknown.
     *
     * <p>A missing instrument is silent on the wire — Kite simply never sends that token — so the
     * engine would see a symbol that never ticks and treat it as an illiquid stock rather than as a
     * configuration error.</p>
     */
    private List<Long> tokensFor(Collection<String> symbols) {
        List<Long> tokens = new ArrayList<>(symbols.size());
        List<String> unknown = new ArrayList<>();
        for (String symbol : symbols) {
            instruments.tokenFor(symbol).ifPresentOrElse(tokens::add, () -> unknown.add(symbol));
        }
        if (!unknown.isEmpty()) {
            unresolved.addAll(unknown);
            log.error("no instrument token for {} symbol(s), they will never tick: {}",
                    unknown.size(), unknown);
        }
        return tokens;
    }

    /** Routes one client's callbacks to the manager's listener lists. */
    private final class EventBridge implements KiteTickerClient.Events {

        @Override
        public void onTicks(List<Tick> ticks) {
            for (TickListener listener : tickListeners) {
                try {
                    listener.onTicks(ticks);
                } catch (RuntimeException e) {
                    // One misbehaving consumer must not take the feed down for the others.
                    log.error("tick listener {} threw", listener.getClass().getSimpleName(), e);
                }
            }
        }

        @Override
        public void onOrderUpdate(UserId userId, JsonNode payload) {
            BrokerOrder order = KiteOrderMapper.toOrder(payload);
            for (OrderUpdateListener listener : orderListeners) {
                try {
                    listener.onOrderUpdate(userId, order);
                } catch (RuntimeException e) {
                    log.error("order listener {} threw", listener.getClass().getSimpleName(), e);
                }
            }
        }

        @Override
        public void onConnected(UserId userId) {
            if (!userId.equals(primary)) return;
            feedListeners.forEach(l -> l.onConnected(clock.now()));
        }

        @Override
        public void onDisconnected(UserId userId, String reason, boolean willRetry) {
            if (!userId.equals(primary)) return;
            feedListeners.forEach(l -> l.onDisconnected(clock.now(), reason, willRetry));
        }
    }
}
