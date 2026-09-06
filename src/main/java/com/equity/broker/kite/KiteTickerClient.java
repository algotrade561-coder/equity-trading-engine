package com.equity.broker.kite;

import com.equity.broker.SubscriptionMode;
import com.equity.domain.market.Tick;
import com.equity.domain.user.UserId;
import com.equity.platform.time.TradingClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongFunction;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One Kite streaming connection, belonging to one user.
 *
 * <p>Kite carries two unrelated things on this socket: binary market-data frames, which are the same
 * for everybody, and text order postbacks, which belong to whoever holds the access token. That is
 * why a connection is per user even though market data is shared — see {@link KiteTickerManager},
 * which opens exactly one connection for quotes and keeps the rest subscribed to nothing.</p>
 *
 * <h2>Reconnection</h2>
 * <p>Kite drops idle or slow clients, and a dropped feed is indistinguishable from a quiet market
 * unless something says so. This client reconnects with exponential backoff, replays its
 * subscriptions on the new socket, and reports both edges to a status listener so the engine can
 * suspend entries while the feed is down rather than acting on prices that stopped updating.</p>
 *
 * <p>A silence watchdog runs alongside: Kite heartbeats roughly every second even outside market
 * hours, so a socket that is open but silent past the configured timeout is dead in a way that
 * generates no callback at all, and must be recycled deliberately.</p>
 */
public class KiteTickerClient {

    private static final Logger log = LoggerFactory.getLogger(KiteTickerClient.class);
    private static final int NORMAL_CLOSURE = 1000;

    /** Callbacks out of the client. Kept as one interface so the manager wires a connection in one place. */
    public interface Events {
        void onTicks(List<Tick> ticks);
        void onOrderUpdate(UserId userId, JsonNode payload);
        void onConnected(UserId userId);
        void onDisconnected(UserId userId, String reason, boolean willRetry);
    }

    private final UserId userId;
    private final KiteProperties properties;
    private final OkHttpClient httpClient;
    private final TradingClock clock;
    private final LongFunction<String> symbolResolver;
    private final Events events;
    private final ObjectMapper json = new ObjectMapper();

    private final ScheduledExecutorService scheduler;
    private final Map<Long, SubscriptionMode> subscriptions = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicLong lastMessageNanos = new AtomicLong(System.nanoTime());

    private volatile WebSocket socket;
    private volatile long reconnectDelayMillis;
    private volatile String accessToken;
    private volatile String apiKey;

