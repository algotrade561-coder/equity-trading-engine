package com.equity.risk;

import com.equity.broker.BrokerPort;
import com.equity.domain.user.UserId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The last known available margin per user.
 *
 * <p>It exists because the risk check runs on the feed thread, and an HTTP call to the broker there
 * would stall every instrument in the universe behind one user's margin lookup — the same thread
 * carries all of them, and Kite drops a client that falls behind.</p>
 *
 * <p>A cached figure is slightly stale by construction. That is acceptable in this direction: the
 * exchange rejects an order that exceeds real margin, so the cost of being optimistic is a rejected
 * order, whereas the cost of blocking the feed is every stop in the book going unevaluated.</p>
 *
 * <p>Unknown means zero, not unlimited. A user whose margin has never been fetched cannot size a
 * position, which is the safe reading of "we do not know".</p>
 */
@Component
public class MarginCache {

    private static final Logger log = LoggerFactory.getLogger(MarginCache.class);

    private final BrokerPort broker;
    private final Map<UserId, Double> available = new ConcurrentHashMap<>();

    public MarginCache(BrokerPort broker) {
        this.broker = broker;
    }

    public double available(UserId userId) {
        return available.getOrDefault(userId, 0.0);
    }

    /** Called on a timer and after every fill, since a fill is what changes the number most. */
    public void refresh(UserId userId) {
        // A user who has not logged in is the normal resting state, not a fault. Calling anyway
        // produced a warning every thirty seconds for the whole session, which is how a log stops
        // being read — and this cache is one of the places a genuine broker problem shows up first.
        if (!broker.isAuthenticated(userId)) {
            available.remove(userId);
            return;
        }
        try {
            available.put(userId, broker.availableMargin(userId));
        } catch (RuntimeException e) {
            // Leave the previous value rather than zeroing it: a transient broker error should not
            // read as "this user has no money", which would look identical to a real margin call.
            log.warn("could not refresh margin for user={}: {}", userId, e.getMessage());
        }
    }

    public void clear(UserId userId) { available.remove(userId); }
}
