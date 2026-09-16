package com.pengcheng.phoenixgraph.hnsw;

/**
 * A link to delete on one layer, identified by its edge id plus both endpoints
 * (a bidirectional edge is stored on both of them).
 */
public record HnswUnlink(String edgeId, Object from, Object to, int level) {
}
