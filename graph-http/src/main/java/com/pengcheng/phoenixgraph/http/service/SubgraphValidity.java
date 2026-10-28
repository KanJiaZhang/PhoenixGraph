package com.pengcheng.phoenixgraph.http.service;

import com.pengcheng.phoenixgraph.agent.Validity;
import com.pengcheng.phoenixgraph.http.dto.QueryResponse;
import com.pengcheng.phoenixgraph.http.dto.ScriptResponse;
import com.pengcheng.phoenixgraph.query.result.Subgraph;

import java.util.List;
import java.util.Map;

/**
 * Drops the elements that are not valid at an instant from an answer.
 *
 * <p>A query may ask what the graph looked like at a time ({@code asOf}): a vertex or an edge that declares
 * {@code validFrom}/{@code validTo} and whose window excludes that instant is not part of the picture. This is
 * applied to the <b>answer</b> rather than inside the traversal - the walk itself may pass through an element
 * whose window has closed (it was true when it was written), and deciding what the answer may <em>contain</em>
 * is one rule in one place, for Gremlin, Cypher and scripts alike.
 *
 * <p>An element with no window is valid at every instant, so a graph built before this dimension existed
 * behaves exactly as it did.
 */
final class SubgraphValidity {

    private SubgraphValidity() {
    }

    /** The answer with every element outside its validity window at {@code asOf} removed. */
    static Object filter(Object body, Long asOf) {
        if (asOf == null || body == null) {
            return body;
        }
        if (body instanceof Subgraph subgraph) {
            return filter(subgraph, asOf);
        }
        if (body instanceof QueryResponse response) {
            Subgraph filtered = filter(response.subgraph(), asOf);
            return filtered == response.subgraph() ? response : new QueryResponse(response.result(), filtered);
        }
        if (body instanceof ScriptResponse response) {
            Subgraph filtered = filter(response.subgraph(), asOf);
            return filtered == response.subgraph()
                    ? response : new ScriptResponse(response.result(), response.output(), filtered, response.compile());
        }
        return body;
    }

    /** The subgraph without the elements whose window does not contain {@code asOf}. */
    static Subgraph filter(Subgraph subgraph, Long asOf) {
        if (subgraph == null || subgraph == Subgraph.EMPTY || asOf == null) {
            return subgraph;
        }
        List<Subgraph.Vertex> vertices = subgraph.vertices().stream()
                .filter(vertex -> validAt(vertex.properties(), asOf))
                .toList();
        // an edge whose endpoint was dropped cannot be drawn, and one that is itself outside its window is
        // not part of the answer either
        java.util.Set<String> kept = new java.util.HashSet<>();
        for (Subgraph.Vertex vertex : vertices) {
            kept.add(String.valueOf(vertex.id()));
        }
        List<Subgraph.Edge> edges = subgraph.edges().stream()
                .filter(edge -> validAt(edge.properties(), asOf))
                .filter(edge -> kept.contains(String.valueOf(edge.source())) && kept.contains(String.valueOf(edge.target())))
                .toList();
        if (vertices.size() == subgraph.vertices().size() && edges.size() == subgraph.edges().size()) {
            return subgraph;
        }
        return new Subgraph(vertices, edges);
    }

    private static boolean validAt(Map<String, Object> properties, long asOf) {
        return Validity.ofProperties(properties).validAt(asOf);
    }
}
