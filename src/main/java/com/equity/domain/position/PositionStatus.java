package com.equity.domain.position;

/**
 * Where a position is in its life.
 *
 * <p>Separate from {@link com.equity.domain.momentum.MomentumState}, which is the strategy's view.
 * The two must not be merged: the strategy can believe a trade is over while the broker still holds
 * shares, and it is the broker's view that decides whether money is at risk.</p>
 */
public enum PositionStatus {
    /** Entry order sent, no fill yet. Capital is committed but no shares are held. */
    PENDING_ENTRY,
    /** Shares held. */
    OPEN,
    /** An exit order is live. No further exit may be raised for this position. */
    EXIT_PENDING,
    CLOSED,
    /**
     * The engine lost track of it — an entry that neither filled nor cancelled cleanly. Never
     * silently reclassified: an abandoned position is a human's problem, and pretending it is
     * closed is how a real holding becomes invisible.
     */
    ABANDONED;

    public boolean hasExposure() { return this == OPEN || this == EXIT_PENDING; }

    public boolean isFinished()  { return this == CLOSED || this == ABANDONED; }
}
