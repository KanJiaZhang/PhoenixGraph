package com.pengcheng.phoenixgraph.mapreduce;

import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.storage.aerospike.EdgePropertyValue;

import java.util.Map;

/**
 * Intermediate value emitted by the edge map phase (the edge id is the key).
 */
public record EdgeRow(EdgeSchema edgeSchema, Object source, Object destination,
                      Map<Integer, EdgePropertyValue> properties,
                      long createdTime, long lastUpdatedTime) {
}
