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
 *
 * <h2>The sequence must not repeat within a day</h2>
 * <p>Everything above depends on a tag identifying <b>one</b> order. It did not: the counter was a
 * static field starting at zero, so a restart reissued numbers the day had already used, and a fill
 * was matched to the wrong stock. See {@link #resumeAfter(long)}. A tag that can repeat is not an
 * idempotency handle, it is a coincidence.</p>
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

    /**
     * Continues the day's numbering rather than starting again from one.
     *
     * <p>The sequence used to live only in the static counter above, so every restart began again at
     * {@code 000001}. Three restarts in one live session minted that same tag for ACUTAAS, ZFCVINDIA
     * and PNBHOUSING; the broker echoed it back on PNBHOUSING's fill, the engine matched it to the
     * stale ZFCVINDIA position, and PNBHOUSING was left holding 170 shares that nothing was watching
     * because its own row never recorded a fill.</p>
     *
     * <p>Called once at startup with the highest sequence already on today's orders. Only ever moves
     * forward — a lower value is ignored, so a partial or out-of-order restore cannot walk the
     * counter backwards into ground it has already used.</p>
     *
     * @param used the highest sequence seen in today's tags; anything below the current value is
     *             ignored rather than applied
     */
    public static void resumeAfter(long used) {
        SEQUENCE.updateAndGet(current -> Math.max(current, used));
    }

    /** The sequence this process would issue next. For diagnostics and for tests. */
    public static long currentSequence() { return SEQUENCE.get(); }

    /**
     * The numeric sequence inside a tag, or -1 if it does not carry one.
     *
     * <p>Parsed rather than stored because the tag is the only thing the broker echoes back, so it
     * has to be self-describing on recovery.</p>
     */
    public long sequence() {
        int dash = value.indexOf('-');
        if (dash < 0 || dash + 1 >= value.length()) return -1;
        try {
            return Long.parseLong(value.substring(dash + 1));
        } catch (NumberFormatException e) {
            return -1;
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
