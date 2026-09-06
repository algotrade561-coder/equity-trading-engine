package com.equity.market.candle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.equity.domain.market.Candle;
import com.equity.domain.market.Tick;
import com.equity.domain.market.Timeframe;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The invariants that matter for CandleEngine. Each test names the failure it prevents rather than
 * simply asserting a value.
 */
class CandleEngineTest {

    private static final String SYM = "RELIANCE";
    private static final Instant T0 = Instant.parse("2026-09-04T03:45:00Z");   // 09:15 IST

    private static Tick tick(Instant at, double px, long cumVol) {
        return Tick.ltp(SYM, px, cumVol, at);
    }

    @Test
    void closesAOneMinuteCandleWhenTheMinuteRolls() {
        CandleEngine e = new CandleEngine();
        List<Candle> closed = new ArrayList<>();
        e.onCandleClosed(closed::add);

        e.onTick(tick(T0.plusSeconds(1), 100.0, 1_000));
        e.onTick(tick(T0.plusSeconds(30), 103.0, 1_500));
        e.onTick(tick(T0.plusSeconds(59), 101.0, 2_000));
        assertThat(closed).as("nothing may publish until the minute completes").isEmpty();

        e.onTick(tick(T0.plusSeconds(61), 102.0, 2_100));

        assertThat(closed).hasSize(1);
        Candle c = closed.get(0);
        assertThat(c.timeframe()).isEqualTo(Timeframe.M1);
        assertThat(c.open()).isEqualTo(100.0);
        assertThat(c.high()).isEqualTo(103.0);
        assertThat(c.low()).isEqualTo(100.0);
        assertThat(c.close()).isEqualTo(101.0);
    }

    @Test
    void volumeIsDifferencedFromCumulative_notSummedPerTick() {
        // A redelivered tick must not inflate volume. Summing tick deltas would double-count it.
        CandleEngine e = new CandleEngine();
        List<Candle> closed = new ArrayList<>();
        e.onCandleClosed(closed::add);

        e.onTick(tick(T0.plusSeconds(1), 100.0, 1_000));
        e.onTick(tick(T0.plusSeconds(20), 101.0, 1_400));
        e.onTick(tick(T0.plusSeconds(20), 101.0, 1_400));   // duplicate redelivery
        e.onTick(tick(T0.plusSeconds(50), 102.0, 1_900));
        e.onTick(tick(T0.plusSeconds(61), 102.0, 2_000));

        assertThat(closed.get(0).volume())
                .as("1900 - 1000, unaffected by the duplicate")
                .isEqualTo(900);
    }

    @Test
    void higherTimeframesAggregateFromCompletedOneMinuteCandles() {
        // The invariant: a 3m candle's high can never exceed the highest 1m high inside it.
        CandleEngine e = new CandleEngine();
        List<Candle> closed = new ArrayList<>();
        e.onCandleClosed(closed::add);

        double[] highs = {101, 105, 103};
        for (int m = 0; m < 3; m++) {
            e.onTick(tick(T0.plusSeconds(m * 60 + 1), 100 + m, 1_000L * (m + 1)));
            e.onTick(tick(T0.plusSeconds(m * 60 + 30), highs[m], 1_000L * (m + 1) + 500));
        }
        e.onTick(tick(T0.plusSeconds(181), 104.0, 5_000));   // rolls the 3rd minute

        List<Candle> m3 = closed.stream().filter(c -> c.timeframe() == Timeframe.M3).toList();
        assertThat(m3).as("one completed 3m candle").hasSize(1);
        assertThat(m3.get(0).high())
                .as("3m high equals the max of its 1m highs")
                .isEqualTo(105.0);
        assertThat(m3.get(0).startTime()).isEqualTo(T0);
    }

    @Test
    void doesNotEmitAnIncompleteHigherTimeframeCandle() {
        // A gap in the 1m series must not produce a short 3m candle built from 2 minutes.
        CandleEngine e = new CandleEngine();
        List<Candle> closed = new ArrayList<>();
        e.onCandleClosed(closed::add);

        e.onTick(tick(T0.plusSeconds(1), 100.0, 1_000));
        e.onTick(tick(T0.plusSeconds(61), 101.0, 1_500));    // closes minute 0
        // minute 1 has no ticks at all
        e.onTick(tick(T0.plusSeconds(181), 102.0, 2_000));   // closes minute 2's bucket

        assertThat(closed.stream().filter(c -> c.timeframe() == Timeframe.M3))
                .as("bucket is incomplete, so no 3m candle")
                .isEmpty();
    }

    @Test
    void lateTickForAClosedBucketIsDropped() {
        CandleEngine e = new CandleEngine();
        List<Candle> closed = new ArrayList<>();
        e.onCandleClosed(closed::add);

        e.onTick(tick(T0.plusSeconds(10), 100.0, 1_000));
        e.onTick(tick(T0.plusSeconds(70), 101.0, 1_500));    // closes minute 0
        e.onTick(tick(T0.plusSeconds(20), 999.0, 1_200));    // late tick for minute 0

        assertThat(closed).hasSize(1);
        assertThat(closed.get(0).high())
                .as("a late tick must never rewrite a published candle")
                .isEqualTo(100.0, within(1e-9));
    }

    @Test
    void staleBucketClosesWithoutAFurtherTick() {
        // A stock that stops trading must still close its candle, or its state silently freezes.
        CandleEngine e = new CandleEngine();
        List<Candle> closed = new ArrayList<>();
        e.onCandleClosed(closed::add);

        e.onTick(tick(T0.plusSeconds(5), 100.0, 1_000));
        assertThat(closed).isEmpty();

        e.closeStaleBuckets(T0.plusSeconds(125));

        assertThat(closed).hasSize(1);
        assertThat(closed.get(0).close()).isEqualTo(100.0);
    }
}
