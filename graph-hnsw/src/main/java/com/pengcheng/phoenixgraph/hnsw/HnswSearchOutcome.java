package com.pengcheng.phoenixgraph.hnsw;

import java.util.List;

/**
 * What a (filtered) search did: the results plus the shape of the walk that found
 * them, which is what makes the cost of a selective filter visible.
 *
 * @param results      the nearest vertices found, closest first (at most {@code k})
 * @param ef           candidate list size of the unfiltered layer search
 * @param efTarget     candidate list size the filtered search widened to
 * @param visited      vertices examined
 * @param expansions   vertices whose neighbours were expanded
 * @param accepted     vertices that passed the filter (the pool the results come from)
 * @param selectivity  selectivity estimate the widening used ({@code 1} when unfiltered)
 */
public record HnswSearchOutcome(List<HnswSearchResult> results, int ef, int efTarget, int visited,
                                int expansions, int accepted, double selectivity) {

    public String describe() {
        return "results=" + results.size() + ", ef=" + ef + " -> efTarget=" + efTarget
                + ", visited=" + visited + ", expansions=" + expansions
                + ", accepted=" + accepted + ", selectivity=" + String.format("%.4f", selectivity);
    }
}
