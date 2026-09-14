package com.pengcheng.phoenixgraph.query;

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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VertexEdgeQueryEngineTest {

    private final VertexEdgeQueryEngine engine = new VertexEdgeQueryEngine();

    @Test
    void queryWithoutPropertyListReturnsAllPropertiesPlusMeta() {
        InMemoryGraph graph = graph();
        try {
            List<EdgeQueryResult> results = engine.queryEdges(graph, 100L, EdgeQuery.all("knows"));

            assertEquals(Set.of(1L, 2L), edgeIds(results));
            for (EdgeQueryResult result : results) {
                assertEquals("knows", result.edgeSchema());
                assertEquals(Map.of("weight", weight(result.edgeId()), "since", since(result.edgeId())),
                        result.properties());
                // meta properties are always returned
                assertEquals(graph.edge(result.edgeId()).meta(), result.meta());
            }
        } finally {
            graph.close();
        }
    }

    @Test
    void queryWithPropertyListReturnsOnlyRequestedProperties() {
        InMemoryGraph graph = graph();
        try {
            List<EdgeQueryResult> results =
                    engine.queryEdges(graph, 100L, EdgeQuery.select("knows", "weight"));

            assertEquals(Set.of(1L, 2L), edgeIds(results));
            for (EdgeQueryResult result : results) {
                assertEquals(Map.of("weight", weight(result.edgeId())), result.properties());
                assertTrue(result.meta().createdTime() > 0);
                assertTrue(result.meta().lastUpdatedTime() > 0);
            }
        } finally {
            graph.close();
        }
    }

    @Test
    void queryMultipleEdgeTypesEachWithOwnPropertySelection() {
        InMemoryGraph graph = graph();
        try {
            List<EdgeQueryResult> results = engine.queryEdges(graph, 100L,
                    EdgeQuery.select("knows", "weight"),
                    EdgeQuery.all("follows"));

            assertEquals(Set.of(1L, 2L, 3L), edgeIds(results));

            EdgeQueryResult follows = byEdgeId(results, 3L);
            assertEquals("follows", follows.edgeSchema());
            assertEquals(Map.of("level", 3), follows.properties());
            assertEquals(graph.edge(3L).meta(), follows.meta());

            assertEquals(Map.of("weight", weight(1L)), byEdgeId(results, 1L).properties());
            assertEquals(Map.of("weight", weight(2L)), byEdgeId(results, 2L).properties());
        } finally {
            graph.close();
        }
    }

    @Test
    void queryIncludesBothOutgoingAndIncomingEdges() {
        InMemoryGraph graph = graph();
        try {
            List<EdgeQueryResult> results = engine.queryEdges(graph, 100L, EdgeQuery.all("knows"));

            assertEquals(Set.of(1L, 2L), edgeIds(results));
            EdgeQueryResult incoming = byEdgeId(results, 2L);
            assertEquals(102L, incoming.source());
            assertEquals(100L, incoming.destination());
        } finally {
            graph.close();
        }
    }

    @Test
    void queryUnknownPropertyThrowsQueryException() {
        InMemoryGraph graph = graph();
        try {
            assertThrows(QueryException.class,
                    () -> engine.queryEdges(graph, 100L, EdgeQuery.select("knows", "nope")));
        } finally {
            graph.close();
        }
    }

    @Test
    void queryUnknownEdgeTypeReturnsEmpty() {
        InMemoryGraph graph = graph();
        try {
            assertTrue(engine.queryEdges(graph, 100L, EdgeQuery.all("likes")).isEmpty());
        } finally {
            graph.close();
        }
    }

    @Test
    void queryMissingVertexReturnsEmpty() {
        InMemoryGraph graph = graph();
        try {
            assertTrue(engine.queryEdges(graph, 999L, EdgeQuery.all("knows")).isEmpty());
        } finally {
            graph.close();
        }
    }

    @Test
    void blankEdgeSchemaIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new EdgeQuery("  "));
    }

    @Test
    void nullGraphAndVertexAreRejected() {
        InMemoryGraph graph = graph();
        try {
            assertThrows(NullPointerException.class,
                    () -> engine.queryEdges(null, 100L, EdgeQuery.all("knows")));
            assertThrows(NullPointerException.class,
                    () -> engine.queryEdges(graph, null, EdgeQuery.all("knows")));
        } finally {
            graph.close();
        }
    }

    // --- helpers ---

    private static InMemoryGraph graph() {
        InMemoryGraph graph = new InMemoryGraph();
        VertexSchema person = new VertexSchema(1, "person", null);

        EdgeSchema knows = new EdgeSchema(1, "knows", null,
                new EdgePropertySchema(1, "source", null, PropertyType.ScalarType.LONG),
                new EdgePropertySchema(2, "target", null, PropertyType.ScalarType.LONG),
                EdgeDirection.DIRECTED)
                .property(new EdgePropertySchema(3, "weight", null, PropertyType.ScalarType.DOUBLE))
                .property(new EdgePropertySchema(4, "since", null, PropertyType.ScalarType.LONG));

        EdgeSchema follows = new EdgeSchema(2, "follows", null,
                new EdgePropertySchema(1, "source", null, PropertyType.ScalarType.LONG),
                new EdgePropertySchema(2, "target", null, PropertyType.ScalarType.LONG),
                EdgeDirection.DIRECTED)
                .property(new EdgePropertySchema(3, "level", null, PropertyType.ScalarType.INT));

        graph.addVertex(person, 100L, Map.of());
        graph.addVertex(person, 101L, Map.of());
        graph.addVertex(person, 102L, Map.of());

        graph.addEdge(knows, 1L, 100L, 101L, Map.of("weight", 0.5, "since", 2020L));   // out of 100
        graph.addEdge(knows, 2L, 102L, 100L, Map.of("weight", 0.25, "since", 2021L));  // into 100
        graph.addEdge(follows, 3L, 100L, 102L, Map.of("level", 3));                    // out of 100
        return graph;
    }

    private static double weight(Object edgeId) {
        return edgeId.equals(1L) ? 0.5 : 0.25;
    }

    private static long since(Object edgeId) {
        return edgeId.equals(1L) ? 2020L : 2021L;
    }

    private static Set<Object> edgeIds(List<EdgeQueryResult> results) {
        return results.stream().map(EdgeQueryResult::edgeId).collect(Collectors.toSet());
    }

    private static EdgeQueryResult byEdgeId(List<EdgeQueryResult> results, Object edgeId) {
        return results.stream()
                .filter(r -> r.edgeId().equals(edgeId))
                .findFirst()
                .orElseThrow();
    }
}
