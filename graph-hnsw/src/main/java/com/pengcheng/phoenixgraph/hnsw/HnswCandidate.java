package com.pengcheng.phoenixgraph.hnsw;

/**
 * One entry of a candidate list: a vertex, its distance to the reference point
 * (the query during a search, the pruned vertex during a re-prune) and the edge
 * that reaches it (only set for existing neighbours).
 */
record HnswCandidate(Object vertexId, double distance, String edgeId) {

    HnswCandidate(Object vertexId, double distance) {
        this(vertexId, distance, null);
    }
}
