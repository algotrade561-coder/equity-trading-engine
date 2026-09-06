package com.equity.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A person who may sign in.
 *
 * <p><b>The table is the allow-list.</b> Google proves who somebody is; it says nothing about
 * whether they should be able to trade this account. A row here is what grants access, so the
 * failure mode of a misconfigured OAuth client is "nobody can log in", not "anybody can".</p>
 *
 * <p>{@code tradingUserId} is the stable identity the whole engine keys on. It is minted once, on
 * first sign-in, and never changes — an email can be edited, and every position, order and ledger
 * row would otherwise follow it.</p>
 */
@Entity
@Table(name = "app_user")
public class AppUserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 320)
    private String email;

    /** The UUID every other table and the whole engine refers to. */
    @Column(name = "trading_user_id", nullable = false, unique = true, length = 36)
    private String tradingUserId;

    @Column(length = 200)
    private String displayName;

    @Column(nullable = false, length = 32)
    private String role = "TRADER";

    @Column(nullable = false)
    private boolean enabled = true;

    private Instant createdAt;
    private Instant lastLoginAt;

    protected AppUserEntity() {}

    public AppUserEntity(String email, String displayName, String role) {
        this.email = email;
        this.displayName = displayName;
        this.role = role;
        this.tradingUserId = UUID.randomUUID().toString();
        this.createdAt = Instant.now();
    }

    public Long getId()                 { return id; }
    public String getEmail()            { return email; }
    public String getTradingUserId()    { return tradingUserId; }
    public String getDisplayName()      { return displayName; }
    public String getRole()             { return role; }
    public boolean isEnabled()          { return enabled; }
    public Instant getCreatedAt()       { return createdAt; }
    public Instant getLastLoginAt()     { return lastLoginAt; }

    public void setDisplayName(String v) { this.displayName = v; }
    public void setRole(String v)        { this.role = v; }
    public void setEnabled(boolean v)    { this.enabled = v; }
    public void setLastLoginAt(Instant v) { this.lastLoginAt = v; }

    public boolean isAdmin() { return "ADMIN".equals(role); }
}
