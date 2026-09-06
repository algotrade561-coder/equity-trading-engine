package com.equity.broker.kite;

import java.util.concurrent.locks.LockSupport;

/**
 * A minimum interval between outbound broker calls.
 *
 * <p>Kite rate-limits per API key and answers a burst with 429s. Being throttled mid-session is not
 * a nuisance, it is a risk event: the call most likely to be refused is the one competing with a
 * polling loop, and that is usually an exit.</p>
 *
 * <p>Uses {@link System#nanoTime()} rather than the trading clock, on purpose. This is a measurement
 * of elapsed real time for pacing a socket, not a business timestamp, and it must behave the same
 * under REPLAY — where the trading clock jumps in tape time and would produce nonsense delays.</p>
 */
final class KiteRateLimiter {

    private final long minIntervalNanos;
    private long nextAllowedNanos;

    KiteRateLimiter(int callsPerSecond) {
        this.minIntervalNanos = 1_000_000_000L / Math.max(1, callsPerSecond);
        this.nextAllowedNanos = System.nanoTime();
    }

    /** Blocks until the next call is permitted. */
    void acquire() {
        long waitNanos;
        synchronized (this) {
            long now = System.nanoTime();
            if (now < nextAllowedNanos) {
                waitNanos = nextAllowedNanos - now;
                nextAllowedNanos += minIntervalNanos;
            } else {
                waitNanos = 0;
                nextAllowedNanos = now + minIntervalNanos;
            }
        }
        if (waitNanos > 0) LockSupport.parkNanos(waitNanos);
    }
}
