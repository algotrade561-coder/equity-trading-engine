package com.equity.broker.kite;

import static org.assertj.core.api.Assertions.assertThat;

import com.equity.domain.user.UserId;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Spec section 37: credentials must not appear in API responses, frontend storage, ordinary logs or
 * audit details.
 *
 * <p>Logging is the leak nobody plans. Every one of these types ends up inside a log line sooner or
 * later — a session on connect, the properties on a startup dump, credentials in an exception
 * message — and the default {@code toString} of a record prints every field. These tests are the
 * enforcement of that rule, not a description of it.</p>
 */
class CredentialRedactionTest {

    private static final String SECRET = "sup3r-secret-value";

    @Test
    void aSessionNeverPrintsItsAccessToken() {
        KiteSession session = new KiteSession(UserId.random(), "AB1234", SECRET, "public-tok",
                LocalDate.of(2026, 9, 4), Instant.parse("2026-09-04T04:00:00Z"));

        assertThat(session.toString())
                .doesNotContain(SECRET)
                .doesNotContain("public-tok")
                .contains("AB1234");   // the client code is not a credential and is useful in logs
    }

    @Test
    void credentialsNeverPrintTheSecret() {
        KiteCredentials credentials = new KiteCredentials("api-key-1234", SECRET);

        assertThat(credentials.toString()).doesNotContain(SECRET);
    }

    @Test
    void propertiesNeverPrintTheSecret() {
        KiteProperties props = new KiteProperties();
        props.setApiKey("api-key-1234");
        props.setApiSecret(SECRET);

        assertThat(props.toString())
                .as("Spring Boot will happily dump configuration properties at startup")
                .doesNotContain(SECRET);
    }

    @Test
    void maskingKeepsEnoughToIdentifyButNotEnoughToUse() {
        String masked = Redaction.mask("abcdefghijklmnop");

        assertThat(masked).startsWith("abcd").doesNotContain("efghijklmnop");
        assertThat(Redaction.mask("abc")).isEqualTo("****");
        assertThat(Redaction.mask(null)).isEqualTo("<unset>");
        assertThat(Redaction.mask("")).isEqualTo("<unset>");
    }
}
