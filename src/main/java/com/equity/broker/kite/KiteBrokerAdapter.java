package com.equity.broker.kite;

import com.equity.app.EngineProperties;
import com.equity.app.ExecutionMode;
import com.equity.broker.BrokerException;
import com.equity.broker.BrokerOrder;
import com.equity.broker.BrokerPort;
import com.equity.broker.BrokerPosition;
import com.equity.broker.OrderRequest;
import com.equity.domain.user.UserId;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link BrokerPort} over the Kite Connect REST API.
 *
 * <h2>What this class refuses to do</h2>
 * <p>It will not send anything to the exchange while the engine is in REPLAY, and it will not send
 * anything while the Kite integration is disabled. Both checks live here, at the last point before
 * the wire, rather than only in the strategy: a replay that reaches a live broker is not a bug you
 * get to fix afterwards.</p>
 *
 * <p><b>It deliberately does not check the {@code tradingEnabled} kill switch.</b> That switch stops
 * new risk being taken; exits must keep working while it is off. Design note 0.1 — a halt that also
 * blocks the exit path converts a bad day into an unhedged overnight position, which is the sibling
 * engine failure this design exists to avoid. Entry permission belongs to the entry path, one layer
 * up, where the difference between opening and closing is known.</p>
 */
@Component
public class KiteBrokerAdapter implements BrokerPort {

    private static final Logger log = LoggerFactory.getLogger(KiteBrokerAdapter.class);

    private final KiteHttp http;
    private final KiteSessionStore sessions;
    private final KiteCredentialsProvider credentials;
    private final KiteProperties kiteProperties;
    private final EngineProperties engineProperties;

    public KiteBrokerAdapter(KiteHttp http, KiteSessionStore sessions,
                             KiteCredentialsProvider credentials, KiteProperties kiteProperties,
                             EngineProperties engineProperties) {
        this.http = http;
        this.sessions = sessions;
        this.credentials = credentials;
        this.kiteProperties = kiteProperties;
        this.engineProperties = engineProperties;
    }

    @Override
    public String placeOrder(UserId userId, OrderRequest request) {
        guardOutboundOrder();
        Map<String, String> form = KiteOrderMapper.toForm(
                request, kiteProperties.getMarketProtectionPercent());

        // The tag is logged, the token is not. This line is the audit trail for a submission.
        log.info("kite order submit user={} {} {} x{} {} {} {} tag={}",
                userId, request.side(), request.symbol(), request.quantity(),
                request.type(), request.product(), request.variety(), request.tag());

        String path = "/orders/" + request.variety().path();
        JsonNode data = call(userId, (creds, token) -> http.postForm(path, form, creds, token));
        String orderId = data.path("order_id").asText("");
        if (orderId.isBlank()) {
            throw new BrokerException("kite accepted the order but returned no order_id",
                    "DataException", false);
        }
        return orderId;
    }

    @Override
    public void cancelOrder(UserId userId, String brokerOrderId) {
        cancelOrder(userId, brokerOrderId, com.equity.broker.OrderVariety.REGULAR);
    }

    @Override
    public void cancelOrder(UserId userId, String brokerOrderId,
                            com.equity.broker.OrderVariety variety) {
        guardOutboundOrder();
        call(userId, (creds, token) ->
                http.delete("/orders/" + variety.path() + "/" + brokerOrderId, Map.of(), creds, token));
    }

    @Override
    public void modifyOrder(UserId userId, String brokerOrderId, int quantity, double limitPrice) {
        guardOutboundOrder();
        Map<String, String> form = KiteHttp.form();
        form.put("quantity", Integer.toString(quantity));
        if (limitPrice > 0) {
            form.put("order_type", "LIMIT");
            form.put("price", String.format("%.2f", limitPrice));
        }
        call(userId, (creds, token) ->
                http.putForm("/orders/regular/" + brokerOrderId, form, creds, token));
    }

    @Override
    public List<BrokerOrder> fetchOrders(UserId userId) {
        JsonNode data = call(userId, (creds, token) -> http.get("/orders", creds, token));
        List<BrokerOrder> out = new ArrayList<>();
        data.forEach(n -> out.add(KiteOrderMapper.toOrder(n)));
        return out;
    }

