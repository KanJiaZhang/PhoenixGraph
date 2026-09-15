package com.pengcheng.phoenixgraph.query.gremlin;

import java.util.List;
import java.util.Map;

/**
 * The induced subgraph visited by a traversal: the union of every vertex and
 * edge the query touched (start vertices, every hop's edges and the reached
 * neighbours). Used by graph renderers.
 *
 * @param vertices vertices keyed by {@code (label, id)}; properties are read in
 *                 full for the subgraph
 * @param edges    edges keyed by {@code (label, id)} with their resolved
 *                 {@code source} / {@code target} endpoints
 */
public record Subgraph(List<Vertex> vertices, List<Edge> edges) {

    public static final Subgraph EMPTY = new Subgraph(List.of(), List.of());

    /** A vertex in the subgraph. */
    public record Vertex(String label, Object id, Map<String, Object> properties,
                         long createdTime, long lastUpdatedTime) {
    }

    /** An edge in the subgraph with resolved endpoints. */
    public record Edge(String label, Object id, Object source, Object target, Map<String, Object> properties,
                       long createdTime, long lastUpdatedTime) {
    }
}
