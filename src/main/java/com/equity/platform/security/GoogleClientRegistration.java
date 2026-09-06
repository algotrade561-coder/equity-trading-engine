package com.equity.platform.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;

/**
 * Registers the Google client, but only when sign-in is switched on.
 *
 * <p>Built here rather than declared in {@code application.yml} because Spring Boot's yml binding
 * refuses an empty client id outright — "Client id of registration 'google' must not be empty" —
 * which means a fresh checkout with no OAuth credentials cannot start at all. Declaring it
 * programmatically behind the same flag that turns sign-in on keeps the default checkout runnable
 * and still fails loudly if somebody enables auth without supplying credentials.</p>
 */
@Configuration
@ConditionalOnProperty(name = "equity.auth.google.enabled", havingValue = "true")
public class GoogleClientRegistration {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(GoogleClientRegistration.class);

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(
            @Value("${GOOGLE_CLIENT_ID:}") String clientId,
            @Value("${GOOGLE_CLIENT_SECRET:}") String clientSecret,
            @Value("${server.port:8090}") String serverPort,
            @Value("${equity.auth.public-base-url:}") String baseUrl) {

        if (clientId.isBlank() || clientSecret.isBlank()) {
            throw new IllegalStateException(
                    "equity.auth.google.enabled is true but GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET "
                    + "are not set. Refusing to start: with auth half-configured every endpoint "
                    + "would be reachable by anyone.");
        }

        ClientRegistration google = CommonOAuth2Provider.GOOGLE
                .getBuilder("google")
                .clientId(clientId)
                .clientSecret(clientSecret)
                .scope("openid", "email", "profile")
                .build();

        // Printed at every start because the failure it prevents is opaque from the outside:
        // Google answers an unregistered redirect with "Access blocked: This app's request is
        // invalid" and never says which URI it expected. Having the exact string in the log turns
        // a guessing game into a copy and paste.
        log.info("Google sign-in ready. This callback MUST be an authorised redirect URI on the "
                + "OAuth client, character for character: {}/login/oauth2/code/google",
                baseUrl.isBlank() ? "http://localhost:" + serverPort : baseUrl);

        return new InMemoryClientRegistrationRepository(google);
    }
}
