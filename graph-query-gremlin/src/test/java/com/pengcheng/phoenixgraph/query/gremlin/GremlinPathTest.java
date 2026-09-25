package com.pengcheng.phoenixgraph.query.gremlin;

import com.pengcheng.phoenixgraph.query.QueryException;
import com.pengcheng.phoenixgraph.query.storage.EdgeQueryResult;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
import com.pengcheng.phoenixgraph.query.storage.GraphQuery;
import com.pengcheng.phoenixgraph.query.storage.VertexQueryResult;
import com.pengcheng.phoenixgraph.query.traversal.NeighborVertexResolver;
import com.pengcheng.phoenixgraph.schema.EdgeEndpointSchema;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.PropertyType;
import com.pengcheng.phoenixgraph.schema.VertexPropertySchema;
import com.pengcheng.phoenixgraph.schema.VertexSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The two things a walk needs that a flat answer does not: {@code path()} (the vertices and edges visited)
 * and the rule that an <em>unnamed</em> hop skips a vector index' own layer edges.
 */
class GremlinPathTest {

    private final GraphQuery graphQuery = mock(GraphQuery.class);
    private final NeighborVertexResolver resolver = NeighborVertexResolver.of(
            Map.of("knows", "person"), Map.of("knows", "person"));
    private final GremlinGraphQuery gremlin = new GremlinGraphQuery(graphQuery, resolver);

    @BeforeEach
    void stubVertexReads() {
        when(graphQuery.readVertex(any(), anyString(), anyList()))
                .thenAnswer(inv -> new VertexQueryResult(inv.getArgument(1), inv.getArgument(0),
                        Map.of("name", String.valueOf(inv.getArgument(0))), 1L, 2L));
        when(graphQuery.readVertices(anyString(), anyList(), anyList()))
                .thenAnswer(inv -> {
                    String name = inv.getArgument(0);
                    List<Object> ids = inv.getArgument(1);
                    List<VertexQueryResult> out = new ArrayList<>(ids.size());
                    for (Object id : ids) {
                        out.add(new VertexQueryResult(name, id,
                                Map.of("name", String.valueOf(id)), 1L, 2L));
                    }
                    return out;
                });
        // the path walk reads edges in batches now: one list per vertex, from the single-vertex stub
        when(graphQuery.readEdgesBatch(anyString(), any(), anyList(), anyList()))
                .thenAnswer(inv -> {
                    String edgeName = inv.getArgument(0);
                    EdgeSide side = inv.getArgument(1);
                    List<Object> ids = inv.getArgument(2);
                    List<String> properties = inv.getArgument(3);
                    List<List<EdgeQueryResult>> out = new ArrayList<>(ids.size());
                    for (Object id : ids) {
                        out.add(graphQuery.readEdges(id, "person", edgeName, side, properties));
                    }
                    return out;
                });
    }

    @Test
    void aPathHoldsTheVerticesAndEdgesItWalked() {
        when(graphQuery.readEdges(eq("v1"), anyString(), eq("knows"), eq(EdgeSide.OUT), anyList()))
                .thenReturn(List.of(new EdgeQueryResult("knows", "e1", EdgeSide.OUT, "v2",
                        Map.of(), 1L, 2L)));
        when(graphQuery.readEdges(eq("v2"), anyString(), eq("knows"), eq(EdgeSide.OUT), anyList()))
                .thenReturn(List.of(new EdgeQueryResult("knows", "e2", EdgeSide.OUT, "v3",
                        Map.of(), 1L, 2L)));

        List<Object> result = gremlin.execute("g.V('v1','person').out('knows').out('knows').path()");

        assertEquals(1, result.size());
        GremlinPath path = assertInstanceOf(GremlinPath.class, result.get(0));
        assertEquals(5, path.size(), "vertex, edge, vertex, edge, vertex");
        assertEquals("v1", ((GremlinVertex) path.start()).id());
        assertEquals("e1", ((GremlinEdge) path.get(1)).id());
        assertEquals("v2", ((GremlinVertex) path.get(2)).id());
        assertEquals("e2", ((GremlinEdge) path.get(3)).id());
        assertEquals("v3", ((GremlinVertex) path.end()).id());
        assertEquals("name", ((GremlinVertex) path.start()).properties().keySet().iterator().next(),
                "a path reads its vertices in full, so the answer can show them");
    }

