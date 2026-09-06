package com.equity.api;

import com.equity.broker.kite.KiteAuthService;
import com.equity.broker.kite.KiteInstrumentMaster;
import com.equity.broker.kite.KiteProperties;
import com.equity.broker.kite.KiteSession;
import com.equity.broker.kite.KiteSessionStore;
import com.equity.broker.kite.KiteTickerManager;
import com.equity.domain.user.UserId;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Kite login round trip, exposed to the UI.
 *
 * <p><b>No endpoint here returns an access token, a public token, an API secret or a request
 * token.</b> Spec section 37 forbids credentials in API responses and frontend storage, so the UI is
 * told only whether a user is connected and, if so, which Zerodha client code they are connected as.
 * The browser never needs more than that: it opens the login URL and reads back a boolean.</p>
 */
@RestController
@RequestMapping("/api/broker/kite")
public class BrokerAuthController {

    private static final Logger log = LoggerFactory.getLogger(BrokerAuthController.class);

    private final KiteProperties properties;
    private final KiteAuthService auth;
    private final KiteSessionStore sessions;
    private final KiteInstrumentMaster instruments;
    private final KiteTickerManager tickers;
    private final com.equity.user.UserRegistry users;
    private final com.equity.platform.security.CurrentUser currentUser;

    public BrokerAuthController(KiteProperties properties, KiteAuthService auth,
                                KiteSessionStore sessions, KiteInstrumentMaster instruments,
                                KiteTickerManager tickers, com.equity.user.UserRegistry users,
                                com.equity.platform.security.CurrentUser currentUser) {
        this.currentUser = currentUser;
        this.properties = properties;
        this.auth = auth;
        this.sessions = sessions;
        this.instruments = instruments;
        this.tickers = tickers;
        this.users = users;
    }

    /**
     * What the UI needs to render the setup panel.
     *
     * <p>Carries the registered redirect URL because a mismatch there is the single most common
     * setup failure, and the error Zerodha shows for it does not say what value it expected. It
     * carries no key and no secret: {@code configured} is a boolean, deliberately, so the page can
     * say whether credentials are present without ever holding them.</p>
     */
    @GetMapping("/config")
    public Map<String, Object> config() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", properties.isEnabled());
        m.put("configured", properties.isConfigured());
        m.put("redirectUrl", properties.getRedirectUrl());
        return m;
    }

    /** Returns the URL the user must open to log in to Zerodha. Contains an API key, no secret. */
    @GetMapping("/login-url")
    public Map<String, String> loginUrl(
            @RequestParam(value = "userId", required = false) String userId) {
        UserId id = userId == null || userId.isBlank() ? currentUser.require() : UserId.of(userId);
        return Map.of("loginUrl", auth.buildLoginUrl(id));
    }

    /**
     * Where Kite sends the browser after login.
     *
     * <p>{@code eq_user} and {@code eq_nonce} are our own parameters, echoed back by Kite through
     * {@code redirect_params}. The nonce is what makes the callback trustworthy: without it, anyone
     * who can reach this endpoint could bind a request token to somebody else's account.</p>
     *
     * <p>Returns HTML because a human browser lands here, not the UI's fetch client.</p>
     */
    @GetMapping(value = "/callback", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> callback(
            @RequestParam(value = "request_token", required = false) String requestToken,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "eq_user", required = false) String eqUser,
            @RequestParam(value = "eq_nonce", required = false) String eqNonce) {

        if (eqUser == null || eqUser.isBlank()) {
            return html(400, "Login failed", "The callback did not identify a user.");
        }
        if (status != null && !"success".equalsIgnoreCase(status)) {
            return html(400, "Login failed", "Zerodha reported: " + escape(status));
        }

        UserId userId;
        try {
            userId = UserId.of(eqUser);
        } catch (RuntimeException e) {
            return html(400, "Login failed", "The callback carried an unusable user id.");
        }

        try {
            KiteSession session = auth.completeLogin(userId, eqNonce, requestToken);

            // Both of these are needed before the feed is useful, and both are safe to repeat.
            // A failure here leaves the session valid — the user is logged in even if the feed is
            // not yet up, and saying otherwise would send them back through a login they do not need.
            try {
                // A successful login is an explicit act by this user, so it is the right moment to
                // register them. Without it nobody is in the registry until they arm, and the
                // periodic margin refresh — which iterates registered users — never runs for them.
                users.ensure(userId);
                if (!instruments.isLoaded()) instruments.refresh(userId);
                tickers.connect(userId);
            } catch (RuntimeException e) {
                log.error("kite session established for user={} but startup failed: {}",
                        userId, e.getMessage());
                return html(200, "Connected, with a warning",
                        "Logged in as " + escape(session.kiteUserId())
                                + ", but the market data feed did not start. Check the server log.");
            }

            return html(200, "Connected", "Logged in as " + escape(session.kiteUserId())
                    + ". Returning to the dashboard.");

        } catch (RuntimeException e) {
            log.warn("kite callback rejected for user={}: {}", userId, e.getMessage());
            return html(400, "Login failed", escape(e.getMessage()));
        }
    }

    /** Whether this user is connected. Deliberately carries no token of any kind. */
    @GetMapping("/session")
    public Map<String, Object> session(
            @RequestParam(value = "userId", required = false) String userId) {
        UserId id = userId == null || userId.isBlank() ? currentUser.require() : UserId.of(userId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", id.toString());
        sessions.get(id).ifPresentOrElse(s -> {
            m.put("connected", true);
            m.put("kiteUserId", s.kiteUserId());
            m.put("tradingDate", s.tradingDate().toString());
        }, () -> m.put("connected", false));
        m.put("feedConnected", tickers.isConnected());
        m.put("instrumentsLoaded", instruments.size());
        return m;
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(
            @RequestParam(value = "userId", required = false) String userId) {
        UserId id = userId == null || userId.isBlank() ? currentUser.require() : UserId.of(userId);
        tickers.disconnect(id);
        auth.logout(id);
        return Map.of("userId", id.toString(), "connected", false);
    }

    /**
     * The one page in this application that is rendered server-side.
     *
     * <p>The browser arrives here from Zerodha, not from the single-page app, so there is nothing
     * loaded to render it. It sends the user back to the dashboard — after a pause on success, and
     * never automatically on failure, because a failure is something to read rather than skip past.</p>
     */
    private static ResponseEntity<String> html(int status, String heading, String detail) {
        String redirect = status == 200
                ? "<script>setTimeout(function(){location.href='/'},1800)</script>"
                : "";
        String body = """
                <!doctype html><meta charset="utf-8">
                <title>%s</title>
                <style>
                  :root{color-scheme:light dark}
                  body{font:15px/1.5 system-ui,sans-serif;margin:0;display:grid;place-items:center;min-height:100vh}
                  main{max-width:32rem;padding:2rem}
                  h1{font-size:1.15rem;margin:0 0 .5rem}
                  p{margin:0 0 1.25rem;opacity:.85}
                  a{color:inherit}
                </style>
                <main><h1>%s</h1><p>%s</p><p><a href="/">Back to the dashboard</a></p></main>%s
                """.formatted(heading, heading, detail, redirect);
        return ResponseEntity.status(status).contentType(MediaType.TEXT_HTML).body(body);
    }

    /** The callback reflects broker-supplied text back into a page, so it gets escaped. */
    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
