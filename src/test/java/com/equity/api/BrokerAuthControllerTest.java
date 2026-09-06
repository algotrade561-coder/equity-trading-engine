package com.equity.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.equity.broker.kite.KiteAuthService;
import com.equity.broker.kite.KiteCredentialsProvider;
import com.equity.broker.kite.KiteHttp;
import com.equity.broker.kite.KiteInstrumentMaster;
import com.equity.broker.kite.KiteProperties;
import com.equity.broker.kite.KiteSessionStore;
import com.equity.broker.kite.KiteTickerManager;
import com.equity.domain.user.UserId;
import com.equity.platform.time.FixedTradingClock;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * The API surface the login screen talks to.
 *
 * <p>The assertions that matter are negative ones. Spec section 37 forbids credentials in API
 * responses and frontend storage, and this controller is the only place where a browser and an
 * access token are one field apart.</p>
 */
class BrokerAuthControllerTest {

    private static final String SECRET = "sup3r-secret-value";

    private KiteProperties props;
    private BrokerAuthController controller;

    @BeforeEach
    void setUp() {
        props = new KiteProperties();
        props.setApiKey("api-key-1234");
        props.setApiSecret(SECRET);
        props.setEnabled(true);

        FixedTradingClock clock = new FixedTradingClock(Instant.parse("2026-09-04T04:00:00Z"));
        KiteHttp http = new KiteHttp(props);
        KiteSessionStore sessions = new KiteSessionStore(clock);
        KiteCredentialsProvider credentials = new KiteCredentialsProvider(props);
        KiteInstrumentMaster instruments = new KiteInstrumentMaster(props, http, credentials, sessions);
        KiteTickerManager tickers =
                new KiteTickerManager(props, http, sessions, credentials, instruments, clock);
        KiteAuthService auth = new KiteAuthService(props, credentials, sessions, http, clock);

        // A CurrentUser backed by an in-memory repository: these tests exercise the callback and
        // the config endpoint, neither of which reads the signed-in identity.
        controller = new BrokerAuthController(props, auth, sessions, instruments, tickers,
                new com.equity.user.UserRegistry(),
                new com.equity.platform.security.CurrentUser(null, true));
    }

    @Test
    void configTellsTheUiWhatItNeedsAndNothingMore() {
        Map<String, Object> config = controller.config();

        assertThat(config).containsEntry("enabled", true)
                .containsEntry("configured", true)
                .containsKey("redirectUrl");
        assertThat(config.toString())
                .as("the page needs a boolean, never the credentials themselves")
                .doesNotContain(SECRET)
                .doesNotContain("api-key-1234");
    }

    @Test
    void configReportsMissingCredentialsWithoutFailing() {
        props.setApiKey("");
        props.setApiSecret("");

        assertThat(controller.config()).containsEntry("configured", false);
    }

    @Test
    void sessionForAUserWhoNeverLoggedInSaysSoAndCarriesNoToken() {
        Map<String, Object> session = controller.session(UserId.random().toString());

        assertThat(session).containsEntry("connected", false);
        assertThat(session).doesNotContainKey("accessToken");
        assertThat(session.toString()).doesNotContain(SECRET);
    }

    @Test
    void aCallbackWithoutOurUserParameterIsRefused() {
        ResponseEntity<String> response = controller.callback("req_token", "success", null, "nonce");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("did not identify a user");
    }

    @Test
    void aCallbackWithAnUnknownNonceIsRefused() {
        ResponseEntity<String> response =
                controller.callback("req_token", "success", UserId.random().toString(), "made-up");

        assertThat(response.getStatusCode().value())
                .as("without the nonce check anyone reaching this endpoint could bind a token")
                .isEqualTo(400);
    }

    @Test
    void aFailedLoginPageDoesNotBounceTheUserOnward() {
        ResponseEntity<String> response = controller.callback(null, "cancelled",
                UserId.random().toString(), "nonce");

        assertThat(response.getBody())
                .as("a failure is something to read, not something to redirect past")
                .doesNotContain("setTimeout");
    }

    @Test
    void brokerSuppliedTextIsEscapedBeforeItReachesThePage() {
        ResponseEntity<String> response = controller.callback(
                "req_token", "<script>alert(1)</script>", UserId.random().toString(), "nonce");

        assertThat(response.getBody()).doesNotContain("<script>alert(1)</script>")
                .contains("&lt;script&gt;");
    }
}
