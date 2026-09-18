package com.equity.strategy;

import com.equity.domain.market.Tick;
import com.equity.domain.order.TradeIntent;
import com.equity.domain.position.ExitReason;
import com.equity.domain.position.TradeCost;
import com.equity.domain.risk.RiskLimits;
import com.equity.domain.user.UserId;
import com.equity.platform.time.TradingClock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Follows every intent to its conclusion on paper, whether or not an order was sent.
 *
 * <h2>Why</h2>
 * <p>A disarmed session still finds setups and still records the intents, but an intent with no
 * outcome cannot be scored: the journal says "would have bought here" and nothing about what
 * happened next. So a week spent collecting evidence without trading collected nothing that could
 * tune the entry. This closes that gap. From the moment an intent is raised, a paper position is
 * opened at the trigger price and walked forward on the live ticks with the same exits as a real
 * one — hard stop, target, time stop, square-off — and its outcome is journaled beside the intent,
 * with the same excursion figures a real outcome carries.</p>
 *
 * <h2>What it is, and is not</h2>
 * <p>It is a measurement of the <em>entry decision</em>. It is not a simulation of the account:
 * paper positions ignore the open-position cap, the daily attempt count, the loss latch and the
 * margin, so a disarmed day will show more shadow trades than the account could have taken —
 * which is the point, since more resolved entries is more evidence. It runs for armed users too,
 * so the same trade has a paper outcome and a real one and the difference between them is the
 * cost of execution.</p>
 *
 * <h2>Fills</h2>
 * <p>Entry at the trigger tick plus 0.03%, exits at the tick that crossed the level less 0.03% —
 * the slippage the live fills have averaged. Charges as the real book computes them. So the
 * paper P&amp;L is comparable to a real net P&amp;L, not a best case.</p>
 *
 * <h2>Cost</h2>
 * <p>One map lookup per tick for symbols with a paper position and nothing for the rest. No I/O on
 * the feed thread; the outcome row goes to the journal's queue like every other row.</p>
 */
@Component
public class ShadowTrader {

    private static final Logger log = LoggerFactory.getLogger(ShadowTrader.class);
    /** Adverse slippage applied to every paper fill, as a fraction. Live fills average about this. */
    static final double SLIPPAGE = 0.0003;

    /** A paper position and everything the outcome row needs. */
    public static final class Paper {
        public final UserId userId;
        public final String symbol;
        public final String pattern;
        public final boolean userWasArmed;
        public final Instant intentAt;
        public final Instant openedAt;
        public final double intended;
        public final double entry;
        public final double stop;
        public final double target;
        public final int quantity;
        public final int timeStopMinutes;
        public final LocalTime squareOff;
        volatile double high;
        volatile double low;
        volatile boolean closed;

        Paper(UserId userId, String symbol, String pattern, boolean userWasArmed, Instant at,
              double intended, double entry, double stop, double target, int quantity,
              int timeStopMinutes, LocalTime squareOff) {
            this.userId = userId;
            this.symbol = symbol;
            this.pattern = pattern;
            this.userWasArmed = userWasArmed;
            this.intentAt = at;
            this.openedAt = at;
            this.intended = intended;
            this.entry = entry;
            this.stop = stop;
            this.target = target;
            this.quantity = quantity;
            this.timeStopMinutes = timeStopMinutes;
            this.squareOff = squareOff;
            this.high = entry;
            this.low = entry;
        }

        public double riskPerShare() { return entry - stop; }
        public double favourableExcursionR() { return riskPerShare() > 0 ? (high - entry) / riskPerShare() : 0; }
        public double adverseExcursionR() { return riskPerShare() > 0 ? (entry - low) / riskPerShare() : 0; }
    }

    /** What the journal receives when a paper position closes. */
    public record Outcome(Paper paper, Instant closedAt, double exit, ExitReason reason,
                          double grossPnl, double charges, double netPnl) {}

    private final TradingClock clock;
    private final Map<String, List<Paper>> open = new ConcurrentHashMap<>();
    private volatile Consumer<Outcome> listener = o -> {};

    public ShadowTrader(TradingClock clock) {
        this.clock = clock;
    }

    public void onOutcome(Consumer<Outcome> listener) {
        this.listener = listener;
    }

