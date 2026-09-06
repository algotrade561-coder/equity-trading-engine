package com.equity.domain.risk;

/**
 * The answer risk gives about one candidate entry.
 *
 * <p>An approval carries the size, because sizing and permission are the same decision: "yes" with
 * no quantity would leave the caller to invent one, and a caller that can invent a quantity can
 * exceed the budget that approved it.</p>
 */
public record RiskDecision(
        boolean approved,
        DenialReason denialReason,
        int quantity,
        double stopPrice,
        double riskRupees,
        double positionValue,
        String note) {

    public static RiskDecision approve(int quantity, double stopPrice,
                                       double riskRupees, double positionValue, String note) {
        return new RiskDecision(true, null, quantity, stopPrice, riskRupees, positionValue, note);
    }

    public static RiskDecision deny(DenialReason reason, String note) {
        return new RiskDecision(false, reason, 0, 0, 0, 0, note);
    }

    public static RiskDecision deny(DenialReason reason) {
        return deny(reason, "");
    }
}
