package com.pengcheng.phoenixgraph.hnsw;

/**
 * A neighbour of a vertex on one HNSW layer.
 *
 * @param edgeId   id of the edge in storage (needed to remove the link)
 * @param vertexId the vertex on the other side of the edge
 */
public record HnswNeighbor(String edgeId, Object vertexId) {
}
