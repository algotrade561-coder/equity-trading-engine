package com.equity.strategy;

import com.equity.domain.Direction;
import com.equity.domain.market.Candle;
import com.equity.domain.market.SharedInstrumentState;
import com.equity.domain.market.Tick;
import com.equity.domain.momentum.EntryPattern;
import com.equity.domain.momentum.MomentumState;
import com.equity.domain.momentum.RejectionStage;
import com.equity.domain.order.TradeIntent;
import com.equity.domain.user.UserId;
import com.equity.platform.time.TradingClock;
import com.equity.user.UserAccount;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Long-only intraday continuation.
 *
 * <p>The thesis in one line: a stock that is strongly up on the day, leading the market, thrusts on
 * volume, pauses without giving the thrust back, and then resumes. The engine joins the resumption,
 * with the stop where the pause is proven wrong.</p>
 *
 * <h2>How a decision is made</h2>
 * <p>Three separate gates, evaluated in order, each one a named condition rather than a score:</p>
 * <ol>
 *   <li><b>Mandatory</b> — properties that must hold at every moment. Failure invalidates the setup
 *       outright, at whatever stage it had reached.</li>
 *   <li><b>Setup</b> — the state machine IDLE → IMPULSE → PULLBACK/CONSOLIDATION → ARMED, advanced
 *       only by <b>completed</b> 1-minute candles. A forming bar can still change, and acting on one
 *       is a standard source of live/replay divergence.</li>
 *   <li><b>Trigger</b> — ARMED → TRIGGERED, driven by <b>live ticks</b>, because waiting for a
 *       candle close to notice a breakout gives up most of the move being traded.</li>
 * </ol>
 *
 * <p>SHORT is deliberately absent rather than disabled. Every comparison here is written for the
 * long side only, and a flag claiming SHORT support while the arithmetic assumes upward moves would
 * be worse than an honest gap.</p>
 */
@Component
public class MomentumStrategy {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(MomentumStrategy.class);

    /** An armed setup that has not triggered within this many bars has gone stale. */
    private static final int MAX_BARS_ARMED = 10;
    /** After an invalidation, wait before rebuilding a setup on the same symbol. */
    private static final Duration INVALIDATION_COOLDOWN = Duration.ofMinutes(3);
    /**
     * After an authorised entry fails to become a position, how long before the same setup may
     * trigger again. Whatever refused it needs time to change; retrying on the next tick cannot help.
     */
    private static final Duration ENTRY_RETRY_HOLD_OFF = Duration.ofMinutes(2);
    /**
     * Long enough to outlast any trading session, for refusals that cannot change until tomorrow.
     * Setups are per-process and discarded at shutdown, so nothing carries this into the next day.
     */
    private static final Duration REST_OF_SESSION = Duration.ofHours(12);

    private final TradingClock clock;
    private final Map<String, SetupState> setups = new ConcurrentHashMap<>();

    public MomentumStrategy(TradingClock clock) {
        this.clock = clock;
    }

    public SetupState setupFor(UserId userId, String symbol) {
        return setups.computeIfAbsent(key(userId, symbol), k -> new SetupState());
    }

    public int trackedSetups() { return setups.size(); }

    /** Everything this user is currently watching closely enough to have a state for. */
    public Map<String, SetupState> setupsFor(UserId userId) {
        String prefix = userId + "|";
        Map<String, SetupState> out = new java.util.LinkedHashMap<>();
        setups.forEach((k, v) -> {
            if (k.startsWith(prefix) && v.state() != MomentumState.IDLE) {
                out.put(k.substring(prefix.length()), v);
            }
        });
        return out;
    }

    // ── Candle path: advance the setup ───────────────────────────────────────

