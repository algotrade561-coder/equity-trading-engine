package com.equity.store;

import com.equity.domain.user.UserId;
import com.equity.provisioning.IpAllocation;
import com.equity.provisioning.IpAllocationStatus;
import com.equity.provisioning.IpAllocationStore;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Address allocations on disk. Written on every provisioning step, so a crash leaves the truth. */
@Component
public class JpaIpAllocationStore implements IpAllocationStore {

    private final IpAllocationRepository repository;

    public JpaIpAllocationStore(IpAllocationRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public IpAllocation save(IpAllocation allocation) {
        IpAllocationEntity entity = allocation.id() == null
                ? IpAllocationEntity.from(allocation)
                : repository.findById(allocation.id())
                        .map(e -> { e.apply(allocation); return e; })
                        .orElseGet(() -> IpAllocationEntity.from(allocation));
        return repository.save(entity).toAllocation();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<IpAllocation> current(UserId userId) {
        return repository.findFirstByTradingUserIdAndStatusNotOrderByCreatedAtDesc(
                        userId.toString(), IpAllocationStatus.RELEASED)
                .map(IpAllocationEntity::toAllocation);
    }

    @Override
    @Transactional(readOnly = true)
    public List<IpAllocation> live() {
        return repository.findByStatusNot(IpAllocationStatus.RELEASED).stream()
                .map(IpAllocationEntity::toAllocation)
                .toList();
    }
}
