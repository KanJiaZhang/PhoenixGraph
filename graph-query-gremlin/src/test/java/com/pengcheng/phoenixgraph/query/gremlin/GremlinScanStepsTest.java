package com.pengcheng.phoenixgraph.query.gremlin;

import com.pengcheng.phoenixgraph.gemini.GeminiCluster;
import com.pengcheng.phoenixgraph.query.QueryException;
import com.pengcheng.phoenixgraph.query.storage.EdgeQueryResult;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
import com.pengcheng.phoenixgraph.query.storage.GraphQuery;
import com.pengcheng.phoenixgraph.query.storage.ScannedEdge;
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
 * Traversals that start from a whole type instead of from ids: {@code g.V('Account')},
 * {@code g.E('payment')}.
 *
 * <p>These are the questions an analytical engine is asked and a keyed store cannot answer, so they
 * need a store that reports {@link GraphQuery#supportsScan()} - and a query on a store that does not
 * is a clear error rather than an empty answer. The last tests run the distributed algorithm steps on
 * a scan-seeded traversal, which is the form the class-based entry point exists for.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GremlinScanStepsTest {

    /** a1 -> a2 -> a3 -> a4 -> a1, a4 -> a5, on one edge class; a5 has no outgoing payment. */
    private static final Map<String, List<String>> TARGETS = new LinkedHashMap<>(Map.of(
            "a1", List.of("a2"), "a2", List.of("a3"), "a3", List.of("a4"),
            "a4", List.of("a1", "a5")));

    private static final Map<String, String> STATUS = Map.of(
            "a1", "ACTIVE", "a2", "FROZEN", "a3", "ACTIVE", "a4", "ACTIVE", "a5", "FROZEN");

    private static final List<String> ACCOUNTS = List.of("a1", "a2", "a3", "a4", "a5");

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

    // --- the store the traversals read ---

    /** The fake analytical store: point reads plus the two scans. */
    private abstract static class ScanStore implements GraphQuery {

        @Override
        public boolean supportsScan() {
            return true;
        }

        @Override
        public List<VertexQueryResult> scanVertices(String vertexName, List<String> propertyNames,
                                                    int maxResults) {
            List<VertexQueryResult> out = new ArrayList<>();
            for (String id : ACCOUNTS) {
                if (maxResults > 0 && out.size() >= maxResults) {
                    break;
                }
                out.add(vertex(id, propertyNames));
            }
            return out;
        }

        @Override
        public List<ScannedEdge> scanEdges(String edgeName, List<String> propertyNames, int maxResults) {
            List<ScannedEdge> out = new ArrayList<>();
            for (Map.Entry<String, List<String>> entry : TARGETS.entrySet()) {
                for (String target : entry.getValue()) {
                    if (maxResults > 0 && out.size() >= maxResults) {
                        return out;
                    }
                    EdgeQueryResult edge = new EdgeQueryResult(edgeName, entry.getKey() + "->" + target,
                            EdgeSide.BOTH, target, Map.of("amount", 1.0), 1L, 2L);
                    out.add(new ScannedEdge(entry.getKey(), target, edge));
                }
            }
            return out;
        }
    }

    private static VertexQueryResult vertex(String id, List<String> propertyNames) {
        Map<String, Object> properties = new LinkedHashMap<>();
        if (propertyNames.isEmpty() || propertyNames.contains("name")) {
            properties.put("name", "name-" + id);
        }
        if (propertyNames.isEmpty() || propertyNames.contains("status")) {
            properties.put("status", STATUS.get(id));
        }
        return new VertexQueryResult("Account", id, properties, 1L, 2L);
    }

    /** A scan-capable store over the accounts and payments above. */
    /**
     * An edge id names one edge of a class: {@code g.E('a1->a2', 'payment')}. A store that keeps no
     * id index walks the class once for the whole request, so several ids are one scan, and the answer
     * keeps the order the ids were asked in.
     */
    @Test
    void aNamedEdgeIdStartsFromThatEdge() {
        GremlinGraphQuery engine = new GremlinGraphQuery(store(), NeighborVertexResolver.of(Map.of(), Map.of()));

        // one id: the edge itself, and otherV() knows where it goes
        assertEquals(List.of("a1->a2"), engine.execute("g.E('a1->a2','payment').values('id')"));
        assertEquals(List.of("a2"), engine.execute("g.E('a1->a2','payment').otherV().values('id')"));
        // the properties of the edge are there as well
        assertEquals(List.of(1.0), engine.execute("g.E('a1->a2','payment').values('amount')"));

        // several ids: one answer per id, in the order asked
        assertEquals(List.of("a4->a5", "a1->a2"),
                engine.execute("g.E('a4->a5','a1->a2','payment').values('id')"));
        // an id that names no edge of the class is an empty answer, not a failure
        assertEquals(List.of(), engine.execute("g.E('nope','payment').values('id')"));
        // and the whole class is still a scan
        assertEquals(5, ((Number) engine.execute("g.E('payment').count()").get(0)).intValue());
    }

    /** A store without a scan cannot look an edge id up either, and says so instead of guessing. */
    @Test
    void anEdgeIdOnAStoreWithoutAScanSaysWhatIsMissing() {
        QueryException error = assertThrows(QueryException.class,
                () -> new GremlinGraphQuery(keyedStore(), NeighborVertexResolver.of(Map.of(), Map.of()))
                        .execute("g.E('a1->a2','payment')"));
        assertTrue(error.getMessage().contains("edge id index"), error.getMessage());
    }

    private static GraphQuery store() {
        return new ScanStore() {
            @Override
            public VertexQueryResult readVertex(Object vertexId, String vertexName,
                                                List<String> propertyNames) {
                return vertex(String.valueOf(vertexId), propertyNames);
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
                for (Map.Entry<String, List<String>> entry : TARGETS.entrySet()) {
                    if (direction != EdgeSide.IN && entry.getKey().equals(String.valueOf(vertexId))) {
                        for (String target : entry.getValue()) {
                            out.add(new EdgeQueryResult(edgeName, entry.getKey() + "->" + target,
                                    EdgeSide.OUT, target, Map.of("amount", 1.0), 1L, 2L));
                        }
                    }
                    if (direction != EdgeSide.OUT && entry.getValue().contains(String.valueOf(vertexId))) {
                        out.add(new EdgeQueryResult(edgeName, entry.getKey() + "->" + vertexId,
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

    /** A store that answers by key only, to check what a scan-less backend is told. */
    private static GraphQuery keyedStore() {
        GraphQuery scanning = store();
        return new GraphQuery() {
            @Override
            public VertexQueryResult readVertex(Object vertexId, String vertexName,
                                                List<String> propertyNames) {
                return scanning.readVertex(vertexId, vertexName, propertyNames);
            }

            @Override
            public List<EdgeQueryResult> readEdges(Object vertexId, String vertexName, String edgeName,
                                                   EdgeSide direction, List<String> propertyNames) {
                return scanning.readEdges(vertexId, vertexName, edgeName, direction, propertyNames);
            }
        };
    }

    private GremlinGraphQuery engine() {
        return engine(store());
    }

    private GremlinGraphQuery engine(GraphQuery store) {
        return new GremlinGraphQuery(store,
                NeighborVertexResolver.of(Map.of("payment", "Account"), Map.of()))
                .geminiCluster(cluster);
    }

    // --- starting from a vertex type ---

    @Test
    void aVertexTypeStartReadsEveryVertexOfIt() {
        List<Object> names = engine().execute("g.V('Account').values('name')");
        assertEquals(List.of("name-a1", "name-a2", "name-a3", "name-a4", "name-a5"), names);
    }

    @Test
    void aVertexTypeStartCarriesWhatALaterFilterNeeds() {
        // the filter asks for 'status', so the scan projects it - and it is applied to what was read
        List<Object> active = engine().execute("g.V('Account').filter(status.equals('ACTIVE')).values('name')");
        assertEquals(List.of("name-a1", "name-a3", "name-a4"), active);

        assertEquals(List.of(3L), engine().execute("g.V('Account').has('status','ACTIVE').count()"));
    }

    @Test
    void aVertexTypeStartCountsWhatItWalked() {
        assertEquals(List.of(5L), engine().execute("g.V('Account').count()"));
    }

    // --- starting from an edge class ---

    @Test
    void anEdgeClassStartReadsEveryEdgeOfItOnce() {
        assertEquals(List.of(5L), engine().execute("g.E('payment').count()"), "five payments");
        // values() reads the edge properties, exactly as it does after an edge hop
        assertEquals(List.of(1.0, 1.0, 1.0, 1.0, 1.0), engine().execute("g.E('payment').values('amount')"));
        // otherV() is the far endpoint, so a class scan reaches the paying accounts
        List<Object> payers = engine().execute("g.E('payment').otherV().values('name')");
        assertEquals(5, payers.size(), "every payment has a target: " + payers);
    }

    @Test
    void anEdgeClassStartAlsoWalksTheEdgeClassOfTheTraversal() {
        // a filter on the edges themselves, then the reached vertices
        assertEquals(3, engine().execute("g.E('payment').limit(3).values('amount')").size());
        // otherV() on a class-seeded edge is its far endpoint, so the reached accounts are the targets
        assertEquals(List.of("name-a1", "name-a2", "name-a3", "name-a4", "name-a5"),
                engine().execute("g.E('payment').otherV().values('name')").stream().map(String::valueOf)
                        .sorted().toList());
    }

    // --- the distributed steps on a scan-seeded traversal ---

    @Test
    void pageRankRunsOnAClassOfEdges() {
        List<Object> answer = engine().execute("g.E('payment').pagerank(DIST, 10)");

        Map<?, ?> values = assertInstanceOf(Map.class, answer.get(0));
        assertEquals(5, values.size(), "both endpoints of every payment: " + values);
        assertTrue(values.keySet().containsAll(ACCOUNTS));
        for (Object value : values.values()) {
            assertTrue(((Number) value).doubleValue() > 0);
        }
    }

    @Test
    void connectedComponentsRunsFromAFilteredVertexScan() {
        // the query the class-based entry point exists for: the active accounts and the payments they
        // make, with the accounts themselves in the answer
        List<Object> answer = engine().execute(
                "g.V('Account').filter(status.equals('ACTIVE')).bothE('payment').cc(DIST)");

        Map<?, ?> values = assertInstanceOf(Map.class, answer.get(0));
        assertTrue(values.keySet().containsAll(List.of("a1", "a3", "a4")),
                "the active accounts are in the answer: " + values);
        assertTrue(values.size() >= 3, "their neighbours are too: " + values);
        assertEquals(1L, values.values().stream().distinct().count(),
                "the reached accounts are one component: " + values);
    }

    @Test
    void shortestPathsRunFromAClassOfEdges() {
        List<Object> answer = engine().execute("g.E('payment').sssp(DIST, 'a1')");

        Map<?, ?> values = assertInstanceOf(Map.class, answer.get(0));
        assertEquals(0.0, ((Number) values.get("a1")).doubleValue());
        assertEquals(1.0, ((Number) values.get("a2")).doubleValue());
        assertEquals(2.0, ((Number) values.get("a3")).doubleValue());
        assertEquals(3.0, ((Number) values.get("a4")).doubleValue());
        assertEquals(4.0, ((Number) values.get("a5")).doubleValue());
    }

    @Test
    void theSameScanSeededQueryAlsoWorksInMemory() {
        // the in-memory steps take the same input (edges and vertices), so the two engines stay
        // comparable on one query
        List<Object> answer = engine().execute("g.E('payment').cc()");
        Map<?, ?> values = assertInstanceOf(Map.class, answer.get(0));
        assertTrue(values.keySet().containsAll(ACCOUNTS));
        assertEquals(1L, values.values().stream().distinct().count());
    }

    // --- the requirement a scan-less store is told ---

    @Test
    void aStoreThatCannotScanSaysSoInsteadOfAnsweringNothing() {
        GremlinGraphQuery keyed = engine(keyedStore());

        QueryException className = assertThrows(QueryException.class,
                () -> keyed.execute("g.V('Account').count()"));
        assertTrue(className.getMessage().contains("starts from a whole type"), className.getMessage());
        assertTrue(className.getMessage().contains("by id"), className.getMessage());

        QueryException edges = assertThrows(QueryException.class,
                () -> keyed.execute("g.E('payment').count()"));
        assertTrue(edges.getMessage().contains("g.E('payment')"), edges.getMessage());
    }

    @Test
    void aScanSeededTraversalStillChecksItsSteps() {
        // the steps after the scan are the ordinary ones, so their own checks still apply
        assertThrows(QueryException.class, () -> engine().execute("g.V('Account').nope()"));
    }
}
