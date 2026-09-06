package com.equity.broker;

/**
 * A vendor-neutral order instruction.
 *
 * <p>{@code tag} is the idempotency handle owned by the engine. It is echoed back by the broker on
 * every order update, and it is what lets a reconnect or a restart match a broker order to the
 * intent that created it. Without it, a duplicate submission after a timeout is indistinguishable
 * from a second legitimate entry — see design note 0.3.</p>
 *
 * @param limitPrice   required for LIMIT, ignored otherwise
 * @param triggerPrice required for SL_M, ignored otherwise
 */
public record OrderRequest(
        String symbol,
        String exchange,
        OrderSide side,
        int quantity,
        OrderType type,
        ProductType product,
        double limitPrice,
        double triggerPrice,
        OrderVariety variety,
        String tag) {

    public OrderRequest {
        if (variety == null) variety = OrderVariety.REGULAR;
        if (symbol == null || symbol.isBlank()) throw new IllegalArgumentException("symbol required");
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");
        if (type == OrderType.LIMIT && !(limitPrice > 0))
            throw new IllegalArgumentException("LIMIT order requires a positive limit price");
        if (type == OrderType.SL_M && !(triggerPrice > 0))
            throw new IllegalArgumentException("SL_M order requires a positive trigger price");
        if (tag == null || tag.isBlank())
            throw new IllegalArgumentException("tag required for idempotency");
    }

    public static OrderRequest market(String symbol, OrderSide side, int qty, String tag) {
        return new OrderRequest(symbol, "NSE", side, qty, OrderType.MARKET, ProductType.MIS,
                0, 0, OrderVariety.REGULAR, tag);
    }

    public static OrderRequest limit(String symbol, OrderSide side, int qty, double price, String tag) {
        return new OrderRequest(symbol, "NSE", side, qty, OrderType.LIMIT, ProductType.MIS,
                price, 0, OrderVariety.REGULAR, tag);
    }
}
