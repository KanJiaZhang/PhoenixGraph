package com.pengcheng.phoenixgraph.query;

import com.pengcheng.phoenixgraph.core.Edge;
import com.pengcheng.phoenixgraph.core.Graph;
import com.pengcheng.phoenixgraph.core.Property;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Executes vertex-to-edge queries against a {@link Graph}.
 *
 * <p>Given a single vertex id and one or more {@link EdgeQuery} specs, returns
 * every edge attached to that vertex — as source or destination — whose edge
 * schema name matches one of the specs. Each result projects exactly the
 * properties listed in the matching spec, or all declared properties when the
 * spec lists none. The edge's meta properties (createdTime / lastUpdatedTime)
 * are always included in {@link EdgeQueryResult#meta()}.
 *
 * <p>Results are grouped by the order of the supplied {@link EdgeQuery} specs;
 * within a spec they are ordered by lastUpdatedTime descending (most recent
 * first), ties broken by edge id.
 */
public class VertexEdgeQueryEngine {

    /** Queries the edges of {@code vertexId} matching the given edge queries. */
    public List<EdgeQueryResult> queryEdges(Graph graph, Object vertexId, EdgeQuery... queries) {
        Objects.requireNonNull(queries, "queries");
        return queryEdges(graph, vertexId, List.of(queries));
    }

    /**
     * Queries the edges of {@code vertexId} matching the given edge queries.
     *
     * @param graph    the graph to query
     * @param vertexId the single vertex whose attached edges are queried
     * @param queries  one or more edge schema/property specs; each spec may list
     *                 the property names to project (empty = all)
     * @return one {@link EdgeQueryResult} per matched edge
     */
    public List<EdgeQueryResult> queryEdges(Graph graph, Object vertexId, List<EdgeQuery> queries) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(vertexId, "vertexId");
        Objects.requireNonNull(queries, "queries");

        // Group the vertex's attached edges by edge schema name in one pass.
        Map<String, List<Edge>> bySchema = new LinkedHashMap<>();
        try (Stream<Edge> edges = graph.edges()) {
            edges.forEach(edge -> {
                if (Objects.equals(edge.source(), vertexId) || Objects.equals(edge.destination(), vertexId)) {
                    bySchema.computeIfAbsent(edge.schema().name(), k -> new ArrayList<>()).add(edge);
                }
            });
        }

        List<EdgeQueryResult> results = new ArrayList<>();
        for (EdgeQuery query : queries) {
            for (Edge edge : ordered(bySchema.getOrDefault(query.edgeSchema(), List.of()))) {
                results.add(project(edge, query));
            }
        }
        return results;
    }

    private static List<Edge> ordered(List<Edge> edges) {
        return edges.stream()
                .sorted(Comparator
                        .comparingLong((Edge e) -> e.meta().lastUpdatedTime()).reversed()
                        .thenComparing(e -> String.valueOf(e.edgeId())))
                .toList();
    }

    private static EdgeQueryResult project(Edge edge, EdgeQuery query) {
        Map<String, Object> properties = new LinkedHashMap<>();
        if (query.selectsAll()) {
            for (Property property : edge.properties()) {
                properties.put(property.schema().name(), property.value());
            }
        } else {
            for (String name : query.properties()) {
                Property property = edge.property(name);
                if (property == null) {
                    throw new QueryException("Unknown property '" + name
                            + "' in edge schema '" + edge.schema().name() + "'");
                }
                properties.put(name, property.value());
            }
        }
        return new EdgeQueryResult(edge.schema().name(), edge.edgeId(), edge.source(), edge.destination(),
                properties, edge.meta());
    }
}
