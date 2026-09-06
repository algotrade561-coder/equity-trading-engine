package com.equity.domain.order;

import com.equity.domain.user.UserId;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The idempotency handle carried on every order and echoed back by the broker.
 *
 * <p>Kite allows 20 characters and truncates anything longer <b>silently</b>, so the format is built
 * to fit rather than trimmed to fit: {@code <kind><user8>-<seq>} — for example
 * {@code e7ab13718-000042}. That is 16 characters with a six-digit sequence, leaving headroom.</p>
 *
 * <p>It encodes the user because reconciliation after a restart has only the broker's order list to
 * work from, and an order whose owner cannot be identified is an order nobody can safely close.
 * The kind letter separates entries from exits for the same reason: on recovery, an untagged fill
 * could be read as either opening or closing a position, and those are opposite actions.</p>
 */
public record OrderTag(String value) {

    /** Kite's limit. Exceeding it is a silent truncation, not an error. */
    public static final int MAX_LENGTH = 20;

    private static final AtomicLong SEQUENCE = new AtomicLong();

    public OrderTag {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("tag required");
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("tag longer than the " + MAX_LENGTH
                    + " characters the broker accepts: " + value);
        }
    }

    public static OrderTag forEntry(UserId userId) { return build('e', userId); }

    public static OrderTag forExit(UserId userId)  { return build('x', userId); }

    private static OrderTag build(char kind, UserId userId) {
        String user = userId.toString().replace("-", "");
        return new OrderTag(String.format("%c%s-%06d",
                kind, user.substring(0, 8), SEQUENCE.incrementAndGet() % 1_000_000));
    }

    public boolean isEntry() { return value.charAt(0) == 'e'; }

    public boolean isExit()  { return value.charAt(0) == 'x'; }

    /** True if this tag was minted for the given user. Used when adopting orders found at the broker. */
    public boolean belongsTo(UserId userId) {
        String user = userId.toString().replace("-", "");
        return value.length() > 9 && value.startsWith(user.substring(0, 8), 1);
    }

    @Override
    public String toString() { return value; }
}