    /**
     * Opens a paper position for an intent. Sized exactly as the risk engine would size the real
     * one — rupee risk over risk per share, capped by notional — so the P&amp;L is in the same
     * units as a real trade's.
     */
    public void open(TradeIntent intent, String pattern, boolean userArmed, RiskLimits limits,
                     int timeStopMinutes, LocalTime squareOff) {
        double risk = intent.riskPerShare();
        if (!(risk > 0) || !(intent.referencePrice() > 0)) return;
        int quantity = (int) Math.floor(limits.riskPerTradeRupees() / risk);
        int byValue = (int) Math.floor(limits.maxPositionValue() / intent.referencePrice());
        quantity = Math.min(quantity, byValue);
        if (quantity <= 0) return;

        double entry = round2(intent.referencePrice() * (1 + SLIPPAGE));
        Paper p = new Paper(intent.userId(), intent.symbol(), pattern, userArmed, clock.now(),
                intent.referencePrice(), entry, intent.stopPrice(), intent.targetPrice(),
                quantity, timeStopMinutes, squareOff);
        open.computeIfAbsent(intent.symbol(), s -> new CopyOnWriteArrayList<>()).add(p);
        log.info("SHADOW OPEN {} {} x{} at {} stop {} target {} ({})", p.userId, p.symbol, quantity,
                entry, p.stop, p.target, userArmed ? "also traded live" : "user not armed");
    }

    /** On the feed thread. Cheap when the symbol has no paper position. */
    public void onTick(Tick tick) {
        List<Paper> papers = open.get(tick.symbol());
        if (papers == null || papers.isEmpty()) return;
        double price = tick.lastPrice();
        if (!(price > 0)) return;
        for (Paper p : papers) {
            if (p.closed) continue;
            if (price > p.high) p.high = price;
            if (price < p.low) p.low = price;
            if (price <= p.stop) {
                close(p, price, ExitReason.HARD_STOP);
            } else if (price >= p.target) {
                close(p, price, ExitReason.TARGET);
            }
        }
    }

    /** On the scheduler, every few seconds: time stops and the square-off. */
    public void checkClocks(java.util.function.ToDoubleFunction<String> lastPrice) {
        Instant now = clock.now();
        LocalTime time = clock.timeOfDay();
        for (Map.Entry<String, List<Paper>> e : open.entrySet()) {
            for (Paper p : e.getValue()) {
                if (p.closed) continue;
                double price = lastPrice.applyAsDouble(p.symbol);
                if (!(price > 0)) continue;
                if (!time.isBefore(p.squareOff)) {
                    close(p, price, ExitReason.SQUARE_OFF);
                } else if (p.openedAt.plus(Duration.ofMinutes(p.timeStopMinutes)).isBefore(now)) {
                    close(p, price, ExitReason.TIME_STOP);
                }
            }
        }
    }

    private void close(Paper p, double price, ExitReason reason) {
        synchronized (p) {
            if (p.closed) return;
            p.closed = true;
        }
        double exit = round2(price * (1 - SLIPPAGE));
        double gross = (exit - p.entry) * p.quantity;
        double charges = TradeCost.forRoundTrip(p.entry * p.quantity, exit * p.quantity).total();
        Outcome outcome = new Outcome(p, clock.now(), exit, reason, gross, charges, gross - charges);
        List<Paper> papers = open.get(p.symbol);
        if (papers != null) papers.remove(p);
        log.info("SHADOW CLOSE {} {} {} at {} net {} (mfe {}R mae {}R)", p.userId, p.symbol, reason, exit,
                String.format("%.0f", outcome.netPnl()), String.format("%.2f", p.favourableExcursionR()),
                String.format("%.2f", p.adverseExcursionR()));
        try {
            listener.accept(outcome);
        } catch (RuntimeException ex) {
            log.warn("shadow outcome listener threw: {}", ex.toString());
        }
    }

    public int openCount() {
        return open.values().stream().mapToInt(List::size).sum();
    }

    /** Anything still open at the end of the session is closed at its last price and journaled. */
    public void closeAll(java.util.function.ToDoubleFunction<String> lastPrice, ExitReason reason) {
        for (List<Paper> papers : new ArrayList<>(open.values())) {
            for (Paper p : papers) {
                double price = lastPrice.applyAsDouble(p.symbol);
                if (price > 0) close(p, price, reason);
            }
        }
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
