package com.equity.market.gainer;

import com.equity.domain.market.SharedInstrumentState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Ranks the universe by day change and tracks how each rank is MOVING.
 *
 * <p>Discovery only. Rank never authorises an entry, and once a position is open its rank stops
 * mattering entirely — a stock that has been rank 1 all morning while fading is a worse candidate
 * than one climbing 41 → 30 → 18 → 10 right now.</p>
 *
 * <h2>Hysteresis</h2>
 * A single top-N cutoff makes symbols at the boundary flap in and out on every recompute, which
 * churns discovery state and floods the rejection log with noise. Entry into the discovery set uses
 * {@code enterRank}; leaving it requires falling past the wider {@code exitRank}.
 *
 * <h2>Corporate actions</h2>
 * Ranking divides by previous close, so an unadjusted split shows as a −80% mover and a bonus as a
 * crash. Any symbol outside {@code sanityBandPercent} is excluded from the ranking with
 * SUSPECT_CORPORATE_ACTION rather than being allowed to top the board.
 */
public final class TopGainerEngine {

    private final int enterRank;
    private final int exitRank;
    private final double sanityBandPercent;

    /** Previous published ranking, so rank direction is available without a second pass. */
    private final AtomicReference<Map<String, Integer>> previousRanks =
            new AtomicReference<>(Map.of());
    private final AtomicReference<List<Ranked>> latest = new AtomicReference<>(List.of());

    public TopGainerEngine(int enterRank, int exitRank, double sanityBandPercent) {
        if (exitRank < enterRank) {
            throw new IllegalArgumentException("exitRank must be >= enterRank for hysteresis");
        }
        this.enterRank = enterRank;
        this.exitRank = exitRank;
        this.sanityBandPercent = sanityBandPercent;
    }

    /** One ranked row. {@code previousRank} is 0 when the symbol was not previously ranked. */
    public record Ranked(String symbol, int rank, int previousRank, double dayChangePercent) {
        public int rankChange()      { return previousRank <= 0 ? 0 : rank - previousRank; }
        public boolean improving()   { return rankChange() < 0; }
    }

    /**
     * Recompute the ranking. Called on a timer (seconds), not per tick — ranking the whole universe
     * on every tick would burn CPU to produce a number that only matters at 1m granularity.
     */
    public List<Ranked> rank(Iterable<SharedInstrumentState> universe) {
        Map<String, Integer> prev = previousRanks.get();

        List<SharedInstrumentState> eligible = new ArrayList<>();
        for (SharedInstrumentState s : universe) {
            if (s.previousClose() <= 0 || s.lastPrice() <= 0) continue;
            if (s.suspectCorporateAction(sanityBandPercent)) continue;
            eligible.add(s);
        }
        eligible.sort(Comparator.comparingDouble(
                (SharedInstrumentState s) -> s.changeFromPreviousClosePercent()).reversed());

        List<Ranked> out = new ArrayList<>(eligible.size());
        Map<String, Integer> now = new HashMap<>();
        for (int i = 0; i < eligible.size(); i++) {
            SharedInstrumentState s = eligible.get(i);
            int rank = i + 1;
            now.put(s.symbol(), rank);
            out.add(new Ranked(s.symbol(), rank, prev.getOrDefault(s.symbol(), 0),
                    s.changeFromPreviousClosePercent()));
        }

        previousRanks.set(now);
        List<Ranked> published = List.copyOf(out);
        latest.set(published);
        return published;
    }

    public List<Ranked> latest() { return latest.get(); }

    /**
     * Hysteresis test. {@code alreadyInSet} is the caller's view of whether this symbol was in the
     * discovery set on the previous pass.
     */
    public boolean inDiscoverySet(int rank, boolean alreadyInSet) {
        if (rank <= 0) return false;
        return alreadyInSet ? rank <= exitRank : rank <= enterRank;
    }
}
