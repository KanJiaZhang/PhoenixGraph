package com.pengcheng.phoenixgraph.hnsw;

import java.util.List;

/**
 * Snapshot of an index' state and of the work it has done since it was opened.
 *
 * @param size           vertices inserted so far
 * @param topLevel       highest layer in use
 * @param maxEntryLevel  layer of the highest entry point
 * @param entryPoints    the entry points kept in the registry (highest layer first)
 * @param inserts        insert calls
 * @param searches       search calls
 * @param distances      distance computations (the search work metric)
 */
public record HnswIndexStats(long size, int topLevel, int maxEntryLevel, List<HnswEntryPoint> entryPoints,
                             long inserts, long searches, long distances) {

    public String describe() {
        return "size=" + size + ", layers=0.." + topLevel + ", entryPoints=" + entryPoints.size()
                + " (max layer " + maxEntryLevel + "), inserts=" + inserts + ", searches=" + searches
                + ", distances=" + distances;
    }
}
