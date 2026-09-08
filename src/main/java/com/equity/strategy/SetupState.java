package com.equity.strategy;

import com.equity.domain.momentum.EntryPattern;
import com.equity.domain.momentum.MomentumState;
import java.time.Instant;

/**
 * What one user's strategy currently believes about one symbol.
 *
 * <p>This is the per-(user × symbol) half of the two-tier design — the small half. The expensive
 * half, the market geometry, is computed once and shared; only these few fields multiply by the
 * number of users, which is what keeps 200 symbols × N users tractable (design note 0.4).</p>
 *
 * <p>Mutable and confined to the evaluation thread. It is never published to readers, so it does
 * not need the copy-on-write discipline that {@code SharedInstrumentState} has.</p>
 */
public class SetupState {

    private MomentumState state = MomentumState.IDLE;
    private EntryPattern pattern;

    /** The high the impulse leg reached. The pullback is measured against it. */
    private double impulseHigh;
    /** The low the pause held. Breaking it invalidates the setup, and it is where the stop goes. */
    private double structureLow;
    /** Price that must trade for the setup to become a trade. */
    private double triggerLevel;

    private int barsInPause;
    private int barsSinceArmed;
    private Instant enteredStateAt;
    private Instant cooldownUntil;
    /**
     * Earliest moment an armed setup may trigger again after an entry was abandoned.
     *
     * <p>Separate from {@link #cooldownUntil}, which suppresses rebuilding an invalidated setup.
     * This one suppresses re-firing a setup that is still perfectly valid but whose entry did not
     * get placed.</p>
     */
    private Instant retriggerAfter;
    /**
     * Whether the mandatory conditions held at the most recent candle close.
     *
     * <p>An armed setup is triggered by ticks, but the mandatory conditions are evaluated on candle
     * closes — and a failure there left the setup ARMED and perfectly triggerable. The tick path
     * checks only that it is armed, and the trigger-time recheck deliberately covers just the
     * price-derived conditions, because the rest cannot change between closes. Which is true, and
     * exactly why it was wrong to ignore them: the last close had already said no.</p>
     */
    /** True when the current hold-off is waiting on a free position slot rather than on time. */
    private boolean heldForCapacity;
    private boolean mandatoryOk = true;
    private String mandatoryFailure = "";

    public MomentumState state()        { return state; }
    public EntryPattern pattern()       { return pattern; }
    public double impulseHigh()         { return impulseHigh; }
    public double structureLow()        { return structureLow; }
    public double triggerLevel()        { return triggerLevel; }
    public int barsInPause()            { return barsInPause; }
    public int barsSinceArmed()         { return barsSinceArmed; }
    public Instant enteredStateAt()     { return enteredStateAt; }
    public Instant cooldownUntil()      { return cooldownUntil; }
    public Instant retriggerAfter()     { return retriggerAfter; }
    public boolean mandatoryOk()        { return mandatoryOk; }
    public String mandatoryFailure()    { return mandatoryFailure; }

    /** Records the mandatory verdict from a candle close, for the tick path to honour. */
    public void recordMandatory(boolean ok, String failure) {
        this.mandatoryOk = ok;
        this.mandatoryFailure = ok ? "" : failure;
    }

    /**
     * Re-arms after an entry that was authorised but never became a position.
     *
     * <p>The setup itself is still valid — the price is above the trigger and the structure holds —
     * so returning it to ARMED is right. Returning it to ARMED <b>and nothing else</b> was not: the
     * next tick is still above the trigger, so it fires again immediately, and again, for as long as
     * the price stays there. On the first live morning that submitted twelve orders for one stock in
     * under seven seconds, stopped only by the daily attempt cap, and then produced five hundred
     * further intents that the cap refused.</p>
     *
     * <p>The hold-off makes a repeat deliberate rather than automatic. Whatever refused the entry —
     * a wide spread, a margin shortfall, a broker that will not accept orders at all — is a
     * condition that needs time to change, and retrying it hundreds of times a second cannot help.</p>
     */
    public void holdOffUntil(Instant until, Instant at) {
        holdOffUntil(until, at, false);
    }

    public void holdOffUntil(Instant until, Instant at, boolean forCapacity) {
        this.retriggerAfter = until;
        this.heldForCapacity = forCapacity;
        moveTo(MomentumState.ARMED, at);
    }

    /**
     * Lifts a hold-off that was only waiting for a position slot.
     *
     * <p>Leaves every other hold-off alone. A setup held off because the broker refused it, or
     * because the attempt budget is spent, is waiting on something a closing position does not
     * change.</p>
     */
    public boolean releaseCapacityHold() {
        if (!heldForCapacity) return false;
        heldForCapacity = false;
        retriggerAfter = null;
        return true;
    }

    /** True while a re-trigger is suppressed after an abandoned entry. */
    public boolean inRetriggerHoldOff(Instant now) {
        return retriggerAfter != null && now.isBefore(retriggerAfter);
    }

    public void moveTo(MomentumState next, Instant at) {
        if (this.state != next) {
            this.state = next;
            this.enteredStateAt = at;
            if (next == MomentumState.ARMED) barsSinceArmed = 0;
        }
    }

    public void beginImpulse(double high, Instant at) {
        this.impulseHigh = high;
        this.barsInPause = 0;
        moveTo(MomentumState.IMPULSE, at);
    }

    public void beginPause(MomentumState pauseState, EntryPattern pattern,
                           double structureLow, Instant at) {
        this.pattern = pattern;
        this.structureLow = structureLow;
        this.barsInPause = 1;
        moveTo(pauseState, at);
    }

    public void extendPause(double structureLow) {
        this.barsInPause++;
        // The invalidation level only ratchets upward. A pause that keeps making lower lows is not
        // a pause, and letting the level follow it down would keep the setup alive indefinitely.
        if (structureLow > this.structureLow) this.structureLow = structureLow;
    }

    public void arm(double triggerLevel, Instant at) {
        this.triggerLevel = triggerLevel;
        moveTo(MomentumState.ARMED, at);
    }

    public void countArmedBar() { barsSinceArmed++; }

    public void invalidate(Instant at, Instant cooldownUntil) {
        this.impulseHigh = 0;
        this.structureLow = 0;
        this.triggerLevel = 0;
        this.barsInPause = 0;
        this.barsSinceArmed = 0;
        this.pattern = null;
        this.cooldownUntil = cooldownUntil;
        moveTo(MomentumState.IDLE, at);
    }

    public boolean inCooldown(Instant now) {
        return cooldownUntil != null && now.isBefore(cooldownUntil);
    }

    public void startCooldown(Instant until) { this.cooldownUntil = until; }

    public boolean isArmed() { return state == MomentumState.ARMED; }
}
