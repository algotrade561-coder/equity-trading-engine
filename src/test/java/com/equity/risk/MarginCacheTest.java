package com.equity.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.equity.broker.BrokerException;
import com.equity.broker.BrokerOrder;
import com.equity.broker.BrokerPort;
import com.equity.broker.BrokerPosition;
import com.equity.broker.OrderRequest;
import com.equity.domain.user.UserId;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarginCacheTest {

    private static final UserId USER = UserId.random();

    private static final class StubBroker implements BrokerPort {
        boolean authenticated = true;
        boolean fail;
        double margin = 125_000;
        int marginCalls;

        @Override public String placeOrder(UserId u, OrderRequest r) { return "o1"; }
        @Override public void cancelOrder(UserId u, String id) {}
        @Override public void cancelOrder(UserId u, String id, com.equity.broker.OrderVariety v) {}
        @Override public void modifyOrder(UserId u, String id, int q, double p) {}
        @Override public List<BrokerOrder> fetchOrders(UserId u) { return List.of(); }
        @Override public List<BrokerPosition> fetchPositions(UserId u) { return List.of(); }
        @Override public boolean isAuthenticated(UserId u) { return authenticated; }

        @Override public double availableMargin(UserId u) {
            marginCalls++;
            if (fail) throw new BrokerException("broker down", "NetworkException", true);
            return margin;
        }
    }

    @Test
    void readsAndCachesTheBrokerFigure() {
        StubBroker broker = new StubBroker();
        MarginCache cache = new MarginCache(broker);

        cache.refresh(USER);

        assertThat(cache.available(USER)).isEqualTo(125_000);
    }

    @Test
    void anUnknownUserHasNoMarginRatherThanUnlimited() {
        assertThat(new MarginCache(new StubBroker()).available(UserId.random()))
                .as("sizing against an unfetched margin must refuse, not assume")
                .isZero();
    }

    @Test
    void doesNotCallTheBrokerForAUserWhoHasNotLoggedIn() {
        StubBroker broker = new StubBroker();
        broker.authenticated = false;
        MarginCache cache = new MarginCache(broker);

        for (int i = 0; i < 10; i++) cache.refresh(USER);

        assertThat(broker.marginCalls)
                .as("not being logged in is the normal resting state, not a fault worth logging")
                .isZero();
        assertThat(cache.available(USER)).isZero();
    }

    @Test
    void aLogoutDropsTheStaleFigure() {
        StubBroker broker = new StubBroker();
        MarginCache cache = new MarginCache(broker);
        cache.refresh(USER);

        broker.authenticated = false;
        cache.refresh(USER);

        assertThat(cache.available(USER))
                .as("yesterday's margin must not authorise today's position")
                .isZero();
    }

    @Test
    void aTransientBrokerFailureKeepsTheLastKnownFigure() {
        StubBroker broker = new StubBroker();
        MarginCache cache = new MarginCache(broker);
        cache.refresh(USER);

        broker.fail = true;
        cache.refresh(USER);

        assertThat(cache.available(USER))
                .as("zeroing here would look identical to a real margin call")
                .isEqualTo(125_000);
    }
}
