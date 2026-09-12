package com.equity.broker.kite;

/**
 * The API key and secret of a Kite application, and the address its traffic must leave from.
 *
 * <p>Held per user rather than globally. Most deployments will use one app for everybody, but a user
 * who brings their own Kite subscription can be given their own key without a code change, and more
 * importantly the type makes it impossible to place an order for user A using the key of user B.</p>
 *
 * <h2>Why the source address lives here</h2>
 * <p>SEBI requires each API key to be registered to one static public IP, and the broker rejects
 * traffic from any other. With several users on one machine, each user's calls have to leave from
 * their own address. That address is a property of the key — it is the address the key was
 * registered against — so it travels with the key. Every Kite call already takes a
 * {@code KiteCredentials}; putting the address on it means the right source is chosen on every call
 * by construction, and a new call site cannot forget it any more than it could forget the key.</p>
 *
 * @param sourceIp the local address to bind outbound sockets to, or null to use the machine's
 *                 default interface — which is every single-user deployment, and every user who has
 *                 not yet been given an address of their own
 */
public record KiteCredentials(String apiKey, String apiSecret, String sourceIp) {

    public KiteCredentials {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("apiKey required");
        if (apiSecret == null || apiSecret.isBlank()) throw new IllegalArgumentException("apiSecret required");
        if (sourceIp != null && sourceIp.isBlank()) sourceIp = null;
    }

    /** Credentials that leave from the default interface. */
    public KiteCredentials(String apiKey, String apiSecret) {
        this(apiKey, apiSecret, null);
    }

    /** Whether this user's traffic is pinned to a specific local address. */
    public boolean hasSourceIp() { return sourceIp != null; }

    /** Redacted. The secret must never reach a log line, an audit row or an API response. */
    @Override
    public String toString() {
        // The source address is not a secret — it is the one thing here the operator has to read
        // off a screen to register with the broker.
        return "KiteCredentials{apiKey=" + Redaction.mask(apiKey)
                + ", apiSecret=" + Redaction.mask(apiSecret)
                + ", sourceIp=" + (sourceIp == null ? "default" : sourceIp) + "}";
    }
}
