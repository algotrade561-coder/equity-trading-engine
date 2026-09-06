package com.equity.platform.security;

import com.equity.store.AppUserEntity;
import java.util.Set;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

/**
 * The path Google actually takes.
 *
 * <p>Requesting the {@code openid} scope makes this an OpenID Connect login, and Spring Security
 * routes those through {@link OidcUserService} — the plain OAuth2 user service is never consulted.
 * This is therefore where the allow-list has to be enforced, and getting that wrong is not a
 * cosmetic error: without it the engine cannot identify who signed in, which showed up as an endless
 * bounce back to the Google button.</p>
 *
 * <p>The principal keeps Google's own claims and gains no custom ones. {@link CurrentUser} resolves
 * the account from the email on every request instead — which also means a role change or a disabled
 * flag takes effect on the next request rather than the next login.</p>
 */
@Service
public class GoogleOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    private final OidcUserService delegate = new OidcUserService();
    private final GoogleAccountAuthoriser authoriser;

    public GoogleOidcUserService(GoogleAccountAuthoriser authoriser) {
        this.authoriser = authoriser;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) throws OAuth2AuthenticationException {
        OidcUser user = delegate.loadUser(request);
        AppUserEntity account =
                authoriser.authorise(user.getEmail(), user.getEmailVerified(), user.getFullName());

        return new DefaultOidcUser(
                Set.of(new SimpleGrantedAuthority("ROLE_" + account.getRole())),
                user.getIdToken(), user.getUserInfo(), "email");
    }
}