    /**
     * Advance the setup on a completed 1-minute candle.
     *
     * @param minutes completed 1m history for the symbol, oldest first
     */
    public StrategySignal onCandleClosed(UserAccount account, SharedInstrumentState state,
                                         List<Candle> minutes) {
        StrategyThresholds t = account.thresholds();
        SetupState setup = setupFor(account.userId(), state.symbol());
        java.time.Instant now = clock.now();

        // History first. The mandatory conditions read VWAP, the EMAs and relative strength, and
        // those are NaN until enough bars exist — which made every symbol at the start of a session
        // report "emaNotStacked" or "belowVwap" when the truth was "we do not know yet". A rejection
        // log whose reasons are wrong is worse than none, because it gets believed.
        if (minutes.size() < 20) {
            return StrategySignal.reject(RejectionStage.DISCOVERY, "insufficientHistory",
                    minutes.size() + " completed minutes");
        }

        // Aged here rather than inside the state machine. Down there it sat after the mandatory
        // check, which returns early — so a setup whose mandatory conditions were failing never
        // aged at all, and one was still ARMED fourteen bars past a ten-bar limit.
        if (setup.state() == MomentumState.ARMED) {
            setup.countArmedBar();
            if (setup.barsSinceArmed() > MAX_BARS_ARMED) {
                invalidate(setup, now);
                return StrategySignal.reject(RejectionStage.ARMING, "armedTooLong",
                        setup.barsSinceArmed() + " bars without a trigger");
            }
        }

        StrategySignal mandatory = checkMandatory(state, t, setup, now);
        // Remembered for the tick path. Without this an armed setup stayed triggerable while the
        // most recent close had already refused it on a mandatory condition.
        setup.recordMandatory(!mandatory.isRejected(),
                mandatory.isRejected() ? mandatory.condition() + ": " + mandatory.detail() : "");
        if (mandatory.isRejected()) return mandatory;

        if (setup.inCooldown(now)) {
            return StrategySignal.reject(RejectionStage.DISCOVERY, "symbolCooldown",
                    "invalidated recently, waiting before rebuilding");
        }

        return switch (setup.state()) {
            case IDLE -> tryImpulse(state, t, setup, minutes, now);
            case IMPULSE -> tryPause(state, t, setup, minutes, now);
            case PULLBACK, CONSOLIDATION -> tryArm(state, t, setup, minutes, now);
            case ARMED -> holdArmed(state, setup, now);
            default -> StrategySignal.NOTHING;   // the position lifecycle owns the rest
        };
    }

