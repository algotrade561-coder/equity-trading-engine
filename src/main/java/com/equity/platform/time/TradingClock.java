package com.equity.platform.time;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * The only source of time in the system.
 *
 * <p><b>Direct calls to {@code Instant.now()}, {@code System.currentTimeMillis()} and
 * {@code LocalDate.now()} are banned outside this package</b> and an ArchUnit rule fails the build
 * on them. The reason is REPLAY: the specification requires that LIVE and REPLAY run identical
 * strategy code, differing only in the event source, the execution sink and the clock. A single
 * stray wall-clock call anywhere in the strategy makes a replay non-reproducible — two runs of the
 * same tape then disagree, and every result derived from that tape becomes unfalsifiable.</p>
 *
 * <p>LIVE uses {@link SystemTradingClock}; REPLAY uses a tape clock advanced by event timestamps.</p>
 */
public interface TradingClock {

    /** Indian market timezone. All session boundaries are expressed in it. */
    ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Current instant — wall clock under LIVE, tape position under REPLAY. */
    Instant now();

    default ZonedDateTime nowIst() {
        return now().atZone(IST);
    }

    default LocalTime timeOfDay() {
        return nowIst().toLocalTime();
    }

    /** The trading date. Not simply "today" — under REPLAY it is the tape's date. */
    default LocalDate tradingDate() {
        return nowIst().toLocalDate();
    }
}
