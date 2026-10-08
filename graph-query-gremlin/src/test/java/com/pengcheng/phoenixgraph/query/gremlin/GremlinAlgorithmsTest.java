package com.pengcheng.phoenixgraph.query.gremlin;

import com.pengcheng.phoenixgraph.query.storage.EdgeQueryResult;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
import com.pengcheng.phoenixgraph.query.storage.GraphQuery;
import com.pengcheng.phoenixgraph.query.storage.VertexQueryResult;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.pengcheng.phoenixgraph.query.traversal.NeighborVertexResolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GremlinAlgorithmsTest {

    @Test
    void pageRankScoresInLinkedChain() {
        Map<Object, List<Object>> adj = new LinkedHashMap<>();
        adj.put("a", List.of("b"));
        adj.put("b", List.of("c"));
        adj.put("c", List.of()); // dangling

        Map<Object, Double> pr = GremlinAlgorithms.pageRank(adj);

        assertEquals(Set.of("a", "b", "c"), pr.keySet());
        // c receives from b, a receives from nobody, so c > a
        assertTrue(pr.get("c") > pr.get("a"));
        assertTrue(pr.get("b") > pr.get("a"));
    }

    @Test
    void connectedComponentsGroupsVertices() {
        Map<Object, List<Object>> adj = new LinkedHashMap<>();
        adj.put("a", List.of("b"));
        adj.put("b", List.of("a"));
        adj.put("c", List.of("d"));
        adj.put("d", List.of("c"));

        Map<Object, Long> cc = GremlinAlgorithms.connectedComponents(adj);

        assertEquals(cc.get("a"), cc.get("b"));
        assertEquals(cc.get("c"), cc.get("d"));
        assertNotEquals(cc.get("a"), cc.get("c"));
    }

    @Test
    void louvainFindsTwoCommunities() {
        // two triangles joined by a single bridge edge c-d
        Map<Object, List<Object>> adj = new LinkedHashMap<>();
        adj.put("a", List.of("b", "c"));
        adj.put("b", List.of("a", "c"));
        adj.put("c", List.of("a", "b", "d"));
        adj.put("d", List.of("c", "e", "f"));
        adj.put("e", List.of("d", "f"));
        adj.put("f", List.of("d", "e"));

        Map<Object, Long> comm = GremlinAlgorithms.louvain(adj);

        assertEquals(comm.get("a"), comm.get("b"));
        assertEquals(comm.get("e"), comm.get("f"));
        assertNotEquals(comm.get("a"), comm.get("e"));
    }

    /** A path v0-v1-...-v(n-1), both directions, as the in-memory steps build it. */
    private static Map<Object, List<Object>> path(int n) {
        Map<Object, List<Object>> adj = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            adj.put("v" + i, new java.util.ArrayList<>());
        }
        for (int i = 0; i + 1 < n; i++) {
            adj.get("v" + i).add("v" + (i + 1));
            adj.get("v" + (i + 1)).add("v" + i);
        }
        return adj;
    }

    @Test
    void degreeCentralityCountsTheIncidentEdges() {
        Map<Object, Long> degree = GremlinAlgorithms.degreeCentrality(path(5));

        assertEquals(1L, degree.get("v0"));
        assertEquals(2L, degree.get("v2"));
        assertEquals(1L, degree.get("v4"));
    }

    @Test
    void closenessRanksTheMiddleOfAPathHighest() {
        // a path of five: the middle is 2+1+1+2 = 6 hops from everything, an end is 1+2+3+4 = 10
        Map<Object, Double> closeness = GremlinAlgorithms.closenessCentrality(path(5));

        assertEquals(16.0 / (4 * 6), closeness.get("v2"), 1e-12);
        assertEquals(16.0 / (4 * 10), closeness.get("v0"), 1e-12);
        assertTrue(closeness.get("v2") > closeness.get("v1"));
        assertTrue(closeness.get("v1") > closeness.get("v0"));
    }

    @Test
    void closenessOfACliqueIsNotPunishedForUnreachableVertices() {
        // a triangle plus two vertices nothing connects to: the Wasserman-Faust form measures a vertex
        // against the vertices it can reach, so the score does not drop because the graph is bigger
        Map<Object, List<Object>> adj = path(3); // v0-v1-v2, made a triangle below
        adj.get("v0").add("v2");
        adj.get("v2").add("v0");
        adj.put("v3", new java.util.ArrayList<>());
        adj.put("v4", new java.util.ArrayList<>());

        Map<Object, Double> closeness = GremlinAlgorithms.closenessCentrality(adj);

        // three vertices, two hops each, in a graph of five: (3-1)^2 / ((5-1) * 2) = 1/2. The
        // (n-1)-normalised form would say 1/4 and blame the vertex for the two it cannot reach.
        assertEquals(0.5, closeness.get("v0"), 1e-12);
        assertEquals(0.0, closeness.get("v3"), 1e-12, "a vertex that reaches nobody scores zero");
    }

    @Test
    void betweennessCountsTheShortestPathsThroughAVertex() {
        // on a path of five the middle is on four of the six pairs around it (its own included, as the
        // normalisation counts them), b and d on three, the ends on none
        Map<Object, Double> betweenness = GremlinAlgorithms.betweennessCentrality(path(5));

        assertEquals(4.0 / 6.0, betweenness.get("v2"), 1e-12);
        assertEquals(3.0 / 6.0, betweenness.get("v1"), 1e-12);
        assertEquals(3.0 / 6.0, betweenness.get("v3"), 1e-12);
        assertEquals(0.0, betweenness.get("v0"), 1e-12);
        assertEquals(0.0, betweenness.get("v4"), 1e-12);
    }

    @Test
    void betweennessOnAStarIsAllOnTheHub() {
        Map<Object, List<Object>> adj = new LinkedHashMap<>();
        for (Object v : List.of("hub", "l1", "l2", "l3")) {
            adj.put(v, new java.util.ArrayList<>());
        }
        for (String leaf : List.of("l1", "l2", "l3")) {
            adj.get("hub").add(leaf);
            adj.get(leaf).add("hub");
        }

        Map<Object, Double> betweenness = GremlinAlgorithms.betweennessCentrality(adj);

        assertEquals(1.0, betweenness.get("hub"), 1e-12, "every path between the leaves goes through it");
        assertEquals(0.0, betweenness.get("l1"), 1e-12);
    }

    @Test
    void eigenvectorCentralityFindsThePrincipalVectorOfACompleteGraph() {
        // a complete graph of four plus an isolated vertex: the principal eigenvector is the all-ones
        // vector (every vertex of a complete graph is equally central, and a vertex nothing connects to
        // has no influence), and the gap to the next eigenvalue is wide enough for a power iteration to
        // settle in the iterations the step runs
        Map<Object, List<Object>> adj = new LinkedHashMap<>();
        for (int i = 0; i < 5; i++) {
            adj.put("v" + i, new java.util.ArrayList<>());
        }
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                if (i != j) {
                    adj.get("v" + i).add("v" + j);
                }
            }
        }

        Map<Object, Double> eigenvector = GremlinAlgorithms.eigenvectorCentrality(adj);

        for (int i = 0; i < 4; i++) {
            assertEquals(0.5, eigenvector.get("v" + i), 1e-9, "the four are equal: " + eigenvector);
        }
        assertEquals(0.0, eigenvector.get("v4"), 1e-12, "nothing points at it and it points at nothing");
    }

    @Test
    void normalizeScoresFixesTheLengthAndTheSign() {
        Map<Object, Double> normalised = GremlinAlgorithms.normalizeScores(
                new LinkedHashMap<>(Map.of("a", -3.0, "b", -4.0)));

        // the vector is scaled to unit length, and the sign is chosen so its largest entry is positive
        assertEquals(0.6, normalised.get("a"), 1e-12);
        assertEquals(0.8, normalised.get("b"), 1e-12);
        assertEquals(1.0, Math.hypot(normalised.get("a"), normalised.get("b")), 1e-12);
        assertEquals(0.0, GremlinAlgorithms.normalizeScores(new LinkedHashMap<>(Map.of("a", 0.0))).get("a"));
    }

    @Test
    void ccStepRunsInMemoryAlgorithm() {
        GraphQuery gq = new GraphQuery() {
            @Override
            public VertexQueryResult readVertex(Object vertexId, String vertexName, List<String> propertyNames) {
                return new VertexQueryResult("p", vertexId, Map.of(), 1L, 2L);
            }

            @Override
            public List<VertexQueryResult> readVertices(String vertexName, List<Object> vertexIds,
                                                        List<String> propertyNames) {
                return List.of();
            }

            @Override
            public List<EdgeQueryResult> readEdges(Object vertexId, String vertexName, String edgeName,
                                                   EdgeSide direction, List<String> propertyNames) {
                if (edgeName.equals("knows")) {
                    return List.of(
                            new EdgeQueryResult("knows", "e1", EdgeSide.OUT, "n1", Map.of(), 1L, 2L),
                            new EdgeQueryResult("knows", "e2", EdgeSide.OUT, "n2", Map.of(), 1L, 2L));
                }
                return List.of();
            }

            @Override
            public List<List<EdgeQueryResult>> readEdgesBatch(String edgeName, EdgeSide direction,
                                                              List<Object> vertexIds, List<String> propertyNames) {
                return List.of();
            }

            @Override
            public List<List<EdgeQueryResult>> readAllEdgesBatch(List<Object> vertexIds, EdgeSide direction,
                                                                 List<String> propertyNames) {
                if (vertexIds.equals(List.of("n1", "n2"))) {
                    // n1 <-> n2
                    return List.of(
                            List.of(new EdgeQueryResult("knows", "e1", EdgeSide.OUT, "n2", Map.of(), 1L, 2L)),
                            List.of(new EdgeQueryResult("knows", "e2", EdgeSide.IN, "n1", Map.of(), 1L, 2L)));
                }
                return List.of();
            }
        };

        GremlinGraphQuery gremlin = new GremlinGraphQuery(gq,
                NeighborVertexResolver.of(Map.of("knows", "p"), Map.of()));

        List<Object> result = gremlin.execute("g.V('v1','p').out('knows').cc()");

        assertEquals(1, result.size());
        @SuppressWarnings("unchecked")
        Map<Object, Long> cc = (Map<Object, Long>) result.get(0);
        assertEquals(cc.get("n1"), cc.get("n2"));
    }
}
