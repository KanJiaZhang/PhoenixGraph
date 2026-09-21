package com.pengcheng.phoenixgraph.query.gremlin;

import com.pengcheng.phoenixgraph.hnsw.HeapHnswVectorSource;
import com.pengcheng.phoenixgraph.hnsw.HnswIndex;
import com.pengcheng.phoenixgraph.hnsw.InMemoryHnswStore;import com.pengcheng.phoenixgraph.query.QueryException;
import com.pengcheng.phoenixgraph.query.result.Subgraph;
import com.pengcheng.phoenixgraph.query.storage.EdgeQueryResult;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
import com.pengcheng.phoenixgraph.query.storage.GraphQuery;
import com.pengcheng.phoenixgraph.query.storage.VertexQueryResult;
import com.pengcheng.phoenixgraph.schema.EdgeEndpointSchema;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.PropertyType;
import com.pengcheng.phoenixgraph.schema.VertexSchema;
import com.pengcheng.phoenixgraph.schema.geo.GeoHit;
import com.pengcheng.phoenixgraph.schema.geo.GeoPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.pengcheng.phoenixgraph.query.traversal.NeighborVertexResolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

        EdgeQueryResult e3 = new EdgeQueryResult("created", "e3", EdgeSide.OUT, "s1", Map.of(), 1L, 2L);
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
        when(graphQuery.readEdges("v1", "person", "knows", EdgeSide.OUT, List.of("weight")))
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
        when(graphQuery.readEdges("v1", "person", "knows", EdgeSide.OUT, List.of("weight")))
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
    void dedupUsesElementIdentityAndSkipsThePropertyRead() {
        EdgeQueryResult e1 = edge("e1", EdgeSide.OUT, "n1", Map.of());
        when(graphQuery.readEdges("v1", "person", "knows", EdgeSide.OUT, List.of()))
                .thenReturn(List.of(e1, e1));

        List<Object> result = gremlin.execute("g.V('v1', 'person').out('knows').dedup()");

        // one traverser per distinct vertex id, and the vertex read is skipped entirely (only ids are needed)
        assertEquals(1, result.size());
        assertEquals("n1", ((GremlinVertex) result.get(0)).id());
        verify(graphQuery, never()).readVertex(eq("n1"), eq("person"), anyList());
    }

    @Test
    void dedupThenValuesProjectsOnlyThatProperty() {
        EdgeQueryResult e1 = edge("e1", EdgeSide.OUT, "n1", Map.of());
        when(graphQuery.readEdges("v1", "person", "knows", EdgeSide.OUT, List.of()))
                .thenReturn(List.of(e1, e1));
        when(graphQuery.readVertex("n1", "person", List.of("name")))
                .thenReturn(new VertexQueryResult("person", "n1", Map.of("name", "alice"), 5L, 6L));

        List<Object> result = gremlin.execute("g.V('v1', 'person').out('knows').dedup().values('name')");

        // dedup only needs the id; the read that follows projects exactly the property it names
        assertEquals(List.of("alice"), result);
        verify(graphQuery).readVertex("n1", "person", List.of("name"));
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
    void hopOverSeveralNamedClassesConcatenatesThem() {
        EdgeQueryResult e1 = edge("e1", EdgeSide.OUT, "n1", Map.of());
        EdgeQueryResult e2 = edge("e2", EdgeSide.OUT, "s1", Map.of());
        when(graphQuery.readEdges("v1", "person", "knows", EdgeSide.OUT, List.of()))
                .thenReturn(List.of(e1));
        when(graphQuery.readEdges("v1", "person", "created", EdgeSide.OUT, List.of()))
                .thenReturn(List.of(e2));
        when(graphQuery.readVertex("n1", "person", List.of()))
                .thenReturn(new VertexQueryResult("person", "n1", Map.of(), 5L, 6L));
        when(graphQuery.readVertex("s1", "software", List.of()))
                .thenReturn(new VertexQueryResult("software", "s1", Map.of(), 7L, 8L));

        List<Object> result = gremlin.execute("g.V('v1', 'person').out('knows', 'created')");

        assertEquals(2, result.size());
        assertEquals(List.of("n1", "s1"),
                result.stream().map(o -> ((GremlinVertex) o).id()).toList());
    }

    @Test
    void multiClassHopCountIsNotRewrittenToASingleBinCount() {
        EdgeQueryResult e1 = edge("e1", EdgeSide.OUT, "n1", Map.of());
        EdgeQueryResult e2 = edge("e2", EdgeSide.OUT, "s1", Map.of());
        when(graphQuery.readEdges("v1", "person", "knows", EdgeSide.OUT, List.of()))
                .thenReturn(List.of(e1));
        when(graphQuery.readEdges("v1", "person", "created", EdgeSide.OUT, List.of()))
                .thenReturn(List.of(e2));
        when(graphQuery.readVertex("n1", "person", List.of()))
                .thenReturn(new VertexQueryResult("person", "n1", Map.of(), 5L, 6L));
        when(graphQuery.readVertex("s1", "software", List.of()))
                .thenReturn(new VertexQueryResult("software", "s1", Map.of(), 7L, 8L));

        assertEquals(List.of(2L), gremlin.execute("g.V('v1', 'person').out('knows', 'created').count()"));
    }

    @Test
    void unknownStepThrows() {
        assertThrows(QueryException.class,
                () -> gremlin.execute("g.V('v1', 'person').jump('knows')"));
    }

    @Test
    void fanOutUsesNativeBatchReadChunkedByBatchSize() {
        List<List<Object>> batches = java.util.Collections.synchronizedList(new ArrayList<>());
        GraphQuery fanOut = batchTrackingGraph(30, batches);
        GremlinGraphQuery query = new GremlinGraphQuery(fanOut,
                NeighborVertexResolver.of(Map.of("knows", "p"), Map.of()));

        List<Object> result = query.execute("g.V('v1', 'p').batch(10).out('knows')");

        assertEquals(30, result.size());
        assertEquals(List.of(10, 10, 10), batches.stream().map(List::size).toList());
    }

    @Test
    void batchOneReadsNeighboursOneAtATime() {
        List<List<Object>> batches = java.util.Collections.synchronizedList(new ArrayList<>());
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
    void severalSeedIdsAreReadInOneBatchPerSeedInParallel() {
        List<List<Object>> seedBatches = java.util.Collections.synchronizedList(new ArrayList<>());
        GraphQuery fanOut = seedTrackingGraph(seedBatches);
        GremlinGraphQuery query = new GremlinGraphQuery(fanOut,
                NeighborVertexResolver.of(Map.of("knows", "p"), Map.of()));

        List<Object> result = query.execute("g.V('a','b','c','p').batch(20).values('name')");

        // every seed becomes its own read request (at most batch(20) ids per request)
        assertEquals(3, result.size());
        assertEquals(List.of(1, 1, 1), seedBatches.stream().map(List::size).sorted().toList());
    }

    @Test
    void seedBatchesNeverExceedTheConfiguredBatchSize() {
        List<List<Object>> seedBatches = java.util.Collections.synchronizedList(new ArrayList<>());
        GraphQuery fanOut = seedTrackingGraph(seedBatches);
        GremlinGraphQuery query = new GremlinGraphQuery(fanOut,
                NeighborVertexResolver.of(Map.of("knows", "p"), Map.of()));
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            ids.append("'s").append(i).append("',");
        }

        query.execute("g.V(" + ids + "'p').batch(20).values('name')");

        // 100 seeds with batch(20): one request per parallel slot, each at most 20 ids
        assertTrue(seedBatches.size() <= GremlinContext.DEFAULT_PARALLELISM,
                "expected at most " + GremlinContext.DEFAULT_PARALLELISM + " requests, got " + seedBatches.size());
        assertTrue(seedBatches.stream().allMatch(b -> b.size() <= 20));
        assertEquals(100, seedBatches.stream().mapToInt(List::size).sum());
    }

    @Test
    void sequentialParallelismReadsOneBatchAtATime() {
        List<List<Object>> seedBatches = java.util.Collections.synchronizedList(new ArrayList<>());
        GraphQuery fanOut = seedTrackingGraph(seedBatches);
        GremlinGraphQuery query = new GremlinGraphQuery(fanOut,
                        NeighborVertexResolver.of(Map.of("knows", "p"), Map.of()))
                .parallelism(1);

        List<Object> result = query.execute("g.V('a','b','c','p').batch(20).values('name')");

        // parallelism(1) is the escape hatch back to a single batch for the seeds
        assertEquals(3, result.size());
        assertEquals(List.of(3), seedBatches.stream().map(List::size).toList());
    }

    @Test
    void limitKeepsThePropertiesProjectedForTheFollowingSteps() {
        when(graphQuery.readEdges("v1", "person", "knows", EdgeSide.OUT, List.of()))
                .thenReturn(List.of(edge("e1", EdgeSide.OUT, "n1", Map.of())));
        // a limit now folds into the hop itself, so the read is the capped form (empty filters, cap 1)
        when(graphQuery.readEdges("v1", "person", "knows", EdgeSide.OUT, List.of(), List.of(), 1))
                .thenReturn(List.of(edge("e1", EdgeSide.OUT, "n1", Map.of())));
        when(graphQuery.readVertex("n1", "person", List.of("name")))
                .thenReturn(new VertexQueryResult("person", "n1", Map.of("name", "bob"), 5L, 6L));
        // an empty projection means "all properties", as elementMap() needs
        when(graphQuery.readVertex("n1", "person", List.of()))
                .thenReturn(new VertexQueryResult("person", "n1",
                        Map.of("name", "bob", "age", 42L), 5L, 6L));

        // limit() lets the elements through unchanged, so values() after it still sees
        // the properties the hop was asked to project
        assertEquals(List.of("bob"), gremlin.execute("g.V('v1','person').out('knows').limit(1).values('name')"));
        // ... and elementMap() after a limit() keeps asking for every property
        assertEquals(Map.of("id", "n1", "label", "person", "name", "bob", "age", 42L),
                gremlin.execute("g.V('v1','person').out('knows').limit(1).elementMap()").get(0));
        // the projection of the hop itself must name the property, not skip the read
        verify(graphQuery).readVertex("n1", "person", List.of("name"));
    }

    @Test
    void circleReplacesTheTraversersWithThePointsInsideTheRadius() {
        when(graphQuery.supportsGeoSearch()).thenReturn(true);
        when(graphQuery.geoNear(eq("person"), eq("location"), any())).thenReturn(List.of(
                new GeoHit("person", "n1", new GeoPoint(31.2304d, 121.4737d), 200d),
                new GeoHit("person", "n2", new GeoPoint(31.24d, 121.48d), 900d)));
        when(graphQuery.readVertex("n1", "person", List.of())).thenReturn(
                new VertexQueryResult("person", "n1", Map.of("name", "coffee"), 5L, 6L));
        when(graphQuery.readVertex("n2", "person", List.of())).thenReturn(
                new VertexQueryResult("person", "n2", Map.of("name", "bakery"), 5L, 6L));
        when(graphQuery.readVertex("n1", "person", List.of("name"))).thenReturn(
                new VertexQueryResult("person", "n1", Map.of("name", "coffee"), 5L, 6L));
        when(graphQuery.readVertex("n2", "person", List.of("name"))).thenReturn(
                new VertexQueryResult("person", "n2", Map.of("name", "bakery"), 5L, 6L));
        when(graphQuery.readVertex("n1", "person", List.of("distanceMeters"))).thenReturn(
                new VertexQueryResult("person", "n1", Map.of(), 5L, 6L));
        when(graphQuery.readVertex("n2", "person", List.of("distanceMeters"))).thenReturn(
                new VertexQueryResult("person", "n2", Map.of(), 5L, 6L));
        GremlinGraphQuery engine = new GremlinGraphQuery(graphQuery, resolver)
                .geoProperties(Map.of("person", "location"));

        // the radius rides in the JSON ...
        List<Object> names = engine.execute(
                "g.V('v1','person').circle('{\"lat\":31.2304,\"lon\":121.4737,\"radius\":1000}')"
                        + ".values('name')");
        assertEquals(List.of("coffee", "bakery"), names);

        // ... and can be given as the step's own argument instead
        List<Object> distances = engine.execute(
                "g.V('v1','person').circle('{\"lat\":31.2304,\"lon\":121.4737}', 2000)"
                        + ".values('distanceMeters')");
        assertEquals(List.of(200d, 900d), distances);

        // the search ran on the type's geo property, once per traverser
        verify(graphQuery, org.mockito.Mockito.atLeastOnce()).geoNear(eq("person"), eq("location"), any());
    }

    @Test
    void anEdgeWithANullTargetIsReturnedByOutEButHopsNowhere() {
        // a nullable target: the edge exists on its source, but there is no far vertex
        when(graphQuery.readEdges("v1", "person", "opens", EdgeSide.OUT, List.of()))
                .thenReturn(List.of(new EdgeQueryResult("opens", "e9", EdgeSide.OUT, null, Map.of("since", 3),
                        5L, 6L)));

        assertEquals(List.of(), gremlin.execute("g.V('v1','person').out('opens')"),
                "following an edge with no target reaches no vertex");
        assertEquals(1, gremlin.execute("g.V('v1','person').outE('opens')").size(),
                "the edge itself is still there");
        assertEquals(List.of(), gremlin.execute("g.V('v1','person').both('opens')"));

        // the subgraph keeps the half edge: its target is null, so only the source is drawn
        when(graphQuery.readVertex("v1", "person", List.of())).thenReturn(
                new VertexQueryResult("person", "v1", Map.of(), 1L, 2L));
        Subgraph subgraph = gremlin.executeSubgraph("g.V('v1','person').outE('opens')");
        assertEquals(1, subgraph.vertices().size(), "the source vertex");
        assertEquals(1, subgraph.edges().size());
        assertEquals("v1", subgraph.edges().get(0).source());
        assertNull(subgraph.edges().get(0).target(), "the edge has no far endpoint");
    }

    @Test
    void aLimitAfterAWindowedHopBecomesTheWindowCap() {
        // a hub's edges live in aggregated tables: createdBetween(...).both().limit(k) must tell
        // the storage to stop after k edges instead of materialising the whole window
        when(graphQuery.readEdgesWithin(eq("v1"), any(), eq("knows"), eq(EdgeSide.BOTH), any(), anyList(),
                eq(7)))
                .thenReturn(List.of());

        GremlinGraphQuery engine = new GremlinGraphQuery(graphQuery, resolver);
        assertEquals(List.of(), engine.execute("g.V('v1','person')"
                + ".createdBetween('2025-01-01','2025-02-01').both('knows').limit(7)"));

        // the window really was bounded by the two dates and carried the cap
        org.mockito.ArgumentCaptor<com.pengcheng.phoenixgraph.storage.TimeRange> windows =
                org.mockito.ArgumentCaptor.forClass(com.pengcheng.phoenixgraph.storage.TimeRange.class);
        verify(graphQuery).readEdgesWithin(eq("v1"), any(), eq("knows"), eq(EdgeSide.BOTH), windows.capture(),
                anyList(), eq(7));
        assertEquals(com.pengcheng.phoenixgraph.storage.TimeRange.parse("2025-01-01", "2025-02-01"),
                windows.getValue());

        // without the limit the cap is 0 (everything inside the window)
        org.mockito.Mockito.clearInvocations(graphQuery);
        when(graphQuery.readEdgesWithin(eq("v1"), any(), eq("knows"), eq(EdgeSide.BOTH), any(), anyList(),
                eq(0)))
                .thenReturn(List.of());
        engine.execute("g.V('v1','person').createdBetween('2025-01-01','2025-02-01').both('knows')");
        verify(graphQuery).readEdgesWithin(eq("v1"), any(), eq("knows"), eq(EdgeSide.BOTH), any(), anyList(),
                eq(0));
    }

    @Test
    void aLimitAfterCircleIsPushedIntoTheSearch() {
        when(graphQuery.supportsGeoSearch()).thenReturn(true);
        when(graphQuery.geoNear(eq("person"), eq("location"), any())).thenAnswer(inv -> {
            com.pengcheng.phoenixgraph.schema.geo.GeoSearch search = inv.getArgument(2);
            java.util.List<GeoHit> hits = new java.util.ArrayList<>();
            for (int i = 0; i < 20; i++) {
                hits.add(new GeoHit("person", "n" + i, new GeoPoint(31.23d + i / 1000d, 121.47d), 100d + i));
            }
            // what a storage does with the pushed limit: only that many hits come back
            return search.maxResults() > 0 ? hits.subList(0, search.maxResults()) : hits;
        });
        when(graphQuery.readVertices(eq("person"), anyList(), anyList())).thenAnswer(inv -> {
            List<Object> ids = inv.getArgument(1);
            List<VertexQueryResult> out = new ArrayList<>(ids.size());
            for (Object id : ids) {
                out.add(new VertexQueryResult("person", id, Map.of("name", "shop-" + id), 5L, 6L));
            }
            return out;
        });
        GremlinGraphQuery engine = new GremlinGraphQuery(graphQuery, resolver)
                .geoProperties(Map.of("person", "location"));

        // 20 hits inside the circle, but the query wants three ...
        List<Object> names = engine.execute(
                "g.V('v1','person').circle('{\"lat\":31.23,\"lon\":121.47,\"radius\":5000}')"
                        + ".limit(3).values('name')");
        assertEquals(List.of("shop-n0", "shop-n1", "shop-n2"), names);
        // ... so the storage was asked for three hits and only three vertices were read
        org.mockito.ArgumentCaptor<com.pengcheng.phoenixgraph.schema.geo.GeoSearch> searches =
                org.mockito.ArgumentCaptor.forClass(com.pengcheng.phoenixgraph.schema.geo.GeoSearch.class);
        verify(graphQuery, org.mockito.Mockito.atLeastOnce()).geoNear(eq("person"), eq("location"),
                searches.capture());
        assertEquals(3, searches.getValue().maxResults(), "the limit reached the storage");
        verify(graphQuery, org.mockito.Mockito.times(3)).readVertices(eq("person"), anyList(), anyList());

        // without a limit every hit is read (the previous behaviour, still correct)
        org.mockito.Mockito.clearInvocations(graphQuery);
        List<Object> all = engine.execute(
                "g.V('v1','person').circle('{\"lat\":31.23,\"lon\":121.47,\"radius\":5000}')"
                        + ".values('name')");
        assertEquals(20, all.size());
        verify(graphQuery, org.mockito.Mockito.times(20)).readVertices(eq("person"), anyList(), anyList());
    }

    @Test
    void circleAttachesTheDistanceSoElementMapShowsIt() {
        when(graphQuery.supportsGeoSearch()).thenReturn(true);
        when(graphQuery.geoNear(eq("person"), eq("location"), any())).thenReturn(
                List.of(new GeoHit("person", "n1", new GeoPoint(31.2304d, 121.4737d), 250d)));
        when(graphQuery.readVertex("n1", "person", List.of())).thenReturn(
                new VertexQueryResult("person", "n1", Map.of("name", "coffee"), 5L, 6L));
        GremlinGraphQuery engine = new GremlinGraphQuery(graphQuery, resolver)
                .geoProperties(Map.of("person", "location"));

        assertEquals(Map.of("id", "n1", "label", "person", "name", "coffee", "distanceMeters", 250d),
                engine.execute("g.V('v1','person').circle('{\"lat\":31.23,\"lon\":121.47,\"radius\":1000}')"
                        + ".elementMap()").get(0));
    }

    @Test
    void circleExplainsAMissingIndexOrRadius() {
        GremlinGraphQuery engine = new GremlinGraphQuery(graphQuery, resolver);
        // no geo property is declared for the type
        QueryException noIndex = assertThrows(QueryException.class, () -> engine.execute(
                "g.V('v1','person').circle('{\"lat\":31.23,\"lon\":121.47,\"radius\":1000}')"));
        assertTrue(noIndex.getMessage().contains("No geo index"), noIndex.getMessage());

        // the point JSON without a radius anywhere
        QueryException noRadius = assertThrows(QueryException.class, () -> engine.execute(
                "g.V('v1','person').circle('{\"lat\":31.23,\"lon\":121.47}')"));
        assertTrue(noRadius.getMessage().contains("radius"), noRadius.getMessage());

        // a point that is not a point
        QueryException badPoint = assertThrows(QueryException.class, () -> engine.execute(
                "g.V('v1','person').circle('not-a-point', 1000)"));
        assertTrue(badPoint.getMessage().contains("point"), badPoint.getMessage());
    }

    @Test
    void executeWithSubgraphReturnsTheAnswerAndTheVisitedGraph() {
        EdgeQueryResult edge = edge("e1", EdgeSide.OUT, "n1", Map.of());
        when(graphQuery.readEdges("v1", "person", "knows", EdgeSide.OUT, List.of()))
                .thenReturn(List.of(edge));
        when(graphQuery.readVertex("n1", "person", List.of("name")))
                .thenReturn(new VertexQueryResult("person", "n1", Map.of("name", "bob"), 5L, 6L));
        when(graphQuery.readVertex("n1", "person", List.of()))
                .thenReturn(new VertexQueryResult("person", "n1", Map.of("name", "bob"), 5L, 6L));

        GremlinGraphQuery.Result result = gremlin.executeWithSubgraph(
                "g.V('v1','person').out('knows').values('name')");

        // the answer of the query ...
        assertEquals(List.of("bob"), result.values());
        // ... and the subgraph it walked, from the same execution
        assertEquals(2, result.subgraph().vertices().size());
        assertEquals(1, result.subgraph().edges().size());
        assertEquals("v1", result.subgraph().vertices().get(0).id());
        assertEquals("e1", result.subgraph().edges().get(0).id());
        // a count() has no graph of its own, but the walk it counted is there
        GremlinGraphQuery.Result counted = gremlin.executeWithSubgraph(
                "g.V('v1','person').out('knows').count()");
        assertEquals(List.of(1L), counted.values());
        assertEquals(2, counted.subgraph().vertices().size());
    }

    @Test
    void aSeedThatDoesNotExistIsNotCounted() {
        // the fast path skips the seed's record, so a plan that only passes it on (or counts it) has to
        // check: otherwise an id that is not there answers one
        when(graphQuery.readVertex("missing", "person", List.of())).thenReturn(null);

        assertEquals(List.of(0L), gremlin.execute("g.V('missing','person').count()"));
        assertEquals(List.of(), gremlin.execute("g.V('missing','person')"));

        when(graphQuery.readVertex("v1", "person", List.of()))
                .thenReturn(new VertexQueryResult("person", "v1", Map.of(), 1L, 2L));

        assertEquals(List.of(1L), gremlin.execute("g.V('v1','person').count()"));
    }

    @Test
    void aCappedVertexHopDrawsOnlyTheEdgesOfTheVerticesItKept() {
        // the limit folds into the hop: the hop stops reading once it has one neighbour, so the edge to
        // the second neighbour is never read - the answer and the drawn graph are exactly what was kept
        when(graphQuery.readEdges("v1", "person", "knows", EdgeSide.OUT, List.of()))
                .thenReturn(List.of(edge("e1", EdgeSide.OUT, "n1", Map.of()),
                        edge("e2", EdgeSide.OUT, "n2", Map.of())));
        when(graphQuery.readEdges("v1", "person", "knows", EdgeSide.OUT, List.of(), List.of(), 1))
                .thenReturn(List.of(edge("e1", EdgeSide.OUT, "n1", Map.of())));
        when(graphQuery.readVertex("n1", "person", List.of()))
                .thenReturn(new VertexQueryResult("person", "n1", Map.of(), 5L, 6L));
        when(graphQuery.readVertex("n2", "person", List.of()))
                .thenReturn(new VertexQueryResult("person", "n2", Map.of(), 5L, 6L));

        GremlinGraphQuery.Result limited = gremlin.executeWithSubgraph(
                "g.V('v1','person').out('knows').limit(1)");

        assertEquals(1, limited.values().size(), "the limit keeps one neighbour");
        assertEquals(1, limited.subgraph().edges().size(),
                "and one edge: " + limited.subgraph().edges());
        assertEquals("e1", limited.subgraph().edges().get(0).id());
        assertEquals(2, limited.subgraph().vertices().size(), "the seed and the neighbour it kept");

        // without the cap both edges are part of the answer, so both are drawn
        assertEquals(2, gremlin.executeSubgraph("g.V('v1','person').out('knows')").edges().size());
    }

    @Test
    void aFilterThatDropsAVertexDropsItsEdgesToo() {
        when(graphQuery.readEdges("v1", "person", "knows", EdgeSide.OUT, List.of()))
                .thenReturn(List.of(edge("e1", EdgeSide.OUT, "n1", Map.of()),
                        edge("e2", EdgeSide.OUT, "n2", Map.of())));
        when(graphQuery.readVertex("n2", "person", List.of("name")))
                .thenReturn(new VertexQueryResult("person", "n2", Map.of("name", "bob"), 5L, 6L));
        when(graphQuery.readVertex("n2", "person", List.of()))
                .thenReturn(new VertexQueryResult("person", "n2", Map.of("name", "bob"), 5L, 6L));

        GremlinGraphQuery.Result filtered = gremlin.executeWithSubgraph(
                "g.V('v1','person').out('knows').has('name','bob')");

        assertEquals(1, filtered.values().size());
        assertEquals("n2", ((com.pengcheng.phoenixgraph.query.gremlin.GremlinVertex) filtered.values().get(0)).id());
        assertEquals(1, filtered.subgraph().edges().size(),
                "the edge of the vertex the filter dropped is gone: " + filtered.subgraph().edges());
        assertEquals("e2", filtered.subgraph().edges().get(0).id());
    }

    @Test
    void nearestStepReturnsTheIndexesNearestVertices() {
        HeapHnswVectorSource source = new HeapHnswVectorSource();
        source.put("v1", new float[] {0f, 0f});
        source.put("v2", new float[] {0.1f, 0f});
        source.put("v3", new float[] {0.2f, 0f});
        source.put("v4", new float[] {9f, 9f});
        HnswIndex index = HnswIndex.builder().vertexName("person").store(new InMemoryHnswStore())
                .vectors(source).size(4).build();
        index.insertAll(List.of("v1", "v2", "v3", "v4"));
        gremlin.hnswIndex(index);
        when(graphQuery.readVertex("v2", "person", List.of()))
                .thenReturn(new VertexQueryResult("person", "v2", Map.of("name", "b"), 1L, 1L));
        when(graphQuery.readVertex("v3", "person", List.of()))
                .thenReturn(new VertexQueryResult("person", "v3", Map.of("name", "c"), 1L, 1L));

        List<Object> result = gremlin.execute("g.V('v1', 'person').nearest(2)");

        assertEquals(2, result.size());
        GremlinVertex first = (GremlinVertex) result.get(0);
        assertEquals("v2", first.id());
        assertEquals("person", first.label());
        assertEquals(Map.of("name", "b"), first.properties());
        assertEquals("v3", ((GremlinVertex) result.get(1)).id());
    }

    @Test
    void nearestAcceptsAnEfSearchArgument() {
        HeapHnswVectorSource source = new HeapHnswVectorSource();
        source.put("v1", new float[] {0f, 0f});
        source.put("v2", new float[] {0.1f, 0f});
        HnswIndex index = HnswIndex.builder().vertexName("person").store(new InMemoryHnswStore())
                .vectors(source).size(2).build();
        index.insertAll(List.of("v1", "v2"));
        gremlin.hnswIndex(index);
        when(graphQuery.readVertex("v2", "person", List.of()))
                .thenReturn(new VertexQueryResult("person", "v2", Map.of(), 1L, 1L));

        assertEquals(1, gremlin.execute("g.V('v1', 'person').nearest(1, 32)").size());
        assertThrows(QueryException.class, () -> gremlin.execute("g.V('v1', 'person').nearest(1, 32, 7)"));
    }

    @Test
    void nearestPushesTheFollowingHasConditionIntoTheSearch() {
        // six points on a line, every second one in bucket 0. The two closest to v0
        // are v1 (bucket 1) and v2 (bucket 0): post-filtering would leave one result,
        // pushing the condition down finds v2 and v4.
        HeapHnswVectorSource source = new HeapHnswVectorSource();
        for (int i = 0; i < 6; i++) {
            source.put("v" + i, new float[] {i, 0f});
            source.putProperties("v" + i, Map.of("bucket", i % 2 == 0 ? 0 : 1, "name", "doc" + i));
        }
        HnswIndex index = HnswIndex.builder().vertexName("person").vectorProperty("vector")
                .store(new InMemoryHnswStore()).vectors(source).size(6).build();
        index.insertAll(List.of("v0", "v1", "v2", "v3", "v4", "v5"));
        gremlin.hnswIndex(index);
        when(graphQuery.readVertex("v2", "person", List.of()))
                .thenReturn(new VertexQueryResult("person", "v2", Map.of("bucket", 0, "name", "doc2"), 1L, 1L));
        when(graphQuery.readVertex("v4", "person", List.of()))
                .thenReturn(new VertexQueryResult("person", "v4", Map.of("bucket", 0, "name", "doc4"), 1L, 1L));
        when(graphQuery.readVertex("v1", "person", List.of()))
                .thenReturn(new VertexQueryResult("person", "v1", Map.of("bucket", 1, "name", "doc1"), 1L, 1L));

        List<Object> pushed = gremlin.execute("g.V('v0', 'person').nearest(2).has('bucket', 0)");

        assertEquals(2, pushed.size(), "the walk keeps going until it has k matches");
        assertEquals(List.of("v2", "v4"), pushed.stream().map(o -> ((GremlinVertex) o).id()).toList());

        // without the push-down only the neighbours that happen to match survive
        List<Object> plain = gremlin.execute("g.V('v0', 'person').nearest(2).values('bucket')");
        assertEquals(List.of(1, 0), plain);
    }

    @Test
    void nearestPushesFilterExpressionsDownToo() {
        HeapHnswVectorSource source = new HeapHnswVectorSource();
        for (int i = 0; i < 6; i++) {
            source.put("v" + i, new float[] {i, 0f});
            source.putProperties("v" + i, Map.of("bucket", i % 2 == 0 ? 0 : 1));
        }
        HnswIndex index = HnswIndex.builder().vertexName("person").vectorProperty("vector")
                .store(new InMemoryHnswStore()).vectors(source).size(6).build();
        index.insertAll(List.of("v0", "v1", "v2", "v3", "v4", "v5"));
        gremlin.hnswIndex(index);
        when(graphQuery.readVertex("v2", "person", List.of()))
                .thenReturn(new VertexQueryResult("person", "v2", Map.of("bucket", 0), 1L, 1L));
        when(graphQuery.readVertex("v4", "person", List.of()))
                .thenReturn(new VertexQueryResult("person", "v4", Map.of("bucket", 0), 1L, 1L));

        List<Object> result = gremlin.execute("g.V('v0', 'person').nearest(2).filter(bucket.equals('0'))");

        assertEquals(2, result.size());
        assertEquals(List.of("v2", "v4"), result.stream().map(o -> ((GremlinVertex) o).id()).toList());
    }

    @Test
    void nearestWithoutAConfiguredIndexFails() {
        QueryException error = assertThrows(QueryException.class,
                () -> gremlin.execute("g.V('v1', 'person').nearest(3)"));
        assertTrue(error.getMessage().contains("No HNSW index"), error.getMessage());
    }

    @Test
    void filteredElementsAreDroppedFromTheSubgraph() {
        EdgeQueryResult keep = new EdgeQueryResult("knows", "e1", EdgeSide.OUT, "n1",
                Map.of("relation", "r"), 1L, 2L);
        EdgeQueryResult drop = new EdgeQueryResult("knows", "e2", EdgeSide.OUT, "n2",
                Map.of("relation", "other"), 1L, 2L);
        when(graphQuery.readEdges("v1", "person", "knows", EdgeSide.OUT,
                List.of("relation"))).thenReturn(List.of(keep, drop));
        when(graphQuery.readVertex("n1", "person", List.of()))
                .thenReturn(new VertexQueryResult("person", "n1", Map.of(), 5L, 6L));

        Subgraph subgraph = gremlin.executeSubgraph(
                "g.V('v1', 'person').outE('knows').filter(relation.equals('r'))");

        // the dropped edge takes its exclusive endpoint with it
        assertEquals(1, subgraph.edges().size());
        assertEquals("e1", subgraph.edges().get(0).id());
        assertEquals(java.util.Set.of("v1", "n1"),
                subgraph.vertices().stream().map(v -> (String) v.id()).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void otherVHopsToTheFarEndpointOfAnEdge() {
        EdgeQueryResult keep = new EdgeQueryResult("knows", "e1", EdgeSide.OUT, "n1",
                Map.of("relation", "r"), 1L, 2L);
        EdgeQueryResult drop = new EdgeQueryResult("knows", "e2", EdgeSide.OUT, "n2",
                Map.of("relation", "other"), 1L, 2L);
        when(graphQuery.readEdges("v1", "person", "knows", EdgeSide.OUT,
                List.of("relation"))).thenReturn(List.of(keep, drop));
        // the @BeforeEach readVertices answer delegates to readVertex per id
        when(graphQuery.readVertex("n1", "person", List.of()))
                .thenReturn(new VertexQueryResult("person", "n1", Map.of("name", "alice"), 5L, 6L));

        List<Object> result = gremlin.execute(
                "g.V('v1', 'person').outE('knows').filter(relation.equals('r')).otherV()");

        assertEquals(1, result.size());
        GremlinVertex vertex = (GremlinVertex) result.get(0);
        assertEquals("n1", vertex.id());
        assertEquals("person", vertex.label());
        assertEquals(Map.of("name", "alice"), vertex.properties());
    }

    @Test
    void otherVRejectsNonEdgeTraversers() {
        when(graphQuery.readVertex("v1", "person", List.of()))
                .thenReturn(new VertexQueryResult("person", "v1", Map.of("name", "alice"), 1L, 2L));

        assertThrows(QueryException.class,
                () -> gremlin.execute("g.V('v1', 'person').otherV()"));
    }

    private static GraphQuery seedTrackingGraph(List<List<Object>> seedBatches) {
        return new GraphQuery() {
            @Override
            public VertexQueryResult readVertex(Object vertexId, String vertexName, List<String> propertyNames) {
                return new VertexQueryResult("p", vertexId, Map.of(), 1L, 2L);
            }

            @Override
            public List<VertexQueryResult> readVertices(String vertexName, List<Object> vertexIds,
                                                        List<String> propertyNames) {
                seedBatches.add(vertexIds);
                List<VertexQueryResult> out = new ArrayList<>(vertexIds.size());
                for (Object vertexId : vertexIds) {
                    out.add(new VertexQueryResult("p", vertexId, Map.of(), 1L, 2L));
                }
                return out;
            }

            @Override
            public List<EdgeQueryResult> readEdges(Object vertexId, String vertexName, String edgeName,
                                                   EdgeSide direction, List<String> propertyNames) {
                return List.of();
            }

            @Override
            public List<List<EdgeQueryResult>> readEdgesBatch(String edgeName, EdgeSide direction,
                                                              List<Object> vertexIds, List<String> propertyNames) {
                List<List<EdgeQueryResult>> out = new ArrayList<>();
                for (Object id : vertexIds) {
                    out.add(List.of());
                }
                return out;
            }
        };
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
        List<List<Object>> edgeBatches = java.util.Collections.synchronizedList(new ArrayList<>());
        GraphQuery fanOut = edgeBatchTrackingGraph(30, edgeBatches);
        GremlinGraphQuery query = new GremlinGraphQuery(fanOut,
                NeighborVertexResolver.of(Map.of("knows", "p"), Map.of()));

        List<Object> result = query.execute("g.V('v1', 'p').batch(10).out('knows').outE('created')");

        assertEquals(0, result.size()); // 'created' has no edges in this fake
        assertEquals(List.of(10, 10, 10), edgeBatches.stream().map(List::size).toList());
    }

    @Test
    void adaptiveBatchSizeScalesSecondHopToFirstHopFanOut() {
        List<List<Object>> edgeBatches = java.util.Collections.synchronizedList(new ArrayList<>());
        GraphQuery fanOut = edgeBatchTrackingGraph(100, edgeBatches);
        GremlinGraphQuery query = new GremlinGraphQuery(fanOut,
                NeighborVertexResolver.of(Map.of("knows", "p"), Map.of()));

        List<Object> result = query.execute("g.V('v1', 'p').out('knows').outE('created')");

        assertEquals(0, result.size());
        // 100 first-hop neighbours -> adaptive batch = 100/2 = 50 (the default), two requests of fifty
        assertEquals(List.of(50, 50), edgeBatches.stream().map(List::size).toList());
    }

    @Test
    void explicitBatchSizeIsNotOverriddenByAdaptiveTuning() {
        List<List<Object>> edgeBatches = java.util.Collections.synchronizedList(new ArrayList<>());
        GraphQuery fanOut = edgeBatchTrackingGraph(100, edgeBatches);
        GremlinGraphQuery query = new GremlinGraphQuery(fanOut,
                NeighborVertexResolver.of(Map.of("knows", "p"), Map.of()));

        query.execute("g.V('v1', 'p').batch(10).out('knows').outE('created')");

        // explicit batch(10) is authoritative: 100 neighbours stay chunked by 10
        assertEquals(List.of(10, 10, 10, 10, 10, 10, 10, 10, 10, 10),
                edgeBatches.stream().map(List::size).toList());
    }

    @Test
    void filterKeepsDefaultBatchSizeOnSecondHop() {
        List<List<Object>> edgeBatches = java.util.Collections.synchronizedList(new ArrayList<>());
        GraphQuery fanOut = new GraphQuery() {
            @Override
            public VertexQueryResult readVertex(Object vertexId, String vertexName, List<String> propertyNames) {
                return new VertexQueryResult("p", vertexId, Map.of(), 1L, 2L);
            }

            @Override
            public List<VertexQueryResult> readVertices(String vertexName, List<Object> vertexIds,
                                                        List<String> propertyNames) {
                List<VertexQueryResult> out = new ArrayList<>(vertexIds.size());
                for (Object id : vertexIds) {
                    int idx = Integer.parseInt(((String) id).substring(1));
                    out.add(new VertexQueryResult("p", id, Map.of("kind", idx < 30 ? "keep" : "drop"), 1L, 2L));
                }
                return out;
            }

            @Override
            public List<EdgeQueryResult> readEdges(Object vertexId, String vertexName, String edgeName,
                                                   EdgeSide direction, List<String> propertyNames) {
                List<EdgeQueryResult> edges = new ArrayList<>();
                for (int i = 0; i < 100; i++) {
                    edges.add(new EdgeQueryResult("knows", "e" + i, EdgeSide.OUT, "n" + i, Map.of(), 1L, 2L));
                }
                return edges;
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
        GremlinGraphQuery query = new GremlinGraphQuery(fanOut,
                NeighborVertexResolver.of(Map.of("knows", "p"), Map.of()));

        query.execute("g.V('v1', 'p').out('knows').has('kind', 'keep').outE('created')");

        // has() filter reduced 100 -> 30, so the second hop keeps the default batch size (50)
        assertEquals(List.of(30), edgeBatches.stream().map(List::size).sorted().toList());
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
    void filterKeepsElementsMatchingEitherProperty() {
        EdgeQueryResult keep = edge("e1", EdgeSide.OUT, "n1",
                Map.of("relation", "directedBy", "xrelation", "directed"));
        EdgeQueryResult drop = edge("e2", EdgeSide.OUT, "n2",
                Map.of("relation", "other", "xrelation", "other"));
        when(graphQuery.readEdges("v1", "person", "knows", EdgeSide.OUT, List.of("relation", "xrelation")))
                .thenReturn(List.of(keep, drop));

        List<Object> result = gremlin.execute("g.V('v1', 'person').outE('knows')"
                + ".filter(relation.equals('directedBy') || xrelation.equals('directed'))");

        assertEquals(1, result.size());
        assertEquals("e1", ((GremlinEdge) result.get(0)).id());
    }

    @Test
    void filterAcceptsTheGroovyElementAccessor() {
        EdgeQueryResult keep = edge("e1", EdgeSide.OUT, "n1",
                Map.of("relation", "directedBy", "xrelation", "directed"));
        EdgeQueryResult drop = edge("e2", EdgeSide.OUT, "n2",
                Map.of("relation", "other", "xrelation", "other"));
        when(graphQuery.readEdges("v1", "person", "knows", EdgeSide.OUT, List.of("relation")))
                .thenReturn(List.of(keep, drop));

        List<Object> result = gremlin.execute("g.V('v1', 'person').outE('knows')"
                + ".filter(it.get().value('relation').equals('directedBy'))");

        assertEquals(1, result.size());
        assertEquals("e1", ((GremlinEdge) result.get(0)).id());
    }

    @Test
    void filterClosureCanMatchTheElementLabel() {
        EdgeQueryResult e1 = edge("e1", EdgeSide.OUT, "n1", Map.of());
        EdgeQueryResult e2 = edge("e2", EdgeSide.OUT, "n2", Map.of());
        when(graphQuery.readEdges("v1", "person", "knows", EdgeSide.OUT, List.of()))
                .thenReturn(List.of(e1, e2));
        when(graphQuery.readVertices(eq("person"), anyList(), isNull()))
                .thenAnswer(inv -> {
                    List<Object> ids = inv.getArgument(1);
                    List<VertexQueryResult> out = new ArrayList<>(ids.size());
                    for (Object id : ids) {
                        out.add(new VertexQueryResult("person", id, Map.of(), 1L, 2L));
                    }
                    return out;
                });

        List<Object> result = gremlin.execute("g.V('v1', 'person').out('knows')"
                + ".filter { it.get().label() == 'person' }");

        assertEquals(2, result.size());
    }

    @Test
    void filterClosureComparesStringsCaseInsensitively() {
        EdgeQueryResult keep = edge("e1", EdgeSide.OUT, "n1", Map.of("status", "ACTIVE"));
        EdgeQueryResult drop = edge("e2", EdgeSide.OUT, "n2", Map.of("status", "FROZEN"));
        when(graphQuery.readEdges("v1", "person", "knows", EdgeSide.OUT, List.of("status")))
                .thenReturn(List.of(keep, drop));

        List<Object> result = gremlin.execute("g.V('v1', 'person').outE('knows')"
                + ".filter( it.get().value('status').equals('Active') )");

        assertEquals(1, result.size());
        assertEquals("e1", ((GremlinEdge) result.get(0)).id());
    }

    @Test
    void mapClosureProjectsOnePropertyValue() {
        EdgeQueryResult e1 = edge("e1", EdgeSide.OUT, "n1", Map.of("weight", 0.5));
        EdgeQueryResult e2 = edge("e2", EdgeSide.OUT, "n2", Map.of("weight", 0.25));
        when(graphQuery.readEdges("v1", "person", "knows", EdgeSide.OUT, List.of("weight")))
                .thenReturn(List.of(e1, e2));

        List<Object> result = gremlin.execute(
                "g.V('v1', 'person').outE('knows').map { it.get().value('weight') }");

        assertEquals(List.of(0.5, 0.25), result);
    }

    @Test
    void mapRejectsAnExpressionThatIsNotASingleAccessor() {
        QueryException failure = assertThrows(QueryException.class, () -> gremlin.execute(
                "g.V('v1', 'person').outE('knows').map { it.get().value('weight') + 1 }"));
        assertTrue(failure.getMessage().contains("single element accessor"), failure.getMessage());
    }

    @Test
    void aHopAgainstTheEdgeDirectionIsRejectedWithTheDirectionToUse() {
        gremlin.schema(phoneGraph());

        QueryException failure = assertThrows(QueryException.class, () -> gremlin.execute(
                "g.V('+86-138-10008','Phone').in('PhoneToAccount')"));
        assertTrue(failure.getMessage().contains("PhoneToAccount runs Phone -> Account"),
                failure.getMessage());
        assertTrue(failure.getMessage().contains("use out('PhoneToAccount') or both('PhoneToAccount')"),
                failure.getMessage());

        // the direction the schema declares is not rejected, and both() always is not
        assertTrue(gremlin.execute("g.V('+86-138-10008','Phone').out('PhoneToAccount')").isEmpty());
        assertTrue(gremlin.execute("g.V('+86-138-10008','Phone').both('PhoneToAccount')").isEmpty());
        // ... and from an account the same class is walked in the other direction
        assertTrue(gremlin.execute("g.V('1001','Account').in('PhoneToAccount')").isEmpty());
    }

    @Test
    void aDirectionTheSchemaCannotDecideIsNotRejected() {
        // a class whose endpoints the schema does not name: nothing to check against
        GraphSchema schema = new GraphSchema(1, "g")
                .vertex(new VertexSchema(1, "Phone", "phone"))
                .edge(new EdgeSchema(1, "Link", "link",
                        new EdgeEndpointSchema(1, "source", "source", PropertyType.ScalarType.STRING, null),
                        new EdgeEndpointSchema(2, "target", "target", PropertyType.ScalarType.STRING, null)));
        gremlin.schema(schema);

        assertTrue(gremlin.execute("g.V('p','Phone').in('Link')").isEmpty());
        // and without a schema at all the check is skipped
        new GremlinGraphQuery(graphQuery, resolver)
                .execute("g.V('p','Phone').in('PhoneToAccount')");
    }

    private static GraphSchema phoneGraph() {
        return new GraphSchema(1, "payment-graph")
                .vertex(new VertexSchema(1, "Account", "An account"))
                .vertex(new VertexSchema(2, "Phone", "A phone number"))
                .edge(new EdgeSchema(2, "PhoneToAccount", "A phone belongs to an account",
                        new EdgeEndpointSchema(1, "source", "Phone vertex id",
                                PropertyType.ScalarType.STRING, "Phone"),
                        new EdgeEndpointSchema(2, "target", "Account vertex id",
                                PropertyType.ScalarType.LONG, "Account")));
    }

    @Test
    void filterOverAllEdgeClassesProjectsOnlyTheFilteredProperties() {
        when(graphQuery.readAllEdgesBatch(anyList(), any(), anyList()))
                .thenAnswer(inv -> {
                    List<Object> ids = inv.getArgument(0);
                    List<List<EdgeQueryResult>> out = new ArrayList<>();
                    for (Object id : ids) {
                        out.add(List.of(
                                edge("e-keep-" + id, EdgeSide.OUT, "n-keep-" + id,
                                        Map.of("relation", "r", "xrelation", "x")),
                                edge("e-drop-" + id, EdgeSide.OUT, "n-drop-" + id,
                                        Map.of("relation", "other", "xrelation", "other"))));
                    }
                    return out;
                });

        List<Object> result = gremlin.execute("g.V('v1', 'person').bothE()"
                + ".filter(relation.equals('r') || xrelation.equals('x'))");

        assertEquals(1, result.size());
        assertEquals("e-keep-v1", ((GremlinEdge) result.get(0)).id());
        // the filter keys are the only property bins the edge read has to project
        verify(graphQuery).readAllEdgesBatch(List.of("v1"), EdgeSide.BOTH, List.of("relation", "xrelation"));
    }

    @Test
    void edgeHopSubgraphIncludesTheFarEndpoint() {
        when(graphQuery.readEdges("v1", "person", "knows", EdgeSide.OUT, List.of()))
                .thenReturn(List.of(edge("e1", EdgeSide.OUT, "n1", Map.of())));
        when(graphQuery.readVertex("n1", "person", List.of()))
                .thenReturn(new VertexQueryResult("person", "n1", Map.of("name", "alice"), 5L, 6L));

        Subgraph subgraph = gremlin.executeSubgraph("g.V('v1', 'person').outE('knows')");

        // start vertex + the edge's other endpoint, so bothE() subgraphs render fully
        assertEquals(2, subgraph.vertices().size());
        assertEquals(1, subgraph.edges().size());
        assertEquals("v1", subgraph.edges().get(0).source());
        assertEquals("n1", subgraph.edges().get(0).target());
    }

    @Test
    void executeSubgraphReturnsTouchedVerticesAndEdges() {
        GraphQuery gq = new GraphQuery() {
            @Override
            public VertexQueryResult readVertex(Object vertexId, String vertexName, List<String> propertyNames) {
                return new VertexQueryResult("p", vertexId, Map.of("name", vertexId), 1L, 2L);
            }

            @Override
            public List<VertexQueryResult> readVertices(String vertexName, List<Object> vertexIds,
                                                        List<String> propertyNames) {
                List<VertexQueryResult> out = new ArrayList<>(vertexIds.size());
                for (Object id : vertexIds) {
                    out.add(new VertexQueryResult(vertexName, id, Map.of("name", id), 1L, 2L));
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
                    out.add(List.of());
                }
                return out;
            }
        };
        GremlinGraphQuery gremlin = new GremlinGraphQuery(gq,
                NeighborVertexResolver.of(Map.of("knows", "p"), Map.of()));

        Subgraph subgraph = gremlin.executeSubgraph("g.V('v1', 'p').out('knows')");

        assertEquals(3, subgraph.vertices().size());
        assertEquals(2, subgraph.edges().size());
        Subgraph.Edge e1 = subgraph.edges().get(0);
        assertEquals("v1", e1.source());
        assertEquals("n1", e1.target());
        Subgraph.Edge e2 = subgraph.edges().get(1);
        assertEquals("v1", e2.source());
        assertEquals("n2", e2.target());
    }

    @Test
    void hopWithoutEdgeNameReadsAllEdgeClasses() {
        GraphQuery gq = new GraphQuery() {
            @Override
            public VertexQueryResult readVertex(Object vertexId, String vertexName, List<String> propertyNames) {
                return new VertexQueryResult(vertexName, vertexId, Map.of(), 1L, 2L);
            }

            @Override
            public List<VertexQueryResult> readVertices(String vertexName, List<Object> vertexIds,
                                                        List<String> propertyNames) {
                List<VertexQueryResult> out = new ArrayList<>(vertexIds.size());
                for (Object id : vertexIds) {
                    out.add(new VertexQueryResult(vertexName, id, Map.of(), 1L, 2L));
                }
                return out;
            }

            @Override
            public List<EdgeQueryResult> readEdges(Object vertexId, String vertexName, String edgeName,
                                                   EdgeSide direction, List<String> propertyNames) {
                return List.of();
            }

            @Override
            public List<List<EdgeQueryResult>> readAllEdgesBatch(List<Object> vertexIds, EdgeSide direction,
                                                                 List<String> propertyNames) {
                List<List<EdgeQueryResult>> out = new ArrayList<>();
                for (Object id : vertexIds) {
                    out.add(List.of(
                            new EdgeQueryResult("knows", "e1", EdgeSide.OUT, "n1", Map.of(), 1L, 2L),
                            new EdgeQueryResult("created", "e2", EdgeSide.OUT, "s1", Map.of(), 1L, 2L)));
                }
                return out;
            }
        };
        GremlinGraphQuery gremlin = new GremlinGraphQuery(gq,
                NeighborVertexResolver.of(Map.of("knows", "p", "created", "s"), Map.of()));

        List<Object> result = gremlin.execute("g.V('v1', 'p').both()");

        assertEquals(2, result.size());
        assertEquals("n1", ((GremlinVertex) result.get(0)).id());
        assertEquals("p", ((GremlinVertex) result.get(0)).label());
        assertEquals("s1", ((GremlinVertex) result.get(1)).id());
        assertEquals("s", ((GremlinVertex) result.get(1)).label());
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
        when(graphQuery.readEdges("v1", "person", "knows", EdgeSide.OUT, List.of("status")))
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
    void countAfterAVertexHopIsAnsweredByTheStoreWithoutReadingTheEdges() {
        List<String> resolved = new ArrayList<>();
        int[] counted = {0};
        GremlinStepRegistry counting = new GremlinStepRegistry() {
            @Override
            public GremlinStep resolve(String name, List<String> args) {
                resolved.add(name);
                return super.resolve(name, args);
            }
        };
        GraphQuery gq = new GraphQuery() {
            @Override
            public VertexQueryResult readVertex(Object id, String name, List<String> props) {
                return new VertexQueryResult("p", id, Map.of(), 1L, 2L);
            }

            @Override
            public List<VertexQueryResult> readVertices(String name, List<Object> ids, List<String> props) {
                return List.of();
            }

            @Override
            public List<EdgeQueryResult> readEdges(Object id, String name, String edgeName, EdgeSide dir, List<String> props) {
                return List.of(new EdgeQueryResult("knows", "e1", EdgeSide.OUT, "n1", Map.of(), 1L, 2L));
            }

            @Override
            public List<List<EdgeQueryResult>> readEdgesBatch(String edgeName, EdgeSide dir, List<Object> ids, List<String> props) {
                return List.of();
            }

            @Override
            public long countEdges(Object id, String name, String edgeName, EdgeSide dir) {
                counted[0]++;
                return 1;
            }
        };
        GremlinGraphQuery query = new GremlinGraphQuery(gq,
                NeighborVertexResolver.of(Map.of("knows", "p"), Map.of()), counting);

        List<Object> answer = query.execute("g.V('v1','p').out('knows').count()");

        // out(...).count() is compiled into one counting step the store answers itself
        assertEquals(List.of("countOutE"), resolved);
        assertEquals(List.of(1L), answer);
        assertEquals(1, counted[0], "the store counted the edges");
    }

    @Test
    void inAndBothHopsAreAlsoRewrittenBeforeCount() {
        for (String hop : List.of("in", "both")) {
            List<String> resolved = new ArrayList<>();
            GremlinStepRegistry counting = new GremlinStepRegistry() {
                @Override
                public GremlinStep resolve(String name, List<String> args) {
                    resolved.add(name);
                    return super.resolve(name, args);
                }
            };
            GraphQuery gq = new GraphQuery() {
                @Override
                public VertexQueryResult readVertex(Object id, String name, List<String> props) {
                    return new VertexQueryResult("p", id, Map.of(), 1L, 2L);
                }

                @Override
                public List<VertexQueryResult> readVertices(String name, List<Object> ids, List<String> props) {
                    return List.of();
                }

                @Override
                public List<EdgeQueryResult> readEdges(Object id, String name, String edgeName, EdgeSide dir, List<String> props) {
                    return List.of(new EdgeQueryResult("knows", "e1", EdgeSide.OUT, "n1", Map.of(), 1L, 2L));
                }

                @Override
                public List<List<EdgeQueryResult>> readEdgesBatch(String edgeName, EdgeSide dir, List<Object> ids, List<String> props) {
                    return List.of();
                }
            };
            GremlinGraphQuery query = new GremlinGraphQuery(gq,
                    NeighborVertexResolver.of(Map.of("knows", "p"), Map.of()), counting);

            List<Object> answer = query.execute("g.V('v1','p')." + hop + "('knows').count()");

            assertEquals(List.of("count" + Character.toUpperCase(hop.charAt(0)) + hop.substring(1) + "E"),
                    resolved, hop);
            assertEquals(List.of(1L), answer, hop);
        }
    }

    @Test
    void aCountWithoutAnEdgeClassStillReadsTheEdges() {
        // both() without a class means every class; a store cannot count that from one bin, so the
        // step must fall back to reading (this is what the API integration test caught)
        int[] counted = {0};
        List<String> reads = new ArrayList<>();
        GraphQuery gq = new GraphQuery() {
            @Override
            public VertexQueryResult readVertex(Object id, String name, List<String> props) {
                return new VertexQueryResult("p", id, Map.of(), 1L, 2L);
            }

            @Override
            public List<EdgeQueryResult> readEdges(Object id, String name, String edgeName, EdgeSide dir, List<String> props) {
                reads.add("readEdges");
                return List.of(new EdgeQueryResult("knows", "e1", EdgeSide.OUT, "n1", Map.of(), 1L, 2L),
                        new EdgeQueryResult("knows", "e2", EdgeSide.OUT, "n2", Map.of(), 1L, 2L));
            }

            @Override
            public List<List<EdgeQueryResult>> readAllEdgesBatch(List<Object> ids, EdgeSide dir,
                                                                  List<String> props) {
                return ids.stream().map(id -> readEdges(id, null, null, dir, props)).toList();
            }

            @Override
            public long countEdges(Object id, String name, String edgeName, EdgeSide dir) {
                counted[0]++;
                return 0;
            }
        };
        GremlinGraphQuery query = new GremlinGraphQuery(gq,
                NeighborVertexResolver.of(Map.of("knows", "p"), Map.of()));

        List<Object> answer = query.execute("g.V('v1','p').both().count()");

        assertEquals(List.of(2L), answer, "both() counts every edge of the seed");
        assertEquals(0, counted[0], "the store was not asked to count an unnamed class");
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
    void edgeReadsAreDedupedAndCached() {
        List<List<Object>> edgeBatches = java.util.Collections.synchronizedList(new ArrayList<>());
        GraphQuery gq = new GraphQuery() {
            @Override
            public VertexQueryResult readVertex(Object id, String name, List<String> props) {
                return new VertexQueryResult("p", id, Map.of(), 1L, 2L);
            }

            @Override
            public List<VertexQueryResult> readVertices(String name, List<Object> ids, List<String> props) {
                List<VertexQueryResult> out = new ArrayList<>(ids.size());
                for (Object id : ids) {
                    out.add(new VertexQueryResult(name, id, Map.of("name", "x"), 1L, 2L));
                }
                return out;
            }

            @Override
            public List<EdgeQueryResult> readEdges(Object id, String name, String edgeName, EdgeSide dir, List<String> props) {
                if (edgeName.equals("knows")) {
                    // two edges that both reach the SAME neighbour n1
                    return List.of(
                            new EdgeQueryResult("knows", "e1", EdgeSide.OUT, "n1", Map.of(), 1L, 2L),
                            new EdgeQueryResult("knows", "e2", EdgeSide.OUT, "n1", Map.of(), 1L, 2L));
                }
                return List.of();
            }

            @Override
            public List<List<EdgeQueryResult>> readEdgesBatch(String edgeName, EdgeSide dir, List<Object> ids, List<String> props) {
                edgeBatches.add(new ArrayList<>(ids));
                List<List<EdgeQueryResult>> out = new ArrayList<>();
                for (Object id : ids) {
                    out.add(List.of(new EdgeQueryResult("created", "c1", EdgeSide.OUT, "s1", Map.of(), 1L, 2L)));
                }
                return out;
            }
        };
        GremlinGraphQuery gremlin = new GremlinGraphQuery(gq,
                NeighborVertexResolver.of(Map.of("knows", "p", "created", "s"), Map.of()));

        List<Object> result = gremlin.execute("g.V('v1','p').out('knows').out('created')");

        // result preserves the two traversers (s1 twice), but the storage edge read was deduped
        assertEquals(2, result.size());
        assertEquals(1, edgeBatches.size());
        assertEquals(List.of("n1"), edgeBatches.get(0));
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
