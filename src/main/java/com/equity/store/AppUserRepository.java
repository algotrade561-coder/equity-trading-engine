package com.equity.store;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUserEntity, Long> {

    Optional<AppUserEntity> findByEmailIgnoreCase(String email);

    Optional<AppUserEntity> findByTradingUserId(String tradingUserId);
}
