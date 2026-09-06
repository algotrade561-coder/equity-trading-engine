package com.equity.market.universe;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The stocks the engine watches, and how many of them it watches closely.
 *
 * <h2>The universe is fetched, not typed</h2>
 * <p>There is no constituent list in this file. There was one, and it rotted: two of its fifty
 * entries had been renamed by the exchange, resolved to no instrument token, and were silently never
 * evaluated while the engine went on reporting a universe of fifty. Nothing failed and nothing
 * complained. At five hundred names, hand-maintenance is not a plan at all.</p>
 *
 * <p>{@link IndexConstituentSource} reads the constituents from the exchange's own published CSV, so
 * renames, additions and removals arrive on their own. {@link #symbols} exists only as a deliberate
 * override — set it and the fetch is skipped entirely.</p>
 *
 * <h2>Why the whole index is not watched in full depth</h2>
 * <p>The risk check refuses any entry without order-book depth, and depth subscriptions are capped
 * far below the number of instruments a feed will carry — 200 per connection at Kite. A five-hundred
 * stock universe therefore has to be watched cheaply and promoted selectively: everything streams
 * quotes, the day's strongest names are promoted to full depth, and only those can produce an entry.
 * That is what {@link #enterRank} and {@link #exitRank} govern, and it is why {@link #evaluateAll}
 * defaults to false. Turning it on subscribes everything in full and will exceed the cap on any
 * universe larger than the limit — {@link UniverseService} refuses rather than letting the feed
 * silently drop the excess.</p>
 */
@ConfigurationProperties(prefix = "equity.universe")
public class UniverseProperties {

    /**
     * Explicit override. Empty — the normal case — means the index is fetched.
     *
     * <p>Set it to pin the universe to a handful of names for a test, or to work around an exchange
     * file that has gone wrong. Anything set here is used verbatim and nothing is fetched.</p>
     */
    private List<String> symbols = new ArrayList<>();

    /** Shown in logs and on the console. Purely descriptive. */
    private String indexName = "NIFTY 500";

    /**
     * The exchange's published constituent file. Public and unauthenticated.
     *
     * <p>Point it at a different index to trade a different universe — the NIFTY 50, 100 and 200
     * files live alongside this one under the same path and in the same format.</p>
     */
    private String indexUrl =
            "https://nsearchives.nseindia.com/content/indices/ind_nifty500list.csv";

    /**
     * Where the last successful fetch is kept.
     *
     * <p>A briefly unreachable CSV host at 09:15 must not mean an empty universe, and membership
     * changes a handful of times a year, so a cached copy is a good answer for a session.</p>
     */
    private String cacheFile = "./data/universe-constituents.txt";

    /**
     * Evaluate every subscribed stock rather than only the top of the day-change ranking.
     *
     * <p>Only viable for a universe small enough to sit inside the feed's depth-subscription cap,
     * because an entry needs depth and this puts everything into full mode at once. False is the
     * correct setting for anything index-sized.</p>
     */
    private boolean evaluateAll = false;

    /** Rank at or above which a stock enters the discovery set and is promoted to full depth. */
    private int enterRank = 20;

    /**
     * Rank at which it leaves again.
     *
     * <p>Wider than {@link #enterRank} on purpose. With one threshold a stock hovering at the
     * boundary is promoted and demoted repeatedly, and each cycle costs a subscription change and
     * discards the depth that had just started arriving.</p>
     */
    private int exitRank = 35;

    /**
     * A day change beyond this is treated as a corporate action rather than a move.
     *
     * <p>A split or a bonus shows up in the feed as a price that has halved against a previous close
     * the exchange has not adjusted yet. Ranking treats it as the day's biggest loser and the
     * strategy would otherwise take it seriously.</p>
     */
    private double sanityBandPercent = 25.0;

    /** When false the engine watches nothing, which is the safe state for an unattended process. */
    private boolean autoSubscribe = true;

    public List<String> getSymbols() { return symbols; }
    public void setSymbols(List<String> symbols) { this.symbols = symbols; }

    public String getIndexName() { return indexName; }
    public void setIndexName(String indexName) { this.indexName = indexName; }

    public String getIndexUrl() { return indexUrl; }
    public void setIndexUrl(String indexUrl) { this.indexUrl = indexUrl; }

    public String getCacheFile() { return cacheFile; }
    public void setCacheFile(String cacheFile) { this.cacheFile = cacheFile; }

    public boolean isEvaluateAll() { return evaluateAll; }
    public void setEvaluateAll(boolean evaluateAll) { this.evaluateAll = evaluateAll; }

    public int getEnterRank() { return enterRank; }
    public void setEnterRank(int enterRank) { this.enterRank = enterRank; }

    public int getExitRank() { return exitRank; }
    public void setExitRank(int exitRank) { this.exitRank = exitRank; }

    public double getSanityBandPercent() { return sanityBandPercent; }
    public void setSanityBandPercent(double v) { this.sanityBandPercent = v; }

    public boolean isAutoSubscribe() { return autoSubscribe; }
    public void setAutoSubscribe(boolean autoSubscribe) { this.autoSubscribe = autoSubscribe; }
}
