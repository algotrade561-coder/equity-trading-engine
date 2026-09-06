package com.equity.broker.kite;

import com.equity.domain.user.UserId;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A logged-in Kite session for one user.
 *
 * <p>Kite access tokens are valid for a single trading day and are invalidated early each morning,
 * so the session carries the trading date it was obtained on. Treating a token as valid across a
 * date boundary produces a burst of 403s at the open, which is the worst possible moment to discover
 * that nobody is logged in.</p>
 *
 * @param kiteUserId the broker-side client code, kept for reconciliation against contract notes
 */
public record KiteSession(
        UserId userId,
        String kiteUserId,
        String accessToken,
        String publicToken,
        LocalDate tradingDate,
        Instant issuedAt) {

    public boolean isValidOn(LocalDate date) {
        return tradingDate != null && tradingDate.equals(date)
                && accessToken != null && !accessToken.isBlank();
    }

    /** Redacted. This record is logged on connect and must not carry the token into the log. */
    @Override
    public String toString() {
        return "KiteSession{userId=" + userId
                + ", kiteUserId=" + kiteUserId
                + ", accessToken=" + Redaction.mask(accessToken)
                + ", tradingDate=" + tradingDate + "}";
    }
}
