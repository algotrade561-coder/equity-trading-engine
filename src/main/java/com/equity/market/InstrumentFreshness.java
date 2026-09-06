package com.equity.market;

import com.equity.domain.market.Tick;
import com.equity.platform.time.TradingClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * How long ago each instrument last ticked.
 *
 * <p><b>Per instrument, not global</b> — design note 0.6. A global "feed is healthy" flag is the
 * wrong shape: the socket can be perfectly alive while one symbol has not printed for four minutes
 * because it is illiquid or halted. Entering on a four-minute-old price, or leaving a stop to be
 * evaluated against one, is the failure this exists to prevent.</p>
 *
 * <p>Staleness is measured from the time the tick <b>arrived</b>, not from its exchange timestamp.
 * A broker replaying a backlog after a reconnect sends old exchange timestamps at speed; judging
 * freshness by those would declare a recovering feed stale, and judging it by arrival correctly
 * says the data is flowing again.</p>
 */
@Component
public class InstrumentFreshness {

    private final TradingClock clock;
    private final Map<String, Instant> lastSeen = new ConcurrentHashMap<>();

    public InstrumentFreshness(TradingClock clock) {
        this.clock = clock;
    }

    public void record(Tick tick) {
        lastSeen.put(tick.symbol(), tick.receivedAt());
    }

    /** Age of the last tick. {@code null} when the symbol has never ticked, which is not the same as fresh. */
    public Duration age(String symbol) {
        Instant seen = lastSeen.get(symbol);
        return seen == null ? null : Duration.between(seen, clock.now());
    }

    /** False for a symbol that has never ticked at all — absence of data is not freshness. */
    public boolean isFresh(String symbol, Duration limit) {
        Duration age = age(symbol);
        return age != null && age.compareTo(limit) <= 0;
    }

    public int trackedSymbols() { return lastSeen.size(); }

    /** Called on a feed disconnect: everything known is now of unknown age, so nothing is fresh. */
    public void invalidateAll() {
        lastSeen.clear();
    }
}
