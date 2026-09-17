package com.pengcheng.phoenixgraph.http.service;

/**
 * Outcome of a vector index rebuild (see
 * {@link VectorIndexManager#rebuild(String, String, String)}).
 *
 * @param graph     graph that was rebuilt
 * @param vertex    vertex type of the index
 * @param property  vector property of the index
 * @param vertices  vertices read back and re-inserted
 * @param layers    layer edge classes that were cleared and recreated
 * @param links     links written while rebuilding
 * @param pruned    links removed from over-full neighbours while rebuilding
 * @param seconds   wall-clock duration of the rebuild
 */
public record VectorIndexRebuildResult(String graph, String vertex, String property, int vertices,
                                       int layers, long links, long pruned, double seconds) {

    public String describe() {
        return graph + "/" + vertex + "." + property + ": " + vertices + " vertices, "
                + layers + " layers, " + links + " links, " + pruned + " pruned, "
                + String.format("%.1fs", seconds);
    }
}
