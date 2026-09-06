package com.equity.broker.kite;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KiteInstrumentMasterTest {

    private static final String HEADER =
            "instrument_token,exchange_token,tradingsymbol,name,last_price,expiry,strike,"
            + "tick_size,lot_size,instrument_type,segment,exchange";

    private KiteInstrumentMaster master;

    @BeforeEach
    void setUp() {
        KiteProperties props = new KiteProperties();
        KiteHttp http = new KiteHttp(props);
        KiteSessionStore store = new KiteSessionStore(new com.equity.platform.time.FixedTradingClock(
                Instant.parse("2026-09-04T04:00:00Z")));
        master = new KiteInstrumentMaster(props, http, new KiteCredentialsProvider(props), store);
    }

    private int load(String... rows) {
        return master.load(HEADER + "\n" + String.join("\n", rows) + "\n");
    }

    @Test
    void resolvesSymbolAndTokenBothWays() {
        load("738561,2885,RELIANCE,RELIANCE INDUSTRIES,0,,0,0.05,1,EQ,NSE,NSE");

        assertThat(master.tokenFor("RELIANCE")).contains(738561L);
        assertThat(master.byToken(738561L)).map(KiteInstrument::tradingSymbol).contains("RELIANCE");
        assertThat(master.symbolResolver().apply(738561L)).isEqualTo("RELIANCE");
        assertThat(master.isLoaded()).isTrue();
    }

    @Test
    void unknownTokenResolvesToNullRatherThanAGuess() {
        load("738561,2885,RELIANCE,RELIANCE INDUSTRIES,0,,0,0.05,1,EQ,NSE,NSE");

        assertThat(master.symbolResolver().apply(999_999L))
                .as("a fabricated symbol would inject a phantom instrument into the candle engine")
                .isNull();
        assertThat(master.tokenFor("NOTLISTED")).isEmpty();
    }

    @Test
    void quotedCommasInCompanyNamesDoNotShiftTheColumns() {
        // This row is why the parser is not a split on comma. Getting it wrong moves tick_size into
        // lot_size for exactly the large caps this engine trades.
        load("492033,1922,KOTAKBANK,\"KOTAK MAHINDRA BANK, LTD\",0,,0,0.05,1,EQ,NSE,NSE");

        KiteInstrument kotak = master.bySymbol("KOTAKBANK").orElseThrow();
        assertThat(kotak.name()).isEqualTo("KOTAK MAHINDRA BANK, LTD");
        assertThat(kotak.tickSize()).isEqualTo(0.05);
        assertThat(kotak.lotSize()).isEqualTo(1);
        assertThat(kotak.isNseEquity()).isTrue();
    }

    @Test
    void nseCashRowWinsWhenASymbolAppearsInMoreThanOneSegment() {
        load("738561,2885,RELIANCE,RELIANCE INDUSTRIES,0,,0,0.05,1,FUT,NFO-FUT,NFO",
             "111111,2885,RELIANCE,RELIANCE INDUSTRIES,0,,0,0.05,1,EQ,NSE,NSE");

        assertThat(master.bySymbol("RELIANCE")).map(KiteInstrument::instrumentToken)
                .as("subscribing to the futures token for a cash symbol would price every stop wrong")
                .contains(111111L);
    }

    @Test
    void oneMalformedRowDoesNotCostTheRestOfTheDump() {
        int count = load("not-a-token,x,BROKEN,BROKEN CO,0,,0,0.05,1,EQ,NSE,NSE",
                         "738561,2885,RELIANCE,RELIANCE INDUSTRIES,0,,0,0.05,1,EQ,NSE,NSE");

        assertThat(count).isEqualTo(1);
        assertThat(master.tokenFor("RELIANCE")).contains(738561L);
    }

    @Test
    void roundsPricesToTheInstrumentTick() {
        load("738561,2885,RELIANCE,RELIANCE INDUSTRIES,0,,0,0.05,1,EQ,NSE,NSE");

        KiteInstrument reliance = master.bySymbol("RELIANCE").orElseThrow();
        assertThat(reliance.roundToTick(1450.53))
                .as("a limit price off-tick is rejected by the exchange and looks like a missed entry")
                .isEqualTo(1450.55, org.assertj.core.data.Offset.offset(1e-9));
    }

    /**
     * A constituent that stops resolving is nearly always a rename or a demerger, not a typo, and
     * the only symptom is that one stock quietly stops being evaluated for the rest of the session.
     * Naming the candidates is what makes that a one-line fix rather than an investigation.
     */
    @Test
    void suggestsWhatARenamedSymbolMayHaveBecome() {
        load("1,1,TATAMOTORSNEW,TATA MOTORS,0,,0,0.05,1,EQ,NSE,NSE",
             "2,2,TATASTEEL,TATA STEEL,0,,0,0.05,1,EQ,NSE,NSE",
             "3,3,RELIANCE,RELIANCE INDUSTRIES,0,,0,0.05,1,EQ,NSE,NSE");

        assertThat(master.suggestionsFor("TATAMOTORS"))
                .as("the stem matches in both directions, so a longer or shorter rename is found")
                .contains("TATAMOTORSNEW");
        assertThat(master.suggestionsFor("RELIANCE"))
                .as("an unrelated symbol must not be offered as a candidate")
                .doesNotContain("TATASTEEL");
    }

    @Test
    void suggestsNothingBeforeTheMasterIsLoaded() {
        assertThat(master.suggestionsFor("TATAMOTORS")).isEmpty();
    }
}
