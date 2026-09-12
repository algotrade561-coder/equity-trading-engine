package com.equity.broker.kite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

/**
 * A user's calls have to leave from the address their API key is registered to.
 *
 * <p>SEBI registers each key to one public IP and the broker refuses traffic from any other. With
 * several users on one machine that means several source addresses, chosen per call. The tests
 * here pin the two properties that make the scheme safe: the binding actually happens, and a user
 * with no address configured is untouched — because that is every single-user deployment,
 * including the live one, and it must not change behaviour by so much as a socket.</p>
 */
class SourceIpBindingTest {

    private static final KiteProperties PROPS = new KiteProperties();

    // ── The socket factory ───────────────────────────────────────────────────

    @Test
    void everySocketTheFactoryOpensLeavesFromTheChosenAddress() throws IOException {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        BoundSocketFactory factory = new BoundSocketFactory(loopback);

        try (ServerSocket server = new ServerSocket(0, 1, loopback);
             Socket socket = factory.createSocket(loopback, server.getLocalPort());
             Socket accepted = server.accept()) {
            assertThat(socket.getLocalAddress())
                    .as("the bind must happen before the connect, or the OS picks the default "
                            + "interface and the broker sees a key speaking from the wrong place")
                    .isEqualTo(loopback);
            assertThat(accepted.getInetAddress()).isEqualTo(loopback);
        }
    }

    @Test
    void theCallersOwnLocalAddressIsOverriddenNotHonoured() throws IOException {
        // The five-argument overload lets a caller ask for a local address. This factory exists to
        // make that non-negotiable, so the caller's choice is ignored and ours is used.
        InetAddress loopback = InetAddress.getLoopbackAddress();
        BoundSocketFactory factory = new BoundSocketFactory(loopback);

        try (ServerSocket server = new ServerSocket(0, 1, loopback);
             Socket socket = factory.createSocket(loopback, server.getLocalPort(),
                     InetAddress.getByName("0.0.0.0"), 0)) {
            assertThat(socket.getLocalAddress()).isEqualTo(loopback);
        }
    }

    @Test
    void anAddressTheMachineDoesNotHoldFailsLoudlyRatherThanFallingBack() {
        // 192.0.2.0/24 is reserved for documentation and is on nobody's interface. A silent fall
        // back to the default interface here would be an order leaving from an unregistered address.
        BoundSocketFactory factory = new BoundSocketFactory(address("192.0.2.1"));

        assertThatThrownBy(() -> factory.createSocket("127.0.0.1", 1))
                .isInstanceOf(IOException.class);
    }

    // ── The credentials ──────────────────────────────────────────────────────

    @Test
    void credentialsWithoutAnAddressUseTheDefaultInterface() {
        KiteCredentials plain = new KiteCredentials("key", "secret");
        assertThat(plain.hasSourceIp()).isFalse();
        assertThat(plain.sourceIp()).isNull();

        KiteCredentials blank = new KiteCredentials("key", "secret", "   ");
        assertThat(blank.hasSourceIp())
                .as("whitespace from a form field is not an address")
                .isFalse();
    }

    @Test
    void theAddressIsPrintableWhereTheSecretIsNot() {
        KiteCredentials c = new KiteCredentials("abcdefgh", "s3cret", "10.0.1.20");
        assertThat(c.toString())
                .as("the operator has to read the address off a screen; the secret must never appear")
                .contains("10.0.1.20")
                .doesNotContain("s3cret");
    }

    // ── The HTTP client selection ────────────────────────────────────────────

    @Test
    void aUserWithoutAnAddressGetsTheVeryClientTheEngineAlwaysUsed() {
        OkHttpClient shared = new OkHttpClient();
        KiteHttp http = new KiteHttp(PROPS, shared);

        assertThat(http.clientFor(new KiteCredentials("key", "secret")))
                .as("no address means no change — this is the live single-user deployment")
                .isSameAs(shared);
        assertThat(http.clientFor(null)).isSameAs(shared);
    }

    @Test
    void aUserWithAnAddressGetsABoundClientBuiltOnceAndReused() {
        OkHttpClient shared = new OkHttpClient();
        KiteHttp http = new KiteHttp(PROPS, shared);
        KiteCredentials pinned = new KiteCredentials("key", "secret", "127.0.0.1");

        OkHttpClient first = http.clientFor(pinned);
        OkHttpClient second = http.clientFor(pinned);

        assertThat(first).isNotSameAs(shared);
        assertThat(first)
                .as("an OkHttp client owns a connection pool and a dispatcher; one per call would leak both")
                .isSameAs(second);
        assertThat(first.socketFactory()).isInstanceOf(BoundSocketFactory.class);
        assertThat(((BoundSocketFactory) first.socketFactory()).localAddress())
                .isEqualTo(address("127.0.0.1"));
    }

    @Test
    void twoUsersOnDifferentAddressesGetDifferentClients() {
        KiteHttp http = new KiteHttp(PROPS, new OkHttpClient());

        OkHttpClient a = http.clientFor(new KiteCredentials("k1", "s1", "127.0.0.1"));
        OkHttpClient b = http.clientFor(new KiteCredentials("k2", "s2", "127.0.0.2"));

        assertThat(a).isNotSameAs(b);
    }

    @Test
    void theBoundClientKeepsTheDefaultsTimeouts() {
        OkHttpClient shared = new OkHttpClient.Builder()
                .connectTimeout(java.time.Duration.ofSeconds(7))
                .readTimeout(java.time.Duration.ofSeconds(11))
                .build();
        KiteHttp http = new KiteHttp(PROPS, shared);

        OkHttpClient bound = http.clientFor(new KiteCredentials("key", "secret", "127.0.0.1"));
        assertThat(bound.connectTimeoutMillis())
                .as("binding changes where a call leaves from, not how long it may take")
                .isEqualTo(7_000);
        assertThat(bound.readTimeoutMillis()).isEqualTo(11_000);
    }

    @Test
    void forgettingAnAddressMakesTheNextCallRebuildTheClient() {
        KiteHttp http = new KiteHttp(PROPS, new OkHttpClient());
        KiteCredentials pinned = new KiteCredentials("key", "secret", "127.0.0.1");

        OkHttpClient before = http.clientFor(pinned);
        http.forgetClientFor("127.0.0.1");
        OkHttpClient after = http.clientFor(pinned);

        assertThat(after)
                .as("a changed address must not keep serving from the old client's pooled connections")
                .isNotSameAs(before);
    }

    @Test
    void anUnparseableAddressIsAConfigurationErrorNotAFallback() {
        KiteHttp http = new KiteHttp(PROPS, new OkHttpClient());

        assertThatThrownBy(() -> http.clientFor(new KiteCredentials("key", "secret", "not an address!")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not an address!");
    }

    private static InetAddress address(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (java.net.UnknownHostException e) {
            throw new AssertionError(e);
        }
    }
}
