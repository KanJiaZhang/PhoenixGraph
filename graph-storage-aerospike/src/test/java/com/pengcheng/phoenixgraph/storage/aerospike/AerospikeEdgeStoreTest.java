package com.pengcheng.phoenixgraph.storage.aerospike;

import com.pengcheng.phoenixgraph.schema.EdgeDirection;
import com.pengcheng.phoenixgraph.schema.EdgePropertySchema;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.PropertyType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AerospikeEdgeStoreTest {

    @Test
    void edgeBinUsesEdgeSchemaIdThenDirectionCode() {
        assertEquals("ve_7_0", AerospikeEdgeStore.edgeBin(7, AerospikeEdgeStore.EDGE_DIR_BOTH));
        assertEquals("ve_7_1", AerospikeEdgeStore.edgeBin(7, AerospikeEdgeStore.EDGE_DIR_IN));
        assertEquals("ve_7_2", AerospikeEdgeStore.edgeBin(7, AerospikeEdgeStore.EDGE_DIR_OUT));
        assertEquals("ve_2_2", AerospikeEdgeStore.edgeBin(2, AerospikeEdgeStore.EDGE_DIR_OUT));
    }

    @Test
    void directedEdgeStoresOutOnSourceAndInOnDestination() {
        assertEquals(AerospikeEdgeStore.EDGE_DIR_OUT, AerospikeEdgeStore.sourceDirection(EdgeDirection.DIRECTED));
        assertEquals(AerospikeEdgeStore.EDGE_DIR_IN, AerospikeEdgeStore.destinationDirection(EdgeDirection.DIRECTED));
    }

    @Test
    void undirectedAndBidirectionalEdgesStoreBothOnBothEndpoints() {
        assertEquals(AerospikeEdgeStore.EDGE_DIR_BOTH, AerospikeEdgeStore.sourceDirection(EdgeDirection.UNDIRECTED));
        assertEquals(AerospikeEdgeStore.EDGE_DIR_BOTH, AerospikeEdgeStore.destinationDirection(EdgeDirection.UNDIRECTED));
        assertEquals(AerospikeEdgeStore.EDGE_DIR_BOTH, AerospikeEdgeStore.sourceDirection(EdgeDirection.BIDIRECTIONAL));
        assertEquals(AerospikeEdgeStore.EDGE_DIR_BOTH, AerospikeEdgeStore.destinationDirection(EdgeDirection.BIDIRECTIONAL));
    }

    @Test
    void edgeValueSerializesLastUpdatedTimeFirstWithPerPropertyTimestamps() {
        Map<Integer, EdgePropertyValue> properties = Map.of(
                1, new EdgePropertyValue("alice", 10L, 20L),
                2, new EdgePropertyValue(30, 40L, 50L));

        List<Object> value = AerospikeEdgeStore.serializeEdgeValue(100L, 50L, "other", properties);

        assertEquals(100L, value.get(0)); // lastUpdatedTime first
        assertEquals(50L, value.get(1));  // createdTime second
        assertEquals("other", value.get(2)); // other endpoint

        @SuppressWarnings("unchecked")
        Map<Integer, List<Object>> rawProps = (Map<Integer, List<Object>>) value.get(3);
        assertEquals(List.of("alice", 10L, 20L), rawProps.get(1));
        assertEquals(List.of(30, 40L, 50L), rawProps.get(2));
    }

    @Test
    void edgeValueRoundTripsThroughDeserialization() {
        Map<Integer, EdgePropertyValue> properties = Map.of(
                1, new EdgePropertyValue("alice", 10L, 20L));
        List<Object> raw = AerospikeEdgeStore.serializeEdgeValue(100L, 50L, "other", properties);

        StoredEdgeValue edge = AerospikeEdgeStore.deserializeEdgeValue("e1", raw);

        assertEquals("e1", edge.edgeId());
        assertEquals("other", edge.otherVertexId());
        assertEquals(100L, edge.lastUpdatedTime());
        assertEquals(50L, edge.createdTime());
        assertEquals(new EdgePropertyValue("alice", 10L, 20L), edge.properties().get(1));
    }

    @Test
    void deserializeRejectsMalformedValue() {
        assertThrows(IllegalArgumentException.class,
                () -> AerospikeEdgeStore.deserializeEdgeValue("e1", List.of(1L)));
    }

    @Test
    void mergeEdgeValueKeepsOnlyNewerProperties() {
        Map<Integer, EdgePropertyValue> oldProps = Map.of(
                3, new EdgePropertyValue("old", 10L, 100L),
                4, new EdgePropertyValue("keep", 10L, 200L));
        List<Object> existing = AerospikeEdgeStore.serializeEdgeValue(200L, 50L, "other", oldProps);

        Map<Integer, EdgePropertyValue> newProps = Map.of(
                3, new EdgePropertyValue("new", 20L, 300L),    // newer -> replace
                4, new EdgePropertyValue("stale", 20L, 150L),   // older -> keep
                5, new EdgePropertyValue("added", 20L, 250L));  // new -> add
        List<Object> incoming = AerospikeEdgeStore.serializeEdgeValue(300L, 60L, "other", newProps);

        List<Object> merged = AerospikeEdgeStore.mergeEdgeValue("e1", incoming, existing);

        StoredEdgeValue mergedEdge = AerospikeEdgeStore.deserializeEdgeValue("e1", merged);
        assertEquals(300L, mergedEdge.lastUpdatedTime()); // max(200, 300)
        assertEquals(50L, mergedEdge.createdTime());      // keep existing createdTime
        assertEquals("new", mergedEdge.properties().get(3).value());
        assertEquals("keep", mergedEdge.properties().get(4).value());
        assertEquals("added", mergedEdge.properties().get(5).value());
    }

    @Test
    void mergeEdgeValueReturnsNullWhenAllPropertiesStale() {
        Map<Integer, EdgePropertyValue> oldProps = Map.of(3, new EdgePropertyValue("old", 10L, 100L));
        List<Object> existing = AerospikeEdgeStore.serializeEdgeValue(100L, 50L, "other", oldProps);

        Map<Integer, EdgePropertyValue> staleProps = Map.of(3, new EdgePropertyValue("stale", 20L, 100L));
        List<Object> incoming = AerospikeEdgeStore.serializeEdgeValue(90L, 60L, "other", staleProps);

        assertNull(AerospikeEdgeStore.mergeEdgeValue("e1", incoming, existing));
    }

    @Test
    void mergeEdgeValueUsesMaxPropertyLastUpdatedTime() {
        Map<Integer, EdgePropertyValue> oldProps = Map.of(3, new EdgePropertyValue("old", 10L, 150L));
        List<Object> existing = AerospikeEdgeStore.serializeEdgeValue(200L, 50L, "other", oldProps);

        Map<Integer, EdgePropertyValue> newProps = Map.of(3, new EdgePropertyValue("new", 20L, 300L));
        List<Object> incoming = AerospikeEdgeStore.serializeEdgeValue(50L, 60L, "other", newProps);

        List<Object> merged = AerospikeEdgeStore.mergeEdgeValue("e1", incoming, existing);

        StoredEdgeValue mergedEdge = AerospikeEdgeStore.deserializeEdgeValue("e1", merged);
        assertEquals("new", mergedEdge.properties().get(3).value());
        // edge lastUpdatedTime = max(existing 200, max property 300) = 300, NOT max(200, incoming 50)
        assertEquals(300L, mergedEdge.lastUpdatedTime());
    }

    @Test
    void edgeLastUpdatedTimeReturnsMaxOrFallback() {
        assertEquals(300L, AerospikeEdgeStore.edgeLastUpdatedTime(50L,
                Map.of(3, new EdgePropertyValue("a", 1L, 300L), 4, new EdgePropertyValue("b", 1L, 100L))));
        assertEquals(50L, AerospikeEdgeStore.edgeLastUpdatedTime(50L, Map.of()));
    }

    @Test
    void evictsOldestEdgesBeyondLimit() {
        Map<String, Object> edges = new LinkedHashMap<>();
        edges.put("e1", List.of(100L, 0L, Map.of())); // oldest
        edges.put("e2", List.of(300L, 0L, Map.of()));
        edges.put("e3", List.of(200L, 0L, Map.of()));

        AerospikeEdgeStore.evictOldest(edges, 2);

        assertEquals(Set.of("e2", "e3"), edges.keySet());
    }

    @Test
    void noEvictionWhenWithinLimitOrUnlimited() {
        Map<String, Object> edges = new LinkedHashMap<>();
        edges.put("e1", List.of(100L, 0L, Map.of()));
        edges.put("e2", List.of(200L, 0L, Map.of()));

        AerospikeEdgeStore.evictOldest(edges, 0);   // unlimited
        assertEquals(2, edges.size());

        AerospikeEdgeStore.evictOldest(edges, 3);   // limit above size
        assertEquals(2, edges.size());
    }

    @Test
    void lastUpdatedTimeExtractedFromFirstElement() {
        assertEquals(100L, AerospikeEdgeStore.lastUpdatedTimeOf(List.of(100L, 0L, Map.of())));
        assertEquals(Long.MAX_VALUE, AerospikeEdgeStore.lastUpdatedTimeOf("not-a-list"));
    }

    @Test
    void bothLimitUsesTighterPositiveLimit() {
        assertEquals(0, AerospikeEdgeStore.bothLimit(0, 0));
        assertEquals(5, AerospikeEdgeStore.bothLimit(5, 0));
        assertEquals(7, AerospikeEdgeStore.bothLimit(0, 7));
        assertEquals(5, AerospikeEdgeStore.bothLimit(5, 10));
        assertEquals(7, AerospikeEdgeStore.bothLimit(10, 7));
    }

    @Test
    void mergeAndEvictAddsBatchThenEvictsOldestOnce() {
        Map<String, Object> existing = new LinkedHashMap<>();
        existing.put("e1", List.of(100L, 0L, Map.of()));
        existing.put("e2", List.of(200L, 0L, Map.of()));
        existing.put("e3", List.of(300L, 0L, Map.of()));

        Map<String, Object> added = new LinkedHashMap<>();
        added.put("e4", List.of(400L, 0L, Map.of()));
        added.put("e5", List.of(500L, 0L, Map.of()));

        // limit 3: after merging 5 edges, evict the 2 oldest (e1, e2) in one pass
        Map<String, Object> result = AerospikeEdgeStore.mergeAndEvict(existing, added, 3);

        assertEquals(Set.of("e3", "e4", "e5"), result.keySet());
    }

    @Test
    void mergeAndEvictKeepsBatchWhenUnderLimit() {
        Map<String, Object> existing = new LinkedHashMap<>();
        existing.put("e1", List.of(100L, 0L, Map.of()));

        Map<String, Object> added = new LinkedHashMap<>();
        added.put("e2", List.of(200L, 0L, Map.of()));
        added.put("e3", List.of(300L, 0L, Map.of()));

        Map<String, Object> result = AerospikeEdgeStore.mergeAndEvict(existing, added, 0); // unlimited

        assertEquals(Set.of("e1", "e2", "e3"), result.keySet());
    }

    @Test
    void mergeAndEvictKeepsStoredValueForStaleEdge() {
        Map<Integer, EdgePropertyValue> oldProps = Map.of(3, new EdgePropertyValue("old", 10L, 100L));
        Map<String, Object> existing = new LinkedHashMap<>();
        existing.put("e1", AerospikeEdgeStore.serializeEdgeValue(100L, 50L, "other", oldProps));

        Map<Integer, EdgePropertyValue> staleProps = Map.of(3, new EdgePropertyValue("stale", 20L, 100L));
        Map<String, Object> added = new LinkedHashMap<>();
        added.put("e1", AerospikeEdgeStore.serializeEdgeValue(90L, 60L, "other", staleProps));

        Map<String, Object> result = AerospikeEdgeStore.mergeAndEvict(existing, added, 0);

        StoredEdgeValue kept = AerospikeEdgeStore.deserializeEdgeValue("e1", result.get("e1"));
        assertEquals("old", kept.properties().get(3).value());
        assertEquals(100L, kept.lastUpdatedTime());
    }

    @Test
    void planForDirectedIncomingEdge() {
        EdgeSchema schema = schema(1, EdgeDirection.DIRECTED);
        AerospikeEdgeStore.EdgeWritePlan plan = AerospikeEdgeStore.planFor("v", schema,
                edge("e1", schema, "src", "v"));

        assertEquals(AerospikeEdgeStore.EDGE_DIR_IN, plan.sharedDir());
        assertEquals("src", plan.otherEndpoint());
        assertEquals(AerospikeEdgeStore.EDGE_DIR_OUT, plan.otherDir());
    }

    @Test
    void planForDirectedOutgoingEdge() {
        EdgeSchema schema = schema(1, EdgeDirection.DIRECTED);
        AerospikeEdgeStore.EdgeWritePlan plan = AerospikeEdgeStore.planFor("v", schema,
                edge("e1", schema, "v", "dst"));

        assertEquals(AerospikeEdgeStore.EDGE_DIR_OUT, plan.sharedDir());
        assertEquals("dst", plan.otherEndpoint());
        assertEquals(AerospikeEdgeStore.EDGE_DIR_IN, plan.otherDir());
    }

    @Test
    void planForUndirectedEdgeUsesBothBinOnEitherSide() {
        EdgeSchema schema = schema(2, EdgeDirection.UNDIRECTED);

        AerospikeEdgeStore.EdgeWritePlan fromSource = AerospikeEdgeStore.planFor("v", schema,
                edge("e1", schema, "v", "other"));
        assertEquals(AerospikeEdgeStore.EDGE_DIR_BOTH, fromSource.sharedDir());
        assertEquals("other", fromSource.otherEndpoint());
        assertEquals(AerospikeEdgeStore.EDGE_DIR_BOTH, fromSource.otherDir());

        AerospikeEdgeStore.EdgeWritePlan fromDestination = AerospikeEdgeStore.planFor("v", schema,
                edge("e2", schema, "other", "v"));
        assertEquals(AerospikeEdgeStore.EDGE_DIR_BOTH, fromDestination.sharedDir());
        assertEquals("other", fromDestination.otherEndpoint());
        assertEquals(AerospikeEdgeStore.EDGE_DIR_BOTH, fromDestination.otherDir());
    }

    @Test
    void planForRejectsEdgeNotTouchingVertex() {
        EdgeSchema schema = schema(1, EdgeDirection.DIRECTED);
        assertThrows(IllegalArgumentException.class,
                () -> AerospikeEdgeStore.planFor("v", schema, edge("e1", schema, "a", "b")));
    }

    private static EdgeSchema schema(int id, EdgeDirection direction) {
        return new EdgeSchema(id, "e" + id, null,
                new EdgePropertySchema(1, "source", null, PropertyType.ScalarType.LONG),
                new EdgePropertySchema(2, "target", null, PropertyType.ScalarType.LONG),
                direction);
    }

    private static EdgeUpsert edge(String edgeId, EdgeSchema schema, Object source, Object destination) {
        return new EdgeUpsert(edgeId, schema, source, destination, Map.of(), 1L, 2L);
    }
}
