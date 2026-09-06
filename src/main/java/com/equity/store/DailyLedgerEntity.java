package com.equity.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One user's running day: realised profit, attempts, and whether the loss limit has latched.
 *
 * <p>Persisted because <b>a latch that a restart clears is not a latch</b>. The daily loss limit is
 * the one control that stops a bad day, and holding it only in memory means the way to escape it is
 * to restart the process — which is exactly what somebody would do while trying to fix whatever was
 * going wrong. Design note 0.7 says the limit latches for the session; that is only true if it
 * outlives the JVM.</p>
 *
 * <p>Keyed on (user, trading date), so a new session starts clean without anything having to
 * remember to reset it.</p>
 */
@Entity
@Table(name = "daily_ledger", indexes = {
        @Index(name = "idx_ledger_user_date", columnList = "trading_user_id,trading_date", unique = true)
})
public class DailyLedgerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trading_user_id", nullable = false, length = 36)
    private String tradingUserId;

    @Column(name = "trading_date", nullable = false)
    private LocalDate tradingDate;

    private double realisedPnl;
    private int attempts;
    private int fills;

    private boolean lossLatched;

    @Column(length = 512)
    private String latchReason;

    private Instant updatedAt;

    protected DailyLedgerEntity() {}

    public DailyLedgerEntity(String tradingUserId, LocalDate tradingDate) {
        this.tradingUserId = tradingUserId;
        this.tradingDate = tradingDate;
        this.updatedAt = Instant.now();
    }

    public String getTradingUserId() { return tradingUserId; }
    public LocalDate getTradingDate() { return tradingDate; }
    public double getRealisedPnl()   { return realisedPnl; }
    public int getAttempts()         { return attempts; }
    public int getFills()            { return fills; }
    public boolean isLossLatched()   { return lossLatched; }
    public String getLatchReason()   { return latchReason; }

    /**
     * Replaces the whole day with the in-memory truth.
     *
     * <p>Overwrite rather than increment: the ledger in memory is authoritative while the process
     * runs, and an incrementing write would double-count anything replayed after a retry.</p>
     */
    public void overwrite(double realisedPnl, int attempts, int fills,
                          boolean lossLatched, String latchReason) {
        this.realisedPnl = realisedPnl;
        this.attempts = attempts;
        this.fills = fills;
        this.lossLatched = lossLatched;
        this.latchReason = latchReason;
        touch();
    }

    private void touch() { this.updatedAt = Instant.now(); }
}
