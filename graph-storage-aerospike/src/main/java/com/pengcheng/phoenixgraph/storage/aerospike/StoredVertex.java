package com.pengcheng.phoenixgraph.storage.aerospike;

import java.util.Map;

/**
 * A vertex read back from Aerospike, decoded from its bins.
 *
 * @param vertexId        the vertex id (record key)
 * @param properties      property index -> value + lastModifiedTime
 * @param createdTime     creation timestamp (epoch millis)
 * @param lastUpdatedTime last-update timestamp (epoch millis)
 */
public record StoredVertex(Object vertexId,
                           Map<Integer, VertexPropertyValue> properties,
                           long createdTime,
                           long lastUpdatedTime) {
}
