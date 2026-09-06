package com.equity.broker.kite;

/**
 * One place that decides how a secret is allowed to appear in text.
 *
 * <p>Spec section 37 forbids credentials in API responses, frontend storage, ordinary logs and audit
 * details. That is a rule about every string-formatting site in the broker layer, so it is worth a
 * named helper rather than a convention: a convention is not enforceable, and the one log line that
 * forgets it is the one that ends up in a support ticket.</p>
 *
 * <p>A short prefix is kept deliberately. Operationally you need to answer "is this the key I think
 * it is" without ever seeing enough to use it.</p>
 */
final class Redaction {

    private Redaction() {}

    static String mask(String secret) {
        if (secret == null || secret.isEmpty()) return "<unset>";
        if (secret.length() <= 4) return "****";
        return secret.substring(0, 4) + "****(" + secret.length() + ")";
    }
}
