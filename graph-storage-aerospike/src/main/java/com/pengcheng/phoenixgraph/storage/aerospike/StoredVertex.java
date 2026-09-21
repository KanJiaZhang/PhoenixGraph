package com.pengcheng.phoenixgraph.storage.aerospike;

import java.util.Map;

/**
 * A vertex read back from Aerospike, decoded from its bins.
 *
 * @param vertexId        the vertex id (record key)
 * @param properties      property index -> value + lastModifiedTime
 * @param createdTime     creation timestamp (epoch millis)
 * @param lastUpdatedTime last-update timestamp (epoch millis)
 * @param vertexType      the vertex schema name the record was written as, or {@code null} when the
 *                        record predates the type bin: a read may be asked for the wrong type, and
 *                        the record is what says which one it really is
 */
public record StoredVertex(Object vertexId,
                           Map<Integer, VertexPropertyValue> properties,
                           long createdTime,
                           long lastUpdatedTime,
                           String vertexType) {

    /** A vertex whose record carries no type (written before the type bin existed). */
    public StoredVertex(Object vertexId, Map<Integer, VertexPropertyValue> properties,
                        long createdTime, long lastUpdatedTime) {
        this(vertexId, properties, createdTime, lastUpdatedTime, null);
    }
}
