package com.equity.broker;

import com.equity.domain.market.Tick;
import java.util.List;

/**
 * Receives decoded market data.
 *
 * <p>Called on the feed thread. Implementations must not block: one thread carries every instrument,
 * so a single slow consumer delays the whole universe, and Kite disconnects a client that cannot
 * keep up.</p>
 */
@FunctionalInterface
public interface TickListener {
    void onTicks(List<Tick> ticks);
}
