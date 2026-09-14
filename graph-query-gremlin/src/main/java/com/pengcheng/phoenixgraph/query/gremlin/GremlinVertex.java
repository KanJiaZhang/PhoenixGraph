package com.pengcheng.phoenixgraph.query.gremlin;

import java.util.Map;

/**
 * A vertex traverser.
 *
 * @param label           vertex schema name (may be {@code null} when unknown)
 * @param id              vertex id
 * @param properties      projected property values keyed by name
 * @param createdTime     creation timestamp (epoch millis)
 * @param lastUpdatedTime last-update timestamp (epoch millis)
 */
public record GremlinVertex(String label, Object id, Map<String, Object> properties,
                            long createdTime, long lastUpdatedTime) implements GremlinElement {
}
