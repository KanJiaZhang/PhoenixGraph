package com.pengcheng.phoenixgraph.query.storage;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * An edge read back from a storage backend with the requested properties
 * projected.
 *
 * @param edgeName        edge schema name
 * @param edgeId          edge id
 * @param side            which side of the queried vertex the edge is on
 * @param otherVertexId   the endpoint on the other side of the queried vertex
 *                        (the reached neighbour; enables multi-hop traversal)
 * @param properties      projected property values keyed by name; contains only
 *                        the requested properties, or all declared properties
 *                        when none were requested
 * @param createdTime     edge creation timestamp (epoch millis)
 * @param lastUpdatedTime edge last-update timestamp (epoch millis)
 */
public record EdgeQueryResult(String edgeName, String edgeId, EdgeSide side, Object otherVertexId,
                              Map<String, Object> properties,
                              long createdTime, long lastUpdatedTime) {

    public EdgeQueryResult {
        if (edgeName == null || edgeName.isBlank()) {
            throw new IllegalArgumentException("edgeName must not be blank");
        }
        Objects.requireNonNull(side, "side");
        properties = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(properties, "properties")));
    }
}
