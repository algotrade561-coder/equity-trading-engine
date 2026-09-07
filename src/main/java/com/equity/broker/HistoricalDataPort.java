package com.equity.broker;

import com.equity.domain.market.Candle;
import com.equity.domain.user.UserId;
import java.time.LocalDate;
import java.util.List;

/**
 * Completed intraday bars for a session, from the broker.
 *
 * <h2>Why the engine needs this</h2>
 * <p>Every indicator the strategy reads is derived from the in-memory 1-minute series, and that
 * series is built from the live tick stream. So a process that starts at 10:30 believes the session
 * began at 10:30. The visible cost is a warm-up: twenty bars before anything can be evaluated. The
 * invisible cost is worse and permanent — VWAP is a session-cumulative figure, so a truncated series
 * yields a number that is not VWAP and never becomes one, and {@code requireAboveVwap} is a mandatory
 * entry gate. Every decision for the rest of the day is then judged against the wrong level.</p>
 *
 * <p>Backfilling the session's completed bars makes a restart cost nothing, which is what turns
 * "restarting will cost us the day" into an ordinary operation.</p>
 *
 * <p>Separate from {@link MarketDataPort} deliberately: that is a live subscription with listeners
 * and connection state, this is a request for the past. A broker may serve one and not the other —
 * at Kite historical data is a paid add-on — so an implementation must be able to support streaming
 * while honestly reporting that it cannot supply history.</p>
 */
public interface HistoricalDataPort {

    /**
     * Completed 1-minute candles for {@code symbol} on {@code date}, oldest first.
     *
     * <p>Returns empty rather than throwing when history is unavailable — an unsubscribed feature,
     * an unknown symbol, a refused request. The caller's fallback is the live tick stream it was
     * already using, so an empty answer degrades to today's behaviour rather than failing startup.</p>
     */
    default List<Candle> intradayMinutes(UserId userId, String symbol, LocalDate date) {
        return List.of();
    }

    /**
     * Whether history can be fetched at all.
     *
     * <p>Checked once so the reason appears in the log a single time, rather than as one refusal per
     * symbol across five hundred attempts.</p>
     */
    default boolean isHistoryAvailable(UserId userId, LocalDate date) {
        return false;
    }
}
