package com.equity.broker.kite;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for this engine's own Kite Connect application.
 *
 * <p>This engine registers its <b>own</b> Kite app rather than sharing the one the options engine
 * uses. A Kite app has exactly one registered redirect URL, so two engines sharing an app would
 * fight over that single setting and whichever deployed last would break the login of the other.</p>
 *
 * <p>{@code apiKey} and {@code apiSecret} are read from the environment, never committed, and never
 * returned by any API endpoint. {@link #toString()} is overridden so that a properties dump at
 * startup — which Spring Boot will happily produce — cannot leak the secret.</p>
 */
@ConfigurationProperties(prefix = "equity.broker.kite")
public class KiteProperties {

    private String apiKey = "";
    private String apiSecret = "";

    private String restUrl = "https://api.kite.trade";
    private String websocketUrl = "wss://ws.kite.trade";
    private String loginUrl = "https://kite.zerodha.com/connect/login";
    private String instrumentsUrl = "https://api.kite.trade/instruments";

    /**
     * The redirect URL registered against the Kite app. A Kite app holds exactly one, and it cannot
     * vary per user, so tenant identity travels instead in {@code redirect_params} — Kite appends
     * those verbatim to the callback. That is the only mechanism the broker offers for carrying our
     * own identifier through the login round trip; the alternative, a shared callback plus a guess
     * at whose token just arrived, is a cross-tenant mix-up waiting to happen.
     */
    private String redirectUrl = "http://localhost:8090/api/broker/kite/callback";

    /** How long a login may sit half-finished before its pending nonce is discarded. */
    private Duration loginWindow = Duration.ofMinutes(10);

    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(10);

    /** Delay before the first reconnect attempt; doubles up to {@link #maxReconnectDelay}. */
    private Duration reconnectDelay = Duration.ofSeconds(2);
    private Duration maxReconnectDelay = Duration.ofSeconds(60);

    /**
     * How long the feed may be silent before it is treated as dead and the socket recycled. Kite
     * sends a heartbeat roughly every second even outside market hours, so silence past this is a
     * broken connection rather than a quiet market.
     */
    private Duration feedSilenceTimeout = Duration.ofSeconds(15);

    /**
     * Market-protection band for MARKET orders, as a percentage.
     *
     * <p>Kite refuses a MARKET order placed through the API without it — "Market orders without
     * market protection are not allowed via API" — so this is not optional, it is the price of using
     * market orders at all. It caps how far from the last price a market order may fill, which is
     * the protection its name suggests: on a thin book a bare market order can be filled far worse
     * than the price the decision was made at.</p>
     *
     * <p>3% matches the Kite web default. Set to 0 to omit the field entirely, which will bring the
     * refusal back.</p>
     */
    private double marketProtectionPercent = 3.0;

    /** When false, no WebSocket is opened and no order may be placed regardless of engine mode. */
    private boolean enabled = false;

    public String getApiKey() { return apiKey; }
    public void setApiKey(String v) { this.apiKey = v; }

    public String getApiSecret() { return apiSecret; }
    public void setApiSecret(String v) { this.apiSecret = v; }

    public String getRestUrl() { return restUrl; }
    public void setRestUrl(String v) { this.restUrl = v; }

    public String getWebsocketUrl() { return websocketUrl; }
    public void setWebsocketUrl(String v) { this.websocketUrl = v; }

    public String getLoginUrl() { return loginUrl; }
    public void setLoginUrl(String v) { this.loginUrl = v; }

    public String getInstrumentsUrl() { return instrumentsUrl; }
    public void setInstrumentsUrl(String v) { this.instrumentsUrl = v; }

    public String getRedirectUrl() { return redirectUrl; }
    public void setRedirectUrl(String v) { this.redirectUrl = v; }

    public Duration getLoginWindow() { return loginWindow; }
    public void setLoginWindow(Duration v) { this.loginWindow = v; }

    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration v) { this.connectTimeout = v; }

    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration v) { this.readTimeout = v; }

    public Duration getReconnectDelay() { return reconnectDelay; }
    public void setReconnectDelay(Duration v) { this.reconnectDelay = v; }

    public Duration getMaxReconnectDelay() { return maxReconnectDelay; }
    public void setMaxReconnectDelay(Duration v) { this.maxReconnectDelay = v; }

    public Duration getFeedSilenceTimeout() { return feedSilenceTimeout; }
    public void setFeedSilenceTimeout(Duration v) { this.feedSilenceTimeout = v; }

    public double getMarketProtectionPercent() { return marketProtectionPercent; }
    public void setMarketProtectionPercent(double v) { this.marketProtectionPercent = v; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank() && apiSecret != null && !apiSecret.isBlank();
    }

    /** Redacted on purpose. See the class comment. */
    @Override
    public String toString() {
        return "KiteProperties{apiKey=" + Redaction.mask(apiKey)
                + ", apiSecret=" + Redaction.mask(apiSecret)
                + ", restUrl=" + restUrl
                + ", websocketUrl=" + websocketUrl
                + ", enabled=" + enabled + "}";
    }
}
