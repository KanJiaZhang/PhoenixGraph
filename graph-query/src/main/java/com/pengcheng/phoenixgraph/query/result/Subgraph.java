package com.pengcheng.phoenixgraph.query.result;

import java.util.List;
import java.util.Map;

/**
 * The induced subgraph visited by a query: the union of every vertex and edge the
 * traversal touched (start vertices, every hop's edges and the reached
 * neighbours). Produced by the Gremlin and Cypher engines alike and consumed by
 * graph renderers, so it lives next to the storage-agnostic query port.
 *
 * <p>An engine that collects a subgraph reads the full properties of the touched
 * elements once, which is why the vertices/edges carry properties rather than just
 * references.
 *
 * @param vertices vertices keyed by {@code (label, id)}; properties are read in
 *                 full for the subgraph
 * @param edges    edges keyed by {@code (label, id)} with their resolved
 *                 {@code source} / {@code target} endpoints
 */
public record Subgraph(List<Vertex> vertices, List<Edge> edges) {

    public static final Subgraph EMPTY = new Subgraph(List.of(), List.of());

    /**
     * A vertex in the subgraph.
     *
     * <p>The id is kept as text on purpose: a traversal reaches the same vertex once from a
     * query seed - whose id is whatever the caller typed - and once from storage, where the id
     * keeps its stored type ({@code 1000} is a number there). A renderer joins edges to
     * vertices by id, so a graph whose vertices say {@code 1000} and whose edges say
     * {@code "1000"} would draw nodes without edges. Text makes both sides one node.
     */
    public record Vertex(String label, Object id, Map<String, Object> properties,
                         long createdTime, long lastUpdatedTime) {

        public Vertex {
            id = id == null ? null : String.valueOf(id);
        }
    }

    /**
     * An edge in the subgraph with resolved endpoints.
     *
     * <p>{@code target} may be {@code null}: an edge whose schema declares a nullable target
     * only has an outgoing side, so there is no vertex at the far end.
     */
    public record Edge(String label, Object id, Object source, Object target, Map<String, Object> properties,
                       long createdTime, long lastUpdatedTime) {

        public Edge {
            id = id == null ? null : String.valueOf(id);
            source = source == null ? null : String.valueOf(source);
            target = target == null ? null : String.valueOf(target);
        }
    }
}
