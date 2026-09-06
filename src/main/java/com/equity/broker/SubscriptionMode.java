package com.equity.broker;

/**
 * How much data the feed should send for an instrument.
 *
 * <p>Modes exist for bandwidth, not for convenience. A 200-symbol universe in FULL is roughly four
 * times the bytes of QUOTE, and the depth is wasted on symbols nowhere near a trigger. The engine
 * therefore watches the universe in QUOTE and upgrades only live candidates to FULL, which is the
 * only mode carrying the bid and ask that a spread check needs — design note 0.10.</p>
 */
public enum SubscriptionMode {
    LTP, QUOTE, FULL;

    public String wireName() { return name().toLowerCase(); }
}
