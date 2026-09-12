package com.equity.store;

import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface CandleRepository extends JpaRepository<CandleEntity, Long> {

    /** A whole session, oldest first — the order the indicators walk it in. */
    List<CandleEntity> findByTradingDateOrderByStartTimeAsc(LocalDate tradingDate);

    /** One row of a session as the archive writes it. A projection, so nothing is managed. */
    interface CandleRow {
        String getSymbol();
        LocalDate getTradingDate();
        Instant getStartTime();
        double getOpen();
        double getHigh();
        double getLow();
        double getClose();
        long getVolume();
    }

    /**
     * A session as a stream, for the archive.
     *
     * <p>A session is around 180,000 rows. Loaded as entities into a list that is tens of megabytes
     * of heap on a box that has a gigabyte for everything, and every row would also sit in the
     * persistence context until the transaction ended. A stream of projections holds one row at a
     * time and nothing is managed. Must be consumed inside a read-only transaction.</p>
     */
    @QueryHints(@QueryHint(name = "org.hibernate.fetchSize", value = "2000"))
    Stream<CandleRow> streamByTradingDateOrderBySymbolAscStartTimeAsc(LocalDate tradingDate);

    long countByTradingDate(LocalDate tradingDate);

    /** The sessions on disk older than a date, oldest first. What the archive has left to do. */
    @Query("select distinct c.tradingDate from CandleEntity c where c.tradingDate < :cutoff order by c.tradingDate")
    List<LocalDate> tradingDatesBefore(@Param("cutoff") LocalDate cutoff);

    /**
     * Removes one session in a single statement.
     *
     * <p>A derived {@code deleteBy…} loads every matching entity and removes them one at a time,
     * which for a session is 180,000 loads and 180,000 deletes. This is one statement.</p>
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from CandleEntity c where c.tradingDate = :date")
    int deleteSession(@Param("date") LocalDate date);

    /** Removes every session older than a date in a single statement. The hard limit uses this. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from CandleEntity c where c.tradingDate < :cutoff")
    int deleteSessionsBefore(@Param("cutoff") LocalDate cutoff);
}
