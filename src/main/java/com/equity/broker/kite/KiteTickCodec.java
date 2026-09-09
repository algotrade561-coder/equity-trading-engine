package com.equity.broker.kite;

import com.equity.domain.market.BookState;
import com.equity.domain.market.Tick;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongFunction;

/**
 * Decoder for Kite's binary streaming protocol.
 *
 * <p>Written as a <b>pure function of bytes</b> with no socket, no Spring and no clock, because
 * this is the fiddliest code in the broker layer and the only way to be confident in it is to feed
 * it hand-built frames in a unit test. A parser embedded inside a WebSocket listener can only be
 * tested against a live market, which is to say not tested.</p>
 *
 * <h2>Wire format (big-endian throughout)</h2>
 * <pre>
 *   frame:  int16 packetCount, then per packet: int16 length, then `length` bytes
 *   packet: int32 instrumentToken, int32 lastPrice (in paise)
 * </pre>
 *
 * Packet length determines the mode:
 * <pre>
 *    8   LTP
 *   28   index quote      32  index full
 *   44   equity quote    184  equity full (adds 5-level depth)
 * </pre>
 *
 * <p><b>Prices arrive in paise and are divided by 100.</b> Getting this wrong yields prices 100x
 * out, which is obvious — but the same field is also the basis for a stop distance, where a silent
 * factor of 100 would size a position catastrophically. Hence the explicit constant.</p>
 *
 * <p><b>Volume is the exchange's cumulative day figure</b>, not a delta. It is passed through as-is;
 * {@code CandleEngine} differences it per bucket.</p>
 */
public final class KiteTickCodec {

    /** Kite quotes prices in paise. */
    private static final double PAISE = 100.0;

    static final int LEN_LTP = 8;
    static final int LEN_INDEX_QUOTE = 28;
    static final int LEN_INDEX_FULL = 32;
    static final int LEN_QUOTE = 44;
    static final int LEN_FULL = 184;

    private KiteTickCodec() {}

    /**
     * Decode one binary frame into ticks.
     *
     * @param symbolResolver maps an instrument token to a trading symbol; a token we did not
     *                       subscribe to (or that is not in the instrument master) yields null and
     *                       the packet is skipped rather than producing a tick with no symbol
     * @param receivedAt     arrival time, supplied by the caller so the codec stays clock-free
     */
    public static List<Tick> decode(byte[] frame, LongFunction<String> symbolResolver, Instant receivedAt) {
        List<Tick> out = new ArrayList<>();
        if (frame == null || frame.length < 2) {
            return out;   // heartbeat — Kite sends 1-byte frames to keep the socket alive
        }
        ByteBuffer buf = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
        int packetCount = buf.getShort() & 0xFFFF;

        for (int i = 0; i < packetCount; i++) {
            if (buf.remaining() < 2) break;
            int len = buf.getShort() & 0xFFFF;
            if (len <= 0 || buf.remaining() < len) break;   // truncated frame — stop, do not guess
            byte[] packet = new byte[len];
            buf.get(packet);
            Tick t = decodePacket(packet, len, symbolResolver, receivedAt);
            if (t != null) out.add(t);
        }
        return out;
    }

    private static Tick decodePacket(byte[] packet, int len,
                                     LongFunction<String> symbolResolver, Instant receivedAt) {
        if (len < LEN_LTP) {
            return null;   // subscription ack or heartbeat, not a quote
        }
        ByteBuffer p = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN);
        long token = p.getInt() & 0xFFFFFFFFL;
        double lastPrice = p.getInt() / PAISE;

        String symbol = symbolResolver.apply(token);
        if (symbol == null) {
            return null;   // not ours — never fabricate a symbol
        }
        if (lastPrice <= 0) {
            return null;   // a zero LTP is a malformed packet, not a price of zero
        }

