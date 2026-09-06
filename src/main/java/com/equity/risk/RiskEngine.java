package com.equity.risk;

import com.equity.app.EngineProperties;
import com.equity.app.ExecutionMode;
import com.equity.broker.ProductType;
import com.equity.domain.market.SharedInstrumentState;
import com.equity.domain.market.Tick;
import com.equity.domain.order.TradeIntent;
import com.equity.domain.risk.DenialReason;
import com.equity.domain.risk.RiskDecision;
import com.equity.domain.risk.RiskLimits;
import com.equity.market.InstrumentFreshness;
import com.equity.platform.time.TradingClock;
import com.equity.trading.PositionBook;
import com.equity.user.UserAccount;
import java.time.Duration;
import java.time.LocalTime;
import org.springframework.stereotype.Component;

/**
 * The last word on whether an entry happens, and how big it is.
 *
 * <h2>The order of the checks is the design</h2>
 * <p>Cheapest and most categorical first: whether the engine is trading at all, then whether this
 * user may, then whether the account has capacity, then whether the market is offering a tradeable
 * price, and only then sizing. Every step names its refusal, and every refusal is counted — the
 * distribution of {@link DenialReason} over a session is how you find out that a limit you thought
 * was generous is actually the thing stopping the strategy.</p>
 *
 * <h2>Entries only</h2>
 * <p>Nothing here is consulted when closing a position. Design note 0.1: a permission gate shared
 * between entries and exits means a halted account cannot get out, which turns a bad day into an
 * unmanaged one. Exits answer to the position lifecycle alone.</p>
 */
@Component
public class RiskEngine {

    private final EngineProperties engine;
    private final PositionBook positions;
    private final AccountLedger ledger;
    private final InstrumentFreshness freshness;
    private final TradingClock clock;
    private final MarginRequirements requirements;

    public RiskEngine(EngineProperties engine, PositionBook positions, AccountLedger ledger,
                      InstrumentFreshness freshness, TradingClock clock,
                      MarginRequirements requirements) {
        this.engine = engine;
        this.positions = positions;
        this.ledger = ledger;
        this.freshness = freshness;
        this.clock = clock;
        this.requirements = requirements;
    }

