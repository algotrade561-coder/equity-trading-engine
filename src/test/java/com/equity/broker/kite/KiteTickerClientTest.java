package com.equity.broker.kite;

import static org.assertj.core.api.Assertions.assertThat;

import com.equity.broker.SubscriptionMode;
import com.equity.domain.market.Tick;
import com.equity.domain.user.UserId;
import com.equity.platform.time.FixedTradingClock;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.ByteString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The streaming client against a real WebSocket server.
 *
 * <p>What matters here is the shape of the messages Kite is sent and the fact that subscriptions
 * survive a reconnect. A reconnect that comes back subscribed to nothing is the worst kind of
 * failure: the socket is up, the health check is green, and no instrument ever ticks again.</p>
 */
class KiteTickerClientTest {

    private static final UserId USER = UserId.random();
    private static final long RELIANCE = 738561L;
    private static final Instant NOW = Instant.parse("2026-09-04T04:00:00Z");

    private MockWebServer server;
    private KiteProperties props;
    private KiteTickerClient client;
    private OkHttpClient okHttp;

    /** Text frames the server received, in order. */
    private final CopyOnWriteArrayList<String> serverInbox = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Tick> ticks = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> disconnects = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        props = new KiteProperties();
        props.setWebsocketUrl(server.url("/").toString().replaceFirst("^http", "ws"));
        props.setReconnectDelay(Duration.ofMillis(50));
        props.setMaxReconnectDelay(Duration.ofMillis(50));
    }

    @AfterEach
    void tearDown() throws IOException {
        if (client != null) client.stop();
        // The client closes gracefully and then waits for the peer. These stub servers do not answer
        // a close handshake, so the sockets are dropped outright rather than leaving the suite
        // blocked on a server shutdown that never completes.
        if (okHttp != null) {
            okHttp.dispatcher().cancelAll();
            okHttp.connectionPool().evictAll();
            okHttp.dispatcher().executorService().shutdownNow();
        }
        server.shutdown();
    }

    private KiteTickerClient newClient(CountDownLatch connected) {
        okHttp = new OkHttpClient();
        return new KiteTickerClient(USER, props, okHttp, new FixedTradingClock(NOW),
                token -> token == RELIANCE ? "RELIANCE" : null,
                new KiteTickerClient.Events() {
                    @Override public void onTicks(List<Tick> t) { ticks.addAll(t); }
                    @Override public void onOrderUpdate(UserId userId, JsonNode payload) { }
                    @Override public void onConnected(UserId userId) { connected.countDown(); }
                    @Override public void onDisconnected(UserId userId, String reason, boolean retry) {
                        disconnects.add(reason);
                    }
                });
    }

    /**
     * A stub server that records text frames.
     *
     * <p>It answers the close handshake. Without that, {@code MockWebServer.shutdown()} waits for a
     * peer that never replies and the teardown fails intermittently — a test-harness problem, not a
     * client one, but an intermittent failure is worth removing rather than tolerating.</p>
     */
    private void enqueueRecordingServer() {
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override public void onMessage(WebSocket webSocket, String text) {
                serverInbox.add(text);
            }
            @Override public void onClosing(WebSocket webSocket, int code, String reason) {
                webSocket.close(1000, null);
            }
        }));
    }

    @Test
    void sendsKiteSubscribeAndModeMessagesInTheExpectedShape() throws Exception {
        CountDownLatch connected = new CountDownLatch(1);
        enqueueRecordingServer();
        client = newClient(connected);
        client.start("api_key", "access_token");
        assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue();

        client.subscribe(List.of(RELIANCE), SubscriptionMode.QUOTE);

        await(() -> serverInbox.size() >= 2);
        assertThat(serverInbox.get(0)).isEqualTo("{\"a\":\"subscribe\",\"v\":[738561]}");
        assertThat(serverInbox.get(1)).isEqualTo("{\"a\":\"mode\",\"v\":[\"quote\",[738561]]}");
    }

    @Test
    void promotingACandidateToFullSendsOnlyAModeChange() throws Exception {
        CountDownLatch connected = new CountDownLatch(1);
        enqueueRecordingServer();
        client = newClient(connected);
        client.start("api_key", "access_token");
        assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue();

        client.subscribe(List.of(RELIANCE), SubscriptionMode.QUOTE);
        await(() -> serverInbox.size() >= 2);
        serverInbox.clear();

        client.setMode(List.of(RELIANCE), SubscriptionMode.FULL);

        await(() -> !serverInbox.isEmpty());
        assertThat(serverInbox).containsExactly("{\"a\":\"mode\",\"v\":[\"full\",[738561]]}");
    }

    @Test
    void aModeChangeForSomethingUnsubscribedIsNotSent() throws Exception {
        CountDownLatch connected = new CountDownLatch(1);
        enqueueRecordingServer();
        client = newClient(connected);
        client.start("api_key", "access_token");
        assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue();

        client.setMode(List.of(999_999L), SubscriptionMode.FULL);

        Thread.sleep(200);
        assertThat(serverInbox)
                .as("Kite ignores it silently, leaving the caller expecting depth that never arrives")
                .isEmpty();
    }

    @Test
    void decodesBinaryFramesIntoTicks() throws Exception {
        CountDownLatch connected = new CountDownLatch(1);
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {
                webSocket.send(ByteString.of(ltpFrame(RELIANCE, 145_055)));
            }
            @Override public void onClosing(WebSocket webSocket, int code, String reason) {
                webSocket.close(1000, null);
            }
        }));
        client = newClient(connected);
        client.start("api_key", "access_token");
        assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue();

        await(() -> !ticks.isEmpty());
        assertThat(ticks.get(0).symbol()).isEqualTo("RELIANCE");
        assertThat(ticks.get(0).lastPrice()).isEqualTo(1450.55);
    }

    @Test
    void replaysSubscriptionsOntoTheSocketAfterAReconnect() throws Exception {
        CountDownLatch firstConnect = new CountDownLatch(1);

        // First connection: accepts the subscription, then drops.
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override public void onMessage(WebSocket webSocket, String text) {
                serverInbox.add("first:" + text);
                webSocket.close(1000, "bye");
            }
        }));
        // Second connection: records whatever the client replays on its own initiative.
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override public void onMessage(WebSocket webSocket, String text) {
                serverInbox.add("second:" + text);
            }
            @Override public void onClosing(WebSocket webSocket, int code, String reason) {
                webSocket.close(1000, null);
            }
        }));

        client = newClient(firstConnect);
        client.start("api_key", "access_token");
        assertThat(firstConnect.await(5, TimeUnit.SECONDS)).isTrue();

        client.subscribe(List.of(RELIANCE), SubscriptionMode.QUOTE);

        await(() -> serverInbox.stream().anyMatch(m -> m.startsWith("second:")));

        assertThat(serverInbox)
                .as("a reconnect that comes back subscribed to nothing looks healthy and ticks never arrive")
                .anyMatch(m -> m.startsWith("second:") && m.contains("\"a\":\"subscribe\"")
                        && m.contains("738561"));
        assertThat(serverInbox).anyMatch(m -> m.startsWith("second:") && m.contains("\"a\":\"mode\""));
        assertThat(disconnects).isNotEmpty();
        assertThat(client.subscribedTokens()).containsExactly(RELIANCE);
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(20);
        }
        throw new AssertionError("condition not met within 5s");
    }

    /** A one-packet LTP frame, matching the layout exercised in {@link KiteTickCodecTest}. */
    private static byte[] ltpFrame(long token, int paise) {
        ByteBuffer b = ByteBuffer.allocate(2 + 2 + 8).order(ByteOrder.BIG_ENDIAN);
        b.putShort((short) 1).putShort((short) 8).putInt((int) token).putInt(paise);
        return b.array();
    }
}
