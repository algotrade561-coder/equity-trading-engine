package com.equity.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.equity.broker.BrokerException;
import com.equity.broker.BrokerOrder;
import com.equity.broker.BrokerPort;
import com.equity.broker.BrokerPosition;
import com.equity.broker.OrderRequest;
import com.equity.broker.ProductType;
import com.equity.domain.user.UserId;
import com.equity.platform.time.FixedTradingClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Intraday leverage, asked for rather than assumed.
 *
 * <p>RELIANCE at 1322 blocks about 264 a share under MIS. The engine used to compare the full 1322
 * against available cash, so an account with 29,272 was refused a 75-share position needing 19,830 —
 * and the refusal was reported as {@code INSUFFICIENT_MARGIN}, which sends an operator to check their
 * bank balance rather than the arithmetic.</p>
 */
class MarginRequirementsTest {

    private static final UserId USER = UserId.random();
    private static final Instant NOW = Instant.parse("2026-09-04T05:00:00Z");

    private static final class StubBroker implements BrokerPort {
        boolean authenticated = true;
        double perShare = 264.40;
        boolean fail;
        int probes;

        @Override public String placeOrder(UserId u, OrderRequest r) {
            throw new AssertionError("a margin probe must never be submitted as an order");
        }
        @Override public void cancelOrder(UserId u, String id) {}
        @Override public void cancelOrder(UserId u, String id, com.equity.broker.OrderVariety v) {}
        @Override public void modifyOrder(UserId u, String id, int q, double p) {}
        @Override public List<BrokerOrder> fetchOrders(UserId u) { return List.of(); }
        @Override public List<BrokerPosition> fetchPositions(UserId u) { return List.of(); }
        @Override public double availableMargin(UserId u) { return 29_272; }
        @Override public boolean isAuthenticated(UserId u) { return authenticated; }

        @Override public double requiredMargin(UserId u, OrderRequest r) {
            probes++;
            if (fail) throw new BrokerException("broker down", "NetworkException", true);
            return perShare * r.quantity();
        }
    }

    @Test
    void theBrokerFigureIsUsedInPlaceOfTheFullPrice() {
        StubBroker broker = new StubBroker();
        var requirements = new MarginRequirements(broker, new FixedTradingClock(NOW));

        requirements.prime(USER, ProductType.MIS, List.of("RELIANCE"));

        assertThat(requirements.requiredFor("RELIANCE", ProductType.MIS, 75, 99_150))
                .as("75 shares at 264.40 each, not 75 at 1322")
                .isEqualTo(19_830, org.assertj.core.data.Offset.offset(1.0));
    }

    @Test
    void anUnaskedSymbolFallsBackToTheFullNotional() {
        var requirements = new MarginRequirements(new StubBroker(), new FixedTradingClock(NOW));

        assertThat(requirements.requiredFor("INFY", ProductType.MIS, 75, 99_150))
                .as("not knowing the leverage must refuse a trade, never authorise a larger one")
                .isEqualTo(99_150);
        assertThat(requirements.isKnown("INFY", ProductType.MIS)).isFalse();
    }

    @Test
    void aRequirementAboveTheNotionalIsClampedToIt() {
        StubBroker broker = new StubBroker();
        broker.perShare = 5_000;
        var requirements = new MarginRequirements(broker, new FixedTradingClock(NOW));

        requirements.prime(USER, ProductType.MIS, List.of("RELIANCE"));

        assertThat(requirements.requiredFor("RELIANCE", ProductType.MIS, 10, 13_220))
                .as("blocking more than the shares cost means the probe was misread")
                .isEqualTo(13_220);
    }

    @Test
    void eachSymbolIsAskedAboutOnceADay() {
        StubBroker broker = new StubBroker();
        var requirements = new MarginRequirements(broker, new FixedTradingClock(NOW));

        for (int i = 0; i < 20; i++) {
            requirements.prime(USER, ProductType.MIS, List.of("RELIANCE", "INFY"));
        }

        assertThat(broker.probes)
                .as("this runs every thirty seconds; re-asking would be a request storm")
                .isEqualTo(2);
    }

    @Test
    void yesterdaysLeverageIsNotCarriedIntoToday() {
        StubBroker broker = new StubBroker();
        FixedTradingClock clock = new FixedTradingClock(NOW);
        var requirements = new MarginRequirements(broker, clock);
        requirements.prime(USER, ProductType.MIS, List.of("RELIANCE"));

        clock.advance(Duration.ofDays(1));

        assertThat(requirements.isKnown("RELIANCE", ProductType.MIS))
                .as("leverage is set per day and can be cut intraday during volatility")
                .isFalse();
        assertThat(requirements.requiredFor("RELIANCE", ProductType.MIS, 75, 99_150))
                .isEqualTo(99_150);
    }

    @Test
    void aUserWhoHasNotLoggedInIsNotProbed() {
        StubBroker broker = new StubBroker();
        broker.authenticated = false;
        var requirements = new MarginRequirements(broker, new FixedTradingClock(NOW));

        requirements.prime(USER, ProductType.MIS, List.of("RELIANCE"));

        assertThat(broker.probes).isZero();
    }

    @Test
    void oneUnanswerableSymbolDoesNotStopTheRest() {
        StubBroker broker = new StubBroker();
        broker.fail = true;
        var requirements = new MarginRequirements(broker, new FixedTradingClock(NOW));

        requirements.prime(USER, ProductType.MIS, List.of("RELIANCE", "INFY", "TCS"));

        assertThat(broker.probes).isEqualTo(3);
        assertThat(requirements.isKnown("RELIANCE", ProductType.MIS)).isFalse();
    }
}
