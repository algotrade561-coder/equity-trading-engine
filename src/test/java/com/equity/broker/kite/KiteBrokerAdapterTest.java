package com.equity.broker.kite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.equity.app.EngineProperties;
import com.equity.app.ExecutionMode;
import com.equity.broker.BrokerException;
import com.equity.broker.OrderRequest;
import com.equity.broker.OrderSide;
import com.equity.broker.OrderStatus;
import com.equity.domain.user.UserId;
import com.equity.platform.time.FixedTradingClock;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Order placement against a real HTTP server rather than a mocked client, so what gets asserted is
 * the request Kite would actually receive.
 */
class KiteBrokerAdapterTest {

    private static final UserId USER = UserId.random();
    private static final Instant NOW = Instant.parse("2026-09-04T04:00:00Z");   // 09:30 IST

    private MockWebServer server;
    private KiteProperties kiteProps;
    private EngineProperties engineProps;
    private KiteSessionStore sessions;
    private KiteBrokerAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        kiteProps = new KiteProperties();
        kiteProps.setRestUrl(server.url("").toString().replaceAll("/$", ""));
        kiteProps.setApiKey("test_api_key");
        kiteProps.setApiSecret("test_api_secret");
        kiteProps.setEnabled(true);
        kiteProps.setMarketProtectionPercent(3.0);

        engineProps = new EngineProperties();
        engineProps.setMode(ExecutionMode.LIVE);

        FixedTradingClock clock = new FixedTradingClock(NOW);
        sessions = new KiteSessionStore(clock);
        sessions.put(new KiteSession(USER, "AB1234", "test_access_token", "pub",
                LocalDate.of(2026, 9, 4), NOW));

        KiteHttp http = new KiteHttp(kiteProps);
        adapter = new KiteBrokerAdapter(http, sessions,
                new KiteCredentialsProvider(kiteProps),
                new KiteInstrumentMaster(kiteProps, http, new KiteCredentialsProvider(kiteProps),
                        sessions),
                kiteProps, engineProps);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void placesAnOrderWithTheAuthHeaderAndFormKiteExpects() throws Exception {
        server.enqueue(json("{\"status\":\"success\",\"data\":{\"order_id\":\"250904000123\"}}"));

        String orderId = adapter.placeOrder(USER,
                OrderRequest.limit("RELIANCE", OrderSide.BUY, 10, 1450.55, "eq-1"));

        assertThat(orderId).isEqualTo("250904000123");

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/orders/regular");
        assertThat(request.getHeader("Authorization")).isEqualTo("token test_api_key:test_access_token");
        assertThat(request.getHeader("X-Kite-Version")).isEqualTo("3");

        String body = request.getBody().readUtf8();
        assertThat(body).contains("tradingsymbol=RELIANCE")
                .contains("transaction_type=BUY")
                .contains("order_type=LIMIT")
                .contains("price=1450.55")
                .contains("quantity=10")
                .contains("tag=eq-1");
    }

    @Test
    void aMarketOrderReachesKiteWithItsProtectionBand() throws Exception {
        server.enqueue(json("{\"status\":\"success\",\"data\":{\"order_id\":\"1\"}}"));

        adapter.placeOrder(USER, OrderRequest.market("RELIANCE", OrderSide.BUY, 1, "eq-1"));

        assertThat(server.takeRequest().getBody().readUtf8())
                .as("Kite refuses an API market order that does not carry one")
                .contains("market_protection=3.00");
    }