    @Override
    public List<BrokerPosition> fetchPositions(UserId userId) {
        JsonNode data = call(userId, (creds, token) -> http.get("/portfolio/positions", creds, token));
        List<BrokerPosition> out = new ArrayList<>();
        // "net" rather than "day": net is the position that actually exists, which is what a square-off
        // has to act on. "day" excludes anything carried in, and acting on it would leave that behind.
        data.path("net").forEach(n -> out.add(KiteOrderMapper.toPosition(n)));
        return out;
    }

    @Override
    public double availableMargin(UserId userId) {
        JsonNode data = call(userId, (creds, token) -> http.get("/user/margins/equity", creds, token));
        JsonNode available = data.path("available");
        if (available.has("live_balance")) return available.path("live_balance").asDouble(0);
        return available.path("cash").asDouble(0);
    }

    /**
     * Asks Kite what it would block for this order.
     *
     * <p>{@code POST /margins/orders} answers with the real requirement for the actual scrip and
     * product, which is the only trustworthy source: intraday leverage differs per stock, is capped
     * by regulation, and Zerodha revises it without telling anyone. The response also carries the
     * effective leverage, which is worth logging the first time an operator wonders why a position
     * was allowed.</p>
     *
     * <p>Returns NaN rather than throwing when it cannot be determined. A margin lookup failing is
     * not a reason to abandon an entry outright, but it is a reason for the caller to fall back to
     * the notional and err towards refusing.</p>
     */
    @Override
    public double requiredMargin(UserId userId, OrderRequest request) {
        String json = String.format(
                "[{\"exchange\":\"%s\",\"tradingsymbol\":\"%s\",\"transaction_type\":\"%s\","
                + "\"variety\":\"%s\",\"product\":\"%s\",\"order_type\":\"%s\","
                + "\"quantity\":%d,\"price\":%s,\"trigger_price\":0}]",
                request.exchange(), request.symbol(), request.side().name(),
                request.variety().path(), request.product().name(),
                KiteOrderMapper.wireOrderType(request.type()), request.quantity(),
                String.format("%.2f", Math.max(request.limitPrice(), 0)));

        try {
            JsonNode data = call(userId, (creds, token) ->
                    http.postJson("/margins/orders", json, creds, token));
            if (!data.isArray() || data.isEmpty()) return Double.NaN;

            JsonNode first = data.get(0);
            double total = first.path("total").asDouble(Double.NaN);
            if (Double.isNaN(total) || total <= 0) return Double.NaN;

            log.info("kite margin for {} x{} {}: {} required (leverage {})",
                    request.symbol(), request.quantity(), request.product(),
                    String.format("%.0f", total), first.path("leverage").asText("?"));
            return total;

        } catch (RuntimeException e) {
            log.warn("could not read the margin requirement for {} x{}: {} — falling back to the "
                    + "full notional, which refuses more than it should rather than less",
                    request.symbol(), request.quantity(), e.getMessage());
            return Double.NaN;
        }
    }

    @Override
    public boolean isAuthenticated(UserId userId) {
        return sessions.isAuthenticated(userId);
    }

    @FunctionalInterface
    private interface KiteCall {
        JsonNode run(KiteCredentials credentials, String accessToken);
    }

    /** Resolves this user's own credentials and token, so no call can borrow another user's session. */
    private JsonNode call(UserId userId, KiteCall call) {
        KiteSession session = sessions.get(userId).orElseThrow(() -> new BrokerException(
                "user " + userId + " has no valid Kite session", "TokenException", false));
        KiteCredentials creds = credentials.require(userId);
        return call.run(creds, session.accessToken());
    }

    private void guardOutboundOrder() {
        if (engineProperties.getMode() != ExecutionMode.LIVE) {
            throw new BrokerException(
                    "refusing to send an order to Kite while the engine is in " + engineProperties.getMode(),
                    "ModeException", false);
        }
        if (!kiteProperties.isEnabled()) {
            throw new BrokerException("refusing to send an order: the Kite integration is disabled",
                    "ConfigException", false);
        }
    }
}
