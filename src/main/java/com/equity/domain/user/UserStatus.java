package com.equity.domain.user;

public enum UserStatus {
    /** Normal operation. Entries may be enabled. */
    ACTIVE,
    /** New entries blocked; existing positions continue to be managed normally. */
    TRADING_PAUSED,
    /** Emergency stop. New entries blocked, pending entries cancelled, epoch bumped. */
    HALTED,
    /** Account switched off entirely. */
    DISABLED
}
