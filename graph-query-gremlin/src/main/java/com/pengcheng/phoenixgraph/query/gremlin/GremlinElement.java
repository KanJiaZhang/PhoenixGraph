package com.pengcheng.phoenixgraph.query.gremlin;

import java.util.Map;

/**
 * A traverser flowing through a Gremlin traversal: a vertex or an edge.
 *
 * <p>Elements carry a label (schema name), an id, the projected property values
 * and meta timestamps. A vertex whose schema could not be resolved carries a
 * {@code null} label and no properties (only its id), which is still enough to
 * continue traversing along edges.
 */
public sealed interface GremlinElement permits GremlinVertex, GremlinEdge {

    /** Schema name (label) of this element; may be {@code null} for an unresolved vertex. */
    String label();

    /** Element id. */
    Object id();

    /** Projected property values keyed by property name. */
    Map<String, Object> properties();

    /** Creation timestamp (epoch millis). */
    long createdTime();

    /** Last-update timestamp (epoch millis). */
    long lastUpdatedTime();
}
