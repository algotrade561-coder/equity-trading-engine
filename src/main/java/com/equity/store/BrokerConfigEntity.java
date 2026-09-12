package com.equity.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One user's broker credentials and their live session.
 *
 * <p>Every secret column holds ciphertext produced by {@code SecretCipher} — never plaintext. The
 * entity itself is deliberately dumb about that: it stores what it is given, and the service layer
 * owns encrypting on the way in and decrypting on the way out, so there is exactly one place that
 * can get it wrong.</p>
 *
 * <p>The access token is stored with the trading date it was issued for. Kite invalidates tokens
 * each morning, so a token from yesterday is worse than no token: it produces 403s at the open
 * rather than an honest "please log in". Persisting the date is what lets a restart tell those two
 * cases apart.</p>
 */
@Entity
@Table(name = "broker_config")
public class BrokerConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trading_user_id", nullable = false, unique = true, length = 36)
    private String tradingUserId;

    @Column(nullable = false, length = 32)
    private String brokerName = "ZERODHA";

    /** The Zerodha client code, e.g. AB1234. Not a secret — it is on every contract note. */
    @Column(name = "broker_client_id", length = 64)
    private String brokerClientId;

    @Column(name = "api_key_enc", length = 1024)
    private String apiKeyEncrypted;

    @Column(name = "api_secret_enc", length = 2048)
    private String apiSecretEncrypted;

    @Column(name = "access_token_enc", length = 4096)
    private String accessTokenEncrypted;

    /** The trading date the access token belongs to. A token for any other date is not usable. */
    @Column(name = "token_trading_date")
    private LocalDate tokenTradingDate;

    /**
     * The local address this user's broker traffic must leave from, or null for the default.
     *
     * <p>On AWS this is a secondary private IP on the instance's network interface, mapped to an
     * Elastic IP that the user has registered with the broker. Plain text: it is not a secret, it is
     * the one value an operator has to read off the screen and type into the Kite developer console.
     * Validated as an address at the API, not here — an entity should not refuse to load a row.</p>
     */
    @Column(name = "source_ip", length = 45)
    private String sourceIp;

    private Instant tokenIssuedAt;
    private Instant updatedAt;

    protected BrokerConfigEntity() {}

    public BrokerConfigEntity(String tradingUserId) {
        this.tradingUserId = tradingUserId;
        this.updatedAt = Instant.now();
    }

    public Long getId()                       { return id; }
    public String getTradingUserId()          { return tradingUserId; }
    public String getBrokerName()             { return brokerName; }
    public String getBrokerClientId()         { return brokerClientId; }
    public String getApiKeyEncrypted()        { return apiKeyEncrypted; }
    public String getApiSecretEncrypted()     { return apiSecretEncrypted; }
    public String getAccessTokenEncrypted()   { return accessTokenEncrypted; }
    public LocalDate getTokenTradingDate()    { return tokenTradingDate; }
    public String getSourceIp()               { return sourceIp; }
    public Instant getTokenIssuedAt()         { return tokenIssuedAt; }
    public Instant getUpdatedAt()             { return updatedAt; }

    public void setBrokerClientId(String v)       { this.brokerClientId = v; touch(); }
    public void setApiKeyEncrypted(String v)      { this.apiKeyEncrypted = v; touch(); }
    public void setApiSecretEncrypted(String v)   { this.apiSecretEncrypted = v; touch(); }
    public void setSourceIp(String v)             { this.sourceIp = v == null || v.isBlank() ? null : v.trim(); touch(); }

    public void setSession(String accessTokenEncrypted, LocalDate tradingDate, Instant issuedAt) {
        this.accessTokenEncrypted = accessTokenEncrypted;
        this.tokenTradingDate = tradingDate;
        this.tokenIssuedAt = issuedAt;
        touch();
    }

    public void clearSession() {
        this.accessTokenEncrypted = null;
        this.tokenTradingDate = null;
        this.tokenIssuedAt = null;
        touch();
    }

    /** True when both an API key and a secret are present — enough to start a login. */
    public boolean hasCredentials() {
        return apiKeyEncrypted != null && !apiKeyEncrypted.isBlank()
                && apiSecretEncrypted != null && !apiSecretEncrypted.isBlank();
    }

    private void touch() { this.updatedAt = Instant.now(); }
}
