package com.equity.broker.kite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.equity.broker.BrokerException;
import com.equity.domain.user.UserId;
import com.equity.platform.time.FixedTradingClock;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import okhttp3.HttpUrl;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KiteAuthServiceTest {

    private static final UserId USER = UserId.random();
    private static final Instant NOW = Instant.parse("2026-09-04T04:00:00Z");   // 09:30 IST

    private MockWebServer server;
    private FixedTradingClock clock;
    private KiteProperties props;
    private KiteSessionStore sessions;
    private KiteAuthService auth;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        props = new KiteProperties();
        props.setApiKey("test_api_key");
        props.setApiSecret("test_api_secret");
        props.setRestUrl(server.url("").toString().replaceAll("/$", ""));

        clock = new FixedTradingClock(NOW);
        sessions = new KiteSessionStore(clock);
        auth = new KiteAuthService(props, new KiteCredentialsProvider(props), sessions,
                new KiteHttp(props), clock);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    /**
     * A literal vector, computed outside this codebase. Recomputing the digest here with the same
     * algorithm would assert only that the code agrees with itself, and would pass just as happily
     * if both sides concatenated the fields in the wrong order.
     */
    private static final String EXPECTED_CHECKSUM =
            "3cab84885d10b4f4b4f77cde906ce84dcc8017d47a75861043329144a4240728";

    @Test
    void checksumIsSha256OfKeyThenTokenThenSecret() {
        assertThat(KiteAuthService.checksum("test_api_key", "req_token_1", "test_api_secret"))
                .isEqualTo(EXPECTED_CHECKSUM);
    }

    @Test
    void checksumIsOrderSensitive() {
        assertThat(KiteAuthService.checksum("req_token_1", "test_api_key", "test_api_secret"))
                .as("Kite refuses a checksum built from the fields in any other order")
                .isNotEqualTo(EXPECTED_CHECKSUM);
    }

    @Test
    void loginUrlCarriesTheApiKeyAndOurOwnIdentityParametersButNoSecret() {
        String url = auth.buildLoginUrl(USER);
        HttpUrl parsed = HttpUrl.get(url);

        assertThat(parsed.queryParameter("api_key")).isEqualTo("test_api_key");
        assertThat(parsed.queryParameter("v")).isEqualTo("3");

        String redirectParams = parsed.queryParameter("redirect_params");
        assertThat(redirectParams).contains("eq_user=" + USER).contains("eq_nonce=");
        assertThat(url)
                .as("the login URL is opened in a browser; the secret must never be in it")
                .doesNotContain("test_api_secret");
    }

    @Test
    void completesLoginAndStoresASessionForToday() throws Exception {
        String nonce = nonceFrom(auth.buildLoginUrl(USER));
        server.enqueue(success());

        KiteSession session = auth.completeLogin(USER, nonce, "req_token_1");

        assertThat(session.accessToken()).isEqualTo("access_abc");
        assertThat(session.kiteUserId()).isEqualTo("AB1234");
        assertThat(session.tradingDate()).isEqualTo(LocalDate.of(2026, 9, 4));
        assertThat(sessions.isAuthenticated(USER)).isTrue();

        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).isEqualTo("/session/token");
        String body = request.getBody().readUtf8();
        assertThat(body).contains("api_key=test_api_key")
                .contains("request_token=req_token_1")
                .contains("checksum=" + EXPECTED_CHECKSUM);
    }

    @Test
    void aNonceIsSingleUse() {
        String nonce = nonceFrom(auth.buildLoginUrl(USER));
        server.enqueue(success());
        auth.completeLogin(USER, nonce, "req_token_1");

        assertThatThrownBy(() -> auth.completeLogin(USER, nonce, "req_token_2"))
                .isInstanceOf(BrokerException.class)
                .hasMessageContaining("no matching pending login");
    }

    @Test
    void aNonceMintedForOneUserCannotCompleteLoginForAnother() {
        String nonce = nonceFrom(auth.buildLoginUrl(USER));

        assertThatThrownBy(() -> auth.completeLogin(UserId.random(), nonce, "req_token_1"))
                .as("without this, anyone reaching the callback could bind a token to another account")
                .isInstanceOf(BrokerException.class);

        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void anUnknownNonceIsRefusedWithoutCallingTheBroker() {
        assertThatThrownBy(() -> auth.completeLogin(USER, "made-up-nonce", "req_token_1"))
                .isInstanceOf(BrokerException.class);

        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void aLoginLeftOpenPastTheWindowIsRefused() {
        props.setLoginWindow(Duration.ofMinutes(10));
        String nonce = nonceFrom(auth.buildLoginUrl(USER));

        clock.advance(Duration.ofMinutes(11));

        assertThatThrownBy(() -> auth.completeLogin(USER, nonce, "req_token_1"))
                .isInstanceOf(BrokerException.class);
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void aSessionDoesNotSurviveIntoTheNextTradingDay() {
        String nonce = nonceFrom(auth.buildLoginUrl(USER));
        server.enqueue(success());
        auth.completeLogin(USER, nonce, "req_token_1");
        assertThat(sessions.isAuthenticated(USER)).isTrue();

        clock.advance(Duration.ofHours(24));

        assertThat(sessions.isAuthenticated(USER))
                .as("Kite invalidates tokens each morning; believing otherwise means 403s at the open")
                .isFalse();
    }

    @Test
    void everyRejectionReadsTheSameSoTheCallbackLeaksNothing() {
        String nonce = nonceFrom(auth.buildLoginUrl(USER));
        clock.advance(Duration.ofMinutes(30));

        String expired = messageOf(() -> auth.completeLogin(USER, nonce, "req_token_1"));
        String unknown = messageOf(() -> auth.completeLogin(USER, "not-a-nonce", "req_token_1"));

        assertThat(expired).isEqualTo(unknown);
    }

    private static String messageOf(Runnable r) {
        try {
            r.run();
            throw new AssertionError("expected a rejection");
        } catch (BrokerException e) {
            return e.getMessage();
        }
    }

    private static MockResponse success() {
        return new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"status\":\"success\",\"data\":{\"user_id\":\"AB1234\","
                        + "\"access_token\":\"access_abc\",\"public_token\":\"public_abc\"}}");
    }

    private static String nonceFrom(String loginUrl) {
        String params = HttpUrl.get(loginUrl).queryParameter("redirect_params");
        for (String pair : URLDecoder.decode(params, StandardCharsets.UTF_8).split("&")) {
            if (pair.startsWith("eq_nonce=")) return pair.substring("eq_nonce=".length());
        }
        throw new AssertionError("no nonce in " + params);
    }
}
