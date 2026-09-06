package com.equity.market.universe;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Parsing the exchange's constituent file.
 *
 * <p>This replaced a list typed into the source, which had rotted without saying so: two of its
 * fifty entries had been renamed by the exchange, resolved to no instrument token, and were silently
 * never evaluated. The parser is now the thing standing between the engine and its universe, so the
 * shapes the file actually takes are worth pinning.</p>
 */
class IndexConstituentSourceTest {

    /** The real header, including the UTF-8 BOM the file is served with. */
    private static final String HEADER = "﻿Company Name,Industry,Symbol,Series,ISIN Code";

    private static String csv(String... rows) {
        return HEADER + "\n" + String.join("\n", rows) + "\n";
    }

    @Test
    void readsTheSymbolColumn() {
        List<String> symbols = IndexConstituentSource.parse(csv(
                "Reliance Industries Ltd.,Oil Gas & Consumable Fuels,RELIANCE,EQ,INE002A01018",
                "Infosys Ltd.,Information Technology,INFY,EQ,INE009A01021"));

        assertThat(symbols).containsExactly("RELIANCE", "INFY");
    }

    /**
     * The BOM glues itself to the first header cell, and a column looked up by exact name then
     * misses. Harmless-looking, and it would empty the universe.
     */
    @Test
    void findsColumnsDespiteTheByteOrderMark() {
        String shifted = "﻿Symbol,Company Name,Series";
        assertThat(IndexConstituentSource.parse(shifted + "\nRELIANCE,Reliance Industries,EQ\n"))
                .containsExactly("RELIANCE");
    }

    /**
     * Columns are located by header name, never by position: the file has gained and lost columns
     * over the years, and an index into a row is a bug waiting for the next revision.
     */
    @Test
    void toleratesColumnsMovingOrBeingAdded() {
        List<String> symbols = IndexConstituentSource.parse(
                "ISIN Code,Series,Symbol,Company Name,Industry,Extra\n"
                + "INE002A01018,EQ,RELIANCE,Reliance Industries Ltd.,Energy,x\n");

        assertThat(symbols).containsExactly("RELIANCE");
    }

    /** Company names contain commas, so a naive split would shift every later column by one. */
    @Test
    void handlesQuotedFieldsContainingCommas() {
        List<String> symbols = IndexConstituentSource.parse(csv(
                "\"Kotak Mahindra Bank, Ltd.\",Financial Services,KOTAKBANK,EQ,INE237A01028"));

        assertThat(symbols).containsExactly("KOTAKBANK");
    }

    /**
     * BE is trade-to-trade: it cannot be squared off intraday at all, which is precisely the wrong
     * thing to hand an intraday engine.
     */
    @Test
    void takesOnlyTheEquitySeries() {
        List<String> symbols = IndexConstituentSource.parse(csv(
                "Good Co,Industry,GOODCO,EQ,INE000A01001",
                "Watchlisted Co,Industry,WATCHCO,BE,INE000A01002"));

        assertThat(symbols).containsExactly("GOODCO");
    }

    /** NSE seeds these files with placeholder rows for suspended or reconstituted names. */
    @Test
    void dropsExchangePlaceholderRows() {
        assertThat(IndexConstituentSource.parse(csv(
                "HEG Ltd.,Capital Goods,DUMMYHEG,EQ,INE545A01024",
                "Reliance Industries Ltd.,Energy,RELIANCE,EQ,INE002A01018")))
                .containsExactly("RELIANCE");
    }

    @Test
    void deduplicatesAndNormalisesCase() {
        assertThat(IndexConstituentSource.parse(csv(
                "A,Industry,reliance,EQ,X",
                "B,Industry,RELIANCE,EQ,Y")))
                .containsExactly("RELIANCE");
    }

    /**
     * A malformed or empty file must yield nothing rather than a partial universe. Nothing is a
     * state the caller already handles loudly; a plausible-looking half-list is not.
     */
    @Test
    void yieldsNothingRatherThanGuessingFromAnUnusableFile() {
        assertThat(IndexConstituentSource.parse("")).isEmpty();
        assertThat(IndexConstituentSource.parse(null)).isEmpty();
        assertThat(IndexConstituentSource.parse("<html>404 Not Found</html>")).isEmpty();
        assertThat(IndexConstituentSource.parse("Company Name,Industry,ISIN Code\nA,B,C\n"))
                .as("no Symbol column means the format changed; inventing one would be worse")
                .isEmpty();
    }
}
