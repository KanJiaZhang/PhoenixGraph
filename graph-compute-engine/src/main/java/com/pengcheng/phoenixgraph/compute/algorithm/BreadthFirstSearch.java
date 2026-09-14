package com.pengcheng.phoenixgraph.compute.algorithm;

import com.pengcheng.phoenixgraph.compute.GraphAlgorithm;
import com.pengcheng.phoenixgraph.core.Edge;
import com.pengcheng.phoenixgraph.core.Graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Breadth-first search traversal starting from a source vertex.
 *
 * <p>Returns the ids of all vertices reachable from {@code sourceId} in BFS
 * order. Traversal honours each edge's {@link com.pengcheng.phoenixgraph.schema.EdgeDirection}:
 * DIRECTED edges are followed source -&gt; target only, while UNDIRECTED and
 * BIDIRECTIONAL edges are followed in both directions. If the source vertex is
 * not present in the graph, an empty list is returned.
 */
public class BreadthFirstSearch implements GraphAlgorithm<List<Object>> {

    private final Object sourceId;

    public BreadthFirstSearch(Object sourceId) {
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
    }

    @Override
    public String name() {
        return "bfs";
    }

    @Override
    public List<Object> compute(Graph graph) {
        // adjacency: vertexId -> outgoing neighbour ids, honouring edge direction
        Map<Object, List<Object>> adjacency = new HashMap<>();
        try (Stream<Edge> edges = graph.edges()) {
            edges.forEach(edge -> {
                if (edge.isOutgoing(edge.source())) {
                    adjacency.computeIfAbsent(edge.source(), k -> new ArrayList<>()).add(edge.destination());
                }
                if (edge.isOutgoing(edge.destination())) {
                    adjacency.computeIfAbsent(edge.destination(), k -> new ArrayList<>()).add(edge.source());
                }
            });
        }

        List<Object> order = new ArrayList<>();
        if (graph.vertex(sourceId) == null) {
            return order;
        }

        Set<Object> visited = new HashSet<>();
        Deque<Object> queue = new ArrayDeque<>();
        visited.add(sourceId);
        queue.addLast(sourceId);
        while (!queue.isEmpty()) {
            Object current = queue.removeFirst();
            order.add(current);
            for (Object neighbour : adjacency.getOrDefault(current, List.of())) {
                if (visited.add(neighbour)) {
                    queue.addLast(neighbour);
                }
            }
        }
        return order;
    }
}
