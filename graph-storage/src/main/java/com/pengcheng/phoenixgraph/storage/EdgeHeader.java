package com.pengcheng.phoenixgraph.storage;

/**
 * The header of a stored edge: its id, the vertex at its far end, and its timestamps.
 *
 * <p>This is what a read that only wants to reach the other end of an edge needs - a vertex hop walks an
 * edge to arrive somewhere and never looks at the edge itself - so a store whose edges live inside a record
 * can answer it from the head of the stored value instead of decoding properties nobody asked for.
 *
 * @param edgeId          the edge id
 * @param peerVertexId    the vertex at the far end (as stored)
 * @param createdTime     creation timestamp (epoch millis)
 * @param lastUpdatedTime last-update timestamp (epoch millis)
 */
public record EdgeHeader(String edgeId, Object peerVertexId, long createdTime, long lastUpdatedTime) {
}