    /**
     * Conditions that must hold continuously. Checked before the state machine, so a setup cannot
     * survive on structure that has already broken.
     */
    private StrategySignal checkMandatory(SharedInstrumentState s, StrategyThresholds t,
                                          SetupState setup, java.time.Instant now) {
        if (s.previousClose() <= 0) {
            return StrategySignal.reject(RejectionStage.UNIVERSE, "noPreviousClose",
                    "day change cannot be computed");
        }
        if (s.suspectCorporateAction(t.sanityBandPercent())) {
            // Design note 0.9: an unadjusted split shows as a huge day change and would otherwise
            // rank as the strongest gainer on the board.
            invalidate(setup, now);
            return StrategySignal.reject(RejectionStage.UNIVERSE, "suspectCorporateAction",
                    String.format("%.1f%% day change", s.changeFromPreviousClosePercent()));
        }
        // Even with enough bars, an indicator can still be NaN — a symbol that started ticking late,
        // or a gap in the 1m series. Failing a condition on a value that does not exist would count
        // as evidence against the stock; saying the indicator is not ready is the honest answer.
        if (Double.isNaN(s.vwap()) || Double.isNaN(s.ema9()) || Double.isNaN(s.ema20())
                || (t.requireOutperformIndex() && Double.isNaN(s.niftyRelativeStrength()))) {
            return StrategySignal.reject(RejectionStage.DISCOVERY, "indicatorsNotReady",
                    String.format("vwap=%.2f ema9=%.2f ema20=%.2f rs=%.2f",
                            s.vwap(), s.ema9(), s.ema20(), s.niftyRelativeStrength()));
        }

        double change = s.changeFromPreviousClosePercent();
        if (change < t.minDayChangePercent()) {
            return StrategySignal.reject(RejectionStage.DISCOVERY, "dayChangeTooSmall",
                    String.format("%.2f%% < %.2f%%", change, t.minDayChangePercent()));
        }
        if (change > t.maxDayChangePercent()) {
            return StrategySignal.reject(RejectionStage.DISCOVERY, "dayChangeExtended",
                    String.format("%.2f%% > %.2f%%", change, t.maxDayChangePercent()));
        }
        if (t.requireAboveVwap() && !s.aboveVwap()) {
            invalidate(setup, now);
            return StrategySignal.reject(RejectionStage.DISCOVERY, "belowVwap",
                    String.format("%.2f vs vwap %.2f", s.lastPrice(), s.vwap()));
        }
        if (t.requireEmaStack() && !(s.ema9() > s.ema20())) {
            return StrategySignal.reject(RejectionStage.DISCOVERY, "emaNotStacked",
                    String.format("ema9 %.2f <= ema20 %.2f", s.ema9(), s.ema20()));
        }
        if (t.requireOutperformIndex() && !s.outperformingNifty()) {
            return StrategySignal.reject(RejectionStage.DISCOVERY, "notOutperformingIndex",
                    String.format("rs %.2f", s.niftyRelativeStrength()));
        }
        if (s.distanceFromDayHighPercent() > t.maxDistanceFromHighPct()) {
            return StrategySignal.reject(RejectionStage.DISCOVERY, "farFromDayHigh",
                    String.format("%.2f%% below high", s.distanceFromDayHighPercent()));
        }
        LocalTime time = clock.timeOfDay();
        if (time.isBefore(t.entryWindowStart()) || time.isAfter(t.entryWindowEnd())) {
            return StrategySignal.reject(RejectionStage.DISCOVERY, "outsideEntryWindow",
                    time.withNano(0).toString());
        }
        return StrategySignal.NOTHING;
    }

    private StrategySignal tryImpulse(SharedInstrumentState s, StrategyThresholds t,
                                      SetupState setup, List<Candle> minutes, java.time.Instant now) {
        if (!(s.return5m() >= t.minImpulseReturn5m())) {
            return StrategySignal.reject(RejectionStage.IMPULSE, "thrustTooWeak",
                    String.format("%.2f%% over 5m < %.2f%%", s.return5m(), t.minImpulseReturn5m()));
        }
        // Volume is the difference between a thrust and a drift. A move on no participation is the
        // one most likely to hand itself straight back.
        if (!(s.relativeVolume() >= t.minRelativeVolume())) {
            return StrategySignal.reject(RejectionStage.IMPULSE, "noVolumeBehindThrust",
                    String.format("rvol %.2f < %.2f", s.relativeVolume(), t.minRelativeVolume()));
        }
        double high = highOfLast(minutes, 5);
        setup.beginImpulse(high, now);
        return StrategySignal.NOTHING;
    }

    private StrategySignal tryPause(SharedInstrumentState s, StrategyThresholds t,
                                    SetupState setup, List<Candle> minutes, java.time.Instant now) {
        Candle last = minutes.get(minutes.size() - 1);
        double impulseHigh = Math.max(setup.impulseHigh(), last.high());
        double retracement = impulseHigh > 0 ? (impulseHigh - last.low()) / impulseHigh * 100.0 : 0;

        if (retracement > t.maxPullbackPercent()) {
            invalidate(setup, now);
            return StrategySignal.reject(RejectionStage.PULLBACK, "retracedTooDeep",
                    String.format("%.2f%% > %.2f%%", retracement, t.maxPullbackPercent()));
        }
        double range = rangeOfLast(minutes, t.minConsolidationBars());
        if (range <= t.maxConsolidationRangePct()) {
            setup.beginPause(MomentumState.CONSOLIDATION, EntryPattern.CONSOLIDATION_BREAKOUT,
                    lowOfLast(minutes, t.minConsolidationBars()), now);
            return StrategySignal.NOTHING;
        }
        if (retracement >= t.minPullbackPercent()) {
            setup.beginPause(MomentumState.PULLBACK, EntryPattern.PULLBACK_CONTINUATION,
                    last.low(), now);
            return StrategySignal.NOTHING;
        }
        // Still extending. Carry the higher high forward rather than dropping the setup.
        setup.beginImpulse(impulseHigh, now);
        return StrategySignal.NOTHING;
    }

