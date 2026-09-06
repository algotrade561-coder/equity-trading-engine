package com.equity.risk;

import com.equity.broker.BrokerPort;
import com.equity.broker.OrderRequest;
import com.equity.broker.OrderSide;
import com.equity.broker.ProductType;
import com.equity.domain.user.UserId;
import com.equity.platform.time.TradingClock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * What the broker will block per share, by symbol, for an intraday position.
 *
 * <h2>Why this exists</h2>
 * <p>The risk check compared the full notional against available cash, which is the wrong number for
 * an intraday product. RELIANCE at 1322 needs roughly 264 a share under MIS, so a 75-share position
 * needs about 19,800 rather than 99,150 — an account with 29,272 of margin was being refused trades
 * the broker would have accepted, and the refusal read as {@code INSUFFICIENT_MARGIN}, which looked
 * like a funding problem rather than an arithmetic one.</p>
 *
 * <p>The leverage is not a constant to hardcode. It differs per scrip, is capped by regulation that
 * has already been tightened twice, and Zerodha revises its list without announcing it. A stale
 * multiple in a config file is a number that is silently wrong in whichever direction hurts: too low
 * and entries are refused, too high and the exchange rejects the order after the engine has already
 * committed to it. So it is asked for, per symbol, and only believed for the day it was asked.</p>
 *
 * <h2>Why it is a cache and not a call</h2>
 * <p>{@link RiskEngine} runs on the feed thread. An HTTP round trip there stalls every instrument in
 * the universe behind one margin lookup, and Kite disconnects a client that falls behind — the same
 * reasoning as {@link MarginCache}. Requirements are fetched on the scheduler and read from memory.</p>
 *
 * <h2>What is assumed</h2>
 * <p>One probe at quantity 1 gives the per-share figure, which is then multiplied. Equity intraday
 * margin is proportional to turnover, so this holds; brokerage and taxes are not proportional, but
 * they are not margin either and Kite reports them separately. The result is clamped to the notional,
 * because a requirement above the full cash price of the shares would mean the probe was misread.</p>
 */
@Component
public class MarginRequirements {

    private static final Logger log = LoggerFactory.getLogger(MarginRequirements.class);

    /** A probe is an order the engine never sends, but it still has to be a valid one. */
    private static final String PROBE_TAG = "margin-probe";

    private record Key(String symbol, ProductType product) {}

    private final BrokerPort broker;
    private final TradingClock clock;

    private final Map<Key, Double> perShare = new ConcurrentHashMap<>();
    /** Symbols already asked about today, successfully or not, so a refusal is not retried forever. */
    private final Map<Key, LocalDate> asked = new ConcurrentHashMap<>();
    private volatile LocalDate knownFor;

    public MarginRequirements(BrokerPort broker, TradingClock clock) {
        this.broker = broker;
        this.clock = clock;
    }

    /**
     * The margin needed to hold {@code quantity} shares, or the notional when it is not known.
     *
     * <p>Falling back to the notional is deliberately the pessimistic answer. It is the pre-leverage
     * figure, so an unknown symbol is refused where a known one would be allowed — the failure mode
     * is a missed trade, not a position the account cannot carry.</p>
     *
     * <p>Safe to call on the feed thread; it only reads a map.</p>
     */
    public double requiredFor(String symbol, ProductType product, int quantity, double notional) {
        expireAtDateChange();
        Double rate = perShare.get(new Key(symbol, product));
        if (rate == null) return notional;
        return Math.min(rate * quantity, notional);
    }

    /** True when the figure came from the broker rather than from the fallback. */
    public boolean isKnown(String symbol, ProductType product) {
        expireAtDateChange();
        return perShare.containsKey(new Key(symbol, product));
    }

    /**
     * Learns the requirement for any of {@code symbols} not already asked about today.
     *
     * <p>Called from the scheduler, never from the tick path. Only the discovery set is worth asking
     * about — those are the symbols that can actually produce an entry — so this stays a handful of
     * requests a day rather than one per instrument.</p>
     */
    public void prime(UserId userId, ProductType product, Collection<String> symbols) {
        if (!broker.isAuthenticated(userId)) return;
        expireAtDateChange();
        LocalDate today = clock.tradingDate();

        for (String symbol : symbols) {
            Key key = new Key(symbol, product);
            if (today.equals(asked.get(key))) continue;
            asked.put(key, today);

            try {
                OrderRequest probe = new OrderRequest(symbol, "NSE", OrderSide.BUY, 1,
                        com.equity.broker.OrderType.MARKET, product, 0, 0,
                        com.equity.broker.OrderVariety.REGULAR, PROBE_TAG);
                double required = broker.requiredMargin(userId, probe);
                if (Double.isNaN(required) || required <= 0) continue;

                perShare.put(key, required);
                log.info("margin requirement {} {}: {} per share", symbol, product,
                        String.format("%.2f", required));

            } catch (RuntimeException e) {
                // One unanswerable symbol must not stop the rest of the basket being primed.
                log.warn("could not read the {} margin for {}: {}", product, symbol, e.getMessage());
            }
        }
    }

    /**
     * Leverage is set per day and can be cut intraday during volatility. Carrying yesterday's figure
     * into today would let the engine size against a limit the broker no longer offers.
     */
    private void expireAtDateChange() {
        LocalDate today = clock.tradingDate();
        if (!today.equals(knownFor)) {
            perShare.clear();
            asked.clear();
            knownFor = today;
        }
    }

    /** Test and operator hook — forces the next prime to ask again. */
    public void clear() {
        perShare.clear();
        asked.clear();
    }
}
