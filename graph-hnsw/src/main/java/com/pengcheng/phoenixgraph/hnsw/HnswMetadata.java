package com.pengcheng.phoenixgraph.hnsw;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything a reader needs to query an HNSW index without scanning the graph:
 * the layer scheme, the build parameters, the metric and the entry points.
 *
 * <p>One record per index is kept in a dedicated key/value slot (see
 * {@link HnswMetadataStore}) - the "top layer registry" of the index. It holds at
 * most {@link HnswLevels#MAX_ENTRY_POINTS} entry points ({@code vertex id} plus
 * its layer), ordered from the highest layer down.
 *
 * @param version        metadata layout version
 * @param vertexName     vertex type the index is built over
 * @param vectorProperty property holding the vector
 * @param metric         distance metric the graph was built with
 * @param m              neighbours kept per layer while inserting
 * @param efConstruction candidate list size used while inserting
 * @param efSearch       candidate list size used while searching
 * @param dimensions     vector dimensionality
 * @param size           number of inserted vectors
 * @param topLevel       highest layer in use
 * @param entryPoints    entry points, highest layer first (at most {@value HnswLevels#MAX_ENTRY_POINTS})
 * @param updatedAt      last update timestamp (epoch millis)
 */
public record HnswMetadata(int version, String vertexName, String vectorProperty, HnswMetric metric,
                           int m, int efConstruction, int efSearch, int dimensions, long size,
                           int topLevel, List<HnswEntryPoint> entryPoints, long updatedAt) {

    /** Current metadata layout version. */
    public static final int CURRENT_VERSION = 1;

    public HnswMetadata {
        if (vertexName == null || vertexName.isBlank()) {
            throw new IllegalArgumentException("vertexName must not be blank");
        }
        if (metric == null) {
            throw new IllegalArgumentException("metric must not be null");
        }
        if (topLevel < 0) {
            throw new IllegalArgumentException("topLevel must be >= 0, got " + topLevel);
        }
        entryPoints = entryPoints == null ? List.of() : List.copyOf(entryPoints);
        if (entryPoints.size() > HnswLevels.MAX_ENTRY_POINTS) {
            throw new IllegalArgumentException("at most " + HnswLevels.MAX_ENTRY_POINTS
                    + " entry points are kept, got " + entryPoints.size());
        }
    }

    /** A fresh (empty) index description for a dataset of {@code size} vectors. */
    public static HnswMetadata empty(String vertexName, String vectorProperty, HnswConfig config,
                                     int dimensions, long size) {
        return new HnswMetadata(CURRENT_VERSION, vertexName, vectorProperty, config.metric(),
                config.m(), config.efConstruction(), config.efSearch(), dimensions, size,
                HnswLevels.topLevel(size), List.of(), 0L);
    }

    /** Config carried by this metadata. */
    public HnswConfig toConfig() {
        return new HnswConfig(metric, m, efConstruction, efSearch, m, 2 * m);
    }

    /** Same metadata with a new entry point list, re-sorted and truncated. */
    public HnswMetadata withEntryPoints(List<HnswEntryPoint> points, long updatedAt) {
        List<HnswEntryPoint> sorted = new ArrayList<>(points);
        sorted.sort(null);
        List<HnswEntryPoint> kept = sorted.size() > HnswLevels.MAX_ENTRY_POINTS
                ? new ArrayList<>(sorted.subList(0, HnswLevels.MAX_ENTRY_POINTS))
                : sorted;
        return new HnswMetadata(version, vertexName, vectorProperty, metric, m, efConstruction,
                efSearch, dimensions, size, topLevel, kept, updatedAt);
    }

    /** Same metadata with a new size/topLevel (used while building). */
    public HnswMetadata withSize(long size, int topLevel, long updatedAt) {
        return new HnswMetadata(version, vertexName, vectorProperty, metric, m, efConstruction,
                efSearch, dimensions, size, topLevel, entryPoints, updatedAt);
    }
}