    private StrategySignal tryArm(SharedInstrumentState s, StrategyThresholds t,
                                  SetupState setup, List<Candle> minutes, java.time.Instant now) {
        Candle last = minutes.get(minutes.size() - 1);

        if (last.low() < setup.structureLow()) {
            // Captured before invalidating, which zeroes it. Reading it afterwards made every one
            // of these read "x < 0.00" — cosmetic in a log, but it destroyed the one number that
            // says how far the pause actually broke, which is what the journal exists to keep.
            double brokenLow = setup.structureLow();
            invalidate(setup, now);
            return StrategySignal.reject(RejectionStage.CONSOLIDATION, "pauseLowBroken",
                    String.format("%.2f < %.2f", last.low(), brokenLow));
        }
        setup.extendPause(last.low());

        if (setup.barsInPause() < t.minConsolidationBars()) {
            return StrategySignal.reject(RejectionStage.CONSOLIDATION, "pauseTooShort",
                    setup.barsInPause() + " of " + t.minConsolidationBars() + " bars");
        }
        double pauseHigh = highOfLast(minutes, setup.barsInPause());
        double trigger = pauseHigh * (1 + t.triggerBufferPercent() / 100.0);
        setup.arm(trigger, now);
        return StrategySignal.NOTHING;
    }

    /**
     * Nothing to do but wait for a tick through the trigger.
     *
     * <p>Ageing used to live here. It was moved ahead of the mandatory check, which returns early:
     * a setup failing a mandatory condition never reached this method, so it never aged and could
     * sit armed indefinitely on a level set long before.</p>
     */
    private StrategySignal holdArmed(SharedInstrumentState s, SetupState setup, java.time.Instant now) {
        return StrategySignal.NOTHING;
    }

    // ── Tick path: the trigger ───────────────────────────────────────────────

