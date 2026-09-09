package com.equity.domain.market;

/**
 * The order-book and trade-flow fields the exchange already sends and the engine used to discard.
 *
 * <h2>Why these were being thrown away, and why that stopped</h2>
 * <p>Every Kite full packet carries them, and the codec read past them with a comment explaining
 * that the spread check was all the strategy needed. That was true when the only question asked of
 * the book was how wide it was. It is no longer obviously true: entries are MARKET orders, so the
 * fill is decided by the <b>depth</b> behind the touch rather than by the touch itself, and losses
 * have been realising at 1.19R to 1.35R against a 1.00R stop — slippage that a thin book would
 * explain and a tight spread would not reveal.</p>
 *
 * <h2>Captured, not yet used</h2>
 * <p>Nothing in the strategy reads these. They are recorded on every intent so that in a month the
 * question "did entries into a thicker book fare better?" can be answered against real trades,
 * rather than argued from a fortnight of them. Adding a filter now, on twenty trades, is how a
 * system gets fitted to a sample; the whole point of capturing early is to avoid needing to.</p>
 *
 * @param bidQuantity        resting quantity at the best bid
 * @param askQuantity        resting quantity at the best ask — what a market buy eats into first
 * @param totalBuyQuantity   every resting buy order the exchange is showing
 * @param totalSellQuantity  every resting sell order
 * @param lastTradedQuantity size of the most recent print, the raw material for trade-flow measures
 * @param exchangeVwap       the exchange's own session VWAP, worth having as a cross-check against
 *                           the one computed from candles — disagreement between them is a bug
 */
public record BookState(long bidQuantity, long askQuantity,
                        long totalBuyQuantity, long totalSellQuantity,
                        long lastTradedQuantity, double exchangeVwap) {

    /** What an LTP packet carries: nothing. Also the default for anything not built from a tick. */
    public static final BookState NONE = new BookState(0, 0, 0, 0, 0, 0);

    /**
     * Whether the exchange actually sent a book.
     *
     * <p>Only FULL mode carries the depth block, and only two hundred instruments can be in FULL
     * mode at once — design note 0.10 — so on a five hundred symbol scan most ticks will answer
     * false here. A quote packet still carries {@code lastTradedQuantity} and the exchange VWAP;
     * those are trade flow, not book, and this asks about the book.</p>
     */
    public boolean isPresent() {
        return totalBuyQuantity > 0 || totalSellQuantity > 0 || askQuantity > 0;
    }

    /**
     * Resting buy interest divided by resting sell interest.
     *
     * <p>Above 1 means more size wants in than out. NaN when the exchange sent no book, which must
     * never be read as balanced — an absent measurement and a neutral one are different facts.</p>
     */
    public double imbalance() {
        if (totalSellQuantity <= 0) return Double.NaN;
        return (double) totalBuyQuantity / totalSellQuantity;
    }

    /**
     * How many shares rest at the best offer.
     *
     * <p>The number that decides what a market buy actually pays. A 0.05% spread with fifty shares
     * on the offer and the same spread with five thousand are not the same trade, and the spread
     * check cannot tell them apart.</p>
     */
    public long depthAtTouch() {
        return askQuantity;
    }
}
