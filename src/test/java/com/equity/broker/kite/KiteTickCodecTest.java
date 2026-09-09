package com.equity.broker.kite;

import static org.assertj.core.api.Assertions.assertThat;

import com.equity.domain.market.Tick;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Hand-built binary frames. This is the only way to test a wire protocol without a live market,
 * and the reason the codec was written as a pure function rather than inside the socket listener.
 */
class KiteTickCodecTest {

    private static final long RELIANCE_TOKEN = 738561L;
    private static final Instant RECV = Instant.parse("2026-09-04T04:00:00Z");
    private static final Map<Long, String> TOKENS = Map.of(RELIANCE_TOKEN, "RELIANCE");

    private static final int OPEN_PAISE = 143_000;
    private static final int HIGH_PAISE = 146_000;
    private static final int LOW_PAISE = 142_500;
    private static final int PREV_CLOSE_PAISE = 142_000;

    private static String resolve(long token) { return TOKENS.get(token); }

    /** Builds a frame containing a single packet of the given payload length. */
    private static byte[] frame(byte[]... packets) {
        int size = 2;
        for (byte[] p : packets) size += 2 + p.length;
        ByteBuffer b = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        b.putShort((short) packets.length);
        for (byte[] p : packets) {
            b.putShort((short) p.length);
            b.put(p);
        }
        return b.array();
    }

    private static byte[] ltpPacket(long token, int paise) {
        return ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
                .putInt((int) token).putInt(paise).array();
    }

    private static byte[] quotePacket(long token, int paise, int volume) {
        ByteBuffer b = ByteBuffer.allocate(44).order(ByteOrder.BIG_ENDIAN);
        b.putInt((int) token).putInt(paise);
        b.putInt(10);          // last qty
        b.putInt(paise);       // avg price
        b.putInt(volume);      // cumulative volume
        b.putInt(0).putInt(0); // total buy / sell qty
        // Deliberately four different values: identical ones would hide a mis-ordered read, and
        // reading `close` from the wrong slot silently corrupts every day-change figure.
        b.putInt(OPEN_PAISE).putInt(HIGH_PAISE).putInt(LOW_PAISE).putInt(PREV_CLOSE_PAISE);
        return b.array();
    }

    private static byte[] fullPacket(long token, int paise, int volume,
                                     int bidPaise, int askPaise, int exchTs) {
        ByteBuffer b = ByteBuffer.allocate(184).order(ByteOrder.BIG_ENDIAN);
        b.putInt((int) token).putInt(paise);
        b.putInt(10).putInt(paise).putInt(volume).putInt(0).putInt(0);
        b.putInt(OPEN_PAISE).putInt(HIGH_PAISE).putInt(LOW_PAISE).putInt(PREV_CLOSE_PAISE);
        b.putInt(0);           // last trade time
        b.putInt(0).putInt(0).putInt(0);                            // oi, oi high, oi low
        b.putInt(exchTs);      // exchange timestamp
        for (int i = 0; i < 5; i++) {                               // bid levels
            b.putInt(100).putInt(i == 0 ? bidPaise : bidPaise - i).putShort((short) 1).putShort((short) 0);
        }
        for (int i = 0; i < 5; i++) {                               // ask levels
            b.putInt(100).putInt(i == 0 ? askPaise : askPaise + i).putShort((short) 1).putShort((short) 0);
        }
        return b.array();
    }

    @Test
    void decodesLtpPacketAndConvertsPaiseToRupees() {
        List<Tick> ticks = KiteTickCodec.decode(
                frame(ltpPacket(RELIANCE_TOKEN, 145_055)), KiteTickCodecTest::resolve, RECV);

        assertThat(ticks).hasSize(1);
        assertThat(ticks.get(0).symbol()).isEqualTo("RELIANCE");
        assertThat(ticks.get(0).lastPrice())
                .as("145055 paise is Rs 1450.55 — a factor of 100 here would mis-size every stop")
                .isEqualTo(1450.55);
        assertThat(ticks.get(0).hasDepth()).isFalse();
    }

    @Test
    void quotePacketCarriesCumulativeVolumeButNoDepth() {
        List<Tick> ticks = KiteTickCodec.decode(
                frame(quotePacket(RELIANCE_TOKEN, 145_055, 1_234_567)),
                KiteTickCodecTest::resolve, RECV);

        assertThat(ticks).hasSize(1);
        assertThat(ticks.get(0).cumulativeVolume()).isEqualTo(1_234_567);
        assertThat(ticks.get(0).hasDepth())
                .as("quote mode has no depth, so a spread check must be refused not guessed")
                .isFalse();
        assertThat(ticks.get(0).spreadPercent()).isNaN();
    }

