package com.equity.market.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.equity.domain.market.SharedInstrumentState;
import com.equity.domain.market.Tick;
import com.equity.market.candle.CandleEngine;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Relative strength has to survive the pullback it is measured across.
 *
 * <p>The filter refused a third of all candidates, and the reason was the window rather than the
 * threshold: measured over five minutes, a pullback-continuation setup is evaluated exactly when
 * its short-window return is weakest, so the strategy was rejecting stocks for exhibiting the pause
 * it exists to buy. The rejected rows had a median five-minute return of -0.12% and a median
 * fifteen-minute return of +0.58%.</p>
 *
 * <p>Each test here builds that shape deliberately — a strong impulse followed by a pause — and
 * asserts the stock is judged on the whole move rather than on the pause alone.</p>
 */
class RelativeStrengthWindowTest {

    private static final Instant OPEN = Instant.parse("2026-09-10T03:45:00Z");   // 09:15 IST
    private static final String STOCK = "BHEL";
    private static final String INDEX = StructureEngine.INDEX_SYMBOL;

    private final CandleEngine candles = new CandleEngine();
    private final StructureEngine structure = new StructureEngine(candles);

    /** One tick, one minute apart, so each call closes the previous candle. */
    private void tick(String symbol, int minute, double price) {
        Tick t = new Tick(symbol, price, 1_000L * (minute + 1), price - 0.05, price + 0.05,
                100, price, 100, 100, OPEN.plusSeconds(60L * minute), OPEN.plusSeconds(60L * minute));
        candles.onTick(t);
        structure.onTick(t);
    }

    private SharedInstrumentState state() {
        return structure.state(STOCK).orElseThrow();
    }

    /**
     * The shape this strategy trades: a hard move up, then a pause that drifts slightly down.
     *
     * @param impulse how far the stock runs over the first ten minutes, in percent
     * @param pause   how far it drifts back over the last six, in percent
     */
    private void impulseThenPause(double impulse, double pause, double indexDriftPercent) {
        double start = 100.0;
        double peak = start * (1 + impulse / 100);
        double end = peak * (1 - pause / 100);
        for (int m = 0; m <= 20; m++) {
            double stockPrice = m <= 10
                    ? start + (peak - start) * m / 10.0
                    : peak + (end - peak) * (m - 10) / 10.0;
            tick(STOCK, m, stockPrice);
            tick(INDEX, m, 25_000.0 * (1 + indexDriftPercent / 100 * m / 20.0));
        }
    }

    @Test
    void aStockThatLedTheMarketIsStillLeadingItWhilePausing() {
        // Up 2% over ten minutes, then giving back 0.4% over the next ten while the index adds
        // 0.1%. The last five minutes are NEGATIVE for the stock and positive for the index — a
        // five-minute window would call this weaker than the market and refuse the entry.
        impulseThenPause(2.0, 0.4, 0.1);

        assertThat(state().return5m())
                .as("the pause, seen on its own: the stock is going down")
                .isNegative();
        assertThat(state().niftyRelativeStrength())
                .as("over fifteen minutes the impulse is still in the window, so the stock is "
                        + "correctly seen as leading — this is the whole fix")
                .isPositive();
        assertThat(state().outperformingNifty()).isTrue();
    }

    @Test
    void aStockGoingNowhereIsStillRefused() {
        // Flat stock, index up 0.5%. Widening the window must not turn the filter into a no-op.
        for (int m = 0; m <= 20; m++) {
            tick(STOCK, m, 100.0);
            tick(INDEX, m, 25_000.0 * (1 + 0.005 * m / 20.0));
        }

        assertThat(state().niftyRelativeStrength()).isNegative();
        assertThat(state().outperformingNifty())
                .as("a stock that lagged the market over the full window is genuinely weak")
                .isFalse();
    }

    /**
     * An index that has not been measured must not read as an index that did not move.
     *
     * <p>Zero was the old fallback, and it meant every candidate passed a filter that had never
     * run. The measurement being unavailable and the market being flat are different facts.</p>
     */
    @Test
    void anUnmeasuredIndexRefusesRatherThanWavingEverythingThrough() {
        for (int m = 0; m <= 20; m++) {
            tick(STOCK, m, 100.0 + m);      // storming upwards, and no index data at all
        }

        assertThat(state().return15m())
                .as("the stock itself is measured fine")
                .isPositive();
        assertThat(state().niftyRelativeStrength())
                .as("nothing to compare against, so the answer is unknown and not zero")
                .isNaN();
        assertThat(state().outperformingNifty())
                .as("a gate that cannot be evaluated must refuse; passing would be a filter that "
                        + "silently stopped running")
                .isFalse();
    }

    @Test
    void strengthIsUnknownUntilTheWindowIsFull() {
        for (int m = 0; m <= 8; m++) {          // nine minutes, short of the fifteen needed
            tick(STOCK, m, 100.0 + m);
            tick(INDEX, m, 25_000.0);
        }

        assertThat(state().niftyRelativeStrength())
                .as("half a window is not a small measurement, it is no measurement")
                .isNaN();
    }
}
