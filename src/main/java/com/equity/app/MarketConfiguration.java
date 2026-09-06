package com.equity.app;

import com.equity.market.candle.CandleEngine;
import com.equity.market.gainer.TopGainerEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Beans for the market classes that are deliberately framework-free.
 *
 * <p>{@code CandleEngine} and {@code TopGainerEngine} carry no Spring annotations so they can be
 * unit-tested with no context and run unchanged under REPLAY. Wiring them here keeps that property
 * while still letting the running application inject them.</p>
 */
@Configuration
public class MarketConfiguration {

    @Bean
    public CandleEngine candleEngine() {
        return new CandleEngine();
    }

    /**
     * Ranking thresholds.
     *
     * <p>With {@code equity.universe.evaluate-all} on — the default — these gate nothing: the rank
     * is computed and carried on the shared state for display and later analysis, but every stock in
     * the universe is evaluated regardless of it. They apply only if evaluate-all is turned off for
     * a universe large enough that FULL depth on all of it would be wasteful.</p>
     *
     * <p>Enter and exit ranks differ on purpose. The gap is hysteresis: with a single threshold a
     * stock hovering at the boundary joins and leaves repeatedly, and each cycle discards whatever
     * setup state had accumulated against it.</p>
     *
     * <p>The sanity band is the corporate-action guard from design note 0.9 — a day change beyond it
     * almost certainly means an unadjusted split rather than a real move.</p>
     *
     * <p>All three come from {@code equity.universe} rather than from literals here: they are the
     * numbers most likely to want changing once there is live evidence about how wide the promotion
     * band should be, and recompiling to change one is how a tuning parameter stops being tuned.</p>
     */
    @Bean
    public TopGainerEngine topGainerEngine(
            com.equity.market.universe.UniverseProperties universe) {
        return new TopGainerEngine(universe.getEnterRank(), universe.getExitRank(),
                universe.getSanityBandPercent());
    }
}
