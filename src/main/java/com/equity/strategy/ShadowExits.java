package com.equity.strategy;

import com.equity.domain.position.Position;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What every other exit policy would have done to the trades that were actually taken.
 *
 * <h2>Why this exists</h2>
 * <p>Several exit changes were proposed in the first weeks of live trading and two had to be
 * reversed — not because the analysis was careless but because twenty trades cannot separate a good
 * rule from a lucky one, and each recommendation was measured against a base configuration that had
 * since moved. Breakeven at 1R was clearly correct while the stop was tight and clearly wrong once
 * it widened. Replaying a fortnight and getting a different answer each time is not evidence; it is
 * noise with a decimal point.</p>
 *
 * <p>The fix is not a better replay. It is to run the candidate policies <b>alongside</b> the live
 * one, on the same entries, at the same prices, tick by tick — and record where each would have got
 * out. After a month that gives an exact counterfactual for every real trade: same fills, same
 * entries, perfectly paired, no survivorship. It costs no capital, places no orders and adds no
 * risk.</p>
 *
 * <p>It is the A/B that {@link ExitPolicy} describes in its own documentation — one policy run
 * against another, kept only if the difference survives — without needing a second account.</p>
 *
 * <h2>What it does not do</h2>
 * <p>It never touches a position, never places an order and never influences the live policy. It
 * reads the marks the live path has already updated and keeps a number in a map.</p>
 */
public class ShadowExits {

    /**
     * The candidates: two that protect early, two that give the trade room, and the fixed baseline.
     *
     * <p>Deliberately five. A long list would guarantee that one of them looks best on any sample,
     * which is precisely the failure this class exists to prevent — the more policies compared, the
     * larger the best one's margin from luck alone.</p>
     */
    private static final Map<String, ExitPolicy> CANDIDATES = new LinkedHashMap<>();

    static {
        CANDIDATES.put("fixed", ExitPolicy.fixed());
        CANDIDATES.put("be1R", ExitPolicy.breakevenAtOneR());
        CANDIDATES.put("trail1R_1.5atr", new ExitPolicy(false, 1.0, true, 1.0, 1.5, false));
        CANDIDATES.put("trail2R_2.5atr", new ExitPolicy(false, 1.0, true, 2.0, 2.5, false));
        CANDIDATES.put("be1R_trail2R", new ExitPolicy(true, 1.0, true, 2.0, 2.0, false));
    }

    /** Where each policy would have exited, per position. Removed when the position settles. */
    private final Map<UUID, Map<String, Double>> exits = new ConcurrentHashMap<>();

    /**
     * How far each position had already run the first time it was shadowed, in R.
     *
     * <p>Zero for a trade watched from its fill, which is every trade in a normal session. Non-zero
     * for one adopted at startup or reconciled in from the broker: the ticks that made the first
     * part of the move were never seen, so a shadow policy that would have exited during them
     * cannot know it. Recorded rather than discarded, because a row that says how much of the trade
     * it observed can be filtered later, and a silently truncated counterfactual cannot.</p>
     */
    private final Map<UUID, Double> firstSeenR = new ConcurrentHashMap<>();