    /**
     * Checks a live tick against an armed setup.
     *
     * <p>Tick-driven on purpose. The breakout is the entry, and waiting up to 59 seconds for a
     * candle to close before noticing it gives away the part of the move being traded.</p>
     */
    public StrategySignal onTick(UserAccount account, SharedInstrumentState state, Tick tick) {
        SetupState setup = setupFor(account.userId(), state.symbol());
        if (!setup.isArmed()) return StrategySignal.NOTHING;

        // Silence, not a rejection: this setup already produced an intent that went nowhere, and
        // counting every suppressed tick would swamp the rejection log with one symbol.
        if (setup.inRetriggerHoldOff(clock.now())) return StrategySignal.NOTHING;

        // The most recent close refused this stock on a condition that cannot change between
        // closes — relative strength and the EMA pair are computed from completed candles. The
        // trigger-time recheck below covers only the price-derived ones, correctly, which is
        // precisely why the rest have to be honoured from the close that evaluated them.
        if (!setup.mandatoryOk()) {
            return StrategySignal.reject(RejectionStage.TRIGGER, "mandatoryFailedAtLastClose",
                    setup.mandatoryFailure());
        }

        if (tick.lastPrice() < setup.triggerLevel()) {
            return StrategySignal.NOTHING;   // not yet — silence, not a rejection
        }
        StrategyThresholds t = account.thresholds();

        // The mandatory conditions were checked at the last candle close, up to a minute ago. Three
        // of them are derived from the live price and move in between — and one moves in exactly the
        // wrong direction: the breakout that triggers this entry is itself pushing the day change
        // up, so a stock can cross from acceptable to over-extended in the very move being joined.
        StrategySignal stillValid = recheckAtTrigger(state, t, setup);
        if (stillValid.isRejected()) return stillValid;

        double entry = tick.lastPrice();
        double stop = setup.structureLow();

        // The structure low says where the setup is wrong; ATR says how much noise this stock makes
        // in a minute. Take whichever is further, so a pause that happened to be unusually tight
        // does not put the stop inside the instrument's own jitter. It can only ever WIDEN the stop,
        // which reduces size — the rupee risk is unchanged, the chance of being shaken out is not.
        if (state.atr() > 0 && !Double.isNaN(state.atr())) {
            stop = Math.min(stop, entry - t.stopAtrMultiple() * state.atr());
        }

        if (!(stop > 0) || stop >= entry) {
            return StrategySignal.reject(RejectionStage.TRIGGER, "noValidStopLevel",
                    String.format("structure low %.2f vs entry %.2f", stop, entry));
        }

        double target = entry + (entry - stop) * t.targetRMultiple();
        setup.moveTo(MomentumState.TRIGGERED, clock.now());

        TradeIntent intent = new TradeIntent(
                account.userId(), state.symbol(), Direction.LONG, setup.pattern(),
                entry, stop, target, clock.now(),
                String.format("%s: trigger %.2f, pause low %.2f, %.2f%% on the day",
                        setup.pattern(), setup.triggerLevel(), stop,
                        state.changeFromPreviousClosePercent()));

        return StrategySignal.intent(intent);
    }

    /**
     * Re-evaluates the price-derived mandatory conditions at the moment of the trigger.
     *
     * <p>Only the ones that actually move between candle closes. VWAP, the EMAs and relative
     * strength are recomputed on a completed bar and are identical to what the candle path already
     * checked, so re-testing them would cost work and change nothing; {@code lastPrice},
     * {@code dayHigh} and {@code dayLow} are updated on every tick and can have moved.</p>
     *
     * <p>Rejections here are named separately from their candle-path equivalents so the counts stay
     * interpretable — "this failed a minute after it passed" is a different fact from "this never
     * passed", and collapsing them would hide how often a setup goes stale before it fires.</p>
     */
    private StrategySignal recheckAtTrigger(SharedInstrumentState s, StrategyThresholds t,
                                            SetupState setup) {
        if (s.suspectCorporateAction(t.sanityBandPercent())) {
            invalidate(setup, clock.now());
            return StrategySignal.reject(RejectionStage.TRIGGER, "suspectCorporateActionAtTrigger",
                    String.format("%.1f%% day change", s.changeFromPreviousClosePercent()));
        }
        double change = s.changeFromPreviousClosePercent();
        if (change > t.maxDayChangePercent()) {
            return StrategySignal.reject(RejectionStage.TRIGGER, "dayChangeExtendedAtTrigger",
                    String.format("%.2f%% > %.2f%% by the time it triggered",
                            change, t.maxDayChangePercent()));
        }
        if (change < t.minDayChangePercent()) {
            return StrategySignal.reject(RejectionStage.TRIGGER, "dayChangeTooSmallAtTrigger",
                    String.format("%.2f%% < %.2f%%", change, t.minDayChangePercent()));
        }
        if (t.requireAboveVwap() && !s.aboveVwap()) {
            return StrategySignal.reject(RejectionStage.TRIGGER, "belowVwapAtTrigger",
                    String.format("%.2f vs vwap %.2f", s.lastPrice(), s.vwap()));
        }
        return StrategySignal.NOTHING;
    }

    /** Called by the position lifecycle when a trade closes, so the symbol is not re-entered at once. */
    public void onPositionClosed(UserId userId, String symbol, Duration cooldown) {
        SetupState setup = setupFor(userId, symbol);
        setup.invalidate(clock.now(), clock.now().plus(cooldown));
    }

