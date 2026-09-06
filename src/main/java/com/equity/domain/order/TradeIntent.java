package com.equity.domain.order;

import com.equity.domain.Direction;
import com.equity.domain.momentum.EntryPattern;
import com.equity.domain.user.UserId;
import java.time.Instant;

/**
 * A decision to take a position, before anything has been sized, priced or sent.
 *
 * <p>It names an instrument but not an order: no quantity, no order type, no broker. Sizing belongs
 * to risk, which is per user, and the strategy must not be able to influence it — a strategy that
 * can choose its own size can quietly opt out of the risk budget by asking for more.</p>
 *
 * <p>{@code stopPrice} is set here because the stop is part of the trade thesis, not a risk
 * parameter: it is where the setup is wrong. Risk decides how many shares that distance buys.</p>
 */
public record TradeIntent(
        UserId userId,
        String symbol,
        Direction direction,
        EntryPattern pattern,
        double referencePrice,
        double stopPrice,
        double targetPrice,
        Instant createdAt,
        String rationale) {

    public TradeIntent {
        if (symbol == null || symbol.isBlank()) throw new IllegalArgumentException("symbol required");
        if (!(referencePrice > 0)) throw new IllegalArgumentException("referencePrice must be positive");
        if (!(stopPrice > 0)) throw new IllegalArgumentException("stopPrice must be positive");
        if (direction == Direction.LONG && stopPrice >= referencePrice) {
            throw new IllegalArgumentException("a LONG stop must sit below the reference price");
        }
    }

    /** Distance to the stop, in rupees per share. This is what risk divides its budget by. */
    public double riskPerShare() {
        return Math.abs(referencePrice - stopPrice);
    }

    public double stopDistancePercent() {
        return referencePrice > 0 ? riskPerShare() / referencePrice * 100.0 : 0.0;
    }
}
