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

    public MomentumState state()        { return state; }
    public EntryPattern pattern()       { return pattern; }
    public double impulseHigh()         { return impulseHigh; }
    public double structureLow()        { return structureLow; }
    public double triggerLevel()        { return triggerLevel; }
    public int barsInPause()            { return barsInPause; }
    public int barsSinceArmed()         { return barsSinceArmed; }
    public Instant enteredStateAt()     { return enteredStateAt; }
    public Instant cooldownUntil()      { return cooldownUntil; }

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
