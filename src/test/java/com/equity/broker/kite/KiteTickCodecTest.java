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
}
