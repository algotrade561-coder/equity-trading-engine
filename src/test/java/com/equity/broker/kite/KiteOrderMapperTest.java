package com.equity.broker.kite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.equity.broker.OrderRequest;
import com.equity.broker.OrderSide;
import com.equity.broker.OrderStatus;
import com.equity.broker.OrderType;
import com.equity.broker.ProductType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KiteOrderMapperTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void marketOrderCarriesNoPriceFields() throws Exception {
        Map<String, String> form = KiteOrderMapper.toForm(
                OrderRequest.market("RELIANCE", OrderSide.BUY, 10, "eq-1"));

        assertThat(form).containsEntry("tradingsymbol", "RELIANCE")
                .containsEntry("transaction_type", "BUY")
                .containsEntry("order_type", "MARKET")
                .containsEntry("quantity", "10")
                .containsEntry("product", "MIS")
                .containsEntry("validity", "DAY")
                .containsEntry("tag", "eq-1")
                .doesNotContainKey("price")
                .doesNotContainKey("trigger_price");
    }

    @Test
    void marketOrdersCarryMarketProtectionBecauseKiteRefusesThemWithoutIt() {
        Map<String, String> form = KiteOrderMapper.toForm(
                OrderRequest.market("RELIANCE", OrderSide.BUY, 1, "eq-1"), 3.0);

        assertThat(form)
                .as("\"Market orders without market protection are not allowed via API\" — "
                        + "without this every entry and every exit is refused")
                .containsEntry("market_protection", "3.00");
    }

    @Test
    void marketProtectionIsOmittedWhenSetToZero() {
        assertThat(KiteOrderMapper.toForm(
                OrderRequest.market("RELIANCE", OrderSide.BUY, 1, "eq-1"), 0))
                .doesNotContainKey("market_protection");
    }

    @Test
    void aLimitOrderNeedsNoMarketProtection() {
        assertThat(KiteOrderMapper.toForm(
                OrderRequest.limit("RELIANCE", OrderSide.BUY, 1, 1450.55, "eq-1"), 3.0))
                .as("the band only applies to orders with no price of their own")
                .doesNotContainKey("market_protection");
    }

    @Test
    void limitPriceIsSentWithTwoDecimals() {
        Map<String, String> form = KiteOrderMapper.toForm(
                OrderRequest.limit("RELIANCE", OrderSide.BUY, 10, 1450.5499, "eq-2"));

        assertThat(form).containsEntry("order_type", "LIMIT").containsEntry("price", "1450.55");
    }

    @Test
    void stopLossMarketUsesTheHyphenatedWireName() {
        OrderRequest sl = new OrderRequest("RELIANCE", "NSE", OrderSide.SELL, 10,
                OrderType.SL_M, ProductType.MIS, 0, 1400.0,
                com.equity.broker.OrderVariety.REGULAR, "eq-3");

        assertThat(KiteOrderMapper.toForm(sl))
                .as("Kite calls it SL-M; sending SL_M is an InputException, i.e. no stop at all")
                .containsEntry("order_type", "SL-M")
                .containsEntry("trigger_price", "1400.00");
    }

    @Test
    void aTagTooLongIsRefusedRatherThanTruncated() {
        OrderRequest tooLong = OrderRequest.market("RELIANCE", OrderSide.BUY, 1,
                "this-tag-is-far-too-long-for-kite");

        assertThatThrownBy(() -> KiteOrderMapper.toForm(tooLong))
                .as("Kite truncates silently, which breaks the match between order and intent")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("20");
    }

    @Test
    void statusStringsMapToTheirNormalisedForm() {
        assertThat(KiteOrderMapper.status("COMPLETE")).isEqualTo(OrderStatus.COMPLETE);
        assertThat(KiteOrderMapper.status("REJECTED")).isEqualTo(OrderStatus.REJECTED);
        assertThat(KiteOrderMapper.status("CANCELLED")).isEqualTo(OrderStatus.CANCELLED);
        assertThat(KiteOrderMapper.status("OPEN")).isEqualTo(OrderStatus.OPEN);
        assertThat(KiteOrderMapper.status("TRIGGER PENDING")).isEqualTo(OrderStatus.OPEN);
        assertThat(KiteOrderMapper.status("VALIDATION PENDING")).isEqualTo(OrderStatus.PENDING);
        assertThat(KiteOrderMapper.status("PUT ORDER REQ RECEIVED")).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void anUnrecognisedStatusIsUnknownAndNeverTerminal() {
        OrderStatus status = KiteOrderMapper.status("SOMETHING KITE ADDED LATER");

        assertThat(status).isEqualTo(OrderStatus.UNKNOWN);
        assertThat(status.isTerminal())
                .as("treating an unfamiliar status as terminal lets the lifecycle act on a live order")
                .isFalse();
        assertThat(KiteOrderMapper.status(null)).isEqualTo(OrderStatus.UNKNOWN);
    }

    @Test
    void orderJsonBecomesABrokerOrderWithFillDetail() throws Exception {
        String body = """
                {"order_id":"250904000123","tradingsymbol":"RELIANCE","transaction_type":"BUY",
                 "status":"COMPLETE","quantity":10,"filled_quantity":10,"average_price":1450.55,
                 "status_message":"","tag":"eq-1","exchange_timestamp":"2026-09-04 09:20:31"}
                """;

        var order = KiteOrderMapper.toOrder(json.readTree(body));

        assertThat(order.brokerOrderId()).isEqualTo("250904000123");
        assertThat(order.status()).isEqualTo(OrderStatus.COMPLETE);
        assertThat(order.filledQuantity()).isEqualTo(10);
        assertThat(order.averagePrice()).isEqualTo(1450.55);
        assertThat(order.pendingQuantity()).isZero();
        assertThat(order.updatedAt()).isEqualTo("2026-09-04T03:50:31Z");   // 09:20:31 IST
    }

    @Test
    void aPartialFillIsVisibleAsPartialRatherThanComplete() throws Exception {
        var order = KiteOrderMapper.toOrder(json.readTree(
                "{\"order_id\":\"1\",\"status\":\"OPEN\",\"quantity\":10,\"filled_quantity\":4}"));

        assertThat(order.isPartiallyFilled()).isTrue();
        assertThat(order.pendingQuantity()).isEqualTo(6);
    }

    @Test
    void anUnparseableTimestampBecomesNullRatherThanNow() throws Exception {
        var order = KiteOrderMapper.toOrder(json.readTree(
                "{\"order_id\":\"1\",\"status\":\"OPEN\",\"order_timestamp\":\"not a time\"}"));

        assertThat(order.updatedAt())
                .as("substituting the current time would make a stale order look fresh")
                .isNull();
    }

    @Test
    void positionJsonUsesTheSignedNetQuantity() throws Exception {
        var position = KiteOrderMapper.toPosition(json.readTree("""
                {"tradingsymbol":"RELIANCE","quantity":-5,"average_price":1450.0,
                 "last_price":1445.0,"realised":0.0,"unrealised":25.0,"product":"MIS"}
                """));

        assertThat(position.quantity()).isEqualTo(-5);
        assertThat(position.isFlat()).isFalse();
        assertThat(position.product()).isEqualTo(ProductType.MIS);
    }
}