    @Test
    void pathAnswersOneWalkPerBranch() {
        when(graphQuery.readEdges(eq("v1"), anyString(), eq("knows"), eq(EdgeSide.OUT), anyList()))
                .thenReturn(List.of(
                        new EdgeQueryResult("knows", "e1", EdgeSide.OUT, "v2", Map.of(), 1L, 2L),
                        new EdgeQueryResult("knows", "e2", EdgeSide.OUT, "v3", Map.of(), 1L, 2L)));

        List<Object> result = gremlin.execute("g.V('v1','person').out('knows').path()");

        assertEquals(2, result.size());
        assertEquals("v2", ((GremlinVertex) ((GremlinPath) result.get(0)).end()).id());
        assertEquals("v3", ((GremlinVertex) ((GremlinPath) result.get(1)).end()).id());
        assertEquals(2L, gremlin.execute("g.V('v1','person').out('knows').path().count()").get(0),
                "the steps after path() read the path objects");
    }

    @Test
    void aBothReadAnswersALaterOutReadOfTheSameVertex() {
        when(graphQuery.readEdges(eq("v1"), anyString(), eq("knows"), eq(EdgeSide.BOTH), anyList()))
                .thenReturn(List.of(
                        new EdgeQueryResult("knows", "e1", EdgeSide.OUT, "v2", Map.of(), 1L, 2L),
                        new EdgeQueryResult("knows", "e2", EdgeSide.IN, "v0", Map.of(), 1L, 2L)));

        // both first, then out of the same vertex: the second hop is answered out of the first read
        List<Object> sides = gremlin.execute(
                "g.V('v1','person').union(bothE('knows'), outE('knows')).values('side')");

        assertEquals(java.util.Arrays.asList("out", "in", "out"), sides,
                "both answers the out half and the in half, and out is the out half again");
        verify(graphQuery, org.mockito.Mockito.never())
                .readEdgesBatch(anyString(), eq(EdgeSide.OUT), anyList(), anyList());
    }

    @Test
    void anEdgeAnswersTheSideItWasReadFrom() {
        when(graphQuery.readEdges(eq("v1"), anyString(), eq("knows"), eq(EdgeSide.BOTH), anyList()))
                .thenReturn(List.of(
                        new EdgeQueryResult("knows", "e1", EdgeSide.OUT, "v2", Map.of(), 1L, 2L),
                        new EdgeQueryResult("knows", "e2", EdgeSide.IN, "v0", Map.of(), 1L, 2L)));

        assertEquals(java.util.Arrays.asList("out", "in"),
                gremlin.execute("g.V('v1','person').bothE('knows').values('side')"),
                "both() answers the out half and the in half, each tagged with the side it was read from");
        assertEquals(List.of("e1"),
                gremlin.execute("g.V('v1','person').bothE('knows').filter(side.equals('out')).values('id')"),
                "so the out half can be read out of the same both() read, without going back to storage");
        assertEquals(List.of("e2"),
                gremlin.execute("g.V('v1','person').bothE('knows').filter(direction.equals('in')).values('id')"));
    }

    @Test
    void aHopAfterPathIsRefusedWithAMessage() {
        QueryException error = assertThrows(QueryException.class,
                () -> gremlin.execute("g.V('v1','person').out('knows').path().out('knows')"));
        assertTrue(error.getMessage().contains("after path()"), error.getMessage());
    }

    @Test
    void pathByAndSimpleOrCyclicPath() {
        when(graphQuery.readEdges(eq("v1"), anyString(), eq("knows"), eq(EdgeSide.OUT), anyList()))
                .thenReturn(List.of(new EdgeQueryResult("knows", "e1", EdgeSide.OUT, "v2", Map.of(), 1L, 2L)));
        when(graphQuery.readEdges(eq("v2"), anyString(), eq("knows"), eq(EdgeSide.OUT), anyList()))
                .thenReturn(List.of(new EdgeQueryResult("knows", "e2", EdgeSide.OUT, "v3", Map.of(), 1L, 2L)));

        List<Object> mapped = gremlin.execute("g.V('v1','person').out('knows').path().by('name')");
        GremlinPath path = assertInstanceOf(GremlinPath.class, mapped.get(0));
        assertEquals(java.util.Arrays.asList("v1", null, "v2"), path.objects(),
                "an edge has no name, so its slot is null");

        assertEquals(1, gremlin.execute(
                "g.V('v1','person').out('knows').out('knows').simplePath().path()").size());
        assertEquals(0, gremlin.execute(
                "g.V('v1','person').out('knows').out('knows').cyclicPath().path()").size());

        // v3 -> v1 closes a cycle: the two-hop walk v1 -> v2 -> v3 is not one, but a walk that returns is
        when(graphQuery.readEdges(eq("v3"), anyString(), eq("knows"), eq(EdgeSide.OUT), anyList()))
                .thenReturn(List.of());
        when(graphQuery.readEdges(eq("v2"), anyString(), eq("knows"), eq(EdgeSide.OUT), anyList()))
                .thenReturn(List.of(new EdgeQueryResult("knows", "e2", EdgeSide.OUT, "v1", Map.of(), 1L, 2L)));
        assertEquals(0, gremlin.execute(
                "g.V('v1','person').out('knows').out('knows').simplePath().path()").size());
        assertEquals(1, gremlin.execute(
                "g.V('v1','person').out('knows').out('knows').cyclicPath().path()").size());
    }

