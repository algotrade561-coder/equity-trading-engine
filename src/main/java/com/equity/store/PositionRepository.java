package com.equity.store;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PositionRepository extends JpaRepository<PositionEntity, Long> {

    Optional<PositionEntity> findByPositionId(String positionId);

    List<PositionEntity> findByTradingUserIdAndTradingDate(String tradingUserId, LocalDate tradingDate);

    /** Everything still holding shares, across users — what a restart has to reconcile. */
    List<PositionEntity> findByStatusIn(List<String> statuses);

    /** Every position of one session, closed ones included. Used to resume the order-tag sequence. */
    List<PositionEntity> findByTradingDate(LocalDate tradingDate);

    /** Whether this user currently holds or is waiting on a position. Deletion is refused while so. */
    boolean existsByTradingUserIdAndStatusIn(String tradingUserId, java.util.List<String> statuses);
}
