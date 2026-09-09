package com.equity.domain.position;

/**
 * What a round trip costs, so an R-multiple can be read net rather than gross.
 *
 * <h2>Why this is not a detail</h2>
 * <p>Charges scale with <b>notional</b> while risk scales with <b>stop distance</b>, and the two are
 * unrelated. A tight stop on an expensive share puts a large position to work to risk very little:
 * one live trade committed 148,697 rupees of stock to risk 532, and its round trip cost about 95 —
 * eighteen per cent of the risk taken. On another the same 95 was nine per cent. Comparing those two
 * trades on gross R silently compares them on different scales.</p>
 *
 * <p>Recorded per trade so that a month of results can be read net, which is the only figure that
 * compounds. Gross R decides whether the strategy has an edge; net R decides whether you keep it.</p>
 *
 * <h2>Indian equity intraday, as charged</h2>
 * <p>Rates are those applying to a delivery-free intraday round trip. They are a rate card, not a
 * quote: the broker's contract note is authoritative and this exists to make the number available
 * for analysis at the moment a trade closes, not to reconcile a bill.</p>
 */
public record TradeCost(double brokerage, double stt, double exchangeTxn, double sebi,
                        double stampDuty, double gst) {

    /** Zerodha: 0.03% of turnover or 20 rupees per executed order, whichever is lower. */
    private static final double BROKERAGE_RATE = 0.0003;
    private static final double BROKERAGE_CAP = 20.0;
    /** Intraday equity: charged on the sell leg only. Delivery pays 0.1% on both. */
    private static final double STT_SELL = 0.00025;
    private static final double EXCHANGE_TXN = 0.0000297;
    private static final double SEBI = 0.000001;
    /** Charged on the buy leg only, and only at the intraday rate once both legs are same-day. */
    private static final double STAMP_BUY = 0.00003;
    private static final double GST = 0.18;

    public double total() {
        return brokerage + stt + exchangeTxn + sebi + stampDuty + gst;
    }

    /**
     * The cost of one intraday round trip.
     *
     * @param buyValue  quantity times entry price
     * @param sellValue quantity times exit price
     */
    public static TradeCost forRoundTrip(double buyValue, double sellValue) {
        if (!(buyValue > 0) || !(sellValue > 0)) return new TradeCost(0, 0, 0, 0, 0, 0);

        double brokerage = Math.min(BROKERAGE_CAP, buyValue * BROKERAGE_RATE)
                + Math.min(BROKERAGE_CAP, sellValue * BROKERAGE_RATE);
        double turnover = buyValue + sellValue;
        double stt = sellValue * STT_SELL;
        double exchange = turnover * EXCHANGE_TXN;
        double sebi = turnover * SEBI;
        double stamp = buyValue * STAMP_BUY;
        // GST applies to the broker's and the exchange's fees, never to the taxes.
        double gst = (brokerage + exchange + sebi) * GST;
        return new TradeCost(brokerage, stt, exchange, sebi, stamp, gst);
    }
}
