package com.pengcheng.phoenixgraph.storage;

import com.pengcheng.phoenixgraph.schema.EdgeDirection;
import com.pengcheng.phoenixgraph.storage.memory.InMemoryAdjacentEdgeStore;
import com.pengcheng.phoenixgraph.storage.memory.InMemoryColumnStore;
import com.pengcheng.phoenixgraph.storage.memory.InMemoryEdgeTableStore;
import com.pengcheng.phoenixgraph.storage.memory.InMemoryVertexStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class StorageAbstractionTest {

    @Test
    void columnStorePutsGetsAndRemoves() {
        ColumnStore store = new InMemoryColumnStore();
        store.put("v1", Map.of("name", "alice", "age", 30));

        assertEquals("alice", store.getColumn("v1", "name"));
        assertEquals(Map.of("name", "alice", "age", 30), store.get("v1"));

        store.putColumn("v1", "city", "sh");
        assertEquals("sh", store.getColumn("v1", "city"));

        store.removeColumn("v1", "age");
        assertNull(store.getColumn("v1", "age"));

        store.remove("v1");
        assertNull(store.get("v1"));
    }

    @Test
    void vertexStoreStoresAndRemovesVertexAndProperties() {
        VertexStore store = new InMemoryVertexStore(new InMemoryColumnStore());
        store.putVertex("v1", Map.of(1, "alice", 2, 30));

        assertEquals(Map.of(1, "alice", 2, 30), store.getVertex("v1"));
        assertEquals("alice", store.getVertexProperty("v1", 1));

        store.updateVertexProperty("v1", 3, "sh");
        assertEquals("sh", store.getVertexProperty("v1", 3));

        store.removeVertexProperty("v1", 2);
        assertNull(store.getVertexProperty("v1", 2));

        store.removeVertex("v1");
        assertNull(store.getVertex("v1"));
    }

    @Test
    void edgeTableStoreReputsWithoutDuplicateIndexEntries() {
        EdgeTableStore store = new InMemoryEdgeTableStore();
        store.put(edge("e1", "a", "b", 100));
        store.put(edge("e1", "a", "b", 150)); // same endpoints, no duplicates

        assertEquals(1, store.edgesBySource("a").size());
        assertEquals(1, store.edgesByDestination("b").size());

        store.put(edge("e1", "a", "c", 200)); // moved destination b -> c
        assertEquals(List.of(), store.edgesByDestination("b").stream().map(StoredEdge::edgeId).toList());
        assertEquals(List.of("e1"), store.edgesByDestination("c").stream().map(StoredEdge::edgeId).toList());
        assertEquals(1, store.edgesBySource("a").size());
    }

    @Test
    void edgeTableStoresAndIndexesBySourceAndDestination() {
        EdgeTableStore store = new InMemoryEdgeTableStore();
        store.put(edge("e1", "a", "b", 100));
        store.put(edge("e2", "a", "c", 200));

        assertEquals("e1", store.get("e1").edgeId());
        assertEquals(2, store.edgesBySource("a").size());
        assertEquals(List.of("e1"), store.edgesByDestination("b").stream().map(StoredEdge::edgeId).toList());

        store.remove("e1");
        assertNull(store.get("e1"));
        assertEquals(1, store.edgesBySource("a").size());
    }

    @Test
    void adjacentStoreQueriesBothDirectionsSortedByLastUpdatedTime() {
        InMemoryColumnStore vertices = new InMemoryColumnStore();
        vertices.put("a", Map.of("name", "alice"));
        vertices.put("b", Map.of("name", "bob"));
        vertices.put("c", Map.of("name", "carol"));
        vertices.put("d", Map.of("name", "dave"));

        AdjacentEdgeStore store = new InMemoryAdjacentEdgeStore(vertices);
        store.addEdge(edge("e1", "a", "b", 100));
        store.addEdge(edge("e2", "a", "c", 300));
        store.addEdge(edge("e3", "d", "a", 200));

        // outgoing from a: e1(100), e2(300) -> sorted desc: e2, e1
        assertEquals(List.of("e2", "e1"), store.outgoingEdges("a", EDGE_SCHEMA_ID).stream().map(StoredEdge::edgeId).toList());
        // incoming to a: e3(200)
        assertEquals(List.of("e3"), store.incomingEdges("a", EDGE_SCHEMA_ID).stream().map(StoredEdge::edgeId).toList());

        // edges stored inline in vertex columns (forward + reverse)
        assertNotNull(vertices.getColumn("a", AdjacentEdgeStore.OUT_EDGES_COLUMN));
        assertNotNull(vertices.getColumn("a", AdjacentEdgeStore.IN_EDGES_COLUMN));

        store.removeEdge("a", "b", EDGE_SCHEMA_ID, "e1");
        assertEquals(List.of("e2"), store.outgoingEdges("a", EDGE_SCHEMA_ID).stream().map(StoredEdge::edgeId).toList());
    }

    @Test
    void adjacentStoreTrimsOldestOutgoingEdgesBeyondCapacity() {
        InMemoryColumnStore vertices = new InMemoryColumnStore();
        AdjacentEdgeStore store = new InMemoryAdjacentEdgeStore(vertices, 2, 2); // max 2 out, 2 in

        store.addEdge(edge("e1", "a", "b", 100));
        store.addEdge(edge("e2", "a", "c", 200));
        store.addEdge(edge("e3", "a", "d", 300)); // exceeds maxOutgoing(2): evict oldest e1

        assertEquals(List.of("e3", "e2"), store.outgoingEdges("a", EDGE_SCHEMA_ID).stream().map(StoredEdge::edgeId).toList());
        // evicted edge's reverse copy is removed from the destination too
        assertEquals(List.of(), store.incomingEdges("b", EDGE_SCHEMA_ID).stream().map(StoredEdge::edgeId).toList());
    }

    @Test
    void adjacentStoreTrimsOldestIncomingEdgesBeyondCapacity() {
        InMemoryColumnStore vertices = new InMemoryColumnStore();
        AdjacentEdgeStore store = new InMemoryAdjacentEdgeStore(vertices, 0, 2); // unlimited out, max 2 in

        store.addEdge(edge("e1", "x", "z", 100));
        store.addEdge(edge("e2", "y", "z", 200));
        store.addEdge(edge("e3", "w", "z", 300)); // exceeds maxIncoming(2): evict oldest e1

        assertEquals(List.of("e3", "e2"), store.incomingEdges("z", EDGE_SCHEMA_ID).stream().map(StoredEdge::edgeId).toList());
        // evicted edge's forward copy is removed from the source too
        assertEquals(List.of(), store.outgoingEdges("x", EDGE_SCHEMA_ID).stream().map(StoredEdge::edgeId).toList());
    }

    private static final int EDGE_SCHEMA_ID = 1;

    private static StoredEdge edge(String edgeId, Object source, Object destination, long lastUpdatedTime) {
        return new StoredEdge(edgeId, EDGE_SCHEMA_ID, source, destination,
                EdgeDirection.DIRECTED, Map.of("weight", 1.0), lastUpdatedTime);
    }
}
