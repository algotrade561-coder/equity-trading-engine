package com.equity.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Boot smoke test — fails the build if the context cannot start.
 *
 * <p><b>Pinned to the classpath configuration only.</b> Spring Boot also reads {@code ./config/},
 * which on a developer machine holds local credentials and whatever switches that operator has
 * turned on. Without this pin the safety assertion below tests the machine it happens to run on
 * rather than what a fresh checkout does — and it would pass or fail depending on somebody's local
 * file, which is the opposite of what a guard is for.</p>
 */
@SpringBootTest(properties = "spring.config.location=classpath:/application.yml")
class EquityApplicationTests {

    @Autowired
    private EngineProperties props;

    @Test
    void contextLoads() {
        assertThat(props).isNotNull();
    }

    /**
     * A fresh checkout must not be able to send an order.
     *
     * <p>This asserts the combination rather than each switch, deliberately. Sending requires
     * <b>both</b> {@code mode: LIVE} and {@code trading-enabled: true}; either one alone is inert,
     * and the operator of this repository keeps the master switch on in the tracked file while
     * testing. Guarding the pair keeps the property that actually matters — nobody who clones this
     * can trade by accident — without policing a setting that cannot do harm on its own.</p>
     *
     * <p>If this fails, someone made the engine live <i>and</i> armed for everyone who clones the
     * repository. That is a decision worth making explicitly in a local override, never a line that
     * arrives with a checkout.</p>
     */
    @Test
    void aFreshCheckoutCannotSendAnOrder() {
        boolean canSend = props.getMode() == ExecutionMode.LIVE && props.isTradingEnabled();

        assertThat(canSend)
                .as("shipped config is mode=%s, trading-enabled=%s — that combination sends orders",
                        props.getMode(), props.isTradingEnabled())
                .isFalse();
    }

    /** The mode itself still ships as REPLAY; that half is not negotiable. */
    @Test
    void shipsInReplayMode() {
        assertThat(props.getMode())
                .as("mode must ship as REPLAY")
                .isEqualTo(ExecutionMode.REPLAY);
    }
}
