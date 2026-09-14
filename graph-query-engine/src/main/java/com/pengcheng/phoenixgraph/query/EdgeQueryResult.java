package com.pengcheng.phoenixgraph.query;

import com.pengcheng.phoenixgraph.core.MetaProperty;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A single edge returned by a {@link VertexEdgeQueryEngine} query.
 *
 * @param edgeSchema   name of the edge schema (class) the edge belongs to
 * @param edgeId       edge id
 * @param source       source vertex id
 * @param destination  destination vertex id
 * @param properties   projected property values keyed by property name; contains
 *                     only the requested properties, or all declared properties
 *                     when none were requested
 * @param meta         the edge's meta properties (createdTime / lastUpdatedTime);
 *                     always present regardless of the property selection
 */
public record EdgeQueryResult(String edgeSchema, Object edgeId, Object source, Object destination,
                              Map<String, Object> properties, MetaProperty meta) {

    public EdgeQueryResult {
        if (edgeSchema == null || edgeSchema.isBlank()) {
            throw new IllegalArgumentException("edgeSchema must not be blank");
        }
        Objects.requireNonNull(meta, "meta");
        properties = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(properties, "properties")));
    }
}
