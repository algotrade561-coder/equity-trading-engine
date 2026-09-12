package com.equity.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.equity.store.AppUserEntity;
import com.equity.store.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Administration is a closed door, not a broken session.
 *
 * <p>With sign-in off the caller resolves to the local account, which is how these tests reach the
 * guard without an OAuth session. The distinction that matters is the status: a trader who follows
 * a link to the users screen must get a 403 that says "administrators only", not a 401 that sends
 * them back to the sign-in page as though their session had died.</p>
 */
class CurrentUserAdminGuardTest {

    /** Just enough repository to hold one account; every other method is unreachable here. */
    private static AppUserRepository holding(AppUserEntity account) {
        java.lang.reflect.InvocationHandler h = (proxy, method, args) -> switch (method.getName()) {
            case "findByEmailIgnoreCase" -> java.util.Optional.of(account)
                    .filter(a -> a.getEmail().equalsIgnoreCase((String) args[0]));
            case "save" -> args[0];
            case "toString" -> "stub";
            case "hashCode" -> 0;
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (AppUserRepository) java.lang.reflect.Proxy.newProxyInstance(
                AppUserRepository.class.getClassLoader(), new Class<?>[]{AppUserRepository.class}, h);
    }

    @Test
    void anAdministratorPassesAndIsIdentified() {
        AppUserEntity admin = new AppUserEntity("local@localhost", "Local developer", "ADMIN");
        CurrentUser current = new CurrentUser(holding(admin), false);

        assertThat(current.isAdmin()).isTrue();
        assertThat(current.requireAdmin().toString()).isEqualTo(admin.getTradingUserId());
        assertThat(current.actor()).isEqualTo("local@localhost");
    }

    @Test
    void aTraderIsRefusedWithForbiddenNotUnauthorised() {
        AppUserEntity trader = new AppUserEntity("local@localhost", "Local developer", "TRADER");
        CurrentUser current = new CurrentUser(holding(trader), false);

        assertThat(current.isAdmin()).isFalse();
        assertThatThrownBy(current::requireAdmin)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .as("known caller, not allowed — 403, so the UI does not bounce them to sign-in")
                        .isEqualTo(HttpStatus.FORBIDDEN))
                .hasMessageContaining("administrators only");
    }
}
