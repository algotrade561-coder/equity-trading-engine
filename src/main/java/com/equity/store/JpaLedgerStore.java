package com.equity.store;

import com.equity.domain.user.UserId;
import com.equity.risk.LedgerStore;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** The durable half of {@link com.equity.risk.AccountLedger}. */
@Component
public class JpaLedgerStore implements LedgerStore {

    private final DailyLedgerRepository repository;

    public JpaLedgerStore(DailyLedgerRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Snapshot> load(UserId userId, LocalDate tradingDate) {
        return repository.findByTradingUserIdAndTradingDate(userId.toString(), tradingDate)
                .map(e -> new Snapshot(e.getRealisedPnl(), e.getAttempts(), e.getFills(),
                        e.isLossLatched(), e.getLatchReason()));
    }

    /**
     * Written on every change rather than on a timer.
     *
     * <p>The row this protects is the loss latch, and the moment it matters most is the moment
     * something is going badly enough that the process might not survive to flush a buffer.</p>
     */
    @Override
    @Transactional
    public void save(UserId userId, LocalDate tradingDate, Snapshot snapshot) {
        DailyLedgerEntity entity = repository
                .findByTradingUserIdAndTradingDate(userId.toString(), tradingDate)
                .orElseGet(() -> new DailyLedgerEntity(userId.toString(), tradingDate));

        entity.overwrite(snapshot.realised(), snapshot.attempts(), snapshot.fills(),
                snapshot.latched(), snapshot.latchReason());
        repository.save(entity);
    }
}
