package com.pengcheng.phoenixgraph.hnsw;

/**
 * A search hit: a vertex id and its distance to the query vector (lower = closer).
 */
public record HnswSearchResult(Object vertexId, double distance) {
}