    @Test
    void quotePacketCarriesTheDayOhlcBlockInTheRightOrder() {
        List<Tick> ticks = KiteTickCodec.decode(
                frame(quotePacket(RELIANCE_TOKEN, 145_055, 1_234_567)),
                KiteTickCodecTest::resolve, RECV);

        Tick t = ticks.get(0);
        assertThat(t.dayOpen()).isEqualTo(1430.00);
        assertThat(t.dayHigh()).isEqualTo(1460.00);
        assertThat(t.dayLow()).isEqualTo(1425.00);
        assertThat(t.previousClose())
                .as("Kite's OHLC `close` is YESTERDAY's close intraday — the gainer denominator")
                .isEqualTo(1420.00);
        assertThat(t.changeFromPreviousClosePercent())
                .isCloseTo(2.152, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void ltpModeReportsNoDayChangeRatherThanZero() {
        List<Tick> ticks = KiteTickCodec.decode(
                frame(ltpPacket(RELIANCE_TOKEN, 145_055)), KiteTickCodecTest::resolve, RECV);

        assertThat(ticks.get(0).hasDayOhlc()).isFalse();
        assertThat(ticks.get(0).changeFromPreviousClosePercent())
                .as("a 0% day change would rank the stock as flat instead of unknown")
                .isNaN();
    }

    @Test
    void fullPacketCarriesTopOfBookAndExchangeTimestamp() {
        int exchTs = (int) Instant.parse("2026-09-04T04:00:05Z").getEpochSecond();
        List<Tick> ticks = KiteTickCodec.decode(
                frame(fullPacket(RELIANCE_TOKEN, 145_055, 1_234_567, 145_050, 145_060, exchTs)),
                KiteTickCodecTest::resolve, RECV);

        assertThat(ticks).hasSize(1);
        Tick t = ticks.get(0);
        assertThat(t.bestBid()).isEqualTo(1450.50);
        assertThat(t.bestAsk()).isEqualTo(1450.60);
        assertThat(t.hasDepth()).isTrue();
        assertThat(t.spreadPercent()).isCloseTo(0.0069, org.assertj.core.data.Offset.offset(0.001));
        assertThat(t.exchangeTime())
                .as("exchange timestamp must win over arrival time when present")
                .isEqualTo(Instant.ofEpochSecond(exchTs));
    }

    @Test
    void decodesMultiplePacketsInOneFrame() {
        List<Tick> ticks = KiteTickCodec.decode(
                frame(ltpPacket(RELIANCE_TOKEN, 100_00), quotePacket(RELIANCE_TOKEN, 101_00, 500)),
                KiteTickCodecTest::resolve, RECV);

        assertThat(ticks).hasSize(2);
        assertThat(ticks.get(0).lastPrice()).isEqualTo(100.0);
        assertThat(ticks.get(1).lastPrice()).isEqualTo(101.0);
    }

    @Test
    void skipsPacketsForTokensWeDidNotSubscribeTo() {
        // Never fabricate a symbol for an unknown token — that would inject a phantom instrument
        // into the candle engine under whatever name happened to be nearby.
        List<Tick> ticks = KiteTickCodec.decode(
                frame(ltpPacket(999_999L, 145_055)), KiteTickCodecTest::resolve, RECV);
        assertThat(ticks).isEmpty();
    }

    @Test
    void heartbeatAndTruncatedFramesAreIgnoredNotThrown() {
        assertThat(KiteTickCodec.decode(new byte[]{0}, KiteTickCodecTest::resolve, RECV)).isEmpty();
        assertThat(KiteTickCodec.decode(new byte[0], KiteTickCodecTest::resolve, RECV)).isEmpty();
        assertThat(KiteTickCodec.decode(null, KiteTickCodecTest::resolve, RECV)).isEmpty();

        // Claims two packets but carries one — must yield the good one and stop, not throw.
        byte[] good = ltpPacket(RELIANCE_TOKEN, 145_055);
        ByteBuffer b = ByteBuffer.allocate(2 + 2 + good.length).order(ByteOrder.BIG_ENDIAN);
        b.putShort((short) 2).putShort((short) good.length).put(good);
        assertThat(KiteTickCodec.decode(b.array(), KiteTickCodecTest::resolve, RECV)).hasSize(1);
    }

    @Test
    void zeroPriceIsTreatedAsMalformedNotAsAPrice() {
        List<Tick> ticks = KiteTickCodec.decode(
                frame(ltpPacket(RELIANCE_TOKEN, 0)), KiteTickCodecTest::resolve, RECV);
        assertThat(ticks)
                .as("a zero LTP would compute a -100% move and trigger every stop")
                .isEmpty();
    }

    /**
     * A full packet whose every book field is a different number.
     *
     * <p>Distinct values throughout, for the same reason the OHLC block uses them: the depth block
     * is twenty near-identical twelve-byte records, and a read that is one field out would decode
     * perfectly against a packet built from repeated constants.</p>
     */
    private static byte[] fullPacketWithBook(long token, int paise, int lastQty, int avgPaise,
                                             int volume, int totalBuy, int totalSell,
                                             int bidQty, int bidPaise, int askQty, int askPaise) {
        ByteBuffer b = ByteBuffer.allocate(184).order(ByteOrder.BIG_ENDIAN);
        b.putInt((int) token).putInt(paise);
        b.putInt(lastQty).putInt(avgPaise).putInt(volume).putInt(totalBuy).putInt(totalSell);
        b.putInt(OPEN_PAISE).putInt(HIGH_PAISE).putInt(LOW_PAISE).putInt(PREV_CLOSE_PAISE);
        b.putInt(0);                                    // last trade time
        b.putInt(0).putInt(0).putInt(0);                // oi, oi high, oi low
        b.putInt(0);                                    // exchange timestamp
        for (int i = 0; i < 5; i++) {                   // bid levels, thinning away from the touch
            b.putInt(i == 0 ? bidQty : 7).putInt(bidPaise - i).putShort((short) 1).putShort((short) 0);
        }
        for (int i = 0; i < 5; i++) {                   // ask levels
            b.putInt(i == 0 ? askQty : 9).putInt(askPaise + i).putShort((short) 1).putShort((short) 0);
        }
        return b.array();
    }

    /**
     * The four fields the codec used to read past and discard.
     *
     * <p>They were dropped on the grounds that the spread was all the strategy consulted. Entries
     * are MARKET orders, so what rests behind the touch decides the fill, and losses were realising
     * past 1R against a 1R stop — the book is the only place that can say why. Nothing reads these
     * yet; they are recorded so the question can be answered from real trades later.</p>
     */
    @Test
    void fullPacketKeepsTheBookAndTradeFlowFields() {
        List<Tick> ticks = KiteTickCodec.decode(
                frame(fullPacketWithBook(RELIANCE_TOKEN, 145_055, 37, 144_900, 1_234_567,
                        820_000, 410_000, 250, 145_050, 175, 145_060)),
                KiteTickCodecTest::resolve, RECV);

        assertThat(ticks).hasSize(1);
        var book = ticks.get(0).book();
        assertThat(book.isPresent()).isTrue();
        assertThat(book.lastTradedQuantity()).isEqualTo(37);
        assertThat(book.exchangeVwap())
                .as("the exchange's own session VWAP, a cross-check against the candle-derived one")
                .isEqualTo(1449.00);
        assertThat(book.totalBuyQuantity()).isEqualTo(820_000);
        assertThat(book.totalSellQuantity()).isEqualTo(410_000);
        assertThat(book.bidQuantity()).isEqualTo(250);
        assertThat(book.askQuantity()).isEqualTo(175);
        assertThat(book.depthAtTouch())
                .as("what a market buy eats into first, which the spread cannot reveal")
                .isEqualTo(175);
        assertThat(book.imbalance()).isEqualTo(2.0);

        // The prices must still decode exactly as before — the depth read now returns a pair, and
        // getting the tuple order wrong would swap a quantity into a price.
        assertThat(ticks.get(0).bestBid()).isEqualTo(1450.50);
        assertThat(ticks.get(0).bestAsk()).isEqualTo(1450.60);
        assertThat(ticks.get(0).lastPrice()).isEqualTo(1450.55);
    }

    @Test
    void quoteAndLtpPacketsCarryNoBookAtAll() {
        List<Tick> quote = KiteTickCodec.decode(
                frame(quotePacket(RELIANCE_TOKEN, 145_055, 1_234_567)),
                KiteTickCodecTest::resolve, RECV);
        List<Tick> ltp = KiteTickCodec.decode(
                frame(ltpPacket(RELIANCE_TOKEN, 145_055)), KiteTickCodecTest::resolve, RECV);

        assertThat(quote.get(0).book().isPresent())
                .as("a quote packet has no depth block, so there is no book to report")
                .isFalse();
        assertThat(quote.get(0).book().imbalance())
                .as("absent must read as NaN, never as balanced — they are different facts")
                .isNaN();
        assertThat(ltp.get(0).book().isPresent()).isFalse();
    }
}
