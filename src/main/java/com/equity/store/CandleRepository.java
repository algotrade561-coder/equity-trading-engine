package com.equity.store;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CandleRepository extends JpaRepository<CandleEntity, Long> {

    /** A whole session, oldest first — the order the indicators walk it in. */
    List<CandleEntity> findByTradingDateOrderByStartTimeAsc(LocalDate tradingDate);

    /** Pruning. Sessions older than the retention window are of no use to a restart. */
    long deleteByTradingDateBefore(LocalDate cutoff);
}