    /**
     * @param epochAtDecision the user epoch this decision is being made under; it is re-checked at
     *                        submission, because the gap between the two is the race design note 0.3
     *                        exists to close
     */
    public RiskDecision authorise(UserAccount account, TradeIntent intent,
                                  SharedInstrumentState state, Tick lastTick,
                                  double unrealised, double availableMargin, long epochAtDecision) {

        RiskLimits limits = account.limits();

        // ── Is the engine trading at all ─────────────────────────────────────
        if (engine.getMode() != ExecutionMode.LIVE) {
            return RiskDecision.deny(DenialReason.NOT_LIVE_MODE, "mode=" + engine.getMode());
        }
        if (!engine.isTradingEnabled()) {
            return RiskDecision.deny(DenialReason.TRADING_DISABLED, "master switch off");
        }

        // ── May this user open something ─────────────────────────────────────
        if (!account.entriesEnabled()) {
            return RiskDecision.deny(DenialReason.USER_ENTRIES_DISABLED);
        }
        if (!account.mayOpen()) {
            return RiskDecision.deny(DenialReason.USER_NOT_ACTIVE, "status=" + account.status());
        }
        if (!account.isCurrentEpoch(epochAtDecision)) {
            return RiskDecision.deny(DenialReason.STALE_EPOCH,
                    "authorised under epoch " + epochAtDecision + ", now " + account.epoch());
        }
        // Re-checked here even though the strategy already looked: the strategy decided at the
        // last candle close, and up to a minute can pass before a tick triggers the entry.
        LocalTime now = clock.timeOfDay();
        if (now.isBefore(account.thresholds().entryWindowStart())
                || now.isAfter(account.thresholds().entryWindowEnd())) {
            return RiskDecision.deny(DenialReason.OUTSIDE_ENTRY_WINDOW, now.withNano(0).toString());
        }

        // ── Has the account had enough ───────────────────────────────────────
        if (ledger.checkAndLatch(account.userId(), unrealised, limits.maxDailyLossRupees())) {
            return RiskDecision.deny(DenialReason.DAILY_LOSS_LATCHED,
                    ledger.latchReason(account.userId()));
        }
        if (ledger.attempts(account.userId()) >= limits.maxDailyAttempts()) {
            return RiskDecision.deny(DenialReason.MAX_DAILY_ATTEMPTS,
                    ledger.attempts(account.userId()) + " attempts today");
        }
        if (positions.openCount(account.userId()) >= limits.maxOpenPositions()) {
            return RiskDecision.deny(DenialReason.MAX_OPEN_POSITIONS,
                    positions.openCount(account.userId()) + " open");
        }
        if (positions.pendingEntries(account.userId()).size() >= limits.maxPendingOrders()) {
            // A stalled fill must not let the engine queue the same idea repeatedly.
            return RiskDecision.deny(DenialReason.PENDING_ORDER_CAP,
                    positions.pendingEntries(account.userId()).size() + " unfilled entries");
        }
        if (positions.hasLiveInterest(account.userId(), intent.symbol())) {
            return RiskDecision.deny(DenialReason.ALREADY_IN_SYMBOL, intent.symbol());
        }
        var lastClosed = positions.lastClosedAt(account.userId(), intent.symbol());
        if (lastClosed.isPresent() && Duration.between(lastClosed.get(), intent.createdAt())
                .compareTo(Duration.ofSeconds(limits.cooldownSeconds())) < 0) {
            return RiskDecision.deny(DenialReason.SYMBOL_COOLDOWN,
                    "closed " + lastClosed.get() + ", cooldown " + limits.cooldownSeconds() + "s");
        }

        // ── Is the market offering a price we can act on ─────────────────────
        if (!freshness.isFresh(intent.symbol(), Duration.ofSeconds(limits.maxStalenessSeconds()))) {
            var age = freshness.age(intent.symbol());
            return RiskDecision.deny(DenialReason.STALE_PRICE,
                    age == null ? "never ticked" : age.toSeconds() + "s old");
        }
        if (lastTick == null) {
            return RiskDecision.deny(DenialReason.FEED_DOWN, "no tick for " + intent.symbol());
        }
        if (!lastTick.hasDepth()) {
            // Design note 0.10: QUOTE mode has no book, and a spread check that assumes zero is
            // worse than no spread check, because it always passes.
            return RiskDecision.deny(DenialReason.NO_DEPTH, "not in FULL mode");
        }
        double spread = lastTick.spreadPercent();
        if (spread > limits.maxSpreadPercent()) {
            return RiskDecision.deny(DenialReason.SPREAD_TOO_WIDE,
                    String.format("%.3f%% > %.3f%%", spread, limits.maxSpreadPercent()));
        }
        if (state.suspectCorporateAction(account.thresholds().sanityBandPercent())) {
            return RiskDecision.deny(DenialReason.SUSPECT_CORPORATE_ACTION,
                    String.format("%.1f%% day change", state.changeFromPreviousClosePercent()));
        }
        // ── Does the trade fit the budget ────────────────────────────────────
        double stopPercent = intent.stopDistancePercent();
        if (stopPercent < limits.minStopPercent()) {
            // A stop inside the noise gets hit by the spread, not by the thesis being wrong.
            return RiskDecision.deny(DenialReason.STOP_TOO_TIGHT,
                    String.format("%.3f%% < %.3f%%", stopPercent, limits.minStopPercent()));
        }
        if (stopPercent > limits.maxStopPercent()) {
            return RiskDecision.deny(DenialReason.STOP_TOO_WIDE,
                    String.format("%.3f%% > %.3f%%", stopPercent, limits.maxStopPercent()));
        }

        double riskPerShare = intent.riskPerShare();
        int quantity = (int) Math.floor(limits.riskPerTradeRupees() / riskPerShare);
        if (quantity <= 0) {
            return RiskDecision.deny(DenialReason.SIZE_ROUNDS_TO_ZERO,
                    String.format("risk/share %.2f exceeds the %.0f budget",
                            riskPerShare, limits.riskPerTradeRupees()));
        }

        // Trim to the notional cap rather than refusing: a smaller position at the same stop is
        // still the trade that was signalled, just a less concentrated one.
        int byValue = (int) Math.floor(limits.maxPositionValue() / intent.referencePrice());
        if (byValue <= 0) {
            return RiskDecision.deny(DenialReason.POSITION_VALUE_CAP,
                    String.format("one share costs %.2f, cap is %.0f",
                            intent.referencePrice(), limits.maxPositionValue()));
        }
        quantity = Math.min(quantity, byValue);

        // Leverage decides whether the position can be *held*, never how big it is. Quantity was
        // already fixed above by the rupee risk budget and the stop distance, which is the whole
        // point of that sizing rule: the loss if the stop is hit is the same number whether the
        // broker asks for the full price or a fifth of it. Comparing the notional against cash here
        // was refusing entries the broker would have accepted — an intraday product only blocks a
        // fraction — and reporting it as INSUFFICIENT_MARGIN, which reads as a funding problem.
        double positionValue = quantity * intent.referencePrice();
        double blocked = requirements.requiredFor(
                intent.symbol(), ProductType.MIS, quantity, positionValue);
        if (blocked > availableMargin) {
            boolean known = requirements.isKnown(intent.symbol(), ProductType.MIS);
            return RiskDecision.deny(DenialReason.INSUFFICIENT_MARGIN, String.format(
                    "need %.0f, have %.0f (%s)", blocked, availableMargin,
                    known ? String.format("MIS margin on %.0f notional", positionValue)
                          : "leverage unknown, using the full notional"));
        }

        return RiskDecision.approve(quantity, intent.stopPrice(),
                quantity * riskPerShare, positionValue,
                String.format("%d shares, risking %.0f at a %.2f%% stop",
                        quantity, quantity * riskPerShare, stopPercent));
    }

    /**
     * Re-checks a position against the budget after it filled at a different price.
     *
     * <p>Design note 0.8. Sizing was authorised on an expected entry; a slipped fill silently
     * increases the rupees at risk, because the stop did not move with it. Beyond a tolerance the
     * position is no longer the one that was approved and should be closed rather than held on a
     * budget nobody agreed to.</p>
     *
     * @return true if the realised risk is still inside the budget
     */
    public boolean fillStillWithinBudget(UserAccount account, double riskAtStop) {
        double budget = account.limits().riskPerTradeRupees();
        return riskAtStop <= budget * 1.25;
    }
}
