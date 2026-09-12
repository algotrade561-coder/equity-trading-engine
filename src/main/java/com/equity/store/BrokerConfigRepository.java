package com.equity.store;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BrokerConfigRepository extends JpaRepository<BrokerConfigEntity, Long> {

    Optional<BrokerConfigEntity> findByTradingUserId(String tradingUserId);

    List<BrokerConfigEntity> findByTokenTradingDate(java.time.LocalDate tradingDate);

    void deleteByTradingUserId(String tradingUserId);
}
