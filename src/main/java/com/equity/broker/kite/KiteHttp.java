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
 * <p>It owns three things that must not be scattered: the {@code Authorization} header (so no call
 * site can build one from the wrong user's token), rate limiting, and the mapping from a Kite error
 * envelope to a {@link BrokerException}. Kite replies 200 with {@code {"status":"error"}} in some
 * paths and a 4xx in others, so status-code checking alone is not enough.</p>
 *
 * <p>No method here logs a token, and no exception message contains one.</p>
 */
@Component
public class KiteHttp {

    private static final String KITE_VERSION = "3";

    private final KiteProperties properties;
    private final OkHttpClient client;
    private final ObjectMapper json = new ObjectMapper();
    private final KiteRateLimiter limiter = new KiteRateLimiter(8);

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

    OkHttpClient client() { return client; }

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
        limiter.acquire();

        builder.header("X-Kite-Version", KITE_VERSION);
        if (accessToken != null && !accessToken.isBlank()) {
            builder.header("Authorization", "token " + credentials.apiKey() + ":" + accessToken);
        }

        Request request = builder.build();
        try (Response response = client.newCall(request).execute()) {
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
