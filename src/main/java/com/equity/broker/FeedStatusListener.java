package com.equity.broker;

import java.time.Instant;

/**
 * Feed connection transitions.
 *
 * <p>This exists because silence is ambiguous. A symbol that stops ticking may be illiquid, or the
 * socket may be dead, and those demand opposite responses: the first is normal, the second must
 * suspend entries and stop evaluating exits against prices that are no longer moving. Only the
 * transport knows which it is, so it says so explicitly rather than leaving the engine to infer it
 * from a timeout.</p>
 */
public interface FeedStatusListener {

    void onConnected(Instant at);

    /** @param willRetry false only when the client has given up and a human must intervene */
    void onDisconnected(Instant at, String reason, boolean willRetry);
}
