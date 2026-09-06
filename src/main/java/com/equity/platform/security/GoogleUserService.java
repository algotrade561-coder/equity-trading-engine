package com.equity.platform.security;

import com.equity.store.AppUserEntity;
import com.equity.store.AppUserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a Google identity into a user of this engine, or refuses.
 *
 * <p>This is the allow-list. Google will happily authenticate any of its billions of accounts, so
 * without a check here the sign-in button would be an open door to an application that places real
 * orders. A row in {@code app_user}, enabled, is what grants access.</p>
 *
 * <h2>No bootstrap-on-first-login</h2>
 * <p>An empty table cannot let anyone in, so something has to create the first account. That job
 * belongs to {@link SeedUserInitialiser}, which creates it <b>at startup</b> from
 * {@code equity.auth.seed-email}. Admitting the first caller instead would leave a window between
 * deployment and first login that anyone reaching the callback could win.</p>
 *
 * <p>So this class only ever reads the table. It cannot create an account, which means no sign-in
 * path can grant itself access.</p>
 *
 * <p><b>Google does not normally come through here.</b> The {@code openid} scope makes it an OIDC
 * login, handled by {@link GoogleOidcUserService}. This remains for a plain OAuth2 provider, and
 * both share {@link GoogleAccountAuthoriser} so neither can drift from the other.</p>
 */
@Service
public class GoogleUserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    private static final Logger log = LoggerFactory.getLogger(GoogleUserService.class);

    private final DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();
    private final GoogleAccountAuthoriser authoriser;

    public GoogleUserService(GoogleAccountAuthoriser authoriser) {
        this.authoriser = authoriser;
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest request) throws OAuth2AuthenticationException {
        OAuth2User googleUser = delegate.loadUser(request);
        Map<String, Object> attributes = googleUser.getAttributes();

        Object verified = attributes.get("email_verified");
        AppUserEntity account = authoriser.authorise(
                str(attributes.get("email")),
                verified instanceof Boolean b ? b : null,
                str(attributes.get("name")));

        return new DefaultOAuth2User(
                Set.of(new SimpleGrantedAuthority("ROLE_" + account.getRole())),
                attributes, "email");
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString();
    }
}
