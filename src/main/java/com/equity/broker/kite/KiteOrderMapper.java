package com.equity.broker.kite;

import com.equity.broker.BrokerOrder;
import com.equity.broker.BrokerPosition;
import com.equity.broker.OrderRequest;
import com.equity.broker.OrderSide;
import com.equity.broker.OrderStatus;
import com.equity.broker.OrderType;
import com.equity.broker.ProductType;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Translation between the vendor-neutral order model and the Kite wire format.
 *
 * <p>Separated from the adapter so it can be tested without a socket. The status mapping in
 * particular is worth testing directly: Kite has around a dozen status strings, several of which
 * mean "still working" in wording that reads like a failure, and mapping one of those to a terminal
 * status would let the position lifecycle act on an order that is still live.</p>
 */
final class KiteOrderMapper {

    /** Kite stamps order timestamps in IST with no zone marker. */
    private static final DateTimeFormatter KITE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Kite truncates a longer tag silently, which would break the idempotency match. */
    static final int MAX_TAG_LENGTH = 20;

    private KiteOrderMapper() {}

    static Map<String, String> toForm(OrderRequest r) {
        return toForm(r, 0);
    }

    /**
     * @param marketProtectionPercent added to MARKET orders; Kite rejects them without it. Zero
     *                                omits the field, which reproduces that rejection.
     */
    static Map<String, String> toForm(OrderRequest r, double marketProtectionPercent) {
        if (r.tag().length() > MAX_TAG_LENGTH) {
            throw new IllegalArgumentException(
                    "order tag exceeds the " + MAX_TAG_LENGTH + " characters Kite accepts: " + r.tag());
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("tradingsymbol", r.symbol());
        form.put("exchange", r.exchange());
        form.put("transaction_type", r.side().name());
        form.put("order_type", wireOrderType(r.type()));
        form.put("quantity", Integer.toString(r.quantity()));
        form.put("product", r.product().name());
        form.put("validity", "DAY");
        form.put("tag", r.tag());
        if (r.type() == OrderType.MARKET && marketProtectionPercent > 0) {
            // Not decoration: without it Kite answers "Market orders without market protection are
            // not allowed via API", so every entry and every exit this engine sends would fail.
            form.put("market_protection", trimmed(marketProtectionPercent));
        }
        if (r.type() == OrderType.LIMIT) {
            form.put("price", trimmed(r.limitPrice()));
        }
        if (r.type() == OrderType.SL_M) {
            form.put("trigger_price", trimmed(r.triggerPrice()));
        }
        return form;
    }

    static String wireOrderType(OrderType type) {
        return switch (type) {
            case MARKET -> "MARKET";
            case LIMIT -> "LIMIT";
            case SL_M -> "SL-M";
        };
    }

    /**
     * Maps a Kite status string.
     *
     * <p>Anything unrecognised becomes {@link OrderStatus#UNKNOWN}, never a terminal state. Kite adds
     * status strings from time to time, and the safe reading of an unfamiliar one is that we do not
     * know what happened to the order — which forces a reconciliation query rather than an action.</p>
     */
    static OrderStatus status(String kiteStatus) {
        if (kiteStatus == null) return OrderStatus.UNKNOWN;
        String s = kiteStatus.trim().toUpperCase();
        return switch (s) {
            case "COMPLETE" -> OrderStatus.COMPLETE;
            case "REJECTED" -> OrderStatus.REJECTED;
            case "CANCELLED", "CANCELLED AMO" -> OrderStatus.CANCELLED;
            case "OPEN", "TRIGGER PENDING" -> OrderStatus.OPEN;
            case "PUT ORDER REQ RECEIVED", "VALIDATION PENDING", "OPEN PENDING",
                 "MODIFY VALIDATION PENDING", "MODIFY PENDING", "CANCEL PENDING",
                 "AMO REQ RECEIVED" -> OrderStatus.PENDING;
            default -> OrderStatus.UNKNOWN;
        };
    }

    static BrokerOrder toOrder(JsonNode n) {
        return new BrokerOrder(
                n.path("order_id").asText(""),
                n.path("tradingsymbol").asText(""),
                side(n.path("transaction_type").asText("")),
                status(n.path("status").asText("")),
                n.path("quantity").asInt(0),
                n.path("filled_quantity").asInt(0),
                n.path("average_price").asDouble(0),
                n.path("status_message").asText(""),
                timestamp(n),
                n.path("tag").asText(""));
    }

    static BrokerPosition toPosition(JsonNode n) {
        return new BrokerPosition(
                n.path("tradingsymbol").asText(""),
                n.path("quantity").asInt(0),
                n.path("average_price").asDouble(0),
                n.path("last_price").asDouble(0),
                n.path("realised").asDouble(0),
                n.path("unrealised").asDouble(0),
                "CNC".equals(n.path("product").asText("MIS")) ? ProductType.CNC : ProductType.MIS);
    }

    private static OrderSide side(String s) {
        return "SELL".equalsIgnoreCase(s) ? OrderSide.SELL : OrderSide.BUY;
    }

    /**
     * Prefers the exchange timestamp over the order timestamp, and returns null when neither parses.
     * A null is honest; substituting the current time would make a stale order look fresh to any
     * staleness check downstream.
     */
    private static Instant timestamp(JsonNode n) {
        for (String field : new String[]{"exchange_update_timestamp", "exchange_timestamp", "order_timestamp"}) {
            String raw = n.path(field).asText("");
            if (raw.isBlank()) continue;
            try {
                return LocalDateTime.parse(raw, KITE_TIMESTAMP)
                        .atZone(com.equity.platform.time.TradingClock.IST)
                        .toInstant();
            } catch (RuntimeException ignored) {
                // try the next field
            }
        }
        return null;
    }

    /** Kite rejects a price with more than two decimals on most instruments. */
    private static String trimmed(double price) {
        return String.format("%.2f", price);
    }
}
