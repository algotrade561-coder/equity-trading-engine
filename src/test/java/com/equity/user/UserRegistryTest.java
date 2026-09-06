package com.equity.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.equity.domain.user.UserId;
import com.equity.domain.user.UserStatus;
import org.junit.jupiter.api.Test;

/**
 * Which users get evaluated, and which may trade.
 *
 * <p>These two sets were the same thing once, and that was a bug: gating evaluation on "may trade"
 * meant a session with nobody armed recorded nothing at all — no setups, no rejections, no counts —
 * so there was no way to tell whether the strategy had found nothing or had never run.</p>
 */
class UserRegistryTest {

    @Test
    void aNewUserIsCreatedDisarmed() {
        UserRegistry registry = new UserRegistry();

        UserAccount account = registry.register(UserId.random(), "test", "");

        assertThat(account.entriesEnabled())
                .as("registering somebody must never be the same act as letting them trade")
                .isFalse();
        assertThat(account.mayOpen()).isFalse();
    }

    @Test
    void anUnknownUserIsAutoCreatedButStillCannotTrade() {
        UserRegistry registry = new UserRegistry();

        UserAccount account = registry.ensure(UserId.random());

        assertThat(account.mayOpen())
                .as("auto-creation is safe precisely because the worst case is an idle row")
                .isFalse();
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void aDisarmedUserIsStillEvaluated() {
        UserRegistry registry = new UserRegistry();
        registry.register(UserId.random(), "disarmed", "");

        assertThat(registry.armed()).isEmpty();
        assertThat(registry.evaluable())
                .as("a disarmed session has to be observable or there is no point running one")
                .hasSize(1);
    }

    @Test
    void anArmedUserIsInBothSets() {
        UserRegistry registry = new UserRegistry();
        UserAccount account = registry.register(UserId.random(), "armed", "");
        account.setEntriesEnabled(true);

        assertThat(registry.armed()).hasSize(1);
        assertThat(registry.evaluable()).hasSize(1);
    }

    @Test
    void aHaltedUserIsNeitherEvaluatedNorArmed() {
        UserRegistry registry = new UserRegistry();
        UserAccount account = registry.register(UserId.random(), "halted", "");
        account.setEntriesEnabled(true);

        account.halt();

        assertThat(registry.armed()).isEmpty();
        assertThat(registry.evaluable())
                .as("a halted user is stopped, not merely unarmed")
                .isEmpty();
    }

    @Test
    void haltingBumpsTheEpochSoInFlightOrdersAreDisowned() {
        UserRegistry registry = new UserRegistry();
        UserAccount account = registry.register(UserId.random(), "test", "");
        long before = account.epoch();

        long after = account.halt();

        assertThat(after).isGreaterThan(before);
        assertThat(account.isCurrentEpoch(before)).isFalse();
    }

    @Test
    void clearingAHaltDoesNotRearmTheUser() {
        UserRegistry registry = new UserRegistry();
        UserAccount account = registry.register(UserId.random(), "test", "");
        account.setEntriesEnabled(true);
        account.halt();

        account.resume();

        assertThat(account.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(account.entriesEnabled())
                .as("resuming and re-arming are separate, deliberate acts")
                .isFalse();
        assertThat(registry.evaluable()).hasSize(1);
        assertThat(registry.armed()).isEmpty();
    }
}
