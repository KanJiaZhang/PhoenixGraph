package com.pengcheng.phoenixgraph.storage;

import com.pengcheng.phoenixgraph.storage.memory.InMemoryAdjacentEdgeStore;
import com.pengcheng.phoenixgraph.storage.memory.InMemoryColumnStore;
import com.pengcheng.phoenixgraph.storage.memory.InMemoryEdgeTableStore;
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
    void adjacentStoreQueriesBothDirectionsSortedByLastModified() {
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
        assertEquals(List.of("e2", "e1"), store.outgoingEdges("a").stream().map(StoredEdge::edgeId).toList());
        // incoming to a: e3(200)
        assertEquals(List.of("e3"), store.incomingEdges("a").stream().map(StoredEdge::edgeId).toList());

        // edges stored inline in vertex columns (forward + reverse)
        assertNotNull(vertices.getColumn("a", AdjacentEdgeStore.OUT_EDGES_COLUMN));
        assertNotNull(vertices.getColumn("a", AdjacentEdgeStore.IN_EDGES_COLUMN));

        store.removeEdge("a", "b", "e1");
        assertEquals(List.of("e2"), store.outgoingEdges("a").stream().map(StoredEdge::edgeId).toList());
    }

    @Test
    void adjacentStoreTrimsOldestOutgoingEdgesBeyondCapacity() {
        InMemoryColumnStore vertices = new InMemoryColumnStore();
        AdjacentEdgeStore store = new InMemoryAdjacentEdgeStore(vertices, 2, 2); // max 2 out, 2 in

        store.addEdge(edge("e1", "a", "b", 100));
        store.addEdge(edge("e2", "a", "c", 200));
        store.addEdge(edge("e3", "a", "d", 300)); // exceeds maxOutgoing(2): evict oldest e1

        assertEquals(List.of("e3", "e2"), store.outgoingEdges("a").stream().map(StoredEdge::edgeId).toList());
        // evicted edge's reverse copy is removed from the destination too
        assertEquals(List.of(), store.incomingEdges("b").stream().map(StoredEdge::edgeId).toList());
    }

    @Test
    void adjacentStoreTrimsOldestIncomingEdgesBeyondCapacity() {
        InMemoryColumnStore vertices = new InMemoryColumnStore();
        AdjacentEdgeStore store = new InMemoryAdjacentEdgeStore(vertices, 0, 2); // unlimited out, max 2 in

        store.addEdge(edge("e1", "x", "z", 100));
        store.addEdge(edge("e2", "y", "z", 200));
        store.addEdge(edge("e3", "w", "z", 300)); // exceeds maxIncoming(2): evict oldest e1

        assertEquals(List.of("e3", "e2"), store.incomingEdges("z").stream().map(StoredEdge::edgeId).toList());
        // evicted edge's forward copy is removed from the source too
        assertEquals(List.of(), store.outgoingEdges("x").stream().map(StoredEdge::edgeId).toList());
    }

    private static StoredEdge edge(String edgeId, Object source, Object destination, long lastModified) {
        return new StoredEdge(edgeId, source, destination, Map.of("weight", 1.0), lastModified);
    }
}
