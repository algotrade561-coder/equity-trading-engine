package com.equity.strategy;

import com.equity.domain.market.SharedInstrumentState;
import com.equity.domain.user.UserId;
import com.equity.platform.time.TradingClock;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * One line per strategy decision, with the numbers behind it, so a threshold can be tuned against
 * evidence rather than recollection.
 *
 * <h2>Why the log is not enough</h2>
 * <p>{@link RejectionLog} keeps two hundred samples in a ring buffer and cumulative counters, all in
 * memory. At twenty symbols evaluated a minute that buffer holds roughly the last ten seconds, and a
 * restart discards everything. It answers "what is happening right now", which is what it was for.</p>
 *
 * <p>It cannot answer the question that actually decides whether a threshold is right: <i>how many
 * setups would have armed if the limit had been different?</i> Answering that needs the value that
 * failed, not the name of the condition that failed — "belowVwap 400" says nothing, while four
 * hundred rows carrying how far below VWAP each one was says everything.</p>
 *
 * <h2>The funnel is the point</h2>
 * <p>Recording rejections alone would still mislead, because it counts the same setup dying over and
 * over. Every state transition is recorded too, so the day reduces to a funnel — how many reached
 * IMPULSE, how many paused, how many armed, how many triggered — and the stage where the count
 * collapses is the threshold worth arguing about.</p>
 *
 * <h2>Not on the feed thread</h2>
 * <p>Records are queued and written by the scheduler. The tick path must not touch a file: a slow
 * disk would stall every instrument behind one write, and Kite drops a client that falls behind. The
 * queue is bounded and drops rather than grows — losing journal rows is acceptable, stalling the
 * feed is not, and the drop count is reported so the loss is never silent.</p>
 */
@Component
public class DecisionJournal {

    private static final Logger log = LoggerFactory.getLogger(DecisionJournal.class);

    /**
     * Bounded so a stalled writer cannot consume the heap. Sized for several minutes of decisions at
     * the observed rate, which is far longer than any plausible write hiccup.
     */
    private static final int MAX_QUEUED = 50_000;

    private final TradingClock clock;
    private final boolean enabled;
    private final Path directory;
    /**
     * Where the market is when each row is written. Optional so the journal can exist without it
     * (tests, tools); when absent the market fields are simply omitted from every row.
     */
    private volatile java.util.function.Supplier<com.equity.market.state.MarketContext.Snapshot> market = null;

    private final ConcurrentLinkedQueue<String> pending = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private volatile LocalDate openFor;

