package com.equity.broker.kite;

/**
 * One row of the Kite instrument master.
 *
 * <p>{@code tickSize} is kept because a limit price that is not a multiple of it is rejected by the
 * exchange, and a rejected entry looks exactly like a missed opportunity in the logs unless the
 * price is rounded before it is sent.</p>
 */
public record KiteInstrument(
        long instrumentToken,
        String tradingSymbol,
        String name,
        String exchange,
        String segment,
        String instrumentType,
        double tickSize,
        int lotSize) {

    public boolean isNseEquity() {
        return "NSE".equals(exchange) && "EQ".equals(instrumentType);
    }

    /** Rounds a price to the nearest valid tick. Returns the input unchanged if tick size is unknown. */
    public double roundToTick(double price) {
        if (!(tickSize > 0)) return price;
        return Math.round(price / tickSize) * tickSize;
    }
}
