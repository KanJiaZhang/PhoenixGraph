package com.pengcheng.phoenixgraph.query.gremlin;

import com.pengcheng.phoenixgraph.query.QueryException;
import com.pengcheng.phoenixgraph.query.storage.EdgeQueryResult;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
import com.pengcheng.phoenixgraph.query.storage.GraphQuery;
import com.pengcheng.phoenixgraph.query.storage.VertexQueryResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GremlinGraphQueryTest {

    private final GraphQuery graphQuery = mock(GraphQuery.class);
    private final NeighborVertexResolver resolver = NeighborVertexResolver.of(
            Map.of("knows", "person", "created", "software"),
            Map.of("knows", "person"));
    private final GremlinGraphQuery gremlin = new GremlinGraphQuery(graphQuery, resolver);

    @BeforeEach
    void stubStartVertex() {
        when(graphQuery.readVertex("v1", "person", List.of()))
                .thenReturn(new VertexQueryResult("person", "v1", Map.of(), 1L, 2L));
        when(graphQuery.readVertices(anyString(), anyList(), anyList()))
                .thenAnswer(inv -> {
                    String name = inv.getArgument(0);
                    List<Object> ids = inv.getArgument(1);
                    List<String> props = inv.getArgument(2);
                    List<VertexQueryResult> out = new ArrayList<>(ids.size());
                    for (Object id : ids) {
                        out.add(graphQuery.readVertex(id, name, props));
                    }
                    return out;
                });
    }

    @Test
    void outEReturnsEdges() {
        EdgeQueryResult edge = edge("e1", EdgeSide.OUT, "n1", Map.of("weight", 0.5));
        when(graphQuery.readEdges("v1", "person", "knows", EdgeSide.OUT, List.of()))
                .thenReturn(List.of(edge));

        List<Object> result = gremlin.execute("g.V('v1', 'person').outE('knows')");

        assertEquals(1, result.size());
        GremlinEdge gremlinEdge = (GremlinEdge) result.get(0);
        assertEquals("e1", gremlinEdge.id());
        assertEquals(EdgeSide.OUT, gremlinEdge.side());
        assertEquals("n1", gremlinEdge.otherVertexId());
        assertEquals(Map.of("weight", 0.5), gremlinEdge.properties());
    }

    @Test
    void outReturnsNeighbourVertices() {
        EdgeQueryResult edge = edge("e1", EdgeSide.OUT, "n1", Map.of());
        when(graphQuery.readEdges("v1", "person", "knows", EdgeSide.OUT, List.of()))
                .thenReturn(List.of(edge));
        when(graphQuery.readVertex("n1", "person", List.of()))
                .thenReturn(new VertexQueryResult("person", "n1", Map.of("name", "alice"), 5L, 6L));

        List<Object> result = gremlin.execute("g.V('v1', 'person').out('knows')");

        assertEquals(1, result.size());
        GremlinVertex vertex = (GremlinVertex) result.get(0);
        assertEquals("n1", vertex.id());
        assertEquals("person", vertex.label());
        assertEquals(Map.of("name", "alice"), vertex.properties());
    }

    @Test
    void twoHopVertexTraversalFollowsNeighbours() {
        EdgeQueryResult e1 = edge("e1", EdgeSide.OUT, "n1", Map.of());
        EdgeQueryResult e2 = edge("e2", EdgeSide.OUT, "n2", Map.of());
        when(graphQuery.readEdges("v1", "person", "knows", EdgeSide.OUT, List.of()))
                .thenReturn(List.of(e1, e2));
        when(graphQuery.readVertex("n1", "person", List.of()))
                .thenReturn(new VertexQueryResult("person", "n1", Map.of(), 5L, 6L));
        when(graphQuery.readVertex("n2", "person", List.of()))
                .thenReturn(new VertexQueryResult("person", "n2", Map.of(), 7L, 8L));

        EdgeQueryResult e3 = edge("e3", EdgeSide.OUT, "s1", Map.of());
        when(graphQuery.readEdgesBatch("created", EdgeSide.OUT, List.of("n1", "n2"), List.of()))
                .thenReturn(List.of(List.of(e3), List.of()));
        when(graphQuery.readVertex("s1", "software", List.of()))
                .thenReturn(new VertexQueryResult("software", "s1", Map.of("name", "gremlin"), 9L, 10L));

        List<Object> result = gremlin.execute("g.V('v1', 'person').out('knows').out('created')");

        assertEquals(1, result.size());
        GremlinVertex vertex = (GremlinVertex) result.get(0);
        assertEquals("s1", vertex.id());
        assertEquals("software", vertex.label());
        assertEquals(Map.of("name", "gremlin"), vertex.properties());
    }

    @Test
    void hasFiltersEdgesByPropertyValue() {
        EdgeQueryResult e1 = edge("e1", EdgeSide.OUT, "n1", Map.of("weight", 0.5));
        EdgeQueryResult e2 = edge("e2", EdgeSide.OUT, "n2", Map.of("weight", 0.25));
        when(graphQuery.readEdges("v1", "person", "knows", EdgeSide.OUT, List.of()))
                .thenReturn(List.of(e1, e2));

        List<Object> result =
                gremlin.execute("g.V('v1', 'person').outE('knows').has('weight', 0.5)");

        assertEquals(1, result.size());
        assertEquals("e1", ((GremlinEdge) result.get(0)).id());
    }

    @Test
    void valuesProjectsPropertyValues() {
        EdgeQueryResult e1 = edge("e1", EdgeSide.OUT, "n1", Map.of("weight", 0.5));
        EdgeQueryResult e2 = edge("e2", EdgeSide.OUT, "n2", Map.of("weight", 0.25));
        when(graphQuery.readEdges("v1", "person", "knows", EdgeSide.OUT, List.of()))
                .thenReturn(List.of(e1, e2));

        List<Object> result = gremlin.execute("g.V('v1', 'person').outE('knows').values('weight')");

        assertEquals(List.of(0.5, 0.25), result);
    }

    @Test
    void elementMapProjectsIdLabelAndProperties() {
        EdgeQueryResult e1 = edge("e1", EdgeSide.OUT, "n1", Map.of("weight", 0.5));
        when(graphQuery.readEdges("v1", "person", "knows", EdgeSide.OUT, List.of()))
                .thenReturn(List.of(e1));

        List<Object> result = gremlin.execute("g.V('v1', 'person').outE('knows').elementMap()");

        assertEquals(1, result.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result.get(0);
        assertEquals("e1", map.get("id"));
        assertEquals("knows", map.get("label"));
        assertEquals(0.5, map.get("weight"));
    }

    @Test
    void countCountsFinalElements() {
        EdgeQueryResult e1 = edge("e1", EdgeSide.OUT, "n1", Map.of());
        EdgeQueryResult e2 = edge("e2", EdgeSide.OUT, "n2", Map.of());
        when(graphQuery.readEdges("v1", "person", "knows", EdgeSide.OUT, List.of()))
                .thenReturn(List.of(e1, e2));

        List<Object> result = gremlin.execute("g.V('v1', 'person').outE('knows').count()");

        assertEquals(List.of(2L), result);
    }

    @Test
    void limitTruncatesAndDedupRemovesDuplicates() {
        EdgeQueryResult e1 = edge("e1", EdgeSide.OUT, "n1", Map.of());
        when(graphQuery.readEdges("v1", "person", "knows", EdgeSide.OUT, List.of()))
                .thenReturn(List.of(e1, e1));

        assertEquals(1, gremlin.execute("g.V('v1', 'person').outE('knows').limit(1)").size());
        assertEquals(1, gremlin.execute("g.V('v1', 'person').outE('knows').dedup()").size());
    }

    @Test
    void customStepCanBeRegistered() {
        EdgeQueryResult e1 = edge("e1", EdgeSide.OUT, "n1", Map.of());
        when(graphQuery.readEdges("v1", "person", "knows", EdgeSide.OUT, List.of()))
                .thenReturn(List.of(e1, e1));

        GremlinGraphQuery custom = new GremlinGraphQuery(graphQuery, resolver)
                .registerStep("doubleCount", args -> (ctx, input) -> List.of(input.size() * 2L));

        assertEquals(List.of(4L), custom.execute("g.V('v1', 'person').outE('knows').doubleCount()"));
    }

    @Test
    void unknownStepThrows() {
        assertThrows(QueryException.class,
                () -> gremlin.execute("g.V('v1', 'person').jump('knows')"));
    }

    @Test
    void fanOutUsesNativeBatchReadChunkedByBatchSize() {
        List<List<Object>> batches = new ArrayList<>();
        GraphQuery fanOut = batchTrackingGraph(30, batches);
        GremlinGraphQuery query = new GremlinGraphQuery(fanOut,
                NeighborVertexResolver.of(Map.of("knows", "p"), Map.of()));

        List<Object> result = query.execute("g.V('v1', 'p').batch(10).out('knows')");

        assertEquals(30, result.size());
        assertEquals(List.of(10, 10, 10), batches.stream().map(List::size).toList());
    }

    @Test
    void batchOneReadsNeighboursOneAtATime() {
        List<List<Object>> batches = new ArrayList<>();
        GraphQuery fanOut = batchTrackingGraph(5, batches);
        GremlinGraphQuery query = new GremlinGraphQuery(fanOut,
                NeighborVertexResolver.of(Map.of("knows", "p"), Map.of()));

        List<Object> result = query.execute("g.V('v1', 'p').batch(1).out('knows')");

        assertEquals(5, result.size());
        assertEquals(List.of(1, 1, 1, 1, 1), batches.stream().map(List::size).toList());
    }

    @Test
    void batchWithInvalidSizeThrows() {
        assertThrows(QueryException.class,
                () -> gremlin.execute("g.V('v1', 'person').batch(0).out('knows')"));
    }

    @Test
    void barrierStepIsAccepted() {
        EdgeQueryResult e1 = edge("e1", EdgeSide.OUT, "n1", Map.of());
        when(graphQuery.readEdges("v1", "person", "knows", EdgeSide.OUT, List.of()))
                .thenReturn(List.of(e1));

        List<Object> result = gremlin.execute("g.V('v1', 'person').barrier(20).outE('knows')");

        assertEquals(1, result.size());
        assertEquals("e1", ((GremlinEdge) result.get(0)).id());
    }

    @Test
    void fanOutUsesNativeBatchReadForEdges() {
        List<List<Object>> edgeBatches = new ArrayList<>();
        GraphQuery fanOut = edgeBatchTrackingGraph(30, edgeBatches);
        GremlinGraphQuery query = new GremlinGraphQuery(fanOut,
                NeighborVertexResolver.of(Map.of("knows", "p"), Map.of()));

        List<Object> result = query.execute("g.V('v1', 'p').batch(10).out('knows').outE('created')");

        assertEquals(0, result.size()); // 'created' has no edges in this fake
        assertEquals(List.of(10, 10, 10), edgeBatches.stream().map(List::size).toList());
    }

    @Test
    void intermediateHopsSkipVertexReads() {
        List<Object> readVertexIds = new ArrayList<>();
        GraphQuery gq = new GraphQuery() {
            @Override
            public VertexQueryResult readVertex(Object vertexId, String vertexName, List<String> propertyNames) {
                return new VertexQueryResult("p", vertexId, Map.of(), 1L, 2L);
            }

            @Override
            public List<VertexQueryResult> readVertices(String vertexName, List<Object> vertexIds,
                                                        List<String> propertyNames) {
                readVertexIds.addAll(vertexIds);
                List<VertexQueryResult> out = new ArrayList<>(vertexIds.size());
                for (Object id : vertexIds) {
                    out.add(new VertexQueryResult(vertexName, id, Map.of("name", "x"), 1L, 2L));
                }
                return out;
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
                List<List<EdgeQueryResult>> out = new ArrayList<>();
                for (Object id : vertexIds) {
                    if (id.equals("n1")) {
                        out.add(List.of(new EdgeQueryResult("created", "e3", EdgeSide.OUT, "s1", Map.of(), 1L, 2L)));
                    } else {
                        out.add(List.of());
                    }
                }
                return out;
            }
        };

        GremlinGraphQuery gremlin = new GremlinGraphQuery(gq,
                NeighborVertexResolver.of(Map.of("knows", "p", "created", "s"), Map.of()));

        List<Object> result = gremlin.execute("g.V('v1', 'p').out('knows').out('created')");

        assertEquals(1, result.size());
        GremlinVertex vertex = (GremlinVertex) result.get(0);
        assertEquals("s1", vertex.id());
        assertEquals("s", vertex.label());
        assertEquals(Map.of("name", "x"), vertex.properties());
        // intermediate vertices n1/n2 were NOT read; only the final s1 was materialized
        assertEquals(List.of("s1"), readVertexIds);
    }

    @Test
    void startsFromMultipleVertices() {
        when(graphQuery.readVertex("v1", "person", List.of()))
                .thenReturn(new VertexQueryResult("person", "v1", Map.of("name", "a"), 1L, 2L));
        when(graphQuery.readVertex("v2", "person", List.of()))
                .thenReturn(new VertexQueryResult("person", "v2", Map.of("name", "b"), 3L, 4L));

        List<Object> result = gremlin.execute("g.V('v1','v2', 'person')");

        assertEquals(2, result.size());
        assertEquals("v1", ((GremlinVertex) result.get(0)).id());
        assertEquals("v2", ((GremlinVertex) result.get(1)).id());
    }

    @Test
    void userDefinedLogicIsInlined() {
        EdgeQueryResult e1 = edge("e1", EdgeSide.OUT, "n1", Map.of("status", "SUCCESS"));
        EdgeQueryResult e2 = edge("e2", EdgeSide.OUT, "n2", Map.of("status", "FAILED"));
        when(graphQuery.readEdges("v1", "person", "knows", EdgeSide.OUT, List.of()))
                .thenReturn(List.of(e1, e2));

        GremlinGraphQuery query = new GremlinGraphQuery(graphQuery, resolver)
                .registerLogic("logic", ".has('status','SUCCESS').count()");

        List<Object> result = query.execute("g.V('v1','person').outE('knows') { $logic }");

        assertEquals(List.of(1L), result);
    }

    @Test
    void compilesQueryOnlyOnce() {
        EdgeQueryResult e1 = edge("e1", EdgeSide.OUT, "n1", Map.of());
        when(graphQuery.readEdges("v1", "person", "knows", EdgeSide.OUT, List.of()))
                .thenReturn(List.of(e1));

        int[] resolves = {0};
        GremlinStepRegistry countingRegistry = new GremlinStepRegistry() {
            @Override
            public GremlinStep resolve(String name, List<String> args) {
                resolves[0]++;
                return super.resolve(name, args);
            }
        };
        GremlinGraphQuery query = new GremlinGraphQuery(graphQuery, resolver, countingRegistry);

        String q = "g.V('v1','person').outE('knows').count()";
        query.execute(q);
        query.execute(q);
        query.execute(q);

        // outE + count = 2 steps, resolved only on the first (cached) execution
        assertEquals(2, resolves[0]);
    }

    @Test
    void registerLogicInvalidatesCompiledCache() {
        EdgeQueryResult e1 = edge("e1", EdgeSide.OUT, "n1", Map.of());
        EdgeQueryResult e2 = edge("e2", EdgeSide.OUT, "n2", Map.of());
        when(graphQuery.readEdges("v1", "person", "knows", EdgeSide.OUT, List.of()))
                .thenReturn(List.of(e1, e2));

        GremlinGraphQuery query = new GremlinGraphQuery(graphQuery, resolver)
                .registerLogic("l", ".limit(2)");
        assertEquals(2, query.execute("g.V('v1','person').outE('knows') { $l }").size());

        query.registerLogic("l", ".limit(1)");
        assertEquals(1, query.execute("g.V('v1','person').outE('knows') { $l }").size());
    }

    @Test
    void longMultiLineLogicCompilesOnce() {
        String logic = """
                .has('name', 'x')
                .dedup()
                .has('name', 'x')
                .dedup()
                .limit(100)
                .dedup()
                .has('name', 'x')
                .dedup()
                .count()
                """;

        int[] resolves = {0};
        GremlinStepRegistry counting = new GremlinStepRegistry() {
            @Override
            public GremlinStep resolve(String name, List<String> args) {
                resolves[0]++;
                return super.resolve(name, args);
            }
        };
        GremlinGraphQuery query = new GremlinGraphQuery(longLogicFake(),
                NeighborVertexResolver.of(Map.of("knows", "p", "created", "s"), Map.of()), counting)
                .registerLogic("logic", logic);

        String q = """
                g.V('v1', 'p')
                  .batch(40)
                  .out('knows')
                  .out('created') { $logic }
                """;

        assertEquals(List.of(1L), query.execute(q));
        assertEquals(List.of(1L), query.execute(q));
        assertEquals(List.of(1L), query.execute(q));

        // batch + out + out + 9 logic steps = 12 steps, resolved only once (compiled & cached)
        assertEquals(12, resolves[0]);
    }

    @Test
    void hasStepReadsOnlyNeededPropertyBins() {
        List<List<String>> projected = new ArrayList<>();
        GraphQuery gq = new GraphQuery() {
            @Override
            public VertexQueryResult readVertex(Object vertexId, String vertexName, List<String> propertyNames) {
                return new VertexQueryResult("p", vertexId, Map.of("name", "x"), 1L, 2L);
            }

            @Override
            public List<VertexQueryResult> readVertices(String vertexName, List<Object> vertexIds,
                                                        List<String> propertyNames) {
                projected.add(new ArrayList<>(propertyNames));
                List<VertexQueryResult> out = new ArrayList<>(vertexIds.size());
                for (Object id : vertexIds) {
                    out.add(new VertexQueryResult(vertexName, id, Map.of("name", "x"), 1L, 2L));
                }
                return out;
            }

            @Override
            public List<EdgeQueryResult> readEdges(Object vertexId, String vertexName, String edgeName,
                                                   EdgeSide direction, List<String> propertyNames) {
                if (edgeName.equals("knows")) {
                    return List.of(new EdgeQueryResult("knows", "e1", EdgeSide.OUT, "n1", Map.of(), 1L, 2L));
                }
                return List.of();
            }

            @Override
            public List<List<EdgeQueryResult>> readEdgesBatch(String edgeName, EdgeSide direction,
                                                              List<Object> vertexIds, List<String> propertyNames) {
                return List.of();
            }
        };

        GremlinGraphQuery gremlin = new GremlinGraphQuery(gq,
                NeighborVertexResolver.of(Map.of("knows", "p"), Map.of()));

        List<Object> result = gremlin.execute("g.V('v1','p').out('knows').has('name','x')");

        assertEquals(1, result.size());
        // the hop before has('name') should project only 'name', not all properties
        assertEquals(1, projected.size());
        assertEquals(List.of("name"), projected.get(0));
    }

    @Test
    void cacheDedupsRepeatedVertexReads() {
        List<List<Object>> vertexBatches = new ArrayList<>();
        GraphQuery gq = new GraphQuery() {
            @Override
            public VertexQueryResult readVertex(Object vertexId, String vertexName, List<String> propertyNames) {
                return new VertexQueryResult("p", vertexId, Map.of(), 1L, 2L);
            }

            @Override
            public List<VertexQueryResult> readVertices(String vertexName, List<Object> vertexIds,
                                                        List<String> propertyNames) {
                vertexBatches.add(new ArrayList<>(vertexIds));
                List<VertexQueryResult> out = new ArrayList<>(vertexIds.size());
                for (Object id : vertexIds) {
                    out.add(new VertexQueryResult(vertexName, id, Map.of("name", "x"), 1L, 2L));
                }
                return out;
            }

            @Override
            public List<EdgeQueryResult> readEdges(Object vertexId, String vertexName, String edgeName,
                                                   EdgeSide direction, List<String> propertyNames) {
                if (edgeName.equals("knows")) {
                    // two edges that both reach the SAME neighbour n1
                    return List.of(
                            new EdgeQueryResult("knows", "e1", EdgeSide.OUT, "n1", Map.of(), 1L, 2L),
                            new EdgeQueryResult("knows", "e2", EdgeSide.OUT, "n1", Map.of(), 1L, 2L));
                }
                return List.of();
            }

            @Override
            public List<List<EdgeQueryResult>> readEdgesBatch(String edgeName, EdgeSide direction,
                                                              List<Object> vertexIds, List<String> propertyNames) {
                List<List<EdgeQueryResult>> out = new ArrayList<>();
                for (Object id : vertexIds) {
                    out.add(List.of(new EdgeQueryResult("created", "c1", EdgeSide.OUT, "s1", Map.of(), 1L, 2L)));
                }
                return out;
            }
        };

        GremlinGraphQuery gremlin = new GremlinGraphQuery(gq,
                NeighborVertexResolver.of(Map.of("knows", "p", "created", "s"), Map.of()));

        List<Object> result = gremlin.execute("g.V('v1', 'p').out('knows').out('created')");

        assertEquals(2, result.size());
        // the two duplicate s1 ids are fetched once (deduplicated within the batch)
        assertEquals(1, vertexBatches.size());
        assertEquals(List.of("s1"), vertexBatches.get(0));
    }

    @Test
    void traversalWithoutResolverStillHopsEdges() {
        GremlinGraphQuery noResolver = new GremlinGraphQuery(graphQuery); // resolver = none()
        EdgeQueryResult e1 = edge("e1", EdgeSide.OUT, "n1", Map.of());
        when(graphQuery.readEdges("v1", "person", "knows", EdgeSide.OUT, List.of()))
                .thenReturn(List.of(e1));

        List<Object> result = noResolver.execute("g.V('v1', 'person').out('knows')");

        assertEquals(1, result.size());
        GremlinVertex vertex = (GremlinVertex) result.get(0);
        assertEquals("n1", vertex.id());
        assertEquals(null, vertex.label()); // schema unknown
        verify(graphQuery).readEdges("v1", "person", "knows", EdgeSide.OUT, List.of());
    }

    // --- helpers ---

    private static GraphQuery batchTrackingGraph(int edgeCount, List<List<Object>> batches) {
        return new GraphQuery() {
            @Override
            public VertexQueryResult readVertex(Object vertexId, String vertexName, List<String> propertyNames) {
                return new VertexQueryResult("p", vertexId, Map.of(), 1L, 2L);
            }

            @Override
            public List<VertexQueryResult> readVertices(String vertexName, List<Object> vertexIds,
                                                        List<String> propertyNames) {
                batches.add(vertexIds);
                List<VertexQueryResult> out = new ArrayList<>(vertexIds.size());
                for (Object vertexId : vertexIds) {
                    out.add(new VertexQueryResult("p", vertexId, Map.of(), 1L, 2L));
                }
                return out;
            }

            @Override
            public List<EdgeQueryResult> readEdges(Object vertexId, String vertexName, String edgeName,
                                                   EdgeSide direction, List<String> propertyNames) {
                List<EdgeQueryResult> edges = new ArrayList<>();
                for (int i = 0; i < edgeCount; i++) {
                    edges.add(new EdgeQueryResult("knows", "e" + i, EdgeSide.OUT, "n" + i, Map.of(), 1L, 2L));
                }
                return edges;
            }
        };
    }

    private static GraphQuery edgeBatchTrackingGraph(int neighborCount, List<List<Object>> edgeBatches) {
        return new GraphQuery() {
            @Override
            public VertexQueryResult readVertex(Object vertexId, String vertexName, List<String> propertyNames) {
                return new VertexQueryResult("p", vertexId, Map.of(), 1L, 2L);
            }

            @Override
            public List<VertexQueryResult> readVertices(String vertexName, List<Object> vertexIds,
                                                        List<String> propertyNames) {
                List<VertexQueryResult> out = new ArrayList<>(vertexIds.size());
                for (Object id : vertexIds) {
                    out.add(new VertexQueryResult("p", id, Map.of(), 1L, 2L));
                }
                return out;
            }

            @Override
            public List<EdgeQueryResult> readEdges(Object vertexId, String vertexName, String edgeName,
                                                   EdgeSide direction, List<String> propertyNames) {
                if (edgeName.equals("knows")) {
                    List<EdgeQueryResult> edges = new ArrayList<>();
                    for (int i = 0; i < neighborCount; i++) {
                        edges.add(new EdgeQueryResult("knows", "e" + i, EdgeSide.OUT, "n" + i, Map.of(), 1L, 2L));
                    }
                    return edges;
                }
                return List.of();
            }

            @Override
            public List<List<EdgeQueryResult>> readEdgesBatch(String edgeName, EdgeSide direction,
                                                              List<Object> vertexIds, List<String> propertyNames) {
                edgeBatches.add(vertexIds);
                List<List<EdgeQueryResult>> out = new ArrayList<>();
                for (Object id : vertexIds) {
                    out.add(List.of());
                }
                return out;
            }
        };
    }

    private static GraphQuery longLogicFake() {
        return new GraphQuery() {
            @Override
            public VertexQueryResult readVertex(Object vertexId, String vertexName, List<String> propertyNames) {
                return new VertexQueryResult("p", vertexId, Map.of("name", "x"), 1L, 2L);
            }

            @Override
            public List<VertexQueryResult> readVertices(String vertexName, List<Object> vertexIds,
                                                        List<String> propertyNames) {
                List<VertexQueryResult> out = new ArrayList<>(vertexIds.size());
                for (Object id : vertexIds) {
                    out.add(new VertexQueryResult(vertexName, id, Map.of("name", "x"), 1L, 2L));
                }
                return out;
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
                List<List<EdgeQueryResult>> out = new ArrayList<>();
                for (Object id : vertexIds) {
                    if (id.equals("n1")) {
                        out.add(List.of(new EdgeQueryResult("created", "e3", EdgeSide.OUT, "s1", Map.of(), 1L, 2L)));
                    } else {
                        out.add(List.of());
                    }
                }
                return out;
            }
        };
    }

    private static EdgeQueryResult edge(String id, EdgeSide side, Object other, Map<String, Object> props) {
        return new EdgeQueryResult("knows", id, side, other, props, 1L, 2L);
    }
}
