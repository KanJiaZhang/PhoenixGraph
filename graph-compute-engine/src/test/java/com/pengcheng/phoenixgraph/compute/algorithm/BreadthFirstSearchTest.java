package com.pengcheng.phoenixgraph.compute.algorithm;

import com.pengcheng.phoenixgraph.schema.EdgeDirection;
import com.pengcheng.phoenixgraph.schema.EdgePropertySchema;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.PropertyType;
import com.pengcheng.phoenixgraph.schema.VertexSchema;
import com.pengcheng.phoenixgraph.storage.memory.InMemoryGraph;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BreadthFirstSearchTest {

    @Test
    void directedTraversalFollowsOnlyOutgoingEdges() {
        InMemoryGraph graph = directedGraph();
        try {
            List<Object> order = new BreadthFirstSearch(1L).compute(graph);

            assertEquals(4, order.size());
            assertEquals(1L, order.get(0));
            assertEquals(Set.of(1L, 2L, 3L, 4L), Set.copyOf(order));
            // 4 is reachable only through 2 and 3, so it must come after both
            assertTrue(order.indexOf(4L) > order.indexOf(2L));
            assertTrue(order.indexOf(4L) > order.indexOf(3L));
        } finally {
            graph.close();
        }
    }

    @Test
    void directedTraversalStopsAtSink() {
        InMemoryGraph graph = directedGraph();
        try {
            // 4 has no outgoing edges in the directed graph
            assertEquals(List.of(4L), new BreadthFirstSearch(4L).compute(graph));
        } finally {
            graph.close();
        }
    }

    @Test
    void undirectedTraversalReachesBothEndpoints() {
        InMemoryGraph graph = undirectedGraph();
        try {
            List<Object> order = new BreadthFirstSearch(4L).compute(graph);

            assertEquals(4, order.size());
            assertEquals(4L, order.get(0));
            assertEquals(Set.of(1L, 2L, 3L, 4L), Set.copyOf(order));
        } finally {
            graph.close();
        }
    }

    @Test
    void missingSourceReturnsEmptyOrder() {
        InMemoryGraph graph = directedGraph();
        try {
            assertEquals(List.of(), new BreadthFirstSearch(99L).compute(graph));
        } finally {
            graph.close();
        }
    }

    @Test
    void nullSourceIsRejected() {
        assertThrows(NullPointerException.class, () -> new BreadthFirstSearch(null));
    }

    private static InMemoryGraph directedGraph() {
        InMemoryGraph graph = new InMemoryGraph();
        VertexSchema person = new VertexSchema(1, "person", null);
        EdgeSchema follows = new EdgeSchema(1, "follows", null,
                new EdgePropertySchema(1, "source", null, PropertyType.ScalarType.LONG),
                new EdgePropertySchema(2, "target", null, PropertyType.ScalarType.LONG),
                EdgeDirection.DIRECTED);

        for (long id : new long[]{1L, 2L, 3L, 4L}) {
            graph.addVertex(person, id, Map.of());
        }
        graph.addEdge(follows, 10L, 1L, 2L, Map.of());
        graph.addEdge(follows, 11L, 1L, 3L, Map.of());
        graph.addEdge(follows, 12L, 2L, 4L, Map.of());
        graph.addEdge(follows, 13L, 3L, 4L, Map.of());
        return graph;
    }

    private static InMemoryGraph undirectedGraph() {
        InMemoryGraph graph = new InMemoryGraph();
        VertexSchema person = new VertexSchema(1, "person", null);
        EdgeSchema friend = new EdgeSchema(1, "friend", null,
                new EdgePropertySchema(1, "source", null, PropertyType.ScalarType.LONG),
                new EdgePropertySchema(2, "target", null, PropertyType.ScalarType.LONG),
                EdgeDirection.UNDIRECTED);

        for (long id : new long[]{1L, 2L, 3L, 4L}) {
            graph.addVertex(person, id, Map.of());
        }
        graph.addEdge(friend, 10L, 1L, 2L, Map.of());
        graph.addEdge(friend, 11L, 1L, 3L, Map.of());
        graph.addEdge(friend, 12L, 2L, 4L, Map.of());
        graph.addEdge(friend, 13L, 3L, 4L, Map.of());
        return graph;
    }
}
