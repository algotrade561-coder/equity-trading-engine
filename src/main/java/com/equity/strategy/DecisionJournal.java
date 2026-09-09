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
        offer(row("intent", userId, state, setup)
                + ",\"entry\":" + num(entry)
                + ",\"stop\":" + num(stop)
                + ",\"target\":" + num(target)
                + ",\"riskPerShare\":" + num(entry - stop)
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
                + ",\"rank\":" + s.currentGainerRank();
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
