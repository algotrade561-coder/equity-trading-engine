package com.equity.strategy;

import com.equity.domain.momentum.RejectionStage;
import com.equity.domain.risk.DenialReason;
import com.equity.domain.user.UserId;
import com.equity.platform.time.TradingClock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Counts every candidate that did not become a trade, and why.
 *
 * <p>This is not diagnostics. It is the only way to distinguish a gate that filters noise from a
 * gate that blocks the best candidates, and that distinction has been measured going the wrong way:
 * in a sibling engine a run-up gate was found to be rejecting the trades with the strongest forward
 * returns, which was invisible for months because only accepted candidates were ever recorded.</p>
 *
 * <p>In memory and per session for now — the durable table is part of the persistence phase. The
 * counts are the point; the recent samples exist so a surprising count can be inspected without
 * waiting for the next occurrence.</p>
 */
@Component
public class RejectionLog {

    private static final int SAMPLE_LIMIT = 200;

    public record Sample(Instant at, UserId userId, String symbol,
                         String stage, String condition, String detail) {}

    private final TradingClock clock;

    private final Map<RejectionStage, AtomicLong> byStage = new EnumMap<>(RejectionStage.class);
    private final Map<String, AtomicLong> byCondition = new ConcurrentHashMap<>();
    private final Map<DenialReason, AtomicLong> byDenial = new EnumMap<>(DenialReason.class);
    private final Deque<Sample> recent = new ArrayDeque<>();
    private final AtomicLong intents = new AtomicLong();
    private final AtomicLong shadowIntents = new AtomicLong();

    public RejectionLog(TradingClock clock) {
        this.clock = clock;
        for (RejectionStage s : RejectionStage.values()) byStage.put(s, new AtomicLong());
        for (DenialReason r : DenialReason.values()) byDenial.put(r, new AtomicLong());
    }

    public void recordStrategyRejection(UserId userId, String symbol, StrategySignal signal) {
        if (!signal.isRejected()) return;
        byStage.get(signal.stage()).incrementAndGet();
        byCondition.computeIfAbsent(signal.stage() + "." + signal.condition(),
                k -> new AtomicLong()).incrementAndGet();
        addSample(new Sample(clock.now(), userId, symbol,
                signal.stage().name(), signal.condition(), signal.detail()));
    }

    public void recordRiskDenial(UserId userId, String symbol, DenialReason reason, String detail) {
        byStage.get(RejectionStage.RISK).incrementAndGet();
        byDenial.get(reason).incrementAndGet();
        byCondition.computeIfAbsent("RISK." + reason.name(), k -> new AtomicLong()).incrementAndGet();
        addSample(new Sample(clock.now(), userId, symbol, "RISK", reason.name(), detail));
    }

    public void recordExecutionFailure(UserId userId, String symbol, String detail) {
        byStage.get(RejectionStage.EXECUTION).incrementAndGet();
        byCondition.computeIfAbsent("EXECUTION.brokerRefused", k -> new AtomicLong()).incrementAndGet();
        addSample(new Sample(clock.now(), userId, symbol, "EXECUTION", "brokerRefused", detail));
    }

    public void recordIntent() { intents.incrementAndGet(); }

    /**
     * A setup that triggered for a user who is not armed.
     *
     * <p>Counted separately from a real intent because it is the headline number of a shadow
     * session: it is the answer to "would this engine have traded today, and where".</p>
     */
    public void recordShadowIntent(UserId userId, String symbol, String rationale) {
        shadowIntents.incrementAndGet();
        addSample(new Sample(clock.now(), userId, symbol, "SHADOW", "wouldHaveEntered", rationale));
    }

    public long shadowIntentCount() { return shadowIntents.get(); }

    private synchronized void addSample(Sample sample) {
        recent.addFirst(sample);
        while (recent.size() > SAMPLE_LIMIT) recent.removeLast();
    }

    public long intentCount() { return intents.get(); }

    public Map<String, Long> countsByStage() {
        Map<String, Long> out = new LinkedHashMap<>();
        byStage.forEach((stage, count) -> {
            if (count.get() > 0) out.put(stage.name(), count.get());
        });
        return out;
    }

    /** Condition counts, most frequent first — the shape you actually read. */
    public Map<String, Long> countsByCondition() {
        return byCondition.entrySet().stream()
                .sorted(Map.Entry.<String, AtomicLong>comparingByValue(
                        java.util.Comparator.comparingLong(AtomicLong::get)).reversed())
                .collect(LinkedHashMap::new,
                        (m, e) -> m.put(e.getKey(), e.getValue().get()),
                        LinkedHashMap::putAll);
    }

    public synchronized List<Sample> recentSamples(int limit) {
        return recent.stream().limit(Math.max(1, limit)).toList();
    }

    public synchronized void reset() {
        byStage.values().forEach(c -> c.set(0));
        byDenial.values().forEach(c -> c.set(0));
        byCondition.clear();
        recent.clear();
        intents.set(0);
        shadowIntents.set(0);
    }
}
