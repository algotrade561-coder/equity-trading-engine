package com.equity.platform.security;

import com.equity.store.AppUserEntity;
import com.equity.store.AppUserRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The allow-list check, in one place because there are two ways in.
 *
 * <p>Google is an OpenID Connect provider, so requesting the {@code openid} scope makes Spring
 * Security use its <b>OIDC</b> user service and ignore the plain OAuth2 one entirely. Registering
 * only the latter left this check unreachable: sign-in appeared to work, no account was ever
 * verified against the table, and the application simply could not identify whoever came back.</p>
 *
 * <p>Both services now delegate here, so there is exactly one gate and no way to add a third entry
 * path that quietly skips it.</p>
 */
@Service
public class GoogleAccountAuthoriser {

    private static final Logger log = LoggerFactory.getLogger(GoogleAccountAuthoriser.class);

    private final AppUserRepository users;

    public GoogleAccountAuthoriser(AppUserRepository users) {
        this.users = users;
    }

    /**
     * @param emailVerified null when the provider did not say; only an explicit false is a refusal
     * @return the account, never null — refusal throws
     */
    @Transactional
    public AppUserEntity authorise(String email, Boolean emailVerified, String displayName) {
        if (email == null || email.isBlank()) {
            throw reject("google_no_email", "Google returned no email address.");
        }
        // An unverified address is whatever the account holder typed. Matching one against the
        // allow-list would let somebody claim an address they do not control.
        if (Boolean.FALSE.equals(emailVerified)) {
            throw reject("google_email_unverified", "That Google address is not verified.");
        }

        AppUserEntity account = users.findByEmailIgnoreCase(email).orElse(null);
        if (account == null) {
            log.warn("sign-in REFUSED for {} — no row in app_user", email);
            throw reject("not_authorised", "This account is not authorised to use this engine.");
        }
        if (!account.isEnabled()) {
            log.warn("sign-in REFUSED for {} — the account is disabled", email);
            throw reject("account_disabled", "This account has been disabled.");
        }

        account.setLastLoginAt(Instant.now());
        if (account.getDisplayName() == null || account.getDisplayName().isBlank()) {
            account.setDisplayName(displayName);
        }
        users.save(account);

        log.info("sign-in: {} ({}) as {}", email, account.getTradingUserId(), account.getRole());
        return account;
    }

    static OAuth2AuthenticationException reject(String code, String message) {
        return new OAuth2AuthenticationException(new OAuth2Error(code, message, null), message);
    }
}
