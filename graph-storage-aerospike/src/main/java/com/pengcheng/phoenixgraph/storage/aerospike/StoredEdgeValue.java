package com.pengcheng.phoenixgraph.storage.aerospike;

import java.util.Map;

/**
 * An edge read back from Aerospike, decoded from its CDT value.
 *
 * @param edgeId          edge id (the CDT map key)
 * @param otherVertexId   the endpoint on the other side of the vertex this edge
 *                        was read from (enables traversal to neighbours)
 * @param lastUpdatedTime edge last-update timestamp (epoch millis)
 * @param createdTime     edge creation timestamp (epoch millis)
 * @param properties      property index -> value + per-property timestamps
 */
public record StoredEdgeValue(String edgeId, Object otherVertexId, long lastUpdatedTime, long createdTime,
                              Map<Integer, EdgePropertyValue> properties) {
}
