package com.equity.platform.time;

import java.time.Instant;

/** LIVE clock. The single legitimate caller of {@code Instant.now()} in the system. */
public final class SystemTradingClock implements TradingClock {
    @Override
    public Instant now() {
        return Instant.now();
    }
}
