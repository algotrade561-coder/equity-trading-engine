package com.equity.broker.kite;

import com.equity.broker.BrokerException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Component;

/**
 * The single place that speaks HTTP to Kite.
 *
 * <p>It owns four things that must not be scattered: the {@code Authorization} header (so no call
 * site can build one from the wrong user's token), rate limiting, the mapping from a Kite error
 * envelope to a {@link BrokerException}, and — since several users may share one machine — the
 * choice of <b>which local address a call leaves from</b>. Kite replies 200 with
 * {@code {"status":"error"}} in some paths and a 4xx in others, so status-code checking alone is not
 * enough.</p>
 *
 * <h2>One client per source address</h2>
 * <p>SEBI's static-IP rule registers each API key to one public IP. A user whose key is registered to
 * an Elastic IP has to have their calls leave from the private address that IP maps to, and a user
 * without one uses the machine's default interface. The address rides on {@link KiteCredentials},
 * so {@link #clientFor} can pick the right client on every call without any call site knowing the
 * rule exists. Clients are cached per address: an OkHttp client owns a connection pool and a thread
 * pool, and building one per request would leak both.</p>
 *
 * <p>No method here logs a token, and no exception message contains one.</p>
 */
@Component
public class KiteHttp {

    private static final String KITE_VERSION = "3";

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(KiteHttp.class);

    private final KiteProperties properties;
    /** The default-interface client — every call without a source address, which is most of them. */
    private final OkHttpClient client;
    /** One client per bound source address, built on first use. See {@link #clientFor}. */
    private final java.util.concurrent.ConcurrentMap<String, OkHttpClient> boundClients =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final ObjectMapper json = new ObjectMapper();
    /**
     * One pacer per API key. Kite's limit is per key, so two users with their own keys have two
     * budgets, and one user's polling must not slow another user's exit. Users sharing a key share
     * a pacer, which is exactly the budget they share at the broker.
     */
    private final java.util.concurrent.ConcurrentMap<String, KiteRateLimiter> limiters =
            new java.util.concurrent.ConcurrentHashMap<>();

