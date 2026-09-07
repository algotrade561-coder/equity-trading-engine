package com.equity.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.equity.domain.risk.RiskLimits;
import com.equity.domain.user.Role;
import com.equity.domain.user.TradingUser;
import com.equity.domain.user.UserId;
import com.equity.domain.user.UserStatus;
import com.equity.strategy.StrategyThresholds;
import com.equity.trading.Reconciler;
import com.equity.user.UserAccount;
import com.equity.user.UserRegistry;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * That reconciliation is actually scheduled, and that one user's broker problem does not stop the
 * others being reconciled.
 *
 * <p>Worth asserting because the failure is invisible: an unscheduled reconciler compiles, passes
 * every one of its own tests, and simply never runs — leaving the engine with exactly the silent
 * drift it was written to catch. The same goes for the loop: an exception escaping the first user
 * would skip everyone after them, every minute, with nothing to show for it.</p>
 */
class SessionReconciliationScheduleTest {

    @Test
    void reconciliationIsOnTheScheduler() throws Exception {
        Method method = SessionOrchestrator.class.getMethod("reconcile");
        Scheduled schedule = method.getAnnotation(Scheduled.class);

        assertThat(schedule)
                .as("an unscheduled reconciler never runs, and nothing anywhere would say so")
                .isNotNull();
        assertThat(schedule.fixedDelay())
                .as("a minute is short enough that drift is caught while it is still cheap")
                .isEqualTo(60_000L);
        assertThat(schedule.initialDelay())
                .as("positions are restored from the database at startup and are stale until a pass "
                        + "confirms them, so the first one must not wait a full minute")
                .isLessThanOrEqualTo(15_000L);
    }

    @Test
    void oneUsersBrokerFailureDoesNotStopTheOthersBeingReconciled() {
        UserAccount first = account();
        UserAccount second = account();

        UserRegistry users = mock(UserRegistry.class);
        when(users.all()).thenReturn(List.of(first, second));

        Reconciler reconciler = mock(Reconciler.class);
        when(reconciler.reconcile(first)).thenThrow(new IllegalStateException("broker down"));
        when(reconciler.reconcile(second))
                .thenReturn(Reconciler.Report.unavailable(java.time.Instant.now(), "stub"));

        SessionOrchestrator orchestrator = orchestratorWith(users, reconciler);
        orchestrator.reconcile();

        verify(reconciler, times(1)).reconcile(first);
        verify(reconciler, times(1))
                .reconcile(second);
    }

    private static UserAccount account() {
        return new UserAccount(
                new TradingUser(UserId.random(), "u", "", UserStatus.ACTIVE, Set.of(Role.TRADER)),
                RiskLimits.conservative(), StrategyThresholds.defaults());
    }

    /**
     * The orchestrator has a wide constructor and this exercises one method of it, so everything it
     * does not touch is a mock. Building it for real here would be testing Spring, not the loop.
     */
    private static SessionOrchestrator orchestratorWith(UserRegistry users, Reconciler reconciler) {
        return new SessionOrchestrator(
                mock(com.equity.broker.MarketDataPort.class),
                mock(com.equity.broker.OrderUpdatePort.class),
                mock(com.equity.market.MarketDataRouter.class),
                mock(com.equity.market.candle.CandleEngine.class),
                mock(com.equity.market.state.StructureEngine.class),
                mock(com.equity.market.universe.UniverseService.class),
                mock(com.equity.market.universe.UniverseProperties.class),
                mock(com.equity.market.universe.IndexConstituentSource.class),
                mock(com.equity.market.InstrumentFreshness.class),
                mock(com.equity.strategy.MomentumStrategy.class),
                mock(com.equity.strategy.RejectionLog.class),
                mock(com.equity.strategy.DecisionJournal.class),
                mock(com.equity.risk.RiskEngine.class),
                mock(com.equity.risk.AccountLedger.class),
                mock(com.equity.risk.MarginCache.class),
                mock(com.equity.risk.MarginRequirements.class),
                reconciler,
                mock(com.equity.session.SessionBackfill.class),
                mock(com.equity.trading.PositionBook.class),
                mock(com.equity.trading.PositionLifecycle.class),
                users,
                mock(com.equity.platform.time.TradingClock.class));
    }
}
