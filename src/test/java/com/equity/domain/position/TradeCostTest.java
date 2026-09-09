package com.equity.domain.position;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/**
 * The charges on a round trip, checked against a real contract note.
 *
 * <p>Costs follow notional while risk follows stop distance, so a tight stop on an expensive share
 * pays a much larger share of its risk in charges than a wide stop on a cheap one. Comparing two
 * trades on gross R quietly compares them on different scales, which is why this is recorded per
 * trade rather than estimated once.</p>
 */
class TradeCostTest {

    /**
     * The buy legs of one live session: 405,491.70 of turnover across three orders.
     *
     * <p>Zerodha's virtual note showed stamp duty of 60.00 and STT of 405.49 — the delivery rates,
     * because only the buy legs existed and a trade is not intraday until both legs settle on the
     * same day. Once squared off the intraday rates apply, and those are what this computes.</p>
     */
    @Test
    void matchesTheIntradayRatesOnARealSession() {
        // The three buy legs of one live session, priced individually — brokerage is capped PER
        // ORDER, so a session's turnover cannot be summed and costed as a single trade. Doing that
        // understates brokerage by two thirds here, and GST with it.
        double[][] legs = {{148_697.20, 148_500}, {107_908.80, 107_700}, {148_885.70, 148_600}};

        double total = 0, stt = 0, stamp = 0, brokerage = 0;
        for (double[] leg : legs) {
            TradeCost c = TradeCost.forRoundTrip(leg[0], leg[1]);
            total += c.total();
            stt += c.stt();
            stamp += c.stampDuty();
            brokerage += c.brokerage();
        }

        assertThat(stamp)
                .as("intraday stamp duty is 0.003%% on the buy leg — the broker's note showed 60.00, "
                        + "which is the 0.015%% delivery rate, applied only because the positions "
                        + "were still open and a trade is not intraday until both legs settle")
                .isCloseTo(12.16, within(0.05));
        assertThat(stt)
                .as("intraday STT is 0.025%% on the SELL leg only; the note's 405.49 was 0.1%% on "
                        + "the buy, which intraday does not charge at all")
                .isCloseTo(101.2, within(1.0));
        assertThat(brokerage)
                .as("six orders, every one over the cap")
                .isCloseTo(120.0, within(0.01));
        assertThat(total)
                .as("about 285 against the 480.66 the note showed while the legs were open")
                .isCloseTo(285.0, within(15.0));
    }

    /**
     * Brokerage is per order and capped, so it stops scaling on a large position — which is why a
     * bigger position improves the cost-to-risk ratio rather than worsening it.
     */
    @Test
    void brokerageIsCappedPerOrder() {
        TradeCost small = TradeCost.forRoundTrip(10_000, 10_000);
        TradeCost large = TradeCost.forRoundTrip(500_000, 500_000);

        assertThat(small.brokerage())
                .as("0.03%% of 10,000 is 3, below the 20 cap")
                .isCloseTo(6.0, within(0.01));
        assertThat(large.brokerage())
                .as("two orders, both capped at 20")
                .isCloseTo(40.0, within(0.01));
    }

    /** GST applies to the broker's and exchange's fees, never to the statutory taxes. */
    @Test
    void gstIsChargedOnFeesButNotOnTaxes() {
        TradeCost c = TradeCost.forRoundTrip(100_000, 100_000);

        double expected = (c.brokerage() + c.exchangeTxn() + c.sebi()) * 0.18;
        assertThat(c.gst()).isCloseTo(expected, within(0.001));
        assertThat(c.gst())
                .as("STT and stamp duty are taxes; taxing them again would be wrong")
                .isLessThan(c.stt());
    }

    @Test
    void anUnclosedTradeCostsNothingYet() {
        assertThat(TradeCost.forRoundTrip(100_000, 0).total()).isZero();
        assertThat(TradeCost.forRoundTrip(0, 0).total()).isZero();
    }
}
