package com.pengcheng.phoenixgraph.compute.algorithm;

import com.pengcheng.phoenixgraph.schema.EdgeDirection;
import com.pengcheng.phoenixgraph.schema.EdgePropertySchema;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.PropertyType;
import com.pengcheng.phoenixgraph.schema.VertexSchema;
import com.pengcheng.phoenixgraph.storage.memory.InMemoryGraph;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PageRankAlgorithmTest {

    private static final double TOLERANCE = 1e-9;

    @Test
    void symmetricCycleYieldsUniformRank() {
        InMemoryGraph graph = cycleGraph();
        try {
            Map<Object, Double> rank = new PageRankAlgorithm(0.85, 50).compute(graph);

            assertEquals(3, rank.size());
            assertEquals(1.0 / 3.0, rank.get(1L), TOLERANCE);
            assertEquals(1.0 / 3.0, rank.get(2L), TOLERANCE);
            assertEquals(1.0 / 3.0, rank.get(3L), TOLERANCE);
        } finally {
            graph.close();
        }
    }

    @Test
    void undirectedPairYieldsUniformRank() {
        InMemoryGraph graph = new InMemoryGraph();
        VertexSchema node = new VertexSchema(1, "node", null);
        EdgeSchema link = new EdgeSchema(1, "link", null,
                new EdgePropertySchema(1, "source", null, PropertyType.ScalarType.LONG),
                new EdgePropertySchema(2, "target", null, PropertyType.ScalarType.LONG),
                EdgeDirection.UNDIRECTED);
        graph.addVertex(node, 1L, Map.of());
        graph.addVertex(node, 2L, Map.of());
        graph.addEdge(link, 10L, 1L, 2L, Map.of());

        try {
            Map<Object, Double> rank = new PageRankAlgorithm(0.85, 50).compute(graph);

            assertEquals(0.5, rank.get(1L), TOLERANCE);
            assertEquals(0.5, rank.get(2L), TOLERANCE);
        } finally {
            graph.close();
        }
    }

    @Test
    void directedLinkConcentratesRankOnTarget() {
        InMemoryGraph graph = new InMemoryGraph();
        VertexSchema node = new VertexSchema(1, "node", null);
        EdgeSchema link = directedEdgeSchema();
        graph.addVertex(node, 1L, Map.of());
        graph.addVertex(node, 2L, Map.of());
        graph.addEdge(link, 10L, 1L, 2L, Map.of());

        try {
            Map<Object, Double> rank = new PageRankAlgorithm(0.85, 100).compute(graph);

            // 2 (the dangling target) accumulates almost all rank mass
            assertTrue(rank.get(2L) > rank.get(1L));
            assertEquals(0.6491, rank.get(2L), 1e-3);
            assertEquals(0.3509, rank.get(1L), 1e-3);
        } finally {
            graph.close();
        }
    }

    @Test
    void totalRankIsConserved() {
        InMemoryGraph graph = directedChainGraph();
        try {
            Map<Object, Double> rank = new PageRankAlgorithm(0.85, 30).compute(graph);

            double total = rank.values().stream().mapToDouble(Double::doubleValue).sum();
            assertEquals(1.0, total, 1e-9);
            assertEquals(4, rank.size());
        } finally {
            graph.close();
        }
    }

    @Test
    void emptyGraphYieldsEmptyRank() {
        InMemoryGraph graph = new InMemoryGraph();
        try {
            assertTrue(new PageRankAlgorithm(0.85, 10).compute(graph).isEmpty());
        } finally {
            graph.close();
        }
    }

    @Test
    void invalidArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PageRankAlgorithm(-0.1, 10));
        assertThrows(IllegalArgumentException.class, () -> new PageRankAlgorithm(1.1, 10));
        assertThrows(IllegalArgumentException.class, () -> new PageRankAlgorithm(0.85, -1));
    }

    private static InMemoryGraph cycleGraph() {
        InMemoryGraph graph = new InMemoryGraph();
        VertexSchema node = new VertexSchema(1, "node", null);
        EdgeSchema link = directedEdgeSchema();
        for (long id : new long[]{1L, 2L, 3L}) {
            graph.addVertex(node, id, Map.of());
        }
        graph.addEdge(link, 10L, 1L, 2L, Map.of());
        graph.addEdge(link, 11L, 2L, 3L, Map.of());
        graph.addEdge(link, 12L, 3L, 1L, Map.of());
        return graph;
    }

    private static InMemoryGraph directedChainGraph() {
        InMemoryGraph graph = new InMemoryGraph();
        VertexSchema node = new VertexSchema(1, "node", null);
        EdgeSchema link = directedEdgeSchema();
        for (long id : new long[]{1L, 2L, 3L, 4L}) {
            graph.addVertex(node, id, Map.of());
        }
        graph.addEdge(link, 10L, 1L, 2L, Map.of());
        graph.addEdge(link, 11L, 2L, 3L, Map.of());
        graph.addEdge(link, 12L, 3L, 4L, Map.of());
        return graph;
    }

    private static EdgeSchema directedEdgeSchema() {
        return new EdgeSchema(1, "link", null,
                new EdgePropertySchema(1, "source", null, PropertyType.ScalarType.LONG),
                new EdgePropertySchema(2, "target", null, PropertyType.ScalarType.LONG),
                EdgeDirection.DIRECTED);
    }
}
