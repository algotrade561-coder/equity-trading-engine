package com.equity.market.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.equity.domain.market.Tick;
import com.equity.market.candle.CandleEngine;
import com.equity.platform.time.FixedTradingClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The market snapshot says what kind of day it is, from the same states and candles the strategy
 * reads. What matters is that the breadth is counted over instruments that can actually be judged
 * (a known previous close) and that the index figures are NaN until the index has printed.
 */
class MarketContextTest {

    private static final Instant OPEN = Instant.parse("2026-09-15T03:45:00Z");   // 09:15 IST

    private CandleEngine candles;
    private StructureEngine structure;
    private FixedTradingClock clock;
    private MarketContext market;

    @BeforeEach
    void setUp() {
        candles = new CandleEngine();
        structure = new StructureEngine(candles);
        clock = new FixedTradingClock(OPEN);
        market = new MarketContext(structure, candles, clock);
    }

    /** A stock tick with a previous close and OHLC, as a QUOTE packet carries. */
    private void stock(String symbol, double last, double previousClose, Instant at) {
        Tick t = new Tick(symbol, last, 1000, 0, 0, previousClose, Math.max(last, previousClose),
                Math.min(last, previousClose), previousClose, at, at);
        structure.onTick(t);
        candles.onTick(t);
    }

    private void index(double last, Instant at) {
        Tick t = Tick.ltp(StructureEngine.INDEX_SYMBOL, last, 0, at);
        structure.onTick(t);
        candles.onTick(t);
    }

    @Test
    void beforeAnythingTicksEveryFigureIsUnknownNotZero() {
        MarketContext.Snapshot s = market.snapshot();
        assertThat(s.universe()).isZero();
        assertThat(s.indexLast()).isNaN();
        assertThat(s.indexChangeFromOpenPct()).isNaN();
        assertThat(s.pctUpOnDay()).isNaN();
        assertThat(s.pctAboveVwap()).isNaN();
        assertThat(s.toJsonFields()).contains("\"niftyFromOpenPct\":null").contains("\"pctUpOnDay\":null");
    }

    @Test
    void breadthCountsOnlyInstrumentsWithAPreviousCloseAndReadsTheDayChange() {
        Instant t = OPEN.plusSeconds(30);
        stock("UP1", 101.5, 100, t);       // +1.5%
        stock("UP2", 100.4, 100, t);       // +0.4%
        stock("DOWN", 98.5, 100, t);       // -1.5%
        stock("FLAT", 100, 100, t);        // 0
        // An instrument without a previous close cannot be judged and must not dilute the figures.
        Tick noPrev = Tick.ltp("UNKNOWN", 55, 10, t);
        structure.onTick(noPrev);
        clock.setTo(t);

        MarketContext.Snapshot s = market.snapshot();
        assertThat(s.universe()).isEqualTo(4);
        assertThat(s.pctUpOnDay()).isCloseTo(50.0, within(1e-9));
        assertThat(s.pctUpOver1Pct()).isCloseTo(25.0, within(1e-9));
        assertThat(s.pctDownOver1Pct()).isCloseTo(25.0, within(1e-9));
    }

    @Test
    void indexChangeIsMeasuredFromTheFirstBarsOpen() {
        // Index prints 100 in minute one, closes the bar, then trades 99 in minute two.
        index(100, OPEN);
        index(100.5, OPEN.plusSeconds(30));
        index(99, OPEN.plus(Duration.ofMinutes(1)));   // closes the first bar with open 100
        clock.setTo(OPEN.plus(Duration.ofMinutes(1)));

        MarketContext.Snapshot s = market.snapshot();
        assertThat(s.indexOpen()).isEqualTo(100.0);
        assertThat(s.indexLast()).isEqualTo(99.0);
        assertThat(s.indexChangeFromOpenPct()).isCloseTo(-1.0, within(1e-9));
        assertThat(s.indexReturn15mPct()).as("no fifteen completed minutes yet").isNaN();
    }

    @Test
    void theSnapshotIsReusedWithinASecondAndRecomputedAfterIt() {
        Instant t = OPEN.plusSeconds(10);
        clock.setTo(t);
        stock("A", 101, 100, t);
        MarketContext.Snapshot first = market.snapshot();
        stock("B", 99, 100, t);   // arrives in the same second
        assertThat(market.snapshot()).as("cached for the rest of the second").isSameAs(first);

        clock.setTo(t.plusSeconds(1));
        MarketContext.Snapshot next = market.snapshot();
        assertThat(next).isNotSameAs(first);
        assertThat(next.universe()).isEqualTo(2);
        assertThat(next.pctUpOnDay()).isCloseTo(50.0, within(1e-9));
    }
}
