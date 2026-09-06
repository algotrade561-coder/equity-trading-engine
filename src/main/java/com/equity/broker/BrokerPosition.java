package com.equity.broker;

/**
 * A position as the broker reports it. Reconciliation compares this against the view held by the
 * engine; a disagreement is resolved in favour of the broker, never averaged.
 *
 * @param quantity signed — negative means short
 */
public record BrokerPosition(
        String symbol,
        int quantity,
        double averagePrice,
        double lastPrice,
        double realisedPnl,
        double unrealisedPnl,
        ProductType product) {

    public boolean isFlat() { return quantity == 0; }
}
