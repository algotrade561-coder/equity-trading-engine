package com.equity.market.universe;

import com.equity.broker.MarketDataPort;
import com.equity.broker.SubscriptionMode;
import com.equity.domain.market.SharedInstrumentState;
import com.equity.market.gainer.TopGainerEngine;
import com.equity.market.state.StructureEngine;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Decides which instruments the engine watches, and how closely.
 *
 * <h2>Eligibility is the strategy's job, not this class's</h2>
 * <p>With {@code evaluateAll} on — the default for a NIFTY 50 universe — every subscribed stock is a
 * candidate and every one is watched in FULL. Whether a stock is worth trading is then decided
 * entirely by the strategy's named conditions, which is where that decision belongs and where a
 * refusal gets counted and can be argued with.</p>
 *
 * <p>The ranked discovery set remains available for a universe large enough that FULL depth on all
 * of it would be wasteful. It is a <b>bandwidth device</b>, and it was quietly acting as a second
 * entry filter: a stock meeting every condition would be skipped because nineteen others happened
 * to be up more that minute, and nothing in the strategy reads the rank at all.</p>
 *
 * <h2>Hysteresis, when ranking is used</h2>
 * <p>Two ranks, not one — enter tighter than you leave. With a single threshold a stock hovering at
 * the boundary joins and leaves repeatedly, and each cycle discards whatever setup state had
 * accumulated against it.</p>
 */
@Component
public class UniverseService {

    /**
     * How many instruments may hold a depth subscription at once.
     *
     * <p>Kite's limit per connection. Quotes stream for thousands; depth does not, and exceeding it
     * costs no error — the feed simply stops sending the book for the surplus.</p>
     */
    static final int FULL_MODE_LIMIT = 200;

    private static final Logger log = LoggerFactory.getLogger(UniverseService.class);

    private final MarketDataPort marketData;
    private final StructureEngine structure;
    private final TopGainerEngine gainers;
    private final UniverseProperties properties;

    /** The symbols currently eligible for evaluation, published by reference swap. */
    private final AtomicReference<Set<String>> discoverySet = new AtomicReference<>(Set.of());
    private final Set<String> subscribed = new LinkedHashSet<>();
    private final Set<String> inFullMode = new LinkedHashSet<>();
    /** Configured symbols with no instrument token — a stale index constituent, or a typo. */
    private final AtomicReference<List<String>> unresolved = new AtomicReference<>(List.of());

    public UniverseService(MarketDataPort marketData, StructureEngine structure,
                           TopGainerEngine gainers, UniverseProperties properties) {
        this.marketData = marketData;
        this.structure = structure;
        this.gainers = gainers;
        this.properties = properties;
    }

    /**
     * Subscribes the tradeable universe plus the index.
     *
     * <p>The index is subscribed alongside because relative strength is meaningless without it, and
     * a strategy that silently treated a missing index as zero would rate every stock as
     * outperforming on a day when the whole market rose.</p>
     */
    public synchronized void subscribeUniverse(Collection<String> symbols) {
        boolean full = properties.isEvaluateAll();
        if (full && symbols.size() > FULL_MODE_LIMIT) {
            // The feed does not reject the excess, it just stops sending depth for it, and the only
            // symptom is entries refused as NO_DEPTH for stocks that look perfectly healthy. Failing
            // here, at startup, with the reason, is far better than diagnosing that at 09:20.
            log.error("evaluate-all is on for {} symbols but only {} may hold a depth subscription. "
                    + "Falling back to QUOTE with ranked promotion — set equity.universe."
                    + "evaluate-all=false to make this deliberate.", symbols.size(), FULL_MODE_LIMIT);
            full = false;
        }

        List<String> fresh = symbols.stream().filter(s -> !subscribed.contains(s)).toList();
        if (!fresh.isEmpty()) {
            // Straight to FULL when every stock is a candidate: depth is needed at the moment of
            // entry, and a promotion that has to happen first would arrive after the trigger.
            marketData.subscribe(fresh, full ? SubscriptionMode.FULL : SubscriptionMode.QUOTE);
            subscribed.addAll(fresh);
            if (full) inFullMode.addAll(fresh);
        }
        if (!subscribed.contains(StructureEngine.INDEX_SYMBOL)) {
            marketData.subscribe(List.of(StructureEngine.INDEX_SYMBOL), SubscriptionMode.LTP);
            subscribed.add(StructureEngine.INDEX_SYMBOL);
        }

        if (full) {
            Set<String> all = new LinkedHashSet<>(subscribed);
            all.remove(StructureEngine.INDEX_SYMBOL);
            discoverySet.set(Set.copyOf(all));
        }

        log.info("universe subscribed: {} stocks in {}, index in LTP, evaluating {}",
                subscribed.size() - 1,
                full ? "FULL" : "QUOTE",
                full ? "all of them" : "the top " + properties.getEnterRank() + " by day change");
    }

