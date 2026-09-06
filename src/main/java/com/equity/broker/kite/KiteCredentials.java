package com.equity.broker.kite;

/**
 * The API key and secret of a Kite application.
 *
 * <p>Held per user rather than globally. Most deployments will use one app for everybody, but a user
 * who brings their own Kite subscription can be given their own key without a code change, and more
 * importantly the type makes it impossible to place an order for user A using the key of user B.</p>
 */
public record KiteCredentials(String apiKey, String apiSecret) {

    public KiteCredentials {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("apiKey required");
        if (apiSecret == null || apiSecret.isBlank()) throw new IllegalArgumentException("apiSecret required");
    }

    /** Redacted. The secret must never reach a log line, an audit row or an API response. */
    @Override
    public String toString() {
        return "KiteCredentials{apiKey=" + Redaction.mask(apiKey)
                + ", apiSecret=" + Redaction.mask(apiSecret) + "}";
    }
}
