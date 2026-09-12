package com.equity.store;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserSettingsRepository extends JpaRepository<UserSettingsEntity, Long> {

    Optional<UserSettingsEntity> findByTradingUserId(String tradingUserId);

    void deleteByTradingUserId(String tradingUserId);
}
