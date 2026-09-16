package com.pengcheng.phoenixgraph.hnsw;

/**
 * Outcome of inserting one vertex.
 *
 * @param vertexId the inserted vertex
 * @param level    its top layer
 * @param links    edges created while inserting
 * @param pruned   edges removed from over-full neighbours while inserting
 */
public record HnswInsertResult(Object vertexId, int level, int links, int pruned) {
}
