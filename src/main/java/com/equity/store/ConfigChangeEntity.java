package com.equity.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Every change to a user's trading configuration, with the moment it happened.
 *
 * <h2>Why the settings table is not enough</h2>
 * <p>{@code user_settings} holds one row per user and is overwritten in place, so it answers "what
 * is the configuration now" and destroys the answer to "what was it when that trade was taken".</p>
 *
 * <p>That distinction decides whether a month of results means anything. In the first three sessions
 * alone the risk budget went from 1,000 to 3,000, the daily loss limit from 3,000 to 10,000, and
 * breakeven from off to on — mid-week, mid-session in one case. Without a record of when, a hundred
 * trades are a hundred trades under unknown and varying rules, and no comparison between them is
 * valid. Recording the change history is what turns the position table into evidence.</p>
 *
 * <p>Stamped per change rather than per position deliberately: configuration changes a handful of
 * times a month while positions run to hundreds, and any trade joins to the configuration in force
 * by comparing its open time to these timestamps.</p>
 */
@Entity
@Table(name = "config_change",
        indexes = @Index(name = "idx_config_change_user_at", columnList = "trading_user_id,changed_at"))
public class ConfigChangeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trading_user_id", nullable = false, length = 36)
    private String tradingUserId;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    @Column(name = "trading_date", nullable = false)
    private LocalDate tradingDate;

    /** Which group changed: RISK_LIMITS, THRESHOLDS or EXIT_POLICY. */
    @Column(name = "config_kind", nullable = false, length = 32)
    private String kind;

    /** The full settings after the change, as JSON. Verbose on purpose — a diff cannot be replayed. */
    @Lob
    @Column(name = "settings_json", nullable = false)
    private String settingsJson;

    protected ConfigChangeEntity() { }

    public ConfigChangeEntity(String tradingUserId, Instant changedAt, LocalDate tradingDate,
                              String kind, String settingsJson) {
        this.tradingUserId = tradingUserId;
        this.changedAt = changedAt;
        this.tradingDate = tradingDate;
        this.kind = kind;
        this.settingsJson = settingsJson;
    }

    public Instant changedAt()   { return changedAt; }
    public String kind()         { return kind; }
    public String settingsJson() { return settingsJson; }
}
