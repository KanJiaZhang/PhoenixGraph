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
