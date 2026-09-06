package com.equity;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The layering rules that separate Gradle modules used to enforce at build time.
 *
 * <p>Collapsing to a single module traded compile-time enforcement for test-time enforcement. These
 * rules are that trade being honoured — without them, "domain has no framework dependency" is a
 * comment rather than a fact, and the first convenient {@code @Autowired} in a record quietly ends
 * it.</p>
 */
class ArchitectureRulesTest {

    /**
     * Everything that decides or executes a trade. None of it may reach the web layer or a broker
     * vendor package — the list is kept in one place so a new engine package cannot quietly opt out
     * of the rules by not being mentioned.
     */
    private static final String[] ENGINE_PACKAGES = {
            "com.equity.domain..", "com.equity.market..", "com.equity.strategy..",
            "com.equity.trading..", "com.equity.risk..", "com.equity.user..",
            "com.equity.session.."
    };

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.equity");
    }

    /**
     * The rule that keeps REPLAY honest.
     *
     * <p>LIVE and REPLAY must run identical strategy code, differing only in the event source, the
     * execution sink and the clock. A single wall-clock call anywhere else makes a replay
     * non-reproducible — two runs of the same tape then disagree, and every conclusion drawn from
     * that tape becomes unfalsifiable.</p>
     */
    @Test
    void nothingOutsidePlatformTimeMayReadTheWallClock() {
        noClasses()
                // Two narrow exemptions, and only these.
                //
                // `store` and `platform.security` stamp rows with createdAt / updatedAt /
                // lastLoginAt. Those are facts about operating the system — when a person signed in,
                // when a row was written — not about the market, and they are not inputs to any
                // decision. Routing them through the trading clock would be actively wrong under
                // REPLAY: replaying last week's tape would claim the row was written last week.
                //
                // Everything that decides or executes a trade stays banned. If a new package needs
                // an exemption, that is the moment to ask whether it is really doing persistence.
                .that().resideOutsideOfPackages(
                        "com.equity.platform.time..",
                        "com.equity.store..",
                        "com.equity.platform.security..")
                .should().callMethod(Instant.class, "now")
                .orShould().callMethod(System.class, "currentTimeMillis")
                .orShould().callMethod(LocalDate.class, "now")
                .orShould().callMethod(LocalTime.class, "now")
                .because("time must come from TradingClock, or REPLAY is not deterministic")
                .check(classes);
    }

    /**
     * Domain stays framework-free so the state machine and detectors can be unit-tested with no
     * Spring context, and so the same classes run unchanged under LIVE and REPLAY.
     */
    @Test
    void domainDoesNotDependOnSpring() {
        noClasses()
                .that().resideInAPackage("com.equity.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "jakarta.persistence..")
                .because("domain must remain a plain-Java model with no framework coupling")
                .check(classes);
    }

    /**
     * The engine packages carry the ban with no exemptions at all.
     *
     * <p>Stated separately from the rule above so that widening an exemption there can never
     * accidentally widen it here. This is the one that protects REPLAY.</p>
     */
    @Test
    void noEnginePackageReadsTheWallClockUnderAnyCircumstances() {
        noClasses()
                .that().resideInAnyPackage(ENGINE_PACKAGES)
                .should().callMethod(Instant.class, "now")
                .orShould().callMethod(System.class, "currentTimeMillis")
                .orShould().callMethod(LocalDate.class, "now")
                .orShould().callMethod(LocalTime.class, "now")
                .because("a single wall-clock call in the decision path makes a replay "
                        + "non-reproducible, and every result drawn from that tape unfalsifiable")
                .check(classes);
    }

    /**
     * Only the position lifecycle may place an order.
     *
     * <p>Design note 0.2, enforced rather than documented. In the sibling engine five code paths
     * could close a position and 29% of one strategy's trades were exited by a path that did not
     * own them. A strategy or risk class that can reach {@code BrokerPort.placeOrder} is one
     * refactor away from repeating that, so the compiler-adjacent check lives here.</p>
     */
    @Test
    void onlyThePositionLifecycleMayPlaceOrders() {
        noClasses()
                .that().resideOutsideOfPackages("com.equity.trading..", "com.equity.broker..")
                .should().callMethod(com.equity.broker.BrokerPort.class, "placeOrder",
                        com.equity.domain.user.UserId.class, com.equity.broker.OrderRequest.class)
                .because("exactly one component opens and closes positions")
                .check(classes);
    }

    /** Market data is shared and must not reach upward into strategy, API or wiring. */
    @Test
    void marketDoesNotDependOnStrategyOrWeb() {
        noClasses()
                .that().resideInAPackage("com.equity.market..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.equity.strategy..", "com.equity.trading..",
                        "com.equity.api..", "com.equity.app..")
                .because("shared market state must not depend on anything user-specific")
                .check(classes);
    }

    /**
     * The engine talks to a broker port, never to Kite.
     *
     * <p>Without this rule the vendor-neutral port is decoration: one convenient import of a Kite
     * type in the strategy and REPLAY can no longer substitute a tape source, because the strategy
     * now needs an access token to compile. The Kite package is reachable only from wiring and the
     * API layer, which is where a real broker belongs.</p>
     */
    @Test
    void engineCodeTalksToTheBrokerPortNotToKite() {
        noClasses()
                .that().resideInAnyPackage(ENGINE_PACKAGES)
                .should().dependOnClassesThat().resideInAPackage("com.equity.broker.kite..")
                .because("the broker is behind a port; a vendor type in the engine breaks REPLAY")
                .check(classes);
    }

    /** The web layer is a view. Engines must never call back into controllers. */
    @Test
    void engineCodeDoesNotDependOnTheApiLayer() {
        noClasses()
                .that().resideInAnyPackage(ENGINE_PACKAGES)
                .should().dependOnClassesThat().resideInAPackage("com.equity.api..")
                .because("the API is a view over the engine, never an input to it")
                .check(classes);
    }
}
