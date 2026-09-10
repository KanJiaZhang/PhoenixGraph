package com.pengcheng.phoenixgraph.storage;

import java.util.Map;

/**
 * A serialized edge value stored by the edge storage strategies.
 *
 * @param edgeId       edge id
 * @param source       source vertex id
 * @param destination  destination vertex id
 * @param columns      edge properties (columns/bins)
 * @param lastModified last-modified timestamp used for ordering
 */
public record StoredEdge(Object edgeId, Object source, Object destination,
                         Map<String, Object> columns, long lastModified) {
}
