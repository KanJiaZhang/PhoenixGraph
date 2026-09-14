package com.pengcheng.phoenixgraph.storage.aerospike;

/**
 * A vertex property deletion request (hard delete of one property).
 *
 * @param vertexId      vertex id (record key)
 * @param propertyIndex the property index to remove
 */
public record VertexPropertyDelete(Object vertexId, int propertyIndex) {
}
