package com.pengcheng.phoenixgraph.hnsw;

import java.util.List;
import java.util.Map;

/**
 * A predicate a search pushes <em>into</em> the graph walk.
 *
 * <p>Plain nearest-neighbour search answers "the k closest vertices"; with a
 * business constraint it has to answer "the k closest vertices <em>that also
 * match</em>". Filtering the result of a normal search does not guarantee that:
 * the k closest may all fail the predicate. A filtered search instead keeps such
 * vertices out of the result table but still expands through them, so the walk
 * navigates the neighbourhood as usual and only the accepted vertices are
 * collected. The candidate list is widened by the selectivity of the predicate
 * (see {@link HnswIndex#search(float[], int, int, HnswFilter)}), because reaching
 * k matches needs about {@code k / selectivity} candidates.
 *
 * <p>{@link #propertyNames()} tells the search which properties to load together
 * with the vectors (one batch read per expansion), so the predicate itself never
 * touches storage.
 */
@FunctionalInterface
public interface HnswFilter {

    /** Matches everything: the same as passing no filter, but through the filtered path. */
    HnswFilter NONE = (vertexId, properties) -> true;

    /** Largest candidate list a filtered search will widen to, relative to the base ef. */
    int MAX_EF_FACTOR = 20;

    /** Additional properties the predicate reads; the search batch-loads them with the vectors. */
    default List<String> propertyNames() {
        return List.of();
    }

    /**
     * Expected fraction of vertices that match, in {@code (0, 1]}; {@code 0} when it
     * is unknown, in which case the search estimates it from the candidates it
     * visits and widens the candidate list accordingly.
     */
    default double selectivity() {
        return 0;
    }

    /** Whether this predicate matches the vertex given the property values it asked for. */
    boolean test(Object vertexId, Map<String, Object> properties);

    /** A short description used in logs and diagnostics. */
    default String describe() {
        return getClass().getSimpleName();
    }

    /** Widest candidate list a filtered search may use for the given base list size. */
    default int maxEf(int ef, int k) {
        return Math.max(ef, k) * MAX_EF_FACTOR;
    }
}
