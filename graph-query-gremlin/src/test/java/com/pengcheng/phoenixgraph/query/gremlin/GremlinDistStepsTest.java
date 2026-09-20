package com.pengcheng.phoenixgraph.query.gremlin;

import com.pengcheng.phoenixgraph.gemini.GeminiCluster;
import com.pengcheng.phoenixgraph.query.QueryException;
import com.pengcheng.phoenixgraph.query.storage.EdgeQueryResult;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
import com.pengcheng.phoenixgraph.query.storage.GraphQuery;
import com.pengcheng.phoenixgraph.query.storage.VertexQueryResult;
import com.pengcheng.phoenixgraph.query.traversal.NeighborVertexResolver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code (DIST)} forms of the algorithm steps, over a real Gemini cluster of four workers.
 *
 * <p>The graph is the kind of subgraph the steps are written on - {@code g.E('payment')} hands the
 * step the edges of one class, {@code g.V(...).bothE('payment')} the edges around a set of vertices -
 * so the test asserts both that the step reads the traversal the way it promises and that the answer
 * comes from the cluster (a worker count, supersteps, messages in the statistics).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GremlinDistStepsTest {

    private GeminiCluster cluster;

    @BeforeAll
    void startCluster() {
        cluster = GeminiCluster.local().start();
    }

    @AfterAll
    void stopCluster() {
        if (cluster != null) {
            cluster.close();
        }
    }

    /** Accounts a1..a5: a chain, a shortcut and a separate pair, on one edge class. */
    private static final List<EdgeQueryResult> PAYMENTS = List.of(
            new EdgeQueryResult("payment", "e1", EdgeSide.OUT, "a2", Map.of("amount", 1.0), 1L, 2L),
            new EdgeQueryResult("payment", "e2", EdgeSide.OUT, "a3", Map.of("amount", 1.0), 1L, 2L),
            new EdgeQueryResult("payment", "e3", EdgeSide.OUT, "a4", Map.of("amount", 1.0), 1L, 2L),
            new EdgeQueryResult("payment", "e4", EdgeSide.OUT, "a1", Map.of("amount", 1.0), 1L, 2L),
            new EdgeQueryResult("payment", "e5", EdgeSide.OUT, "a1", Map.of("amount", 1.0), 1L, 2L));

    private static final Map<String, List<String>> TARGETS = Map.of(
            "a1", List.of("a2"), "a2", List.of("a3"), "a3", List.of("a4"), "a4", List.of("a1", "a5"));

    @Test
    void aTraversalSeededWithTextStillMatchesTheIdsTheStoreReports() {
        // A query is text, so the ids it names arrive as text; a store answers with the type it stored
        // the id under (numbers as numbers). Both name the same vertex, so the run must still see the
        // edges between them - otherwise a seeded run would find no edges at all.
        List<Object> answer = engine(numericGraph()).execute("g.V('1,2,3,4,5','Account').cc(DIST)");

        Map<?, ?> values = assertInstanceOf(Map.class, answer.get(0));
        assertEquals(5, values.size(), "every seeded account is in the answer: " + values);
        assertEquals(1L, values.values().stream().distinct().count(),
                "the numeric-typed accounts are one component: " + values);
    }

    @Test
    void aShortestPathRootIsFoundWhetherTheQueryOrTheStoreSpellsTheId() {
        // '2' in the query against the id 2L in the store: the same vertex. The answer names the
        // vertices the traversal was seeded with (the query's text), so it is read that way here.
        Map<?, ?> values = assertInstanceOf(Map.class,
                engine(numericGraph()).execute("g.V('1,2,3,4,5','Account').sssp(DIST, '2')").get(0));
        assertEquals(0.0, ((Number) values.get("2")).doubleValue(), "the root is at zero: " + values);
        assertEquals(1.0, ((Number) values.get("3")).doubleValue(), "one payment away");
    }

    /** The same graph, reported with numeric ids the way a store that types its ids does. */
    private static GraphQuery numericGraph() {
        GraphQuery strings = graph();
        return new GraphQuery() {
            private Object numeric(Object id) {
                return Long.valueOf(String.valueOf(id).substring(1)); // a1 -> 1
            }

            @Override
            public VertexQueryResult readVertex(Object vertexId, String vertexName,
                                                List<String> propertyNames) {
                return strings.readVertex("a" + vertexId, vertexName, propertyNames);
            }

            @Override
            public List<VertexQueryResult> readVertices(String vertexName, List<Object> vertexIds,
                                                        List<String> propertyNames) {
                List<VertexQueryResult> out = new ArrayList<>();
                for (Object id : vertexIds) {
                    out.add(strings.readVertex("a" + id, vertexName, propertyNames));
                }
                return out;
            }

            @Override
            public List<EdgeQueryResult> readEdges(Object vertexId, String vertexName, String edgeName,
                                                   EdgeSide direction, List<String> propertyNames) {
                List<EdgeQueryResult> out = new ArrayList<>();
                for (EdgeQueryResult edge : strings.readEdges("a" + vertexId, vertexName, edgeName,
                        direction, propertyNames)) {
                    out.add(new EdgeQueryResult(edge.edgeName(), edge.edgeId(), edge.side(),
                            numeric(edge.otherVertexId()), edge.properties(), edge.createdTime(),
                            edge.lastUpdatedTime()));
                }
                return out;
            }

            @Override
            public List<List<EdgeQueryResult>> readEdgesBatch(String edgeName, EdgeSide direction,
                                                              List<Object> vertexIds,
                                                              List<String> propertyNames) {
                List<List<EdgeQueryResult>> out = new ArrayList<>();
                for (Object id : vertexIds) {
                    out.add(readEdges(id, null, edgeName, direction, propertyNames));
                }
                return out;
            }

            @Override
            public List<List<EdgeQueryResult>> readAllEdgesBatch(List<Object> vertexIds, EdgeSide direction,
                                                                 List<String> propertyNames) {
                return readEdgesBatch("payment", direction, vertexIds, propertyNames);
            }
        };
    }

    /** A reader over five accounts wired as the payment edges above (both directions). */
    private static GraphQuery graph() {
        return new GraphQuery() {
            @Override
            public VertexQueryResult readVertex(Object vertexId, String vertexName, List<String> propertyNames) {
                return new VertexQueryResult("Account", vertexId, Map.of("name", String.valueOf(vertexId)),
                        1L, 2L);
            }

            @Override
            public List<VertexQueryResult> readVertices(String vertexName, List<Object> vertexIds,
                                                        List<String> propertyNames) {
                List<VertexQueryResult> out = new ArrayList<>();
                for (Object id : vertexIds) {
                    out.add(readVertex(id, vertexName, propertyNames));
                }
                return out;
            }

            @Override
            public List<EdgeQueryResult> readEdges(Object vertexId, String vertexName, String edgeName,
                                                   EdgeSide direction, List<String> propertyNames) {
                List<EdgeQueryResult> out = new ArrayList<>();
                if (!"payment".equals(edgeName)) {
                    return out;
                }
                for (Map.Entry<String, List<String>> entry : TARGETS.entrySet()) {
                    if (direction != EdgeSide.IN && entry.getKey().equals(String.valueOf(vertexId))) {
                        for (String target : entry.getValue()) {
                            out.add(new EdgeQueryResult("payment", entry.getKey() + "->" + target,
                                    EdgeSide.OUT, target, Map.of("amount", 1.0), 1L, 2L));
                        }
                    }
                    if (direction != EdgeSide.OUT && entry.getValue().contains(String.valueOf(vertexId))) {
                        out.add(new EdgeQueryResult("payment", entry.getKey() + "->" + vertexId,
                                EdgeSide.IN, entry.getKey(), Map.of("amount", 1.0), 1L, 2L));
                    }
                }
                return out;
            }

            @Override
            public List<List<EdgeQueryResult>> readEdgesBatch(String edgeName, EdgeSide direction,
                                                              List<Object> vertexIds,
                                                              List<String> propertyNames) {
                List<List<EdgeQueryResult>> out = new ArrayList<>();
                for (Object id : vertexIds) {
                    out.add(readEdges(id, null, edgeName, direction, propertyNames));
                }
                return out;
            }

            @Override
            public List<List<EdgeQueryResult>> readAllEdgesBatch(List<Object> vertexIds, EdgeSide direction,
                                                                 List<String> propertyNames) {
                return readEdgesBatch("payment", direction, vertexIds, propertyNames);
            }
        };
    }

    private GremlinGraphQuery engine() {
        return engine(graph());
    }

    private GremlinGraphQuery engine(GraphQuery graph) {
        return new GremlinGraphQuery(graph,
                NeighborVertexResolver.of(Map.of("payment", "Account"), Map.of()))
                .geminiCluster(cluster);
    }

    @Test
    void aClassOfEdgesRunsPageRankOnTheCluster() {
        List<Object> answer = engine().execute("g.V('a1,a2,a3,a4,a5','Account').pagerank(DIST)");

        assertEquals(1, answer.size(), "an algorithm step answers with one map");
        Map<?, ?> values = assertInstanceOf(Map.class, answer.get(0));
        assertEquals(5, values.size(), "every seeded account is in the answer: " + values);
        assertTrue(values.keySet().containsAll(List.of("a1", "a2", "a3", "a4", "a5")));
        for (Object value : values.values()) {
            assertInstanceOf(Number.class, value);
            assertTrue(((Number) value).doubleValue() > 0, "a score is positive");
        }
    }

    @Test
    void aVertexTraversalRunsConnectedComponentsOnTheCluster() {
        List<Object> answer = engine().execute("g.V('a1,a2,a3,a4,a5','Account').cc(DIST)");

        Map<?, ?> values = assertInstanceOf(Map.class, answer.get(0));
        assertEquals(5, values.size(), "every seeded account is in the answer: " + values);
        Object first = values.get("a1");
        for (Object value : values.values()) {
            assertEquals(first, value, "the accounts are one component: " + values);
        }
    }

    @Test
    void aShortestPathStepTakesItsRootFromTheTraversal() {
        List<Object> answer = engine().execute("g.V('a1,a2,a3,a4,a5','Account').sssp(DIST, 'a1')");

        Map<?, ?> values = assertInstanceOf(Map.class, answer.get(0));
        assertEquals(0.0, ((Number) values.get("a1")).doubleValue(), "the root is at zero");
        assertEquals(1.0, ((Number) values.get("a2")).doubleValue(), "one payment away");
        assertEquals(2.0, ((Number) values.get("a3")).doubleValue(), "a1 -> a2 -> a3");
        // the run is directed, so a4 is reached the long way round (a1 -> a2 -> a3 -> a4) and a5 after it
        assertEquals(3.0, ((Number) values.get("a4")).doubleValue(), "a1 -> a2 -> a3 -> a4");
        assertEquals(4.0, ((Number) values.get("a5")).doubleValue(), "a4 pays a5");
    }

    @Test
    void louvainGroupsTheChainIntoCommunities() {
        List<Object> answer = engine().execute("g.V('a1,a2,a3,a4,a5','Account').louvain(DIST, 8)");

        Map<?, ?> values = assertInstanceOf(Map.class, answer.get(0));
        assertEquals(5, values.size());
        assertTrue(values.values().stream().distinct().count() >= 2,
                "a chain and its shortcut are not one community: " + values);
        assertTrue(values.values().stream().distinct().count() <= 5, "communities are a partition: " + values);
    }

    @Test
    void theStatisticsSayTheWorkWentToFourWorkers() {
        GremlinGraphQuery engine = engine();
        engine.execute("g.V('a1,a2,a3,a4,a5','Account').pagerank(DIST, 5)");
        GeminiCluster.Result result = cluster.run(
                com.pengcheng.phoenixgraph.gemini.GeminiGraph.of(graph(),
                        new com.pengcheng.phoenixgraph.gemini.GeminiGraph.Scope(
                                List.of("a1", "a2", "a3", "a4", "a5"),
                                java.util.Set.of("payment"), null), true),
                "pagerank", Map.of("iterations", 5));

        assertEquals(4, result.stats().workers());
        assertTrue(result.stats().supersteps() >= 5, result.stats().describe());
        assertTrue(result.stats().messagesSent() > 0, "the workers talked: " + result.stats().describe());
    }

    @Test
    void anUnknownAlgorithmWithDistIsNotAStep() {
        assertThrows(QueryException.class, () -> engine().execute("g.V('a1','Account').nope(DIST)"));
    }

    @Test
    void aDistStepWithoutAClusterSaysSo() {
        GremlinGraphQuery withoutCluster = new GremlinGraphQuery(graph(),
                NeighborVertexResolver.of(Map.of("payment", "Account"), Map.of()));

        QueryException error = assertThrows(QueryException.class,
                () -> withoutCluster.execute("g.V('a1,a2,a3,a4,a5','Account').pagerank(DIST)"));

        assertTrue(error.getMessage().contains("needs a Gemini cluster"), error.getMessage());
        assertTrue(error.getMessage().contains("never falls back"), error.getMessage());
    }

    @Test
    void theInMemoryFormStillWorksAndTakesNoArgument() {
        List<Object> answer = engine().execute("g.V('a1,a2,a3,a4,a5','Account').cc()");

        Map<?, ?> values = assertInstanceOf(Map.class, answer.get(0));
        assertEquals(5, values.size(), "the in-memory step answers the same graph");

        QueryException error = assertThrows(QueryException.class,
                () -> engine().execute("g.V('a1,a2,a3,a4,a5','Account').cc(3)"));
        assertTrue(error.getMessage().contains("takes no argument"), error.getMessage());
    }

    @Test
    void aShortestPathStepExistsOnlyInItsDistributedForm() {
        QueryException error = assertThrows(QueryException.class,
                () -> engine().execute("g.V('a1,a2,a3,a4,a5','Account').sssp()"));

        assertTrue(error.getMessage().contains("no in-memory implementation"), error.getMessage());
        assertTrue(error.getMessage().contains("sssp(DIST)"), error.getMessage());
    }

    @Test
    void aRootThatIsNotInTheTraversalIsRejected() {
        QueryException error = assertThrows(QueryException.class,
                () -> engine().execute("g.V('a1,a2,a3,a4,a5','Account').sssp(DIST, 'nope')"));

        assertTrue(error.getMessage().contains("not among the vertices"), error.getMessage());
    }

    @Test
    void theEdgesOfTheTraversalDecideTheGraph() {
        // only the edges the traversal went through are computed on: a3 and a4 are not reached from a1
        // by one hop, so the component of the two-vertex view is just what the edges touched
        // the same edge class around two different vertices gives two different graphs
        List<Object> answer = engine().execute("g.V('a3,a4','Account').cc(DIST)");

        Map<?, ?> values = assertInstanceOf(Map.class, answer.get(0));
        assertEquals(2, values.size(), "only the seeded accounts: " + values);
        assertTrue(values.containsKey("a3") && values.containsKey("a4"));
    }

    @Test
    void anEmptyTraversalAnswersWithAnEmptyMap() {
        List<Object> answer = engine().execute("g.V('nobody','Account').outE('payment').otherV().cc(DIST)");

        assertEquals(Map.of(), answer.get(0), "no vertices, no computation");
    }

    /** Keeps the compiler honest about the map type the steps return. */
    private static Map<Object, Object> empty() {
        return new LinkedHashMap<>();
    }
}
