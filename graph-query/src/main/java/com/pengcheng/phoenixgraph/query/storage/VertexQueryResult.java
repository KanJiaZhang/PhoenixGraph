package com.pengcheng.phoenixgraph.query.storage;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A vertex read back from a storage backend with the requested properties
 * projected.
 *
 * @param vertexName      vertex schema name
 * @param vertexId        vertex id (record key)
 * @param properties      projected property values keyed by name; contains only
 *                        the requested properties, or all declared properties
 *                        when none were requested
 * @param createdTime     vertex creation timestamp (epoch millis)
 * @param lastUpdatedTime vertex last-update timestamp (epoch millis)
 */
public record VertexQueryResult(String vertexName, Object vertexId,
                                Map<String, Object> properties,
                                long createdTime, long lastUpdatedTime) {

    public VertexQueryResult {
        if (vertexName == null || vertexName.isBlank()) {
            throw new IllegalArgumentException("vertexName must not be blank");
        }
        properties = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(properties, "properties")));
    }
}
