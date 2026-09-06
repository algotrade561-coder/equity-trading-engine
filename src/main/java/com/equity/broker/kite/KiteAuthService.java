package com.equity.broker.kite;

import com.equity.broker.BrokerException;
import com.equity.domain.user.UserId;
import com.equity.platform.time.TradingClock;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The Kite login round trip, per user.
 *
 * <p>Kite uses a redirect flow: the user logs in at Zerodha, Kite redirects back with a
 * {@code request_token}, and the application exchanges that token plus a checksum for an
 * {@code access_token}. The exchange is the only call that carries the API secret, and it happens
 * server-side — the secret never reaches a browser.</p>
 *
 * <h2>Why a nonce</h2>
 * <p>The redirect URL registered against a Kite app is fixed and shared by every user, so the
 * callback has to say who it belongs to. Carrying only a user id would let anyone who can reach the
 * callback bind an arbitrary request token to another user's account. The login URL therefore also
 * carries a single-use nonce, minted here, and a callback whose nonce does not match a pending login
 * for that exact user is refused rather than guessed at.</p>
 */
@Service
public class KiteAuthService {

    private static final Logger log = LoggerFactory.getLogger(KiteAuthService.class);

    private final KiteProperties properties;
    private final KiteCredentialsProvider credentials;
    private final KiteSessionStore sessions;
    private final KiteHttp http;
    private final TradingClock clock;
    private final SecureRandom random = new SecureRandom();

    private final Map<String, PendingLogin> pending = new ConcurrentHashMap<>();

    private record PendingLogin(UserId userId, Instant expiresAt) {}

    public KiteAuthService(KiteProperties properties, KiteCredentialsProvider credentials,
                           KiteSessionStore sessions, KiteHttp http, TradingClock clock) {
        this.properties = properties;
        this.credentials = credentials;
        this.sessions = sessions;
        this.http = http;
        this.clock = clock;
    }

    /**
     * Builds the URL the user must open to log in, and remembers the nonce it carries.
     *
     * @return the login URL; it contains an API key, which is public by design, and no secret
     */
    public String buildLoginUrl(UserId userId) {
        KiteCredentials creds = credentials.require(userId);
        String nonce = newNonce();

        prune();
        pending.put(nonce, new PendingLogin(userId, clock.now().plus(properties.getLoginWindow())));

        String redirectParams = "eq_user=" + userId + "&eq_nonce=" + nonce;
        return properties.getLoginUrl()
                + "?v=3&api_key=" + enc(creds.apiKey())
                + "&redirect_params=" + enc(redirectParams);
    }

    /**
     * Completes a login from the callback.
     *
     * @param requestToken the single-use token Kite put in the callback query string
     * @throws BrokerException if the nonce is unknown, expired, or was minted for a different user
     */
    public KiteSession completeLogin(UserId userId, String nonce, String requestToken) {
        prune();
        PendingLogin p = nonce == null ? null : pending.remove(nonce);
        if (p == null || !p.userId().equals(userId)) {
            // Deliberately identical message for every failure mode: an attacker probing the
            // callback learns nothing about which half of the check failed.
            throw new BrokerException("kite callback rejected: no matching pending login",
                    "AuthException", false);
        }
        if (clock.now().isAfter(p.expiresAt())) {
            throw new BrokerException("kite callback rejected: no matching pending login",
                    "AuthException", false);
        }
        if (requestToken == null || requestToken.isBlank()) {
            throw new BrokerException("kite callback carried no request_token", "AuthException", false);
        }

        KiteCredentials creds = credentials.require(userId);
        Map<String, String> form = KiteHttp.form();
        form.put("api_key", creds.apiKey());
        form.put("request_token", requestToken);
        form.put("checksum", checksum(creds.apiKey(), requestToken, creds.apiSecret()));

        JsonNode data = http.postForm("/session/token", form, creds, null);

        KiteSession session = new KiteSession(
                userId,
                data.path("user_id").asText(""),
                data.path("access_token").asText(""),
                data.path("public_token").asText(""),
                clock.tradingDate(),
                clock.now());

        if (session.accessToken().isBlank()) {
            throw new BrokerException("kite session response carried no access token",
                    "AuthException", false);
        }

        sessions.put(session);
        log.info("kite session established user={} kiteUser={} tradingDate={}",
                userId, session.kiteUserId(), session.tradingDate());
        return session;
    }

    /** Invalidates the token at the broker as well as locally. Best effort — local clear always wins. */
    public void logout(UserId userId) {
        Optional<KiteSession> current = sessions.get(userId);
        sessions.clear(userId);
        if (current.isEmpty()) return;
        try {
            KiteCredentials creds = credentials.require(userId);
            Map<String, String> query = KiteHttp.form();
            query.put("api_key", creds.apiKey());
            query.put("access_token", current.get().accessToken());
            http.delete("/session/token", query, creds, current.get().accessToken());
        } catch (RuntimeException e) {
            log.warn("kite logout call failed for user={} ({}) — local session cleared regardless",
                    userId, e.getMessage());
        }
    }

    /**
     * SHA-256 of apiKey + requestToken + apiSecret, hex encoded. Kite rejects the exchange without
     * it, and it is the proof that the caller holds the secret.
     */
    static String checksum(String apiKey, String requestToken, String apiSecret) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((apiKey + requestToken + apiSecret).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private String newNonce() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void prune() {
        Instant now = clock.now();
        pending.values().removeIf(p -> now.isAfter(p.expiresAt().plus(Duration.ofMinutes(1))));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
