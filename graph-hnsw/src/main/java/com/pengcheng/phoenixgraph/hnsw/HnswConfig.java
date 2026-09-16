package com.pengcheng.phoenixgraph.hnsw;

/**
 * Tunables of an HNSW index.
 *
 * @param metric          distance metric the graph is ordered by
 * @param m               neighbours kept per layer while inserting ({@code 15} by default)
 * @param efConstruction  candidate list size while inserting ({@code 100} by default)
 * @param efSearch        candidate list size while searching ({@code 30} by default)
 * @param maxLinks        max edges per vertex on layers {@code >= 1} ({@code m}, standard HNSW)
 * @param maxLinksLevel0  max edges per vertex on layer 0 ({@code 2 * m}, standard HNSW)
 */
public record HnswConfig(HnswMetric metric, int m, int efConstruction, int efSearch,
                         int maxLinks, int maxLinksLevel0) {

    public HnswConfig {
        if (metric == null) {
            throw new IllegalArgumentException("metric must not be null");
        }
        if (m < 1) {
            throw new IllegalArgumentException("m must be >= 1, got " + m);
        }
        if (efConstruction < 1) {
            throw new IllegalArgumentException("efConstruction must be >= 1, got " + efConstruction);
        }
        if (efSearch < 1) {
            throw new IllegalArgumentException("efSearch must be >= 1, got " + efSearch);
        }
        if (maxLinks < 1 || maxLinksLevel0 < maxLinks) {
            throw new IllegalArgumentException("maxLinks/maxLinksLevel0 must be >= 1 / >= maxLinks");
        }
    }

    /** {@code m = 15}, {@code efConstruction = 100}, {@code efSearch = 30}, L2 distance. */
    public static HnswConfig defaults() {
        return new HnswConfig(HnswMetric.L2, 15, 100, 30, 15, 30);
    }

    /** Neighbours kept at the given layer while inserting ({@code m}); the search cap is {@link #maxLinksFor(int)}. */
    public int efConstructionAt(int level) {
        return level == 0 ? Math.max(efConstruction, maxLinksLevel0) : efConstruction;
    }

    /** Maximum number of edges a vertex may keep on the given layer. */
    public int maxLinksFor(int level) {
        return level == 0 ? maxLinksLevel0 : maxLinks;
    }

    public HnswConfig withMetric(HnswMetric metric) {
        return new HnswConfig(metric, m, efConstruction, efSearch, maxLinks, maxLinksLevel0);
    }

    public HnswConfig withM(int m) {
        return new HnswConfig(metric, m, efConstruction, efSearch, m, 2 * m);
    }

    public HnswConfig withEfConstruction(int efConstruction) {
        return new HnswConfig(metric, m, efConstruction, efSearch, maxLinks, maxLinksLevel0);
    }

    public HnswConfig withEfSearch(int efSearch) {
        return new HnswConfig(metric, m, efConstruction, efSearch, maxLinks, maxLinksLevel0);
    }

    /** Compact description used in logs. */
    public String describe() {
        return "metric=" + metric + ", m=" + m + ", efConstruction=" + efConstruction
                + ", efSearch=" + efSearch + ", maxLinks=" + maxLinks
                + ", maxLinksLevel0=" + maxLinksLevel0;
    }
}
