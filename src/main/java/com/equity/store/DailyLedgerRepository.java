package com.equity.store;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DailyLedgerRepository extends JpaRepository<DailyLedgerEntity, Long> {

    Optional<DailyLedgerEntity> findByTradingUserIdAndTradingDate(String tradingUserId,
                                                                  LocalDate tradingDate);
}