    public KiteTickerClient(UserId userId, KiteProperties properties, OkHttpClient httpClient,
                            TradingClock clock, LongFunction<String> symbolResolver, Events events) {
        this.userId = userId;
        this.properties = properties;
        this.httpClient = httpClient;
        this.clock = clock;
        this.symbolResolver = symbolResolver;
        this.events = events;
        this.reconnectDelayMillis = properties.getReconnectDelay().toMillis();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "kite-ticker-" + shortId(userId));
            t.setDaemon(true);
            return t;
        });
    }

    public UserId userId() { return userId; }

    public boolean isConnected() { return connected.get(); }

    /** Opens the socket and keeps it open until {@link #stop()}. Safe to call twice. */
    public void start(String apiKey, String accessToken) {
        this.apiKey = apiKey;
        this.accessToken = accessToken;
        if (!running.compareAndSet(false, true)) return;
        scheduler.scheduleAtFixedRate(this::checkSilence, 5, 5, TimeUnit.SECONDS);
        connect();
    }

    public void stop() {
        running.set(false);
        WebSocket s = socket;
        if (s != null) s.close(NORMAL_CLOSURE, "shutdown");
        scheduler.shutdownNow();
    }

    public void subscribe(Collection<Long> tokens, SubscriptionMode mode) {
        if (tokens.isEmpty()) return;
        tokens.forEach(t -> subscriptions.put(t, mode));
        send(subscribeMessage(tokens));
        send(modeMessage(tokens, mode));
    }

    public void setMode(Collection<Long> tokens, SubscriptionMode mode) {
        if (tokens.isEmpty()) return;
        // Only tokens we already hold: a mode change for something unsubscribed is silently ignored
        // by Kite, which would leave the caller believing it had depth it will never receive.
        List<Long> known = tokens.stream().filter(subscriptions::containsKey).toList();
        if (known.isEmpty()) return;
        known.forEach(t -> subscriptions.put(t, mode));
        send(modeMessage(known, mode));
    }

    public void unsubscribe(Collection<Long> tokens) {
        if (tokens.isEmpty()) return;
        tokens.forEach(subscriptions::remove);
        send(unsubscribeMessage(tokens));
    }

    public Set<Long> subscribedTokens() {
        return new LinkedHashSet<>(subscriptions.keySet());
    }

    private void connect() {
        if (!running.get()) return;
        String url = properties.getWebsocketUrl() + "?api_key=" + apiKey + "&access_token=" + accessToken;
        Request request = new Request.Builder().url(url).build();
        // The URL carries the access token as a query parameter, which is what Kite requires. It is
        // therefore never logged: log the user, not the endpoint.
        log.info("kite ticker connecting user={}", userId);
        socket = httpClient.newWebSocket(request, new Listener());
    }

    private void scheduleReconnect(String reason) {
        if (!running.get()) return;
        long delay = reconnectDelayMillis;
        reconnectDelayMillis = Math.min(delay * 2, properties.getMaxReconnectDelay().toMillis());
        log.warn("kite ticker down user={} reason={} reconnecting in {}ms", userId, reason, delay);
        try {
            scheduler.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            log.info("kite ticker reconnect abandoned for user={} — client is shutting down", userId);
        }
    }

    /**
     * Forces a reconnect when the socket is open but has gone quiet.
     *
     * <p>Uses {@link System#nanoTime()} rather than the trading clock on purpose: this measures real
     * elapsed time on a live socket, and under REPLAY the trading clock is tape time, which would
     * make the watchdog fire at nonsensical moments or never at all.</p>
     */
    private void checkSilence() {
        if (!running.get() || !connected.get()) return;
        long silentMillis = (System.nanoTime() - lastMessageNanos.get()) / 1_000_000L;
        if (silentMillis > properties.getFeedSilenceTimeout().toMillis()) {
            log.warn("kite feed silent for {}ms user={} — recycling the socket", silentMillis, userId);
            connected.set(false);
            WebSocket s = socket;
            if (s != null) s.cancel();
            events.onDisconnected(userId, "feed silent for " + silentMillis + "ms", true);
            scheduleReconnect("silence");
        }
    }

    private void send(String message) {
        WebSocket s = socket;
        if (s == null || !connected.get()) return;   // replayed on reconnect from `subscriptions`
        s.send(message);
    }

    private void resubscribeAll() {
        if (subscriptions.isEmpty()) return;
        Map<SubscriptionMode, List<Long>> byMode = new java.util.EnumMap<>(SubscriptionMode.class);
        subscriptions.forEach((token, mode) ->
                byMode.computeIfAbsent(mode, m -> new ArrayList<>()).add(token));
        byMode.forEach((mode, tokens) -> {
            send(subscribeMessage(tokens));
            send(modeMessage(tokens, mode));
        });
        log.info("kite ticker resubscribed {} instruments for user={}", subscriptions.size(), userId);
    }

    private static String subscribeMessage(Collection<Long> tokens) {
        return "{\"a\":\"subscribe\",\"v\":[" + joinTokens(tokens) + "]}";
    }

    private static String unsubscribeMessage(Collection<Long> tokens) {
        return "{\"a\":\"unsubscribe\",\"v\":[" + joinTokens(tokens) + "]}";
    }

    private static String modeMessage(Collection<Long> tokens, SubscriptionMode mode) {
        return "{\"a\":\"mode\",\"v\":[\"" + mode.wireName() + "\",[" + joinTokens(tokens) + "]]}";
    }

    private static String joinTokens(Collection<Long> tokens) {
        StringBuilder sb = new StringBuilder();
        for (Long t : tokens) {
            if (sb.length() > 0) sb.append(',');
            sb.append(t.longValue());
        }
        return sb.toString();
    }

    private static String shortId(UserId userId) {
        String s = userId.toString();
        return s.length() > 8 ? s.substring(0, 8) : s;
    }

    private final class Listener extends WebSocketListener {

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            connected.set(true);
            lastMessageNanos.set(System.nanoTime());
            reconnectDelayMillis = properties.getReconnectDelay().toMillis();
            log.info("kite ticker connected user={}", userId);
            resubscribeAll();
            events.onConnected(userId);
        }

        @Override
        public void onMessage(WebSocket webSocket, ByteString bytes) {
            lastMessageNanos.set(System.nanoTime());
            List<Tick> ticks = KiteTickCodec.decode(bytes.toByteArray(), symbolResolver, clock.now());
            if (!ticks.isEmpty()) events.onTicks(ticks);
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            lastMessageNanos.set(System.nanoTime());
            try {
                JsonNode node = json.readTree(text);
                String type = node.path("type").asText("");
                if ("order".equals(type)) {
                    events.onOrderUpdate(userId, node.path("data"));
                } else if ("error".equals(type)) {
                    log.warn("kite ticker error for user={}: {}", userId, node.path("data").asText(""));
                }
            } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
                log.warn("unparseable ticker text message for user={} ({})", userId, e.getClass().getSimpleName());
            }
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
            connected.set(false);
            webSocket.close(NORMAL_CLOSURE, null);
            events.onDisconnected(userId, "closed: " + reason, running.get());
            scheduleReconnect("closed " + code);
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            connected.set(false);
            String reason = t.getClass().getSimpleName();
            events.onDisconnected(userId, reason, running.get());
            scheduleReconnect(reason);
        }
    }
}
