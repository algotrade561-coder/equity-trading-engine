package com.equity.provisioning;

/**
 * Where a user's address is in its life.
 *
 * <p>The intermediate states exist because provisioning is four AWS calls and an OS command, any
 * of which can fail, and a row that says only "pending" or "active" cannot say which step to retry
 * or what to undo. Each step persists its state before the next begins, so a crash between two of
 * them leaves a row that describes exactly what was done.</p>
 */
public enum IpAllocationStatus {
    /** Row created; nothing done in AWS yet. */
    PENDING,
    /** Private IP assigned to the interface and added to the OS. No Elastic IP yet. */
    OS_CONFIGURED,
    /** Elastic IP allocated and associated. The user's address exists but is not yet their binding. */
    ASSOCIATED,
    /** Bound: the user's broker calls leave from this address. Trading needs the Kite step too. */
    ACTIVE,
    /** A step failed after compensation. {@code lastError} says which. Safe to retry. */
    FAILED,
    /** Teardown in progress. */
    RELEASING,
    /** Torn down. Kept for the audit trail; the addresses are gone. */
    RELEASED;

    public boolean isLive() { return this == ACTIVE; }
    public boolean isFinished() { return this == RELEASED; }
}
