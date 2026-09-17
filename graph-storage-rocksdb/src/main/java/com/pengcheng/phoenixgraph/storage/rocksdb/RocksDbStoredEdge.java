package com.pengcheng.phoenixgraph.storage.rocksdb;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * An edge read back from the inline layout.
 *
 * @param edgeId          edge id
 * @param otherVertexId   the endpoint on the other side of the vertex it was read from
 * @param createdTime     edge creation timestamp (epoch millis)
 * @param lastUpdatedTime edge last-update timestamp (epoch millis)
 * @param properties      property index -> value + per-property timestamps
 */
public record RocksDbStoredEdge(String edgeId, Object otherVertexId, long createdTime, long lastUpdatedTime,
                                Map<Integer, RocksDbEdgeProperty> properties) {

    public RocksDbStoredEdge {
        Objects.requireNonNull(edgeId, "edgeId");
        properties = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(properties, "properties")));
    }
}
