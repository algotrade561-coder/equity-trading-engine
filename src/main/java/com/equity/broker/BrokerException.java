package com.equity.broker;

/**
 * A broker call failed.
 *
 * <p>{@code retryable} separates a network hiccup from a refusal. Retrying a refusal burns the rate
 * limit and changes nothing. Retrying a timeout is worse: the order may already have reached the
 * exchange, so a timeout is reported as <b>not</b> retryable and must be resolved by querying order
 * state rather than by resubmitting.</p>
 *
 * <p>Messages must never carry an access token, API secret or request token. This exception is
 * logged and surfaced in audit trails, both of which are covered by the credential rule in spec
 * section 37.</p>
 */
public class BrokerException extends RuntimeException {

    private final boolean retryable;
    private final boolean outcomeUnknown;
    private final String errorType;

    /** The broker answered and refused. The order did not reach the exchange. */
    public BrokerException(String message, String errorType, boolean retryable) {
        this(message, errorType, retryable, false);
    }

    public BrokerException(String message, String errorType, boolean retryable,
                           boolean outcomeUnknown) {
        super(message);
        this.errorType = errorType;
        this.retryable = retryable;
        this.outcomeUnknown = outcomeUnknown;
    }

    /**
     * The call failed at the transport level, so the outcome is genuinely unknown: the request may
     * have been received and acted on with only the reply lost.
     */
    public BrokerException(String message, Throwable cause) {
        super(message, cause);
        this.errorType = cause == null ? "unknown" : cause.getClass().getSimpleName();
        this.retryable = false;
        this.outcomeUnknown = true;
    }

    public boolean isRetryable() { return retryable; }

    /**
     * Whether the order may nonetheless be live at the exchange.
     *
     * <p>Distinct from {@link #isRetryable()}, and the two are almost opposites for a timeout: a
     * timeout must not be retried <i>because</i> it may have landed. Retryable answers "send it
     * again?"; this answers "go and find out what happened?" — and the caller that ignores it holds
     * shares with no stop, no target, and nothing arranged to square them off.</p>
     */
    public boolean mayHaveReachedExchange() { return outcomeUnknown; }

    public String errorType()    { return errorType; }
}
