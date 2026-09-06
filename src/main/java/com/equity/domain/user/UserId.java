package com.equity.domain.user;

import java.util.UUID;

/**
 * Stable per-user identity. A value type rather than a bare UUID so that a method taking a
 * {@code UserId} cannot silently be handed a {@code SetupId} — tenant isolation starts at the
 * type level, before it reaches the repository.
 */
public record UserId(UUID value) {
    public UserId {
        if (value == null) throw new IllegalArgumentException("userId must not be null");
    }
    public static UserId of(String uuid) { return new UserId(UUID.fromString(uuid)); }
    public static UserId random()        { return new UserId(UUID.randomUUID()); }
    @Override public String toString()   { return value.toString(); }
}
