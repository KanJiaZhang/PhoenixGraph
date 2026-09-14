package com.pengcheng.phoenixgraph.storage.aerospike;

import com.pengcheng.phoenixgraph.schema.VertexSchema;

import java.util.Map;

/**
 * A vertex upsert for batch writing: inserts the vertex if it is absent,
 * otherwise updates each property whose {@code lastModifiedTime} is newer than
 * the stored value.
 *
 * @param vertexId        vertex id (record key)
 * @param vertexSchema    the {@link VertexSchema} this vertex conforms to
 * @param properties      property index -> value + lastModifiedTime
 * @param createdTime     vertex creation timestamp (epoch millis)
 * @param lastUpdatedTime vertex last-update timestamp (epoch millis)
 */
public record VertexUpsert(Object vertexId, VertexSchema vertexSchema,
                           Map<Integer, VertexPropertyValue> properties,
                           long createdTime, long lastUpdatedTime) {
}