    @Test
    void aHopReadsItsEdgesInOneBatchAndKeepsTheWalkOrder() {
        // v1 -> v2 and v1 -> v3, then v2 -> v4 and v3 -> v5: the second hop reads v2 and v3 together
        when(graphQuery.readEdges(eq("v1"), anyString(), eq("knows"), eq(EdgeSide.OUT), anyList()))
                .thenReturn(List.of(
                        new EdgeQueryResult("knows", "e1", EdgeSide.OUT, "v2", Map.of(), 1L, 2L),
                        new EdgeQueryResult("knows", "e2", EdgeSide.OUT, "v3", Map.of(), 1L, 2L)));
        when(graphQuery.readEdges(eq("v2"), anyString(), eq("knows"), eq(EdgeSide.OUT), anyList()))
                .thenReturn(List.of(new EdgeQueryResult("knows", "e3", EdgeSide.OUT, "v4", Map.of(), 1L, 2L)));
        when(graphQuery.readEdges(eq("v3"), anyString(), eq("knows"), eq(EdgeSide.OUT), anyList()))
                .thenReturn(List.of(new EdgeQueryResult("knows", "e4", EdgeSide.OUT, "v5", Map.of(), 1L, 2L)));

        List<Object> result = gremlin.execute("g.V('v1','person').out('knows').out('knows').path()");

        assertEquals(2, result.size());
        assertEquals("v4", ((GremlinVertex) ((GremlinPath) result.get(0)).end()).id(),
                "the first walk is v1 -> v2 -> v4");
        assertEquals("v5", ((GremlinVertex) ((GremlinPath) result.get(1)).end()).id(),
                "the second walk is v1 -> v3 -> v5");
        verify(graphQuery, org.mockito.Mockito.atLeastOnce())
                .readEdgesBatch(anyString(), any(), anyList(), anyList());
    }

    @Test
    void anUnnamedHopSkipsTheVectorIndexLayers() {
        GraphSchema schema = new GraphSchema(1, "g")
                .vertex(new VertexSchema(1, "Account", "account")
                        .property(new VertexPropertySchema(1, "accountVector", "vector",
                                new PropertyType.ListType(PropertyType.ScalarType.DOUBLE)).vector(true)))
                .edge(new EdgeSchema(2, "Payment", "payment",
                        new EdgeEndpointSchema(1, "source", "s", PropertyType.ScalarType.LONG, "Account"),
                        new EdgeEndpointSchema(2, "target", "t", PropertyType.ScalarType.LONG, "Account")))
                .edge(new EdgeSchema(3, "accountVector_0", "layer",
                        new EdgeEndpointSchema(1, "source", "s", PropertyType.ScalarType.LONG, "Account"),
                        new EdgeEndpointSchema(2, "target", "t", PropertyType.ScalarType.LONG, "Account")));
        when(graphQuery.readAllEdgesBatch(anyList(), any(), anyList()))
                .thenAnswer(inv -> {
                    List<Object> ids = inv.getArgument(0);
                    List<List<EdgeQueryResult>> out = new ArrayList<>(ids.size());
                    for (Object id : ids) {
                        out.add(List.of(
                                new EdgeQueryResult("accountVector_0", "vec-" + id, EdgeSide.OUT, "n-vec",
                                        Map.of(), 1L, 2L),
                                new EdgeQueryResult("Payment", "pay-" + id, EdgeSide.OUT, "n-pay",
                                        Map.of(), 1L, 2L)));
                    }
                    return out;
                });
        GremlinGraphQuery engine = new GremlinGraphQuery(graphQuery, resolver).schema(schema);

        List<Object> untyped = engine.execute("g.V('a1','Account').both()");
        assertEquals(1, untyped.size(), "the index layer is not a relationship of the data");
        assertEquals("n-pay", ((GremlinVertex) untyped.get(0)).id());

        when(graphQuery.readEdges(eq("a1"), anyString(), eq("accountVector_0"), any(), anyList()))
                .thenReturn(List.of(new EdgeQueryResult("accountVector_0", "vec-a1", EdgeSide.OUT, "n-vec",
                        Map.of(), 1L, 2L)));
        List<Object> named = engine.execute("g.V('a1','Account').both('accountVector_0')");
        assertEquals(1, named.size(), "a hop that names the layer still reads it");
        assertEquals("n-vec", ((GremlinVertex) named.get(0)).id());
    }
}
