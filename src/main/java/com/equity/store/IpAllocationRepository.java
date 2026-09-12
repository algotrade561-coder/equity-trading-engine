package com.equity.store;

import com.equity.provisioning.IpAllocationStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IpAllocationRepository extends JpaRepository<IpAllocationEntity, Long> {

    Optional<IpAllocationEntity> findFirstByTradingUserIdAndStatusNotOrderByCreatedAtDesc(
            String tradingUserId, IpAllocationStatus status);

    List<IpAllocationEntity> findByStatusNot(IpAllocationStatus status);

    boolean existsByTradingUserId(String tradingUserId);

    void deleteByTradingUserId(String tradingUserId);
}