    /** Records symbols the instrument master could not resolve, so a stale list is visible. */
    public void recordUnresolved(List<String> symbols) {
        recordUnresolved(symbols, s -> List.of());
    }

    /**
     * As above, with near-matches from the instrument master.
     *
     * <p>An unresolved constituent is nearly always a rename or a demerger rather than a typo, and
     * the symptom — one stock quietly never evaluated — gives no clue what it became. Naming the
     * candidates is what makes it a one-line fix instead of an investigation.</p>
     */
    public void recordUnresolved(List<String> symbols,
                                 java.util.function.Function<String, List<String>> suggest) {
        unresolved.set(List.copyOf(symbols));
        if (symbols.isEmpty()) return;

        log.error("{} configured symbol(s) have no instrument token and will never tick — "
                + "a stale index constituent or a typo: {}", symbols.size(), symbols);
        for (String symbol : symbols) {
            List<String> candidates = suggest.apply(symbol);
            if (!candidates.isEmpty()) {
                log.error("  {} did not resolve — the closest NSE equities are {}. If one of these "
                        + "is the renamed listing, update equity.universe.symbols.", symbol, candidates);
            }
        }
    }

    /**
     * Re-ranks the board.
     *
     * <p>Always runs, because the rank is carried on {@code SharedInstrumentState} and is worth
     * having for display and later analysis. It only <b>gates</b> anything when {@code evaluateAll}
     * is off.</p>
     */
    public synchronized void refreshDiscoverySet() {
        Collection<SharedInstrumentState> universe = structure.all();
        if (universe.isEmpty()) return;

        List<TopGainerEngine.Ranked> ranked = gainers.rank(universe);
        Set<String> previous = discoverySet.get();
        Set<String> next = new LinkedHashSet<>();

        for (TopGainerEngine.Ranked r : ranked) {
            structure.applyRank(r.symbol(), r.rank(), r.previousRank());
            if (gainers.inDiscoverySet(r.rank(), previous.contains(r.symbol()))) {
                next.add(r.symbol());
            }
        }
        if (properties.isEvaluateAll()) {
            return;   // ranking is informational; membership does not change
        }
        discoverySet.set(Set.copyOf(next));
        promoteToFull(next, previous);
    }

    /**
     * Moves the depth subscription to follow the discovery set.
     *
     * <p>Demotion matters as much as promotion: leaving every symbol that was ever a candidate in
     * FULL means the bandwidth saving decays to nothing over a session.</p>
     */
    private void promoteToFull(Set<String> current, Set<String> previous) {
        List<String> promote = current.stream()
                .filter(s -> !inFullMode.contains(s) && subscribed.contains(s)).toList();
        List<String> demote = new ArrayList<>(inFullMode);
        demote.removeAll(current);

        if (!promote.isEmpty()) {
            marketData.setMode(promote, SubscriptionMode.FULL);
            inFullMode.addAll(promote);
        }
        if (!demote.isEmpty()) {
            marketData.setMode(demote, SubscriptionMode.QUOTE);
            demote.forEach(inFullMode::remove);
        }
        if (!promote.isEmpty() || !demote.isEmpty()) {
            log.debug("discovery set {} -> {} (+{} full, -{})",
                    previous.size(), current.size(), promote.size(), demote.size());
        }
    }

    public Set<String> discoverySet()      { return discoverySet.get(); }

    public Set<String> subscribedSymbols() { return Set.copyOf(subscribed); }

    public List<String> unresolvedSymbols() { return unresolved.get(); }

    public int fullModeCount()             { return inFullMode.size(); }

    public boolean isEvaluatingAll()       { return properties.isEvaluateAll(); }

    public boolean isCandidate(String symbol) { return discoverySet.get().contains(symbol); }
}