    @Test
    void refusesToSendAnythingWhileTheEngineIsInReplay() {
        engineProps.setMode(ExecutionMode.REPLAY);

        assertThatThrownBy(() -> adapter.placeOrder(USER,
                OrderRequest.market("RELIANCE", OrderSide.BUY, 10, "eq-1")))
                .as("a replay reaching a live broker is not a bug you get to fix afterwards")
                .isInstanceOf(BrokerException.class)
                .hasMessageContaining("REPLAY");

        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void refusesToSendWhileTheKiteIntegrationIsDisabled() {
        kiteProps.setEnabled(false);

        assertThatThrownBy(() -> adapter.placeOrder(USER,
                OrderRequest.market("RELIANCE", OrderSide.BUY, 10, "eq-1")))
                .isInstanceOf(BrokerException.class)
                .hasMessageContaining("disabled");

        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void refusesToActForAUserWithNoSession() {
        assertThatThrownBy(() -> adapter.placeOrder(UserId.random(),
                OrderRequest.market("RELIANCE", OrderSide.BUY, 10, "eq-1")))
                .as("borrowing another user's token is the failure this port exists to prevent")
                .isInstanceOf(BrokerException.class)
                .hasMessageContaining("no valid Kite session");

        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void anExchangeRejectionIsNotRetryable() {
        server.enqueue(json(400,
                "{\"status\":\"error\",\"error_type\":\"InputException\",\"message\":\"Insufficient funds\"}"));

        assertThatThrownBy(() -> adapter.placeOrder(USER,
                OrderRequest.market("RELIANCE", OrderSide.BUY, 10, "eq-1")))
                .isInstanceOfSatisfying(BrokerException.class, e -> {
                    assertThat(e.errorType()).isEqualTo("InputException");
                    assertThat(e.isRetryable())
                            .as("resending a rejected order is how one rejection becomes a hundred")
                            .isFalse();
                })
                .hasMessageContaining("Insufficient funds");
    }

    @Test
    void throttlingIsRetryable() {
        server.enqueue(json(429,
                "{\"status\":\"error\",\"error_type\":\"NetworkException\",\"message\":\"Too many requests\"}"));

        assertThatThrownBy(() -> adapter.fetchOrders(USER))
                .isInstanceOfSatisfying(BrokerException.class, e -> assertThat(e.isRetryable()).isTrue());
    }

    @Test
    void errorMessagesCarryNoCredentials() {
        server.enqueue(json(403,
                "{\"status\":\"error\",\"error_type\":\"TokenException\",\"message\":\"Invalid session\"}"));

        assertThatThrownBy(() -> adapter.fetchPositions(USER))
                .isInstanceOf(BrokerException.class)
                .satisfies(e -> assertThat(e.getMessage())
                        .as("spec 37: no credential may reach a log line or an audit row")
                        .doesNotContain("test_access_token")
                        .doesNotContain("test_api_secret"));
    }

    @Test
    void fetchesOrdersAsTheReconciliationSourceOfTruth() throws Exception {
        server.enqueue(json("""
                {"status":"success","data":[
                  {"order_id":"1","tradingsymbol":"RELIANCE","transaction_type":"BUY",
                   "status":"COMPLETE","quantity":10,"filled_quantity":10,"average_price":1450.55,"tag":"eq-1"},
                  {"order_id":"2","tradingsymbol":"TCS","transaction_type":"SELL",
                   "status":"OPEN","quantity":5,"filled_quantity":0,"tag":"eq-2"}]}
                """));

        var orders = adapter.fetchOrders(USER);

        assertThat(orders).hasSize(2);
        assertThat(orders.get(0).status()).isEqualTo(OrderStatus.COMPLETE);
        assertThat(orders.get(1).status()).isEqualTo(OrderStatus.OPEN);
        assertThat(server.takeRequest().getPath()).isEqualTo("/orders");
    }

    @Test
    void readsNetPositionsNotDayPositions() throws Exception {
        server.enqueue(json("""
                {"status":"success","data":{
                  "day":[{"tradingsymbol":"WRONG","quantity":99,"product":"MIS"}],
                  "net":[{"tradingsymbol":"RELIANCE","quantity":10,"average_price":1450.0,
                          "last_price":1455.0,"realised":0.0,"unrealised":50.0,"product":"MIS"}]}}
                """));

        var positions = adapter.fetchPositions(USER);

        assertThat(positions).hasSize(1);
        assertThat(positions.get(0).symbol())
                .as("day positions exclude anything carried in, so a square-off would leave it behind")
                .isEqualTo("RELIANCE");
    }

    @Test
    void readsAvailableIntradayCash() {
        server.enqueue(json(
                "{\"status\":\"success\",\"data\":{\"available\":{\"live_balance\":125000.5,\"cash\":90000.0}}}"));

        assertThat(adapter.availableMargin(USER)).isEqualTo(125000.5);
    }

    @Test
    void cancelUsesTheOrderPathAndTheDeleteVerb() throws Exception {
        server.enqueue(json("{\"status\":\"success\",\"data\":{\"order_id\":\"250904000123\"}}"));

        adapter.cancelOrder(USER, "250904000123");

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("DELETE");
        assertThat(request.getPath()).isEqualTo("/orders/regular/250904000123");
    }

    // ── Intraday margin ──────────────────────────────────────────────────────

    /**
     * The leverage is asked for, not assumed.
     *
     * <p>An MIS position blocks a fraction of the share price, the fraction differs per scrip, and
     * Zerodha revises it without notice. Kite answers with the figure for the exact order, so that is
     * what the risk gate uses in place of the notional.</p>
     */
    @Test
    void readsTheIntradayMarginKiteWouldActuallyBlock() throws Exception {
        server.enqueue(json("{\"status\":\"success\",\"data\":[{\"type\":\"equity\","
                + "\"tradingsymbol\":\"RELIANCE\",\"exchange\":\"NSE\",\"span\":0,"
                + "\"exposure\":0,\"total\":19830.0,\"leverage\":5}]}"));

        double required = adapter.requiredMargin(USER,
                OrderRequest.market("RELIANCE", OrderSide.BUY, 75, "tag-margin"));

        assertThat(required).isEqualTo(19_830.0);

        RecordedRequest sent = server.takeRequest();
        assertThat(sent.getPath()).isEqualTo("/margins/orders");
        String body = sent.getBody().readUtf8();
        assertThat(body)
                .as("the product is what makes the answer leveraged; asking about CNC would return "
                        + "the full notional and reintroduce the bug this exists to fix")
                .contains("\"product\":\"MIS\"")
                .contains("\"tradingsymbol\":\"RELIANCE\"")
                .contains("\"quantity\":75");
        assertThat(sent.getHeader("Content-Type")).startsWith("application/json");
    }

    @Test
    void aMarginLookupThatFailsReportsNotANumberRatherThanZero() {
        server.enqueue(json(500, "{\"status\":\"error\",\"message\":\"gateway\"}"));

        double required = adapter.requiredMargin(USER,
                OrderRequest.market("RELIANCE", OrderSide.BUY, 75, "tag-margin"));

        assertThat(Double.isNaN(required))
                .as("zero would read as 'this position is free to hold' and authorise anything")
                .isTrue();
    }

    private static MockResponse json(String body) {
        return json(200, body);
    }

    private static MockResponse json(int code, String body) {
        return new MockResponse().setResponseCode(code)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }
}
