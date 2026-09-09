package com.equity.store;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConfigChangeRepository extends JpaRepository<ConfigChangeEntity, Long> {

    /** The change history for a user, oldest first — join a trade to it by its open time. */
    List<ConfigChangeEntity> findByTradingUserIdOrderByChangedAtAsc(String tradingUserId);
}