    public DecisionJournal(TradingClock clock,
                           @Value("${equity.journal.enabled:true}") boolean enabled,
                           @Value("${equity.journal.directory:./data/journal}") String directory) {
        this.clock = clock;
        this.enabled = enabled;
        this.directory = Path.of(directory);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DecisionJournal(TradingClock clock,
                           @Value("${equity.journal.enabled:true}") boolean enabled,
                           @Value("${equity.journal.directory:./data/journal}") String directory,
                           com.equity.market.state.MarketContext marketContext) {
        this(clock, enabled, directory);
        this.market = marketContext::snapshot;
    }

    // ── Shadow gates ─────────────────────────────────────────────────────────
    // Candidate entry filters, evaluated and RECORDED at every intent but never acted on. Each is a
    // hypothesis from the September 2026 sessions about which entries fail; the journal accumulates
    // the evidence that says whether any of them deserves to become a real gate. Thresholds are
    // constants here on purpose: a shadow gate that can be tuned is a shadow gate nobody can read.
    static final double SHADOW_MAX_CLIMAX_ATR = 3.0;     // run-up made of one bar taller than 3 ATR
    static final double SHADOW_MAX_RET15M_PCT = 1.5;     // fifteen-minute return at the trigger
    static final double SHADOW_MAX_VWAP_EXT_PCT = 1.6;   // distance above session VWAP at the trigger
    // The market gate is about direction, not level. 11 September (7 of 13 won) never had more than
    // a third of the universe green, but the index rose from the open all day; 15 September (0 of 3)
    // had better breadth at the open and the index fell one per cent in a straight line. So the
    // gate reads the index's path — not down from the open, not falling over the last hour — and
    // breadth is recorded beside it rather than tested.
    static final double SHADOW_MIN_NIFTY_FROM_OPEN = -0.3;   // the index not already down on the day
    static final double SHADOW_MIN_NIFTY_RET60M = -0.2;      // and not sliding over the last hour
    // Added 18 September after the first three gates inverted on the week's real fills: the trades
    // that failed them were the winners. What did separate winners was freshness of the move and
    // where the stock sat on the board.
    static final long SHADOW_MAX_MINUTES_SINCE_IMPULSE = 6;  // the impulse began within this many minutes
    static final int SHADOW_MAX_RANK = 15;                   // top of the gainers board at the trigger
    static final double SHADOW_MIN_RET5M_PCT = 0.5;          // still accelerating into the trigger

    private String shadowGates(SharedInstrumentState s, SetupState setup,
                               com.equity.market.state.MarketContext.Snapshot m) {
        long sinceImpulse = setup.impulseStartedAt() == null ? Long.MAX_VALUE
                : java.time.Duration.between(setup.impulseStartedAt(), clock.now()).toMinutes();
        boolean freshOk = sinceImpulse <= SHADOW_MAX_MINUTES_SINCE_IMPULSE;
        boolean rankOk = s.currentGainerRank() > 0 && s.currentGainerRank() <= SHADOW_MAX_RANK;
        boolean ret5Ok = s.return5m() >= SHADOW_MIN_RET5M_PCT;
        boolean climaxOk = !(setup.climaxBarAtr() > SHADOW_MAX_CLIMAX_ATR);
        boolean ret15Ok = !(s.return15m() > SHADOW_MAX_RET15M_PCT);
        boolean vwapOk = !(s.distanceFromVwapPercent() > SHADOW_MAX_VWAP_EXT_PCT);
        boolean marketOk = m != null
                && !(m.indexChangeFromOpenPct() < SHADOW_MIN_NIFTY_FROM_OPEN)
                && !(m.indexReturn60mPct() < SHADOW_MIN_NIFTY_RET60M);
        return "\"shadowGates\":{\"climaxLe3Atr\":" + climaxOk
                + ",\"ret15Le1_5\":" + ret15Ok
                + ",\"vwapExtLe1_6\":" + vwapOk
                + ",\"marketOk\":" + marketOk
                + ",\"freshImpulseLe6m\":" + freshOk
                + ",\"rankLe15\":" + rankOk
                + ",\"ret5Ge0_5\":" + ret5Ok
                + ",\"all\":" + (climaxOk && ret15Ok && vwapOk && marketOk) + "}";
    }

    // ── Recording ────────────────────────────────────────────────────────────

    /**
     * A strategy refusal, with every value a threshold is compared against.
     *
     * <p>The full indicator snapshot goes on every row, not just the one that failed. Which field
     * matters is exactly what is unknown when the row is written, and a row missing the field you
     * later want to test is a row that has to be collected all over again tomorrow.</p>
     */
    public void rejection(UserId userId, SharedInstrumentState state, StrategySignal signal,
                          SetupState setup) {
        if (!enabled || !signal.isRejected()) return;
        offer(row("rejection", userId, state, setup)
                + ",\"stage\":\"" + signal.stage() + "\""
                + ",\"condition\":" + quote(signal.condition())
                + ",\"detail\":" + quote(signal.detail())
                + "}");
    }

    /**
     * An entry the strategy authorised that risk then refused.
     *
     * <p>Without this the funnel stops at the strategy boundary, and "the setup was found but no
     * order was placed" has no recorded answer — margin, spread, the attempt cap and the loss latch
     * all look identical from the outside. Answering it once meant querying the database by hand.</p>
     */
    public void riskDenial(UserId userId, SharedInstrumentState state, SetupState setup,
                           String reason, String detail) {
        if (!enabled) return;
        offer(row("riskDenial", userId, state, setup)
                + ",\"reason\":" + quote(reason)
                + ",\"detail\":" + quote(detail)
                + "}");
    }

    /** A setup advancing or dying. These rows are what make the day a funnel rather than a tally. */
    public void transition(UserId userId, SharedInstrumentState state, SetupState setup,
                           String from, String to) {
        if (!enabled) return;
        offer(row("transition", userId, state, setup)
                + ",\"from\":" + quote(from) + ",\"to\":" + quote(to) + "}");
    }

    /**
     * A setup that triggered. The numerator of every hit rate worth computing.
     *
     * <p>Carries the order book as it stood at the trigger. Nothing reads those fields yet — they
     * are here so that in a month "did entries into a thicker book fare better?" is a query rather
     * than an argument. Entries are MARKET orders, so resting depth decides the fill, and the spread
     * check cannot distinguish a tight quote on fifty shares from one on five thousand.</p>
     */
    public void intent(UserId userId, SharedInstrumentState state, SetupState setup,
                       double entry, double stop, double target, boolean armed,
                       com.equity.domain.market.BookState book) {
        if (!enabled) return;
        com.equity.domain.market.BookState b =
                book == null ? com.equity.domain.market.BookState.NONE : book;
        com.equity.market.state.MarketContext.Snapshot m = market == null ? null : market.get();
        double risk = entry - stop;
        offer(row("intent", userId, state, setup)
                + ",\"entry\":" + num(entry)
                + ",\"stop\":" + num(stop)
                + ",\"target\":" + num(target)
                + ",\"riskPerShare\":" + num(risk)
                // Where the entry sits in the pause, in R: 0 would be an entry at the pause low.
                // The counterfactual "enter inside the pause instead" is read off this later.
                + ",\"entryAbovePauseLowR\":" + num(risk > 0 && setup.pauseLowAtArm() > 0 ? (entry - setup.pauseLowAtArm()) / risk : Double.NaN)
                + "," + shadowGates(state, setup, m)
                + ",\"userArmed\":" + armed
                + ",\"bidQty\":" + b.bidQuantity()
                + ",\"askQty\":" + b.askQuantity()
                + ",\"totalBuyQty\":" + b.totalBuyQuantity()
                + ",\"totalSellQty\":" + b.totalSellQuantity()
                + ",\"bookImbalance\":" + num(b.imbalance())
                + ",\"lastTradedQty\":" + b.lastTradedQuantity()
                + ",\"exchangeVwap\":" + num(b.exchangeVwap())
                + "}");
    }

    /**
     * A finished trade, and what every other exit policy would have made on it.
     *
     * <p>The row that closes the loop. Rejections and intents say what the engine thought; this says
     * what happened, on the same timeline, in the same file — so a month of them can be joined on
     * symbol and time without reconciling two sources.</p>
     *
     * <p>The shadow figures are the reason to keep it. They are the only paired evidence there will
     * ever be about the exit: the same entry, the same tape, five policies, one row. Comparing the
     * live policy against a replay compares it against a different market; comparing it against
     * these compares it against itself.</p>
     *
     * <p>Does not go through {@link #row} — that needs an instrument snapshot, and this is written
     * from the order-update thread, which has a position and nothing else.</p>
     */
    public void tradeOutcome(com.equity.domain.position.Position p, ShadowExits.ShadowOutcome shadows) {
        if (!enabled || p == null || shadows == null) return;
        StringBuilder b = new StringBuilder(512);
        b.append("{\"ts\":\"").append(clock.now()).append('"')
                .append(",\"kind\":\"outcome\"")
                .append(",\"user\":\"").append(p.userId()).append('"')
                .append(",\"symbol\":").append(quote(p.symbol()))
                .append(",\"pattern\":\"").append(p.pattern() == null ? "" : p.pattern()).append('"')
                .append(",\"direction\":\"").append(p.direction()).append('"')
                .append(",\"qty\":").append(p.filledQuantity())
                .append(",\"intendedEntry\":").append(num(p.intendedEntryPrice()))
                .append(",\"entry\":").append(num(p.entryPrice()))
                .append(",\"exit\":").append(num(p.exitPrice()))
                // The stop as it was set at entry AND as it finished, because they differ whenever
                // the exit policy moved it, and every R below is measured against the first.
                .append(",\"originalStop\":").append(num(p.originalStopPrice()))
                .append(",\"finalStop\":").append(num(p.stopPrice()))
                .append(",\"target\":").append(num(p.targetPrice()))
                .append(",\"riskPerShare\":").append(num(p.riskPerShare()))
                .append(",\"exitReason\":\"").append(p.exitReason() == null ? "" : p.exitReason()).append('"')
                .append(",\"openedAt\":\"").append(p.openedAt()).append('"')
                // Quoted only when present: a literal "null" string would parse as a timestamp of
                // that name and break every duration computed over a month of these rows.
                .append(",\"closedAt\":")
                .append(p.closedAt() == null ? "null" : "\"" + p.closedAt() + "\"")
                .append(",\"pnl\":").append(num(p.realisedPnl()))
                .append(",\"netPnl\":").append(num(p.netPnl()))
                .append(",\"charges\":").append(num(p.cost().total()))
                .append(",\"mfeR\":").append(num(p.favourableExcursionR()))
                .append(",\"maeR\":").append(num(p.adverseExcursionR()))
                // How much of this trade the shadows actually saw. 0 for one watched from its
                // fill; anything else means the comparison below is missing the opening move.
                .append(",\"shadowWatchedFromR\":").append(num(shadows.watchedFromR()))
                .append(",\"shadow\":{");
        boolean first = true;
        for (java.util.Map.Entry<String, Double> e : shadows.pnl().entrySet()) {
            if (!first) b.append(',');
            b.append(quote(e.getKey())).append(':').append(num(e.getValue()));
            first = false;
        }
        offer(b.append("}}").toString());
    }

    /**
     * The shared prefix: when, who, which stock, and every indicator the strategy reads.
     *
     * <p>Hand-built rather than serialised through Jackson because this runs on the feed thread. The
     * fields are fixed and few, and a reflective serialiser on the tick path is cost paid on every
     * decision for flexibility nothing here needs.</p>
     */
    /**
     * A paper position closed. The same fields as a real outcome so the two are read by the same
     * code, plus whether the user was armed — when they were, a real outcome exists for the same
     * intent and the difference between the two is the cost of execution.
     */
    public void shadowOutcome(ShadowTrader.Outcome o) {
        if (!enabled || o == null) return;
        ShadowTrader.Paper p = o.paper();
        offer("{\"ts\":\"" + clock.now() + "\""
                + ",\"kind\":\"shadowOutcome\""
                + ",\"user\":\"" + p.userId + "\""
                + ",\"symbol\":" + quote(p.symbol)
                + ",\"pattern\":" + quote(p.pattern)
                + ",\"userArmed\":" + p.userWasArmed
                + ",\"intentTs\":\"" + p.intentAt + "\""
                + ",\"openedAt\":\"" + p.openedAt + "\""
                + ",\"closedAt\":\"" + o.closedAt() + "\""
                + ",\"qty\":" + p.quantity
                + ",\"intendedEntry\":" + num(p.intended)
                + ",\"entry\":" + num(p.entry)
                + ",\"exit\":" + num(o.exit())
                + ",\"stop\":" + num(p.stop)
                + ",\"target\":" + num(p.target)
                + ",\"riskPerShare\":" + num(p.riskPerShare())
                + ",\"exitReason\":\"" + o.reason() + "\""
                + ",\"pnl\":" + num(o.grossPnl())
                + ",\"charges\":" + num(o.charges())
                + ",\"netPnl\":" + num(o.netPnl())
                + ",\"mfeR\":" + num(p.favourableExcursionR())
                + ",\"maeR\":" + num(p.adverseExcursionR())
                + marketFields()
                + "}");
    }

    private String row(String kind, UserId userId, SharedInstrumentState s, SetupState setup) {
        return "{\"ts\":\"" + clock.now() + "\""
                + ",\"kind\":\"" + kind + "\""
                + ",\"user\":\"" + userId + "\""
                + ",\"symbol\":" + quote(s.symbol())
                + ",\"state\":\"" + (setup == null ? "NONE" : setup.state()) + "\""
                + ",\"pattern\":\"" + (setup == null || setup.pattern() == null
                        ? "" : setup.pattern()) + "\""
                + ",\"barsInPause\":" + (setup == null ? 0 : setup.barsInPause())
                + ",\"last\":" + num(s.lastPrice())
                + ",\"dayChangePct\":" + num(s.changeFromPreviousClosePercent())
                + ",\"distFromHighPct\":" + num(s.distanceFromDayHighPercent())
                + ",\"distFromVwapPct\":" + num(s.distanceFromVwapPercent())
                + ",\"vwap\":" + num(s.vwap())
                + ",\"ema9\":" + num(s.ema9())
                + ",\"ema20\":" + num(s.ema20())
                + ",\"atr\":" + num(s.atr())
                + ",\"ret1m\":" + num(s.return1m())
                + ",\"ret5m\":" + num(s.return5m())
                + ",\"ret15m\":" + num(s.return15m())
                + ",\"rvol\":" + num(s.relativeVolume())
                + ",\"rs\":" + num(s.niftyRelativeStrength())
                + ",\"rank\":" + s.currentGainerRank()
                + setupShape(setup)
                + marketFields();
    }

    /** What the run-up and pause looked like. Zeros while the setup is idle. */
    private String setupShape(SetupState setup) {
        if (setup == null) return "";
        long sinceImpulse = setup.impulseStartedAt() == null ? -1
                : java.time.Duration.between(setup.impulseStartedAt(), clock.now()).toMinutes();
        return ",\"climaxBarAtr\":" + num(setup.climaxBarAtr())
                + ",\"runUpPct\":" + num(setup.runUpPercent())
                + ",\"minutesSinceImpulse\":" + sinceImpulse
                + ",\"impulseHigh\":" + num(setup.impulseHigh())
                + ",\"pauseHigh\":" + num(setup.pauseHigh())
                + ",\"pauseLow\":" + num(setup.pauseLowAtArm() > 0 ? setup.pauseLowAtArm() : setup.structureLow())
                + ",\"trigger\":" + num(setup.triggerLevel());
    }

    private String marketFields() {
        if (market == null) return "";
        com.equity.market.state.MarketContext.Snapshot m = market.get();
        return m == null || m.at() == null ? "" : "," + m.toJsonFields();
    }

    private void offer(String line) {
        if (queued.get() >= MAX_QUEUED) {
            dropped.incrementAndGet();
            return;
        }
        pending.add(line);
        queued.incrementAndGet();
    }

    // ── Writing, off the feed thread ─────────────────────────────────────────

    /**
     * Appends whatever has accumulated. Called from the scheduler.
     *
     * @return rows written
     */
    public int flush() {
        if (!enabled || pending.isEmpty()) return 0;

        List<String> batch = new ArrayList<>();
        for (String line = pending.poll(); line != null && batch.size() < 10_000;
             line = pending.poll()) {
            batch.add(line);
            queued.decrementAndGet();
        }
        if (batch.isEmpty()) return 0;

        Path file = fileForToday();
        try {
            Files.createDirectories(file.getParent());
            try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                for (String line : batch) {
                    out.write(line);
                    out.newLine();
                }
            }
            written.addAndGet(batch.size());
            return batch.size();

        } catch (IOException e) {
            // The journal is diagnostic. Losing it must never affect trading, so this is reported
            // and abandoned rather than retried into an unbounded backlog.
            dropped.addAndGet(batch.size());
            log.warn("could not write {} journal row(s) to {}: {}",
                    batch.size(), file, e.getMessage());
            return 0;
        }
    }

    private Path fileForToday() {
        LocalDate today = clock.tradingDate();
        if (!today.equals(openFor)) {
            openFor = today;
            log.info("decision journal for {} -> {}", today,
                    directory.resolve("decisions-" + today + ".jsonl").toAbsolutePath());
        }
        return directory.resolve("decisions-" + today + ".jsonl");
    }

    public long rowsWritten() { return written.get(); }
    public long rowsDropped() { return dropped.get(); }
    public boolean isEnabled()  { return enabled; }

    // ── JSON, minimally ──────────────────────────────────────────────────────

    /** NaN and infinity are not JSON. They are common here, and null is the honest rendering. */
    private static String num(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "null";
        return String.format("%.4f", v);
    }

    private static String quote(String s) {
        if (s == null) return "null";
        StringBuilder out = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default   -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }
}
