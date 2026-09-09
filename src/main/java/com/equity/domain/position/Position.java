package com.equity.domain.position;

import com.equity.domain.Direction;
import com.equity.domain.momentum.EntryPattern;
import com.equity.broker.ProductType;
import com.equity.domain.order.OrderTag;
import com.equity.domain.user.UserId;
import java.time.Instant;
import java.util.UUID;

/**
 * One position, from the moment its entry order is sent to the moment it is closed.
 *
 * <p>Immutable, replaced by reference swap on every transition. That is what lets the exit
 * evaluation read a consistent view without a lock, and it makes an illegal transition a method
 * that does not exist rather than a field somebody forgot to update.</p>
 *
 * <p>{@code product} is carried because the exit must use the product the entry used. Squaring a
 * CNC holding off with an MIS sell does not close it — it opens an intraday short alongside it.</p>
 *
 * <p>{@code entryPrice} is the <b>actual average fill</b>, not the price the strategy hoped for.
 * Design note 0.8: a slipped fill invalidates the sizing that authorised the trade, so the stop
 * distance and the risk actually taken are recomputed from what really happened.</p>
 */
public record Position(
        UUID id,
        UserId userId,
        String symbol,
        Direction direction,
        EntryPattern pattern,
        ProductType product,
        PositionStatus status,
        int quantity,
        int filledQuantity,
        double intendedEntryPrice,
        double entryPrice,
        double stopPrice,
        double targetPrice,
        double highWaterMark,
        double lowWaterMark,
        Instant openedAt,
        Instant closedAt,
        double exitPrice,
        ExitReason exitReason,
        OrderTag entryTag,
        String entryOrderId,
        OrderTag exitTag,
        String exitOrderId) {

    public static Position pendingEntry(UserId userId, String symbol, Direction direction,
                                        EntryPattern pattern, ProductType product, int quantity,
                                        double intendedEntryPrice, double stopPrice,
                                        double targetPrice, OrderTag entryTag,
                                        String entryOrderId, Instant at) {
        return new Position(UUID.randomUUID(), userId, symbol, direction, pattern, product,
                PositionStatus.PENDING_ENTRY, quantity, 0, intendedEntryPrice, 0,
                stopPrice, targetPrice, 0, 0, at, null, 0, null,
                entryTag, entryOrderId, null, null);
    }

    public Position withFill(int filled, double averagePrice, Instant at) {
        if (filled <= 0) return this;
        return new Position(id, userId, symbol, direction, pattern, product, PositionStatus.OPEN,
                quantity, filled, intendedEntryPrice, averagePrice, stopPrice, targetPrice,
                // Both marks start at the fill: the trade has neither run nor retraced yet.
                averagePrice, averagePrice, at, closedAt, exitPrice, exitReason,
                entryTag, entryOrderId, exitTag, exitOrderId);
    }

    public Position withExitPending(ExitReason reason, OrderTag tag, String orderId) {
        return new Position(id, userId, symbol, direction, pattern, product,
                PositionStatus.EXIT_PENDING,
                quantity, filledQuantity, intendedEntryPrice, entryPrice, stopPrice, targetPrice,
                highWaterMark, lowWaterMark, openedAt, closedAt, exitPrice, reason, entryTag, entryOrderId, tag, orderId);
    }

    public Position withClose(double price, ExitReason reason, Instant at) {
        return new Position(id, userId, symbol, direction, pattern, product, PositionStatus.CLOSED,
                quantity, filledQuantity, intendedEntryPrice, entryPrice, stopPrice, targetPrice,
                highWaterMark, lowWaterMark, openedAt, at, price, reason, entryTag, entryOrderId, exitTag, exitOrderId);
    }

    public Position withStatus(PositionStatus newStatus) {
        return withStatus(newStatus, closedAt);
    }

    /**
     * Moves to a new status, stamping {@code closedAt} when that status is a final one.
     *
     * <p>A position that reaches CLOSED or ABANDONED without a timestamp is not merely untidy: the
     * per-symbol cooldown is keyed on it, so a null there threw where the position was recorded —
     * on the order-update thread, in the middle of handling a broker rejection. The transition that
     * ends a position is exactly the one that must carry the time it ended.</p>
     */
    public Position withStatus(PositionStatus newStatus, Instant at) {
        Instant finishedAt = newStatus.isFinished() && closedAt == null ? at : closedAt;
        return new Position(id, userId, symbol, direction, pattern, product, newStatus,
                quantity, filledQuantity, intendedEntryPrice, entryPrice, stopPrice, targetPrice,
                highWaterMark, lowWaterMark, openedAt, finishedAt, exitPrice, exitReason,
                entryTag, entryOrderId, exitTag, exitOrderId);
    }

    /** Moves the stop. Refuses to loosen it — a stop that can widen is not a stop. */
    public Position withStop(double newStop) {
        boolean loosening = direction == Direction.LONG ? newStop < stopPrice : newStop > stopPrice;
        if (loosening) return this;
        return new Position(id, userId, symbol, direction, pattern, product, status,
                quantity, filledQuantity, intendedEntryPrice, entryPrice, newStop, targetPrice,
                highWaterMark, lowWaterMark, openedAt, closedAt, exitPrice, exitReason,
                entryTag, entryOrderId, exitTag, exitOrderId);
    }

    /**
     * Records a new best price. Only ever moves in the favourable direction.
     *
     * <p>A trailing stop is meaningless without it, and it has to live on the position rather than
     * in the trailing logic: the mark is a fact about the trade, and a policy that can be switched
     * on mid-position must not start trailing from wherever the price happens to be at that moment.
     */
    /**
     * Records a new worst price. Only ever moves against the position.
     *
     * <p>The mirror of the high-water mark, and the half that was missing. That one says how much a
     * trade gave back; this one says how much heat it took before it worked — which is the only way
     * to answer whether a stop is too tight. A winner that first ran 0.9R against its stop and a
     * winner that never traded below entry are the same row without it.</p>
     */
    public Position withLowWaterMark(double price) {
        if (!hasExposure() || price <= 0) return this;
        boolean worse = direction == Direction.LONG
                ? lowWaterMark == 0 || price < lowWaterMark
                : price > lowWaterMark;
        if (!worse) return this;
        return new Position(id, userId, symbol, direction, pattern, product, status,
                quantity, filledQuantity, intendedEntryPrice, entryPrice, stopPrice, targetPrice,
                highWaterMark, price, openedAt, closedAt, exitPrice, exitReason,
                entryTag, entryOrderId, exitTag, exitOrderId);
    }

    /**
     * How far the trade went against itself, in units of the original risk.
     *
     * <p>Positive. A value near 1 means the stop was nearly touched before the trade worked; a value
     * near 0 means it never traded against the entry at all.</p>
     */
    public double adverseExcursionR() {
        double risk = riskPerShare();
        if (!(risk > 0) || lowWaterMark <= 0) return 0;
        double move = direction == Direction.LONG
                ? entryPrice - lowWaterMark
                : lowWaterMark - entryPrice;
        return Math.max(0, move / risk);
    }

    public Position withHighWaterMark(double price) {
        if (!hasExposure() || price <= 0) return this;
        boolean better = direction == Direction.LONG
                ? price > highWaterMark
                : highWaterMark == 0 || price < highWaterMark;
        if (!better) return this;
        return new Position(id, userId, symbol, direction, pattern, product, status,
                quantity, filledQuantity, intendedEntryPrice, entryPrice, stopPrice, targetPrice,
                price, lowWaterMark, openedAt, closedAt, exitPrice, exitReason,
                entryTag, entryOrderId, exitTag, exitOrderId);
    }

    /** Risk per share the position was opened with. The denominator of every R figure. */
    public double riskPerShare() {
        return Math.abs(entryPrice - stopPrice);
    }

    /**
     * How far the trade has gone in its favour, in units of the original risk.
     *
     * <p>Measured from the high-water mark, not the current price: "reached 1R" has to stay true
     * after a pullback, or a trailing stop would arm and disarm as price oscillates.</p>
     */
    public double favourableExcursionR() {
        double risk = riskPerShare();
        if (!(risk > 0) || highWaterMark <= 0) return 0;
        double move = direction == Direction.LONG
                ? highWaterMark - entryPrice
                : entryPrice - highWaterMark;
        return move / risk;
    }

    public boolean hasExposure() { return status.hasExposure(); }

    /** Signed profit at the given price, in rupees. LONG only in version 1, but written for both. */
    public double unrealisedPnl(double lastPrice) {
        if (!hasExposure() || filledQuantity == 0) return 0;
        double move = lastPrice - entryPrice;
        return (direction == Direction.LONG ? move : -move) * filledQuantity;
    }

    /**
     * What the round trip cost in charges. Derived from the fill prices rather than stored, so it
     * cannot drift away from the trade it describes.
     */
    public TradeCost cost() {
        if (status != PositionStatus.CLOSED || filledQuantity == 0) {
            return new TradeCost(0, 0, 0, 0, 0, 0);
        }
        return TradeCost.forRoundTrip(entryPrice * filledQuantity, exitPrice * filledQuantity);
    }

    /**
     * Profit after charges. The figure that compounds.
     *
     * <p>Gross R decides whether the strategy has an edge; this decides whether it is kept. On a
     * tight stop the two differ materially — costs follow notional, risk follows stop distance.</p>
     */
    public double netPnl() {
        return realisedPnl() - cost().total();
    }

    public double realisedPnl() {
        if (status != PositionStatus.CLOSED || filledQuantity == 0) return 0;
        double move = exitPrice - entryPrice;
        return (direction == Direction.LONG ? move : -move) * filledQuantity;
    }

    /**
     * Rupees at risk if the stop is hit, computed from the <b>actual</b> fill.
     *
     * <p>This is the number that matters after a slipped entry: the position was authorised on an
     * expected price, and if it filled worse the real risk is larger than the budget allowed.</p>
     */
    public double riskAtStop() {
        return Math.abs(entryPrice - stopPrice) * filledQuantity;
    }

    public boolean stopBreached(double lastPrice) {
        if (!hasExposure()) return false;
        return direction == Direction.LONG ? lastPrice <= stopPrice : lastPrice >= stopPrice;
    }

    public boolean targetReached(double lastPrice) {
        if (!hasExposure() || !(targetPrice > 0)) return false;
        return direction == Direction.LONG ? lastPrice >= targetPrice : lastPrice <= targetPrice;
    }
}
