package com.equity.platform.security;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;

/**
 * Who may reach this application.
 *
 * <h2>Two chains, chosen by one flag</h2>
 * <p>{@code equity.auth.google.enabled} decides. On, everything requires a signed-in Google account
 * that is <b>already in the app_user table</b>; off, everything is open. There is no third mode with
 * a password of the engine's own — a trading application that stores passwords is a trading
 * application that can leak them, and Google already does that job better.</p>
 *
 * <p><b>Google proves identity; the table grants access.</b> Anyone with a Google account can
 * authenticate, so authentication alone would let the entire internet at an account that places real
 * orders. {@link GoogleUserService} rejects an email with no enabled row, which makes the failure
 * mode of a misconfigured OAuth client "nobody can log in" rather than "anybody can".</p>
 *
 * <h2>Why off is the default</h2>
 * <p>A fresh checkout has no OAuth client, and an engine that cannot start is not safer than one
 * that starts open on localhost. The combination that matters is guarded elsewhere: the engine ships
 * in REPLAY with trading disabled and every user disarmed, so an open console on a developer machine
 * can look at things and change nothing that reaches an exchange. Before this is exposed beyond
 * localhost the flag must be on — the README says so, and {@link #openChain} logs it at every start.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /** Endpoints that must answer before anyone is signed in. */
    private static final String[] OPEN_ENDPOINTS = { "/api/auth/**", "/actuator/health" };

    @Bean
    @ConditionalOnProperty(name = "equity.auth.google.enabled", havingValue = "true")
    public SecurityFilterChain googleChain(HttpSecurity http, GoogleUserService userService,
                                           GoogleOidcUserService oidcUserService) throws Exception {
        log.info("Google sign-in is ENABLED — only emails present and enabled in app_user may log in");

        http
            // The API is protected; the console bundle is not.
            //
            // Enumerating static paths to permit was a mistake — "/" did not match the list and the
            // browser got a bodyless 401 instead of a page, which looks exactly like a broken app.
            // And protecting the bundle buys nothing: it is a public JavaScript file that renders a
            // sign-in screen when /api/auth/me says nobody is signed in. Every fact worth guarding
            // arrives through /api, which is guarded.
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers(OPEN_ENDPOINTS).permitAll()
                    // A SQL shell over live positions and the daily loss latch. Signed in is not
                    // enough — a trader who may arm their own account has no business editing the
                    // table that records what everyone holds.
                    .requestMatchers("/h2-console/**").hasRole("ADMIN")
                    .requestMatchers("/api/**").authenticated()
                    .anyRequest().permitAll())
            .oauth2Login(oauth -> oauth
                    // BOTH. Google requests the openid scope, so Spring routes it to the OIDC
                    // service and never calls the OAuth2 one — registering only the latter left the
                    // allow-list unreachable and the signed-in user unidentifiable.
                    .userInfoEndpoint(info -> info
                            .userService(userService)
                            .oidcUserService(oidcUserService))
                    .defaultSuccessUrl("/", true)
                    .failureUrl("/login?error"))
            .logout(logout -> logout
                    .logoutRequestMatcher(new AntPathRequestMatcher("/api/auth/logout", "POST"))
                    .logoutSuccessHandler(this::noContent)
                    .deleteCookies("JSESSIONID"))
            .exceptionHandling(ex -> ex
                    // Two behaviours, because two kinds of caller.
                    //
                    // /api is called by fetch: a 302 to Google inside an XHR is an opaque failure,
                    // whereas a 401 lets the page render a sign-in prompt. Everything else is a
                    // browser navigation — the H2 console, chiefly — and a browser handed a bare 401
                    // shows nothing useful, so it goes to Google and comes back.
                    //
                    // Order matters: the first matching mapping wins.
                    .defaultAuthenticationEntryPointFor(
                            new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                            new AntPathRequestMatcher("/api/**"))
                    .defaultAuthenticationEntryPointFor(
                            new LoginUrlAuthenticationEntryPoint("/oauth2/authorization/google"),
                            AnyRequestMatcher.INSTANCE))
            // CSRF stays ON. The API is authenticated by a session cookie, which a browser attaches
            // to any request from anywhere — so without it, a page on another site could make this
            // one arm an account or place an order while you are signed in.
            //
            // The token therefore has to reach a fetch client, and the session-backed default cannot:
            // it never leaves the server, so every POST from the console came back 403 Forbidden.
            // A readable cookie plus an X-XSRF-TOKEN header is the arrangement that works for a
            // single-page app, and it is no weaker — the point is that only same-origin JavaScript
            // can read the cookie back out to echo it.
            .csrf(csrf -> csrf
                    .csrfTokenRepository(org.springframework.security.web.csrf
                            .CookieCsrfTokenRepository.withHttpOnlyFalse())
                    // Opts out of the deferred-token optimisation, so the cookie is issued on the
                    // first GET rather than only once something has already needed it.
                    .csrfTokenRequestHandler(plainCsrfTokenHandler())
                    // Zerodha's callback arrives from another origin with no token; the H2 console
                    // posts its own forms and carries none.
                    .ignoringRequestMatchers("/api/broker/kite/callback", "/h2-console/**"))
            // The H2 console renders itself in frames. sameOrigin rather than disabling the header:
            // this page may frame itself and nothing else may frame it.
            .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));

        return http.build();
    }

    @Bean
    @ConditionalOnProperty(name = "equity.auth.google.enabled", havingValue = "false",
            matchIfMissing = true)
    public SecurityFilterChain openChain(HttpSecurity http) throws Exception {
        log.warn("Google sign-in is DISABLED — every endpoint is open. Acceptable on localhost "
                + "while the engine ships disarmed; set equity.auth.google.enabled=true before "
                + "this is reachable by anyone else.");

        http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .csrf(csrf -> csrf.disable())
            .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));
        return http.build();
    }

    /**
     * Reads the token straight from the request rather than lazily.
     *
     * <p>Spring's default defers resolution as a BREACH mitigation, which means the cookie is not
     * written until something asks for the token — and a client that only ever reads the cookie
     * therefore never gets one.</p>
     */
    private static org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler
            plainCsrfTokenHandler() {
        var handler = new org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler();
        handler.setCsrfRequestAttributeName(null);
        return handler;
    }

    private void noContent(jakarta.servlet.http.HttpServletRequest request,
                           jakarta.servlet.http.HttpServletResponse response,
                           org.springframework.security.core.Authentication authentication)
            throws IOException {
        response.setStatus(HttpStatus.NO_CONTENT.value());
        response.getWriter().flush();
    }
}