    private KiteRateLimiter limiterFor(KiteCredentials credentials) {
        String key = credentials == null || credentials.apiKey() == null ? "" : credentials.apiKey();
        return limiters.computeIfAbsent(key, k -> new KiteRateLimiter(8));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public KiteHttp(KiteProperties properties) {
        this(properties, new OkHttpClient.Builder()
                .connectTimeout(properties.getConnectTimeout())
                .readTimeout(properties.getReadTimeout())
                .build());
    }

    /** Test seam: lets a test point the client at a local server with its own timeouts. */
    KiteHttp(KiteProperties properties, OkHttpClient client) {
        this.properties = properties;
        this.client = client;
    }

    /** The default-interface client. Callers that know the user should prefer {@link #clientFor}. */
    OkHttpClient client() { return client; }

    /**
     * The client whose sockets leave from this user's source address.
     *
     * <p>The default client when no address is set, which keeps a single-user deployment exactly as
     * it was. Otherwise a client sharing the default's timeouts but with every socket bound to the
     * given address, built once and reused — the address is the cache key, so two users given the
     * same address (which should not happen, but the broker would be the one to object) share a
     * client rather than fight over one.</p>
     *
     * <p>An address that does not resolve is a configuration error and is treated as one: the call
     * fails with a clear message naming the address, rather than quietly going out from the default
     * interface — which would be an order leaving from an address the broker will refuse, and the
     * refusal would arrive as an opaque broker error some distance from its cause.</p>
     */
    OkHttpClient clientFor(KiteCredentials credentials) {
        if (credentials == null || !credentials.hasSourceIp()) return client;
        return boundClients.computeIfAbsent(credentials.sourceIp(), this::bindTo);
    }

    private OkHttpClient bindTo(String sourceIp) {
        java.net.InetAddress address;
        try {
            address = java.net.InetAddress.getByName(sourceIp);
        } catch (java.net.UnknownHostException e) {
            throw new IllegalStateException("source IP '" + sourceIp + "' is not a valid address "
                    + "— fix it on the user's broker settings before their calls can leave the box", e);
        }
        log.info("binding a Kite client to source address {} — this user's API key is registered "
                + "to the public IP that address maps to, and calls from anywhere else are refused", sourceIp);
        return client.newBuilder()
                .socketFactory(new BoundSocketFactory(address))
                .build();
    }

    /**
     * Forgets the client bound to an address, so the next call rebuilds it.
     *
     * <p>Called when a user's source address changes. Without it the old client, and its pooled
     * connections from the old address, would keep serving that user until restart — the exact
     * failure the reference design records as its first bug.</p>
     */
    void forgetClientFor(String sourceIp) {
        if (sourceIp == null) return;
        OkHttpClient old = boundClients.remove(sourceIp);
        if (old != null) {
            old.connectionPool().evictAll();
            log.info("dropped the Kite client bound to {}; the next call will rebuild it", sourceIp);
        }
    }

    public JsonNode get(String path, KiteCredentials credentials, String accessToken) {
        return execute(new Request.Builder().url(url(path)).get(), credentials, accessToken);
    }

    public JsonNode postForm(String path, Map<String, String> form,
                             KiteCredentials credentials, String accessToken) {
        return execute(new Request.Builder().url(url(path)).post(body(form)), credentials, accessToken);
    }

    /**
     * POST with a JSON body.
     *
     * <p>Most of Kite is form-encoded; the margin endpoints are not. Keeping this separate rather
     * than making {@code postForm} guess means a call site cannot silently send the wrong encoding.
     */
    public JsonNode postJson(String path, String json,
                             KiteCredentials credentials, String accessToken) {
        RequestBody body = RequestBody.create(json, okhttp3.MediaType.parse("application/json"));
        return execute(new Request.Builder().url(url(path)).post(body), credentials, accessToken);
    }

    public JsonNode putForm(String path, Map<String, String> form,
                            KiteCredentials credentials, String accessToken) {
        return execute(new Request.Builder().url(url(path)).put(body(form)), credentials, accessToken);
    }

    public JsonNode delete(String path, Map<String, String> query,
                           KiteCredentials credentials, String accessToken) {
        HttpUrl.Builder u = HttpUrl.get(url(path)).newBuilder();
        query.forEach(u::addQueryParameter);
        return execute(new Request.Builder().url(u.build()).delete(), credentials, accessToken);
    }

    private String url(String path) {
        return properties.getRestUrl() + path;
    }

    private static RequestBody body(Map<String, String> form) {
        FormBody.Builder b = new FormBody.Builder();
        form.forEach((k, v) -> { if (v != null) b.add(k, v); });
        return b.build();
    }

    /**
     * Runs the request and unwraps the Kite envelope.
     *
     * @param accessToken null for the login exchange, which is the one call made before a token exists
     */
    private JsonNode execute(Request.Builder builder, KiteCredentials credentials, String accessToken) {
        limiterFor(credentials).acquire();

        builder.header("X-Kite-Version", KITE_VERSION);
        if (accessToken != null && !accessToken.isBlank()) {
            builder.header("Authorization", "token " + credentials.apiKey() + ":" + accessToken);
        }

        Request request = builder.build();
        try (Response response = clientFor(credentials).newCall(request).execute()) {
            ResponseBody rb = response.body();
            String text = rb == null ? "" : rb.string();
            JsonNode root = text.isBlank() ? json.createObjectNode() : json.readTree(text);

            if (root.path("status").asText("").equals("error") || !response.isSuccessful()) {
                String type = root.path("error_type").asText("HttpError");
                String message = root.path("message").asText("HTTP " + response.code());
                // A 4xx is Kite saying no, and it says so before the order goes anywhere. A 5xx
                // is Kite failing, which can happen after it has already accepted the order — so
                // the outcome is unknown and has to be resolved by asking, not assumed.
                boolean outcomeUnknown = response.code() >= 500
                        || "GeneralException".equals(type)
                        || "NetworkException".equals(type);
                throw new BrokerException(request.method() + " " + request.url().encodedPath()
                        + " failed: " + message, type, isRetryable(type, response.code()),
                        outcomeUnknown);
            }
            return root.path("data");

        } catch (IOException e) {
            // A timeout is NOT retryable: the order may already be at the exchange. The caller must
            // resolve it by asking the broker what happened, never by sending it again.
            throw new BrokerException(request.method() + " " + request.url().encodedPath()
                    + " failed: " + e.getClass().getSimpleName(), e);
        }
    }

    /**
     * Which failures are worth sending again.
     *
     * <p>Only transport-level and throttling failures. {@code TokenException} means the session is
     * gone and a retry produces an identical refusal; {@code InputException} and
     * {@code OrderException} mean the exchange rejected the instruction itself, and repeating it is
     * how an engine turns one rejected order into a hundred.</p>
     */
    private static boolean isRetryable(String errorType, int httpStatus) {
        if (httpStatus == 429 || httpStatus >= 500) return true;
        return "NetworkException".equals(errorType) || "GatewayException".equals(errorType);
    }

    static Map<String, String> form() {
        return new LinkedHashMap<>();
    }
}
