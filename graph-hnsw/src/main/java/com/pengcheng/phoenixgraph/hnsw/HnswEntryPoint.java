package com.pengcheng.phoenixgraph.hnsw;

/**
 * An entry point of the layered graph: a vertex that reaches {@code level}.
 *
 * <p>The search descends from the highest layer and needs a starting vertex
 * there, so at most {@link HnswLevels#MAX_ENTRY_POINTS} of the highest-reaching
 * vertices are kept next to the graph (see {@link HnswMetadataStore}), which lets
 * a new process start a query without rebuilding or scanning anything.
 *
 * @param vertexId id of the entry vertex
 * @param level    its top layer
 */
public record HnswEntryPoint(Object vertexId, int level) implements Comparable<HnswEntryPoint> {

    public HnswEntryPoint {
        if (vertexId == null) {
            throw new IllegalArgumentException("entry point vertex id must not be null");
        }
        if (level < 0) {
            throw new IllegalArgumentException("entry point level must be >= 0, got " + level);
        }
    }

    /** Highest layer first, then by vertex id for a stable order. */
    @Override
    public int compareTo(HnswEntryPoint other) {
        int byLevel = Integer.compare(other.level, level);
        return byLevel != 0 ? byLevel : String.valueOf(vertexId).compareTo(String.valueOf(other.vertexId));
    }
}
