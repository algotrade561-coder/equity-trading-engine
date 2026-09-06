package com.equity.platform.time;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manually advanced clock for tests and REPLAY.
 *
 * <p>Advance is monotonic and explicit: a test that wants a setup to time out must say so, rather
 * than sleeping. That keeps state-machine tests fast and free of wall-clock flakiness.</p>
 */
public final class FixedTradingClock implements TradingClock {

    private final AtomicReference<Instant> current;

    public FixedTradingClock(Instant start) {
        this.current = new AtomicReference<>(start);
    }

    @Override
    public Instant now() {
        return current.get();
    }

    public void advance(Duration by) {
        if (by.isNegative()) {
            throw new IllegalArgumentException("clock must not move backwards: " + by);
        }
        current.updateAndGet(t -> t.plus(by));
    }

    /** Jump to an absolute instant. Refuses to move backwards — replay must be monotonic. */
    public void setTo(Instant instant) {
        current.updateAndGet(prev -> {
            if (instant.isBefore(prev)) {
                throw new IllegalArgumentException("clock must not move backwards: " + prev + " -> " + instant);
            }
            return instant;
        });
    }
}
