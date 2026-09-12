package com.equity.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.equity.domain.market.Candle;
import com.equity.domain.market.Timeframe;
import com.equity.market.candle.CandleEngine;
import com.equity.market.state.StructureEngine;
import com.equity.platform.time.FixedTradingClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * A restart resumes the session instead of starting it over.
 *
 * <p>The engine builds every 1-minute bar from the tick stream and used to discard them on
 * shutdown. The visible cost was a twenty-bar warm-up. The silent one was worse: VWAP is
 * session-cumulative, so a process restarted at 10:30 computed it from 10:30 onward — a number that
 * is not VWAP, never becomes VWAP, and is a mandatory entry gate.</p>
 *
 * <p>In-memory database on purpose. Pointed at the configured file this would write into the real
 * {@code ./data/equity}, and a test that mutates production data is worse than no test.</p>
 */
@SpringBootTest(classes = com.equity.app.EquityApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:candle-round-trip;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "equity.security.secret-key=test-key-not-a-real-one",
        "equity.auth.google.enabled=false",
})
class SessionCandleStoreTest {

    private static final Instant OPEN = Instant.parse("2026-09-07T03:45:00Z");   // 09:15 IST

    @Autowired private CandleRepository repository;
    @Autowired private SessionCandleStore store;

    /** A rising session, so VWAP computed over all of it differs from VWAP over its tail. */
    private static List<Candle> session(String symbol, int bars) {
        List<Candle> out = new ArrayList<>();
        for (int i = 0; i < bars; i++) {
            double base = 1000 + i;
            out.add(new Candle(symbol, Timeframe.M1, OPEN.plus(Duration.ofMinutes(i)),
                    base, base + 0.5, base - 0.5, base + 0.2, 10_000));
        }
        return out;
    }

    @Test
    void barsSurviveAndComeBackAsAUsableSeries() {
        var clock = new FixedTradingClock(OPEN.plus(Duration.ofMinutes(40)));
        repository.saveAll(session("RELIANCE", 30).stream()
                .map(c -> new CandleEntity(c, clock.tradingDate())).toList());

        // A cold engine, as it would be one second after a restart.
        CandleEngine candles = new CandleEngine();
        StructureEngine structure = new StructureEngine(candles);
        var restored = new SessionCandleStore(repository, candles, structure, clock, true);

        restored.restoreToday();

        assertThat(candles.history("RELIANCE", Timeframe.M1))
                .as("the strategy refuses to evaluate anything under 20 bars")
                .hasSize(30);
        assertThat(restored.restoredCount()).isEqualTo(1);
    }

    /**
     * The reason this exists. VWAP over the whole session and VWAP over its tail are different
     * numbers, and nothing in the engine would have told you which one it was using.
     */
    @Test
    void vwapReflectsTheWholeSessionNotTheProcessLifetime() {
        var clock = new FixedTradingClock(OPEN.plus(Duration.ofMinutes(40)));
        repository.saveAll(session("INFY", 30).stream()
                .map(c -> new CandleEntity(c, clock.tradingDate())).toList());

        CandleEngine candles = new CandleEngine();
        StructureEngine structure = new StructureEngine(candles);
        new SessionCandleStore(repository, candles, structure, clock, true).restoreToday();

        double full = structure.state("INFY").orElseThrow().vwap();

        // The same engine given only the last five bars, which is what a restart used to see.
        CandleEngine truncated = new CandleEngine();
        StructureEngine truncatedStructure = new StructureEngine(truncated);
        truncated.seed("INFY", Timeframe.M1, session("INFY", 30).subList(25, 30));
        truncatedStructure.rebuild("INFY");
        double partial = truncatedStructure.state("INFY").orElseThrow().vwap();

        assertThat(full)
                .as("a restart must not silently redefine a mandatory entry gate")
                .isNotEqualTo(partial);
        assertThat(full).isLessThan(partial);   // the session rose, so its full VWAP sits lower
    }

    /** Seeding must never republish: those bars describe decisions already made or already missed. */
    @Test
    void restoredBarsDoNotRunThroughTheStrategy() {
        var clock = new FixedTradingClock(OPEN.plus(Duration.ofMinutes(40)));
        repository.saveAll(session("TCS", 25).stream()
                .map(c -> new CandleEntity(c, clock.tradingDate())).toList());

        CandleEngine candles = new CandleEngine();
        List<Candle> published = new ArrayList<>();
        candles.onCandleClosed(published::add);
        StructureEngine structure = new StructureEngine(candles);

        new SessionCandleStore(repository, candles, structure, clock, true).restoreToday();

        assertThat(published)
                .as("replaying these would advance setups and could trigger on an hours-old price")
                .isEmpty();
        assertThat(candles.history("TCS", Timeframe.M1)).hasSize(25);
    }

    @Test
    void aLiveBarIsNotOverwrittenByAStoredOneForTheSameMinute() {
        var clock = new FixedTradingClock(OPEN.plus(Duration.ofMinutes(40)));
        repository.saveAll(session("WIPRO", 3).stream()
                .map(c -> new CandleEntity(c, clock.tradingDate())).toList());

        CandleEngine candles = new CandleEngine();
        Candle live = new Candle("WIPRO", Timeframe.M1, OPEN, 999, 999, 999, 999, 1);
        candles.seed("WIPRO", Timeframe.M1, List.of(live));

        new SessionCandleStore(repository, candles, new StructureEngine(candles), clock, true)
                .restoreToday();

        assertThat(candles.history("WIPRO", Timeframe.M1))
                .as("the live series is the one consistent with what the engine already acted on")
                .contains(live)
                .hasSize(3);
    }
}
