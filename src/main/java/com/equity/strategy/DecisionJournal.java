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

    /** A setup advancing or dying. These rows are what make the day a funnel rather than a tally. */
    public void transition(UserId userId, SharedInstrumentState state, SetupState setup,
                           String from, String to) {
        if (!enabled) return;
        offer(row("transition", userId, state, setup)
                + ",\"from\":" + quote(from) + ",\"to\":" + quote(to) + "}");
    }

    /** A setup that triggered. The numerator of every hit rate worth computing. */
    public void intent(UserId userId, SharedInstrumentState state, SetupState setup,
                       double entry, double stop, double target, boolean armed) {
        if (!enabled) return;
        offer(row("intent", userId, state, setup)
                + ",\"entry\":" + num(entry)
                + ",\"stop\":" + num(stop)
                + ",\"target\":" + num(target)
                + ",\"riskPerShare\":" + num(entry - stop)
                + ",\"userArmed\":" + armed
                + "}");
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
