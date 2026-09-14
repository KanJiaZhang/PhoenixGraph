package com.pengcheng.phoenixgraph.mapreduce;

import com.pengcheng.phoenixgraph.schema.VertexSchema;
import com.pengcheng.phoenixgraph.storage.aerospike.VertexPropertyValue;

import java.util.Map;

/**
 * Intermediate value emitted by the vertex map phase (the id is the key).
 */
public record VertexRow(VertexSchema vertexSchema, Map<Integer, VertexPropertyValue> properties,
                        long createdTime, long lastUpdatedTime) {
}