    /**
     * Advances every shadow policy one tick.
     *
     * <p>Called from the tick path, so it does arithmetic and nothing else — the same
     * {@link StopAdjuster} the live policy uses, against a rewound copy. A policy that has already
     * exited is skipped, so the cost falls away as the day goes on.</p>
     *
     * @param marked the position with this tick's price already folded into both water marks, which
     *               is what the live path evaluates against — passing the unmarked one would judge
     *               the shadows against a high-water mark one tick out of date
     */
    public void onTick(Position marked, double lastPrice, double atr) {
        if (!marked.hasExposure() || !(marked.riskPerShare() > 0)) return;

        firstSeenR.computeIfAbsent(marked.id(), id -> marked.favourableExcursionR());
        Map<String, Double> recorded = exits.computeIfAbsent(marked.id(), id -> new ConcurrentHashMap<>());
        if (recorded.size() == CANDIDATES.size()) return;         // every policy is already out

        // The live stop may have been tightened already; a shadow measured against it would be
        // inheriting the live answer rather than producing its own.
        Position base = marked.atOriginalStop();

        for (Map.Entry<String, ExitPolicy> candidate : CANDIDATES.entrySet()) {
            if (recorded.containsKey(candidate.getKey())) continue;

            Position adjusted = StopAdjuster.adjust(base, candidate.getValue(), atr);
            if (adjusted.stopBreached(lastPrice)) {
                // The stop level, not the traded price. A shadow fill cannot be observed, and
                // assuming the trigger is the fill at least treats every candidate identically —
                // the live trade's own slippage is recorded separately and is the honest measure of
                // what a stop actually costs to hit.
                recorded.put(candidate.getKey(), adjusted.stopPrice());
            } else if (adjusted.targetReached(lastPrice)) {
                recorded.put(candidate.getKey(), adjusted.targetPrice());
            }
        }
    }

    /**
     * What each policy returned on this position, in gross rupees, alongside the live result.
     *
     * <p>A policy with no recorded exit never triggered one — it would still have been holding when
     * the real trade ended, so it takes the real exit price. That is the honest comparison: the
     * position had to finish somewhere, and the square-off would have finished it.</p>
     *
     * <p>Gross, not net: charges depend on the exit price only through turnover, and the differences
     * between these policies are in where they get out, not in what they pay to.</p>
     */
    public ShadowOutcome settle(Position closed) {
        Map<String, Double> recorded = exits.remove(closed.id());
        Double seenFrom = firstSeenR.remove(closed.id());
        Map<String, Double> pnl = new LinkedHashMap<>();
        double watchedFromR = seenFrom == null ? Double.NaN : seenFrom;
        if (closed.filledQuantity() == 0 || !(closed.exitPrice() > 0)) {
            return new ShadowOutcome(pnl, watchedFromR);
        }

        int sign = closed.direction() == com.equity.domain.Direction.LONG ? 1 : -1;
        for (String name : CANDIDATES.keySet()) {
            double exitPrice = recorded == null
                    ? closed.exitPrice()
                    : recorded.getOrDefault(name, closed.exitPrice());
            pnl.put(name, sign * (exitPrice - closed.entryPrice()) * closed.filledQuantity());
        }
        return new ShadowOutcome(pnl, watchedFromR);
    }

    /**
     * What the alternatives made, and how much of the trade was actually watched.
     *
     * <p>The caveat travels with the numbers rather than beside them. A gross P&L per policy is
     * meaningless without knowing whether the shadow saw the trade from its fill, and separating
     * the two invites exactly the analysis that forgets to check.</p>
     *
     * @param pnl          gross rupees per policy name, live-policy-equivalent included
     * @param watchedFromR how far the trade had already run when shadowing began: 0 for a trade
     *                     seen from its fill, NaN if it was never ticked at all
     */
    public record ShadowOutcome(Map<String, Double> pnl, double watchedFromR) {

        /**
         * Whether this row is safe to pool with the rest.
         *
         * <p>Not an exact zero. The first tick after a fill is never at the fill — the price has
         * already moved a few paise by the time the order update arrives — so an exact test would
         * be false on essentially every live trade and would quietly discard the whole month. A
         * quarter of R is the tolerance: below it no candidate policy could have acted anyway,
         * since the earliest of them does not arm until 1R.</p>
         */
        public boolean fromEntry() {
            return watchedFromR <= 0.25;      // NaN is false, which is correct: never ticked at all
        }
    }

    /** Positions currently being shadowed. Non-zero only while something is open. */
    public int tracked() { return exits.size(); }

    /** Drops everything without settling. For tests and for a clean start. */
    public void reset() {
        exits.clear();
        firstSeenR.clear();
    }
}