        long cumulativeVolume = 0;
        double bestBid = 0, bestAsk = 0;
        long bidQuantity = 0, askQuantity = 0;
        long totalBuyQuantity = 0, totalSellQuantity = 0, lastTradedQuantity = 0;
        double exchangeVwap = 0;
        double dayOpen = 0, dayHigh = 0, dayLow = 0, previousClose = 0;
        Instant exchangeTime = receivedAt;

        boolean isIndex = len == LEN_INDEX_QUOTE || len == LEN_INDEX_FULL;

        if (isIndex) {
            // Indices carry no volume and no depth, and their OHLC block is ordered differently
            // from an equity packet — high/low/open/close rather than open/high/low/close.
            dayHigh = p.getInt() / PAISE;
            dayLow = p.getInt() / PAISE;
            dayOpen = p.getInt() / PAISE;
            previousClose = p.getInt() / PAISE;
            if (len == LEN_INDEX_FULL) {
                int ts = p.getInt();
                if (ts > 0) exchangeTime = Instant.ofEpochSecond(ts);
            }
        } else if (len >= LEN_QUOTE) {
            // These four were read and dropped. They cost nothing to keep — the bytes are already
            // on the wire and already being parsed — and they are the only view the engine gets of
            // resting interest and trade size. Entries are MARKET orders, so what rests behind the
            // touch decides the fill; the spread alone cannot say whether a book is deep or thin.
            lastTradedQuantity = p.getInt() & 0xFFFFFFFFL;
            exchangeVwap = p.getInt() / PAISE;
            cumulativeVolume = p.getInt() & 0xFFFFFFFFL;  // cumulative day volume
            totalBuyQuantity = p.getInt() & 0xFFFFFFFFL;
            totalSellQuantity = p.getInt() & 0xFFFFFFFFL;
            // The OHLC block. `close` is the PREVIOUS day's close during a session, which is the
            // denominator of every day-change figure and therefore of the whole gainer ranking.
            dayOpen = p.getInt() / PAISE;
            dayHigh = p.getInt() / PAISE;
            dayLow = p.getInt() / PAISE;
            previousClose = p.getInt() / PAISE;

            if (len == LEN_FULL) {
                p.getInt();                               // last trade time
                p.getInt();                               // open interest
                p.getInt();                               // OI day high
                p.getInt();                               // OI day low
                int ts = p.getInt();
                if (ts > 0) exchangeTime = Instant.ofEpochSecond(ts);

                // 5 bid levels then 5 ask levels; each is qty(4) price(4) orders(2) padding(2).
                // Only the top of book is kept — the spread check is all the strategy needs, and
                // retaining 10 levels per tick for 200 symbols is a lot of garbage per second.
                long[] bid = readTopOfBook(p);
                long[] ask = readTopOfBook(p);
                bidQuantity = bid[0];
                bestBid = bid[1] / PAISE;
                askQuantity = ask[0];
                bestAsk = ask[1] / PAISE;
            }
        }

        BookState book = new BookState(bidQuantity, askQuantity, totalBuyQuantity,
                totalSellQuantity, lastTradedQuantity, exchangeVwap);
        return new Tick(symbol, lastPrice, cumulativeVolume, bestBid, bestAsk,
                dayOpen, dayHigh, dayLow, previousClose, exchangeTime, receivedAt, book);
    }

    /**
     * Reads the first of five depth entries and skips the remaining four.
     *
     * <p>Levels two to five are still skipped deliberately: ten levels per tick across five hundred
     * symbols is a great deal of short-lived garbage, and nothing yet asks a question that needs
     * them. The quantity at the touch is kept, because that is the one that decides a market fill.</p>
     *
     * @return {@code [quantity, priceInPaise]}
     */
    private static long[] readTopOfBook(ByteBuffer p) {
        if (p.remaining() < 60) return new long[]{0, 0};
        long quantity = p.getInt() & 0xFFFFFFFFL;
        long price = p.getInt();
        p.getShort();                        // order count
        p.getShort();                        // padding
        p.position(p.position() + 48);       // skip levels 2..5
        return new long[]{quantity, price};
    }
}
