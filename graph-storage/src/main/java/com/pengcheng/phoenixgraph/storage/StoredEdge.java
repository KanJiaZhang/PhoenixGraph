package com.pengcheng.phoenixgraph.storage;

import com.pengcheng.phoenixgraph.schema.EdgeDirection;

import java.util.Map;

/**
 * A serialized edge value stored by the edge storage strategies.
 *
 * @param edgeId          edge id
 * @param edgeSchemaId    id of the {@code EdgeSchema} this edge conforms to
 * @param source          source vertex id
 * @param destination     destination vertex id
 * @param direction       edge direction semantics (DIRECTED / UNDIRECTED / BIDIRECTIONAL)
 * @param columns         edge properties (columns/bins)
 * @param lastUpdatedTime last-update timestamp (epoch millis) used for ordering
 *                        and eviction; mirrors the core model's
 *                        {@code MetaProperty.lastUpdatedTime()}.
 */
public record StoredEdge(Object edgeId, int edgeSchemaId, Object source, Object destination,
                         EdgeDirection direction, Map<String, Object> columns, long lastUpdatedTime) {
}
