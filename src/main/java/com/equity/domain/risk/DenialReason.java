package com.equity.domain.risk;

/**
 * Every way an entry can be refused.
 *
 * <p>An enum rather than a log message, because these get counted. A system that records only what
 * it traded cannot tell a gate that filters noise from one that blocks its best candidates — in a
 * sibling engine a run-up gate turned out to be doing exactly the latter, and it took a full chain
 * audit to see it, because the rejections had never been stored.</p>
 *
 * <p>Grouped by what a human should do about them: nothing, wait, or investigate.</p>
 */
public enum DenialReason {

    // ── The engine is deliberately not trading ────────────────────────────────
    TRADING_DISABLED,
    NOT_LIVE_MODE,
    USER_NOT_ACTIVE,
    USER_ENTRIES_DISABLED,
    OUTSIDE_ENTRY_WINDOW,
    STALE_EPOCH,

    // ── The account has had enough ────────────────────────────────────────────
    DAILY_LOSS_LATCHED,
    MAX_OPEN_POSITIONS,
    MAX_DAILY_ATTEMPTS,
    PENDING_ORDER_CAP,
    ALREADY_IN_SYMBOL,
    SYMBOL_COOLDOWN,

    // ── The market is not offering a tradeable price ──────────────────────────
    FEED_DOWN,
    STALE_PRICE,
    NO_DEPTH,
    SPREAD_TOO_WIDE,
    SUSPECT_CORPORATE_ACTION,

    // ── The trade does not fit the budget ─────────────────────────────────────
    STOP_TOO_TIGHT,
    STOP_TOO_WIDE,
    SIZE_ROUNDS_TO_ZERO,
    POSITION_VALUE_CAP,
    INSUFFICIENT_MARGIN,

    // ── Something went wrong ──────────────────────────────────────────────────
    BROKER_ERROR;

    /** True when the same candidate could pass later today without anything changing structurally. */
    public boolean isTransient() {
        return this == FEED_DOWN || this == STALE_PRICE || this == NO_DEPTH
                || this == SPREAD_TOO_WIDE || this == PENDING_ORDER_CAP
                || this == MAX_OPEN_POSITIONS || this == SYMBOL_COOLDOWN
                || this == INSUFFICIENT_MARGIN || this == BROKER_ERROR;
    }

    /**
     * True when this refusal cannot change for the rest of the session.
     *
     * <p>Most denials are momentary — a spread widens and narrows, margin is freed by an exit, a
     * halted user is resumed — so the setup is worth holding briefly and retrying. These two are
     * not. The attempt budget is spent for the day and the loss latch is deliberately irreversible,
     * so retrying either can only produce the same answer.</p>
     *
     * <p>It mattered on the first live day. The attempt cap was reached at 09:35, and armed setups
     * went on re-triggering every two minutes until the close — one stock 140 times — producing 264
     * intents where nine distinct opportunities existed, and burying the journal that was supposed
     * to explain the day.</p>
     */
    public boolean isTerminalForSession() {
        return this == MAX_DAILY_ATTEMPTS || this == DAILY_LOSS_LATCHED;
    }

    /**
     * True when the refusal is a full book rather than a judgement about this trade.
     *
     * <p>Distinct from {@link #isTerminalForSession()}: capacity comes back the moment a position
     * closes, so holding off for the day would be wrong, and retrying every two minutes is waste.
     * PARADEEP triggered eight times in one session and became a position none of them, because
     * every slot was occupied and the setup simply kept re-firing at the cap.</p>
     */
    public boolean isCapacityLimited() {
        return this == MAX_OPEN_POSITIONS || this == PENDING_ORDER_CAP;
    }
}
