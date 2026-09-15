package com.equity.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.equity.domain.market.BookState;
import com.equity.domain.momentum.MomentumState;
import com.equity.domain.momentum.RejectionStage;
import com.equity.domain.market.SharedInstrumentState;
import com.equity.domain.user.UserId;
import com.equity.market.state.MarketContext;
import com.equity.platform.time.FixedTradingClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every row the journal writes must parse as JSON and carry the context a later reader needs:
 * the shape of the setup, the state of the market, and — at an intent — what each shadow gate
 * would have said. A journal that is almost JSON is a month of analysis that silently skips rows.
 */
class DecisionJournalTest {

    private static final Instant NOW = Instant.parse("2026-09-15T05:23:16Z");   // 10:53:16 IST
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path dir;

    private static SharedInstrumentState state(double last, double vwap, double ret15m, double atr) {
        return new SharedInstrumentState("SAGILITY", 45.2, 45.5, 46.43, 45.1, last, 2_000_000,
                vwap, 46.0, 45.9, atr, 0.1, 0.3, 0.45, ret15m, 3.1, 7, 9, 0.6, Double.NaN, NOW);
    }

    private static MarketContext.Snapshot downDay() {
        return new MarketContext.Snapshot(NOW, 23335.0, 23576.15, -1.02, -0.2, -0.6, 480, 22.0, 18.0, 6.0, 41.0);
    }

    private static SetupState armedSetup() {
        SetupState setup = new SetupState();
        setup.beginImpulse(46.36, NOW.minusSeconds(300));
        setup.describeRunUp(3.2, 1.0);
        setup.beginPause(MomentumState.CONSOLIDATION, com.equity.domain.momentum.EntryPattern.CONSOLIDATION_BREAKOUT, 46.08, NOW.minusSeconds(240));
        setup.extendPause(46.08);   // the pause low only ratchets upward; equal lows leave it
        setup.extendPause(46.05);
        setup.arm(46.36, 46.34, NOW.minusSeconds(16));
        return setup;
    }

    private List<JsonNode> rowsOf(DecisionJournal journal) throws IOException {
        journal.flush();
        Path file = dir.resolve("decisions-2026-09-15.jsonl");
        assertThat(file).exists();
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream().map(line -> {
            try {
                return JSON.readTree(line);
            } catch (IOException e) {
                throw new AssertionError("row is not JSON: " + line, e);
            }
        }).toList();
    }

    @Test
    void anIntentCarriesTheMarketTheSetupShapeAndEveryShadowGate() throws IOException {
        DecisionJournal journal = new DecisionJournal(new FixedTradingClock(NOW), true, dir.toString(),
                new MarketContext(null, null, null) {
                    @Override public Snapshot snapshot() { return downDay(); }
                });
        SetupState setup = armedSetup();

        journal.intent(UserId.random(), state(46.37, 45.8856, 0.89, 0.1207), setup,
                46.37, 46.10, 46.73, true, BookState.NONE);

        JsonNode row = rowsOf(journal).get(0);
        assertThat(row.path("kind").asText()).isEqualTo("intent");
        // the market
        assertThat(row.path("niftyFromOpenPct").asDouble()).isEqualTo(-1.02);
        assertThat(row.path("pctUpOnDay").asDouble()).isEqualTo(22.0);
        assertThat(row.path("breadthUniverse").asInt()).isEqualTo(480);
        // the setup's shape
        assertThat(row.path("climaxBarAtr").asDouble()).isEqualTo(3.2);
        assertThat(row.path("pauseHigh").asDouble()).isEqualTo(46.34);
        assertThat(row.path("pauseLow").asDouble()).isEqualTo(46.08);
        assertThat(row.path("trigger").asDouble()).isEqualTo(46.36);
        assertThat(row.path("minutesSinceImpulse").asInt()).isEqualTo(5);
        assertThat(row.path("entryAbovePauseLowR").asDouble())
                .as("(46.37 - 46.08) / (46.37 - 46.10): the entry sat about one R above the pause low")
                .isCloseTo(1.074, org.assertj.core.data.Offset.offset(0.001));
        // the shadow gates, each a verdict — this trade fails the climax and market gates (the
        // index is down 1.02% from the open and 0.6% over the last hour)
        JsonNode gates = row.path("shadowGates");
        assertThat(gates.path("climaxLe3Atr").asBoolean()).isFalse();
        assertThat(gates.path("ret15Le1_5").asBoolean()).isTrue();
        assertThat(gates.path("vwapExtLe1_6").asBoolean()).isTrue();
        assertThat(gates.path("marketOk").asBoolean()).isFalse();
        assertThat(gates.path("all").asBoolean()).isFalse();
    }

    @Test
    void aRejectionCarriesTheMarketToo_soRefusalsCanBeJudgedAgainstTheDay() throws IOException {
        DecisionJournal journal = new DecisionJournal(new FixedTradingClock(NOW), true, dir.toString(),
                new MarketContext(null, null, null) {
                    @Override public Snapshot snapshot() { return downDay(); }
                });

        journal.rejection(UserId.random(), state(46.0, 46.2, 0.2, 0.1), StrategySignal.reject(
                RejectionStage.DISCOVERY, "belowVwap", "46.00 vs vwap 46.20"), new SetupState());

        JsonNode row = rowsOf(journal).get(0);
        assertThat(row.path("condition").asText()).isEqualTo("belowVwap");
        assertThat(row.path("niftyFromOpenPct").asDouble()).isEqualTo(-1.02);
        assertThat(row.path("pctAboveVwap").asDouble()).isEqualTo(18.0);
        assertThat(row.has("shadowGates")).as("gates are an intent's business").isFalse();
        assertThat(row.path("minutesSinceImpulse").asInt()).as("an idle setup has no impulse").isEqualTo(-1);
    }

    @Test
    void withoutAMarketContextTheRowsSimplyOmitTheMarketFields() throws IOException {
        DecisionJournal journal = new DecisionJournal(new FixedTradingClock(NOW), true, dir.toString());

        journal.intent(UserId.random(), state(46.37, 45.8856, 0.89, 0.1207), armedSetup(),
                46.37, 46.10, 46.73, true, BookState.NONE);

        JsonNode row = rowsOf(journal).get(0);
        assertThat(row.has("niftyFromOpenPct")).isFalse();
        assertThat(row.path("shadowGates").path("marketOk").asBoolean())
                .as("no market means the market gate cannot pass")
                .isFalse();
        assertThat(row.path("shadowGates").path("ret15Le1_5").asBoolean()).isTrue();
    }

    @Test
    void unknownValuesAreWrittenAsJsonNullNeverAsNaN() throws IOException {
        DecisionJournal journal = new DecisionJournal(new FixedTradingClock(NOW), true, dir.toString(),
                new MarketContext(null, null, null) {
                    @Override public Snapshot snapshot() { return Snapshot.NONE; }
                });
        // Snapshot.NONE has a null timestamp: nothing is known, and nothing is written.
        journal.rejection(UserId.random(), state(46.0, Double.NaN, Double.NaN, Double.NaN),
                StrategySignal.reject(RejectionStage.DISCOVERY, "indicatorsNotReady", ""), new SetupState());

        JsonNode row = rowsOf(journal).get(0);
        assertThat(row.path("vwap").isNull()).isTrue();
        assertThat(row.has("niftyLast")).isFalse();
        assertThat(row.path("climaxBarAtr").asDouble()).isZero();
    }
}