    /**
     * Called when a disarmed user's setup triggered and was recorded but not traded.
     *
     * <p>The setup is consumed exactly as a real entry would consume it. Without this the setup
     * would sit at TRIGGERED and either stick there forever or re-fire on every subsequent tick,
     * and a shadow record that counts one setup a thousand times is worse than no record.</p>
     */
    public void onShadowIntent(UserId userId, String symbol, Duration cooldown) {
        setupFor(userId, symbol).invalidate(clock.now(), clock.now().plus(cooldown));
    }

    /** Called when an entry attempt did not become a position, so the setup can be tried again. */
    public void onEntryAbandoned(UserId userId, String symbol) {
        onEntryAbandoned(userId, symbol, false);
    }

    /**
     * @param forTheSession true when the refusal cannot change before tomorrow, in which case the
     *                      setup is held off for the rest of the day rather than for two minutes.
     *                      Retrying a spent attempt budget or a latched loss can only ever produce
     *                      the same answer, and doing so every two minutes until the close is how
     *                      nine real opportunities were recorded as two hundred and sixty-four.
     */
    public void onEntryAbandoned(UserId userId, String symbol, boolean forTheSession) {
        java.time.Instant now = clock.now();
        Duration holdOff = forTheSession ? REST_OF_SESSION : ENTRY_RETRY_HOLD_OFF;
        setupFor(userId, symbol).holdOffUntil(now.plus(holdOff), now);
    }

    /**
     * Holds a setup off until a position slot frees, rather than for a fixed time.
     *
     * <p>A full book is not a judgement about this trade, so retrying it every two minutes only
     * produces noise: one symbol triggered eight times in a session and became a position none of
     * them, because every slot was occupied the whole time. The hold is lifted by
     * {@link #onCapacityFreed}, not by the clock.</p>
     */
    public void onEntryDeferredForCapacity(UserId userId, String symbol) {
        java.time.Instant now = clock.now();
        setupFor(userId, symbol).holdOffUntil(now.plus(REST_OF_SESSION), now, true);
    }

    /**
     * A position closed, so anything waiting only on capacity may trigger again.
     *
     * <p>Released immediately rather than after a delay: the setup was valid when it was deferred
     * and the only thing that had changed is now undone.</p>
     */
    public void onCapacityFreed(UserId userId) {
        String prefix = userId + "|";
        setups.forEach((key, setup) -> {
            if (key.startsWith(prefix) && setup.releaseCapacityHold()) {
                log.debug("released the capacity hold on {}", key.substring(prefix.length()));
            }
        });
    }

    private void invalidate(SetupState setup, java.time.Instant now) {
        setup.invalidate(now, now.plus(INVALIDATION_COOLDOWN));
    }

    private static String key(UserId userId, String symbol) { return userId + "|" + symbol; }

    private static double highOfLast(List<Candle> candles, int n) {
        return lastN(candles, n).stream().mapToDouble(Candle::high).max().orElse(0);
    }

    private static double lowOfLast(List<Candle> candles, int n) {
        return lastN(candles, n).stream().mapToDouble(Candle::low).min().orElse(0);
    }

    /** Range of the last n bars as a percentage of their low. */
    private static double rangeOfLast(List<Candle> candles, int n) {
        List<Candle> window = lastN(candles, n);
        if (window.size() < n) return Double.MAX_VALUE;
        double high = window.stream().mapToDouble(Candle::high).max().orElse(0);
        double low = window.stream().mapToDouble(Candle::low).min().orElse(0);
        return low > 0 ? (high - low) / low * 100.0 : Double.MAX_VALUE;
    }

    private static List<Candle> lastN(List<Candle> candles, int n) {
        int from = Math.max(0, candles.size() - Math.max(1, n));
        return candles.subList(from, candles.size());
    }
}
