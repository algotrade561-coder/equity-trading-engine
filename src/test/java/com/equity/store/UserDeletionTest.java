package com.equity.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.equity.broker.ProductType;
import com.equity.domain.Direction;
import com.equity.domain.momentum.EntryPattern;
import com.equity.domain.order.OrderTag;
import com.equity.domain.position.ExitReason;
import com.equity.domain.position.Position;
import com.equity.domain.user.UserId;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Deleting an account removes the login and keeps the record.
 *
 * <p>Two properties, each worth a test because each can fail alone. A user holding a position cannot
 * be deleted — the exit machinery would still manage it, but the dashboard reads positions through
 * the account and nobody would be able to see it. And when a user with closed history is deleted,
 * that history stays exactly where it was: positions and ledger are keyed by the trading id, not the
 * account row, and a regulator's question about last month does not stop being answerable because
 * the person left.</p>
 */
@SpringBootTest(classes = com.equity.app.EquityApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:user-deletion;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "equity.security.secret-key=test-key-not-a-real-one",
        "equity.auth.google.enabled=false",
})
class UserDeletionTest {

    @Autowired private UserProfileService profiles;
    @Autowired private AppUserRepository users;
    @Autowired private BrokerConfigRepository brokerConfigs;
    @Autowired private JpaPositionStore positions;
    @Autowired private PositionRepository positionRows;

    private static final Instant AT = Instant.parse("2026-09-12T04:00:00Z");

    private UserId newUser(String email) {
        AppUserEntity created = users.save(new AppUserEntity(email, "Test", "TRADER"));
        return UserId.of(created.getTradingUserId());
    }

    private Position position(UserId user, String symbol) {
        return Position.pendingEntry(user, symbol, Direction.LONG, EntryPattern.PULLBACK_CONTINUATION,
                ProductType.MIS, 10, 100, 98, 104, OrderTag.forEntry(user), "o1", AT);
    }

    @Test
    void aUserHoldingAPositionCannotBeDeleted() {
        UserId user = newUser("holding@example.com");
        positions.save(position(user, "RELIANCE").withFill(10, 100, AT));   // OPEN

        assertThat(profiles.hasExposure(user)).isTrue();
        assertThatThrownBy(() -> profiles.deleteAccount(user))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("holds a position");
        assertThat(users.findByTradingUserId(user.toString()))
                .as("a refused delete must leave the account untouched")
                .isPresent();
    }

    @Test
    void aPendingEntryCountsAsExposureToo() {
        // No shares yet, but an order is at the broker and may fill any second. Deleting the account
        // now would leave a fill arriving for a user the dashboard cannot show.
        UserId user = newUser("pending@example.com");
        positions.save(position(user, "TCS"));                              // PENDING_ENTRY

        assertThat(profiles.hasExposure(user)).isTrue();
    }

    @Test
    void deletingRemovesTheAccountAndKeepsTheTradingRecord() {
        UserId user = newUser("history@example.com");
        profiles.saveBrokerCredentials(user, "key-abcdef", "secret-abcdef");
        positions.save(position(user, "INFY").withFill(10, 100, AT)
                .withClose(103, ExitReason.TARGET, AT.plusSeconds(600)));  // CLOSED — history, not exposure

        assertThat(profiles.hasExposure(user)).isFalse();
        String removed = profiles.deleteAccount(user);

        assertThat(removed).isEqualTo("history@example.com");
        assertThat(users.findByTradingUserId(user.toString()))
                .as("the login is gone")
                .isEmpty();
        assertThat(brokerConfigs.findByTradingUserId(user.toString()))
                .as("so are the encrypted broker credentials — nothing that only existed to let them trade")
                .isEmpty();
        assertThat(positionRows.findByTradingUserIdAndTradingDate(user.toString(),
                        positionRows.findAll().get(0).getTradingDate()))
                .as("the closed position stays on record under the trading id")
                .isNotEmpty();
    }
}
