package com.equity.broker;

import java.util.Collection;

/**
 * The only view the engine has of a streaming quote feed.
 *
 * <p>Subscriptions are expressed in trading symbols, not broker tokens. Token mapping is a problem
 * belonging to the adapter; letting it leak upward would put a Kite instrument_token in the strategy
 * layer and make a REPLAY source impossible to substitute.</p>
 */
public interface MarketDataPort {

    void subscribe(Collection<String> symbols, SubscriptionMode mode);

    /** Changes the mode of symbols already subscribed — used to promote a candidate to FULL. */
    void setMode(Collection<String> symbols, SubscriptionMode mode);

    void unsubscribe(Collection<String> symbols);

    void addTickListener(TickListener listener);

    void addFeedStatusListener(FeedStatusListener listener);

    boolean isConnected();

    /**
     * Symbols that could not be mapped to an instrument and will therefore never tick.
     *
     * <p>Only the transport knows this, and it has to be asked rather than logged and forgotten: a
     * symbol the feed silently ignores looks exactly like a stock that is not trading, so a stale
     * index constituent would otherwise present as an unusually quiet day for that name.</p>
     */
    Collection<String> unresolvedSymbols();

    /**
     * Instruments whose symbol resembles one that failed to resolve.
     *
     * <p>An unresolved constituent is nearly always a rename or a demerger, and the only symptom is
     * that a stock quietly stops being evaluated. Naming the candidates turns that into a one-line
     * configuration fix. Defaults to nothing: a data source with no instrument catalogue has no
     * basis for a suggestion, and inventing one would be worse than silence.</p>
     */
    default java.util.List<String> suggestionsFor(String tradingSymbol) {
        return java.util.List.of();
    }
}
