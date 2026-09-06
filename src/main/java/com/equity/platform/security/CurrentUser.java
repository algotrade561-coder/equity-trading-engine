package com.equity.platform.security;

import com.equity.domain.user.UserId;
import com.equity.store.AppUserEntity;
import com.equity.store.AppUserRepository;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who is making this request.
 *
 * <p>Replaces the user id the console used to type into a box. That field was always a placeholder:
 * anything that reads identity from the client can be told anything by the client, which in a
 * multi-user trading engine means one user operating another's account by editing a text field. The
 * id now comes from the signed-in session and nowhere else.</p>
 *
 * <h2>With sign-in disabled</h2>
 * <p>There is no session to read, so a single local developer account is used. It is a real row in
 * {@code app_user} with a stable id, so a developer machine accumulates the same durable state as a
 * deployment rather than a fresh identity per restart.</p>
 */
@Component
public class CurrentUser {

    /** The identity used when sign-in is off. Fixed, so local data persists across restarts. */
    private static final String LOCAL_EMAIL = "local@localhost";

    private final AppUserRepository users;
    private final boolean authEnabled;

    public CurrentUser(AppUserRepository users,
                       @Value("${equity.auth.google.enabled:false}") boolean authEnabled) {
        this.users = users;
        this.authEnabled = authEnabled;
    }

    /** The engine identity of the caller, or empty when nobody is signed in and auth is on. */
    @Transactional
    public Optional<UserId> id() {
        return account().map(a -> UserId.of(a.getTradingUserId()));
    }

    /** As {@link #id()}, but throws — for endpoints that cannot do anything useful anonymously. */
    public UserId require() {
        return id().orElseThrow(() -> new IllegalStateException("no signed-in user"));
    }

    @Transactional
    public Optional<AppUserEntity> account() {
        if (!authEnabled) {
            return Optional.of(users.findByEmailIgnoreCase(LOCAL_EMAIL)
                    .orElseGet(() -> users.save(
                            new AppUserEntity(LOCAL_EMAIL, "Local developer", "ADMIN"))));
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || !(auth.getPrincipal() instanceof OAuth2User principal)) {
            return Optional.empty();
        }
        // Keyed on the email the provider asserted, not on a claim this application added. An OIDC
        // principal is built from the id token and user-info, so a custom claim cannot ride along —
        // and the row has to be read per request anyway, so that a role change or a disabled flag
        // takes effect on the next request rather than the next login.
        Object email = principal.getAttributes().get("email");
        if (email == null) return Optional.empty();
        return users.findByEmailIgnoreCase(email.toString()).filter(AppUserEntity::isEnabled);
    }

    public boolean isAdmin() {
        return account().map(AppUserEntity::isAdmin).orElse(false);
    }

    public boolean isAuthEnabled() { return authEnabled; }
}
