package com.equity.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.equity.domain.risk.RiskLimits;
import com.equity.domain.user.UserId;
import com.equity.strategy.ExitPolicy;
import com.equity.strategy.StrategyThresholds;
import com.equity.user.UserAccount;
import com.equity.user.UserRegistry;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Settings survive a restart, and reach the code that acts on them.
 *
 * <p>"Wired" is three separate claims and each one can fail on its own: the value is persisted, a
 * cold start hydrates it instead of falling back to defaults, and the running engine sees it without
 * waiting for a restart. A change that is saved but not applied is useless mid-session; one that is
 * applied but not saved is the behaviour this whole persistence layer was added to remove.</p>
 *
 * <p>Runs against an in-memory database on purpose — pointed at the configured file it would write
 * into the real {@code ./data/equity}, and a test that mutates production settings is worse than no
 * test.</p>
 */
@SpringBootTest(classes = com.equity.app.EquityApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:settings-round-trip;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "equity.security.secret-key=test-key-not-a-real-one",
        "equity.auth.google.enabled=false",
})
class SettingsRoundTripTest {

    @Autowired private UserProfileService profiles;
    @Autowired private StoredUserSettings storedSettings;
    @Autowired private UserRegistry registry;

    private static final UserId USER = UserId.random();

    /** A registry built fresh from the same store — what a restart produces. */
    private UserAccount afterRestart(UserId userId) {
        return new UserRegistry(storedSettings).ensure(userId);
    }

    @Test
    void riskLimitsAreStoredAndComeBackAfterARestart() {
        RiskLimits custom = new RiskLimits(2_500, 7_000, 5, 20, 3,
                250_000, 0.4, 2.0, 0.15, 600, 20);

        profiles.saveRiskLimits(USER, custom);

        RiskLimits reloaded = afterRestart(USER).limits();
        assertThat(reloaded.riskPerTradeRupees()).isEqualTo(2_500);
        assertThat(reloaded.maxDailyLossRupees())
                .as("the daily loss limit is the control most worth not losing to a restart")
                .isEqualTo(7_000);
        assertThat(reloaded.maxOpenPositions()).isEqualTo(5);
        assertThat(reloaded.cooldownSeconds()).isEqualTo(600);
    }

    @Test
    void entryThresholdsAreStoredAndComeBackAfterARestart() {
        StrategyThresholds custom = new StrategyThresholds(
                2.2, 9.0, 20.0, 1.0, 0.9, 1.8, false, false, false,
                0.3, 1.0, 0.6, 4, 0.08, 2.0, 3.0, 30,
                LocalTime.of(9, 45), LocalTime.of(14, 0), LocalTime.of(15, 5));

        profiles.saveThresholds(USER, custom);

        StrategyThresholds reloaded = afterRestart(USER).thresholds();
        assertThat(reloaded.minDayChangePercent()).isEqualTo(2.2);
        assertThat(reloaded.requireAboveVwap()).isFalse();
        assertThat(reloaded.stopAtrMultiple()).isEqualTo(2.0);
        assertThat(reloaded.targetRMultiple()).isEqualTo(3.0);
        assertThat(reloaded.entryWindowStart())
                .as("times are stored as text and must parse back to the same clock time")
                .isEqualTo(LocalTime.of(9, 45));
        assertThat(reloaded.squareOffTime()).isEqualTo(LocalTime.of(15, 5));
    }

    @Test
    void theExitPolicySurvivesARestart() {
        profiles.saveExitPolicy(USER, ExitPolicy.trailing());

        ExitPolicy reloaded = afterRestart(USER).exitPolicy();
        assertThat(reloaded.trailingEnabled()).isTrue();
        assertThat(reloaded.breakevenEnabled()).isTrue();
        assertThat(reloaded.structureExitEnabled()).isTrue();
        assertThat(reloaded.trailingAtrMultiple()).isEqualTo(1.5);
    }

    @Test
    void aUserWithNothingStoredGetsTheShippedDefaults() {
        UserAccount fresh = afterRestart(UserId.random());

        assertThat(fresh.limits().riskPerTradeRupees())
                .isEqualTo(RiskLimits.conservative().riskPerTradeRupees());
        assertThat(fresh.exitPolicy())
                .as("breakeven at 1R ships on; everything else after entry stays off")
                .isEqualTo(ExitPolicy.breakevenAtOneR());
    }

    /**
     * The shipped exit policy, and the reasoning behind each half of it.
     *
     * <p>It shipped with everything off because nothing had been measured. The first session that
     * traded measured it: ten closed positions gave back Rs 9,616 between their peak and their exit,
     * and two reached a full R of profit before finishing negative — BEML ran to +1.30R and closed
     * at -1.35R. Moving the stop to entry at 1R turns those into scratches.</p>
     *
     * <p>Trailing and structure exit stay off, and that is the more important half. Both can clip a
     * winner, and the same session had three trades run past 2R that needed the room. Breakeven is
     * defensible on one session precisely because it is asymmetric: it cannot cost a winner anything
     * it had not already given back, and it cannot act until the trade has paid for its own risk.</p>
     */
    @Test
    void theShippedPolicyMovesTheStopToEntryButNeverChasesPrice() {
        ExitPolicy shipped = ExitPolicy.breakevenAtOneR();

        assertThat(shipped.breakevenEnabled()).isTrue();
        assertThat(shipped.breakevenArmAtR())
                .as("armed only once the trade is ahead by its own risk")
                .isEqualTo(1.0);
        assertThat(shipped.trailingEnabled())
                .as("trailing would have clipped three trades that needed room to reach 2R")
                .isFalse();
        assertThat(shipped.structureExitEnabled())
                .as("unmeasured, and it can close a winner early")
                .isFalse();
    }

    @Test
    void armingIsNotRestored() {
        UserAccount live = registry.ensure(USER);
        live.setEntriesEnabled(true);

        assertThat(afterRestart(USER).entriesEnabled())
                .as("arming is a decision about right now; coming back armed because it was armed "
                        + "yesterday is the one surprise nobody wants after a restart")
                .isFalse();
    }

    @Test
    void brokerCredentialsAreEncryptedAtRestAndNeverDescribedInFull() {
        UserId user = UserId.random();
        profiles.saveBrokerCredentials(user, "live_api_key_1234", "super_secret_value");

        // Readable by the broker layer, which needs the real thing.
        var credentials = profiles.brokerCredentials(user).orElseThrow();
        assertThat(credentials.apiKey()).isEqualTo("live_api_key_1234");
        assertThat(credentials.apiSecret()).isEqualTo("super_secret_value");

        // Not readable by anything a browser talks to.
        var view = profiles.describeBrokerConfig(user);
        assertThat(view.apiKeySet()).isTrue();
        assertThat(view.apiSecretSet()).isTrue();
        assertThat(view.toString())
                .as("spec 37: the settings page learns that a key is present, never what it is")
                .doesNotContain("super_secret_value")
                .doesNotContain("live_api_key_1234");
        assertThat(view.apiKeyHint()).isEqualTo("…1234");
    }

    @Test
    void aBlankValueKeepsTheStoredCredentialRatherThanClearingIt() {
        UserId user = UserId.random();
        profiles.saveBrokerCredentials(user, "key-one", "secret-one");

        // What a settings form submits when the secret field is left untouched.
        profiles.saveBrokerCredentials(user, "key-two", "");

        var credentials = profiles.brokerCredentials(user).orElseThrow();
        assertThat(credentials.apiKey()).isEqualTo("key-two");
        assertThat(credentials.apiSecret())
                .as("a form that wipes the secret whenever it is not retyped is a trap")
                .isEqualTo("secret-one");
    }
}
