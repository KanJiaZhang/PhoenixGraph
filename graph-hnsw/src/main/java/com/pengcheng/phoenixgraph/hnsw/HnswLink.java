package com.pengcheng.phoenixgraph.hnsw;

/**
 * A link to create on one layer, carrying the edge id the store would generate
 * ({@link HnswStore#edgeId}) so a batch of links can be written in one call and
 * a link can still be identified if it is dropped again within the same insert.
 */
public record HnswLink(String edgeId, Object from, Object to, int level) {
}
