package com.equity.store;

import com.equity.domain.position.Position;
import com.equity.domain.position.PositionStatus;
import com.equity.platform.time.TradingClock;
import com.equity.trading.PositionStore;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Positions on disk. */
@Component
public class JpaPositionStore implements PositionStore {

    private final PositionRepository repository;
    private final TradingClock clock;

    public JpaPositionStore(PositionRepository repository, TradingClock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Written on every transition, synchronously.
     *
     * <p>A position changes a handful of times in its life, so the cost is negligible — and the
     * transitions that matter most (filled, exiting, closed) are exactly the ones you cannot afford
     * to lose to a crash between a buffer and a flush.</p>
     */
    @Override
    @Transactional
    public void save(Position position) {
        PositionEntity entity = repository.findByPositionId(position.id().toString())
                .orElseGet(() -> PositionEntity.from(position, clock.tradingDate()));
        entity.apply(position);
        repository.save(entity);
    }

    /**
     * Everything that still held shares, or was still waiting on an entry, when the process stopped.
     *
     * <p>PENDING_ENTRY is included deliberately. No shares are held yet, but an order may have filled
     * while the engine was down — dropping those would leave a real holding nobody is watching, which
     * is the exact failure this store exists to prevent.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public List<Position> loadOpen() {
        return repository.findByStatusIn(List.of(
                        PositionStatus.PENDING_ENTRY.name(),
                        PositionStatus.OPEN.name(),
                        PositionStatus.EXIT_PENDING.name()))
                .stream()
                .map(PositionEntity::toPosition)
                .toList();
    }
}
