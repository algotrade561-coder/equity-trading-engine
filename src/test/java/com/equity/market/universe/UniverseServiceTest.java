package com.equity.market.universe;

import static org.assertj.core.api.Assertions.assertThat;

import com.equity.broker.FeedStatusListener;
import com.equity.broker.MarketDataPort;
import com.equity.broker.SubscriptionMode;
import com.equity.broker.TickListener;
import com.equity.market.candle.CandleEngine;
import com.equity.market.gainer.TopGainerEngine;
import com.equity.market.state.StructureEngine;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What gets watched, and what gets evaluated.
 *
 * <p>These are two different questions and were briefly the same answer: a rank cut-off intended as
 * a bandwidth device was also deciding eligibility, so a stock meeting every condition in the
 * strategy could be skipped because others happened to be up more that minute.</p>
 */
class UniverseServiceTest {

    private static final List<String> NIFTY_SLICE =
            List.of("RELIANCE", "HDFCBANK", "INFY", "TCS", "ITC");

    /** Records what the transport was asked to do. */
    private static final class RecordingFeed implements MarketDataPort {
        final Map<String, SubscriptionMode> modes = new LinkedHashMap<>();
        final List<String> unresolved = new ArrayList<>();

        @Override public void subscribe(Collection<String> symbols, SubscriptionMode mode) {
            symbols.forEach(s -> modes.put(s, mode));
        }
        @Override public void setMode(Collection<String> symbols, SubscriptionMode mode) {
            symbols.forEach(s -> modes.put(s, mode));
        }
        @Override public void unsubscribe(Collection<String> symbols) { symbols.forEach(modes::remove); }
        @Override public void addTickListener(TickListener listener) {}
        @Override public void addFeedStatusListener(FeedStatusListener listener) {}
        @Override public boolean isConnected() { return true; }
        @Override public Collection<String> unresolvedSymbols() { return unresolved; }
    }

    private RecordingFeed feed;
    private UniverseProperties properties;
    private UniverseService universe;

    @BeforeEach
    void setUp() {
        feed = new RecordingFeed();
        properties = new UniverseProperties();
        properties.setSymbols(new ArrayList<>(NIFTY_SLICE));
        // The shipped default is now false, because the universe is index-sized and depth
        // subscriptions are capped. These tests cover the small-universe path, so they opt in.
        properties.setEvaluateAll(true);
        universe = new UniverseService(feed, new StructureEngine(new CandleEngine()),
                new TopGainerEngine(20, 35, 25.0), properties);
    }

    @Test
    void everyConfiguredStockIsEvaluatedWhenEvaluateAllIsOn() {
        universe.subscribeUniverse(NIFTY_SLICE);

        assertThat(universe.isEvaluatingAll()).isTrue();
        assertThat(universe.discoverySet()).containsExactlyInAnyOrderElementsOf(NIFTY_SLICE);
        NIFTY_SLICE.forEach(s -> assertThat(universe.isCandidate(s)).isTrue());
    }

    @Test
    void everyStockGetsDepthImmediatelyRatherThanOnPromotion() {
        universe.subscribeUniverse(NIFTY_SLICE);

        assertThat(feed.modes).containsEntry("RELIANCE", SubscriptionMode.FULL);
        assertThat(universe.fullModeCount())
                .as("depth is needed at the moment of entry; a promotion would arrive after the trigger")
                .isEqualTo(NIFTY_SLICE.size());
    }

    @Test
    void theIndexIsSubscribedButIsNotACandidate() {
        universe.subscribeUniverse(NIFTY_SLICE);

        assertThat(feed.modes).containsEntry(StructureEngine.INDEX_SYMBOL, SubscriptionMode.LTP);
        assertThat(universe.isCandidate(StructureEngine.INDEX_SYMBOL))
                .as("the index is context, not something to trade")
                .isFalse();
        assertThat(universe.subscribedSymbols()).hasSize(NIFTY_SLICE.size() + 1);
    }

    @Test
    void rankingDoesNotChangeMembershipWhenEvaluatingAll() {
        universe.subscribeUniverse(NIFTY_SLICE);

        universe.refreshDiscoverySet();

        assertThat(universe.discoverySet())
                .as("a stock meeting every condition must not be skipped for being 21st on the board")
                .containsExactlyInAnyOrderElementsOf(NIFTY_SLICE);
    }

    @Test
    void theRankedSetIsStillAvailableForALargerUniverse() {
        properties.setEvaluateAll(false);
        universe.subscribeUniverse(NIFTY_SLICE);

        assertThat(feed.modes)
                .as("without evaluate-all the universe starts in QUOTE and is promoted selectively")
                .containsEntry("RELIANCE", SubscriptionMode.QUOTE);
        assertThat(universe.discoverySet()).isEmpty();
    }

    @Test
    void subscribingTwiceDoesNotResubscribe() {
        universe.subscribeUniverse(NIFTY_SLICE);
        int afterFirst = feed.modes.size();

        universe.subscribeUniverse(NIFTY_SLICE);

        assertThat(feed.modes).hasSize(afterFirst);
    }

    @Test
    void anUnresolvableSymbolIsReportedRatherThanLookingLikeAQuietStock() {
        universe.recordUnresolved(List.of("DELISTEDCO"));

        assertThat(universe.unresolvedSymbols())
                .as("a stale index constituent must not present as a stock that simply never trades")
                .containsExactly("DELISTEDCO");
    }

    /**
     * Nothing is shipped in the source. The constituents come from the exchange, because a list
     * typed into a file rots without saying so — the previous one carried two symbols the exchange
     * had renamed, and those stocks were simply never evaluated.
     */
    @Test
    void noConstituentListIsHardcoded() {
        assertThat(new UniverseProperties().getSymbols())
                .as("an empty list means 'fetch the index'; anything here is a deliberate override")
                .isEmpty();
    }

    /**
     * Depth is capped far below the number of instruments a feed will carry, and exceeding it is
     * silent — the feed simply stops sending the book, and entries are refused as NO_DEPTH for
     * stocks that look perfectly healthy. Falling back is the only safe response.
     */
    @Test
    void refusesToPutAnIndexSizedUniverseIntoFullDepth() {
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < UniverseService.FULL_MODE_LIMIT + 1; i++) tooMany.add("SYM" + i);
        properties.setEvaluateAll(true);

        universe.subscribeUniverse(tooMany);

        assertThat(feed.modes.values())
                .as("every symbol falls back to QUOTE rather than silently losing its book")
                .containsOnly(SubscriptionMode.QUOTE, SubscriptionMode.LTP);
        assertThat(universe.discoverySet())
                .as("membership is then decided by rank, not by everything being a candidate")
                .isEmpty();
    }
}
