package com.equity.store;

import com.equity.domain.risk.RiskLimits;
import com.equity.strategy.ExitPolicy;
import com.equity.strategy.StrategyThresholds;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalTime;

/**
 * One user's risk limits, entry thresholds and exit policy.
 *
 * <p>Flat columns rather than a serialised blob. A blob is quicker to write and impossible to query,
 * and the question worth asking later — "what were this user's limits on the day that trade was
 * taken" — is a query. Flat columns also fail loudly when a field is added, which is the right
 * direction for settings that decide how much money is at risk.</p>
 *
 * <p>The engine's records are the source of truth for shape and defaults; this class converts. Note
 * that {@code entriesEnabled} is deliberately <b>not</b> stored: arming is a decision about right
 * now, and a process that came back armed because it was armed yesterday is exactly the surprise
 * nobody wants after a restart.</p>
 */
@Entity
@Table(name = "user_settings")
public class UserSettingsEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trading_user_id", nullable = false, unique = true, length = 36)
    private String tradingUserId;

    // ── risk ────────────────────────────────────────────────────────────────
    private double riskPerTradeRupees;
    private double maxDailyLossRupees;
    private int maxOpenPositions;
    private int maxDailyAttempts;
    private int maxPendingOrders;
    private double maxPositionValue;
    private double minStopPercent;
    private double maxStopPercent;
    private double maxSpreadPercent;
    private long cooldownSeconds;
    private long maxStalenessSeconds;

    // ── entry thresholds ────────────────────────────────────────────────────
    private double minDayChangePercent;
    private double maxDayChangePercent;
    private double sanityBandPercent;
    private double maxDistanceFromHighPct;
    private double minImpulseReturn5m;
    private double minRelativeVolume;
    private boolean requireAboveVwap;
    private boolean requireEmaStack;
    private boolean requireOutperformIndex;
    private double minPullbackPercent;
    private double maxPullbackPercent;
    private double maxConsolidationRangePct;
    private int minConsolidationBars;
    private double triggerBufferPercent;
    private double stopAtrMultiple;
    private double targetRMultiple;
    private int timeStopMinutes;
    private String entryWindowStart;
    private String entryWindowEnd;
    private String squareOffTime;

    // ── exit policy ─────────────────────────────────────────────────────────
    private boolean breakevenEnabled;
    private double breakevenArmAtR;
    private boolean trailingEnabled;
    private double trailingArmAtR;
    private double trailingAtrMultiple;
    private boolean structureExitEnabled;

    private Instant updatedAt;

    protected UserSettingsEntity() {}

    /** The row a user gets on first sight: the house settings, which are the ones that have traded. */
    public static UserSettingsEntity defaults(String tradingUserId) {
        UserSettingsEntity e = new UserSettingsEntity();
        e.tradingUserId = tradingUserId;
        e.apply(RiskLimits.house());
        e.apply(StrategyThresholds.house());
        e.apply(ExitPolicy.house());
        return e;
    }

    public String getTradingUserId() { return tradingUserId; }
    public Instant getUpdatedAt()    { return updatedAt; }

    public void apply(RiskLimits l) {
        riskPerTradeRupees = l.riskPerTradeRupees();
        maxDailyLossRupees = l.maxDailyLossRupees();
        maxOpenPositions = l.maxOpenPositions();
        maxDailyAttempts = l.maxDailyAttempts();
        maxPendingOrders = l.maxPendingOrders();
        maxPositionValue = l.maxPositionValue();
        minStopPercent = l.minStopPercent();
        maxStopPercent = l.maxStopPercent();
        maxSpreadPercent = l.maxSpreadPercent();
        cooldownSeconds = l.cooldownSeconds();
        maxStalenessSeconds = l.maxStalenessSeconds();
        updatedAt = Instant.now();
    }

    public void apply(StrategyThresholds t) {
        minDayChangePercent = t.minDayChangePercent();
        maxDayChangePercent = t.maxDayChangePercent();
        sanityBandPercent = t.sanityBandPercent();
        maxDistanceFromHighPct = t.maxDistanceFromHighPct();
        minImpulseReturn5m = t.minImpulseReturn5m();
        minRelativeVolume = t.minRelativeVolume();
        requireAboveVwap = t.requireAboveVwap();
        requireEmaStack = t.requireEmaStack();
        requireOutperformIndex = t.requireOutperformIndex();
        minPullbackPercent = t.minPullbackPercent();
        maxPullbackPercent = t.maxPullbackPercent();
        maxConsolidationRangePct = t.maxConsolidationRangePct();
        minConsolidationBars = t.minConsolidationBars();
        triggerBufferPercent = t.triggerBufferPercent();
        stopAtrMultiple = t.stopAtrMultiple();
        targetRMultiple = t.targetRMultiple();
        timeStopMinutes = t.timeStopMinutes();
        entryWindowStart = t.entryWindowStart().toString();
        entryWindowEnd = t.entryWindowEnd().toString();
        squareOffTime = t.squareOffTime().toString();
        updatedAt = Instant.now();
    }

    public void apply(ExitPolicy p) {
        breakevenEnabled = p.breakevenEnabled();
        breakevenArmAtR = p.breakevenArmAtR();
        trailingEnabled = p.trailingEnabled();
        trailingArmAtR = p.trailingArmAtR();
        trailingAtrMultiple = p.trailingAtrMultiple();
        structureExitEnabled = p.structureExitEnabled();
        updatedAt = Instant.now();
    }

    public RiskLimits toRiskLimits() {
        return new RiskLimits(riskPerTradeRupees, maxDailyLossRupees, maxOpenPositions,
                maxDailyAttempts, maxPendingOrders, maxPositionValue, minStopPercent,
                maxStopPercent, maxSpreadPercent, cooldownSeconds, maxStalenessSeconds);
    }

    public StrategyThresholds toThresholds() {
        return new StrategyThresholds(minDayChangePercent, maxDayChangePercent, sanityBandPercent,
                maxDistanceFromHighPct, minImpulseReturn5m, minRelativeVolume, requireAboveVwap,
                requireEmaStack, requireOutperformIndex, minPullbackPercent, maxPullbackPercent,
                maxConsolidationRangePct, minConsolidationBars, triggerBufferPercent,
                stopAtrMultiple, targetRMultiple, timeStopMinutes,
                LocalTime.parse(entryWindowStart), LocalTime.parse(entryWindowEnd),
                LocalTime.parse(squareOffTime));
    }

    public ExitPolicy toExitPolicy() {
        return new ExitPolicy(breakevenEnabled, breakevenArmAtR, trailingEnabled,
                trailingArmAtR, trailingAtrMultiple, structureExitEnabled);
    }
}
