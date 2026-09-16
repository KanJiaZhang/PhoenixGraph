package com.pengcheng.phoenixgraph.hnsw;

import com.pengcheng.phoenixgraph.query.storage.EdgeQueryResult;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
import com.pengcheng.phoenixgraph.query.storage.GraphQuery;
import com.pengcheng.phoenixgraph.query.storage.VertexQueryResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The layered-store port: symmetric links, batching, write-through caching and the read-only query store. */
class HnswStoreTest {

    @Test
    void inMemoryStoreKeepsLinksSymmetricAndBatched() {
        InMemoryHnswStore store = new InMemoryHnswStore();
        store.linkAll("a", List.of("b", "c"), 0);
        store.linkBatch(List.of(new HnswLink(store.edgeId("b", "d", 1), "b", "d", 1),
                new HnswLink(store.edgeId("a", "b", 1), "a", "b", 1)));

        assertEquals(List.of("b", "c"), ids(store.neighbours("a", 0)));
        assertEquals(List.of("a"), ids(store.neighbours("b", 0)));
        // a-b and a-c on layer 0, b-d and a-b on layer 1
        assertEquals(4, store.edgeCount());
        assertEquals(List.of("d", "a"), ids(store.neighbours("b", 1)));
        // a link is stored once even if it is requested twice
        store.linkAll("a", List.of("b"), 0);
        assertEquals(4, store.edgeCount());

        assertEquals(4, store.links());
        store.unlinkBatch(List.of(new HnswUnlink(store.edgeId("a", "b", 0), "a", "b", 0)));
        assertEquals(List.of("c"), ids(store.neighbours("a", 0)));
        assertEquals(List.of(), ids(store.neighbours("b", 0)));
        assertEquals(3, store.edgeCount());
        assertEquals(1, store.unlinks());
    }

    @Test
    void cachingStoreSeesItsOwnWritesAndDelegatesOnce() {
        CountingStore delegate = new CountingStore();
        CachingHnswStore cache = new CachingHnswStore(delegate);
        delegate.put("a", 0, "x");

        assertEquals(List.of("x"), ids(cache.neighbours("a", 0)));
        assertEquals(List.of("x"), ids(cache.neighbours("a", 0)));
        assertEquals(1, delegate.reads);

        cache.linkAll("a", List.of("y"), 0);
        // the cached view is invalidated for both endpoints
        assertEquals(List.of("x", "y"), ids(cache.neighbours("a", 0)));
        assertEquals(List.of("a"), ids(cache.neighbours("y", 0)));
        assertEquals(1, delegate.writes);

        cache.unlinkBatch(List.of(new HnswUnlink(cache.edgeId("a", "y", 0), "a", "y", 0)));
        assertEquals(List.of("x"), ids(cache.neighbours("a", 0)));
        assertEquals(List.of(), ids(cache.neighbours("y", 0)));
    }

    @Test
    void queryStoreIsReadOnlyAndMapsLayerEdges() {
        RecordingGraphQuery query = new RecordingGraphQuery();
        query.edges.put("v1@vector_0", List.of(new EdgeQueryResult("vector_0", "v1:v2", EdgeSide.BOTH,
                "v2", Map.of(), 1L, 2L)));
        query.edges.put("v1@vector_3", List.of(new EdgeQueryResult("vector_3", "v1:v9", EdgeSide.BOTH,
                "v9", Map.of(), 1L, 2L)));
        GraphQueryHnswStore store = new GraphQueryHnswStore(query, "Vector");

        List<HnswNeighbor> neighbours = store.neighbours("v1", 0);
        assertEquals(1, neighbours.size());
        assertEquals("v1:v2", neighbours.get(0).edgeId());
        assertEquals("v2", neighbours.get(0).vertexId());
        assertEquals(List.of("vector_0"), query.readEdgeClasses);

        store.neighboursBatch(List.of("v1", "v2"), 3);
        assertEquals(List.of("vector_0", "vector_3", "vector_3"), query.readEdgeClasses);
        assertThrows(UnsupportedOperationException.class, () -> store.linkAll("v1", List.of("v2"), 0));
        assertThrows(UnsupportedOperationException.class, () -> store.unlink("e", "v1", "v2", 0));
    }

    @Test
    void layerEdgeNamesRoundTrip() {
        assertEquals("vector_7", HnswLevels.edgeName(7));
        assertEquals(7, HnswLevels.levelOfEdgeName("vector_7"));
        assertEquals(-1, HnswLevels.levelOfEdgeName("vector_x"));
        assertEquals(-1, HnswLevels.levelOfEdgeName("Payment"));
        assertEquals(-1, HnswLevels.levelOfEdgeName(null));
        assertTrue(HnswLevels.isLayerEdge("vector_0"));
    }

    @Test
    void vectorsAreReadThroughTheQueryLayer() {
        RecordingGraphQuery query = new RecordingGraphQuery();
        query.vectors.put("v1", List.of(0.5d, 1.5d));
        GraphQueryVectorSource source = new GraphQueryVectorSource(query, "Vector", "vector", 2);

        assertEquals(2, source.dimensions());
        assertEquals(List.of(0.5f, 1.5f), List.of(source.vector("v1")[0], source.vector("v1")[1]));
        assertEquals(2, source.vectors(List.of("v1", "missing")).size());
        assertNull(source.vectors(List.of("missing")).get(0));
    }

    private static List<Object> ids(List<HnswNeighbor> neighbours) {
        return neighbours.stream().map(HnswNeighbor::vertexId).toList();
    }

    /** A store that counts the calls the cache is supposed to absorb. */
    private static final class CountingStore implements HnswStore {

        private final InMemoryHnswStore delegate = new InMemoryHnswStore();
        private int reads;
        private int writes;

        void put(Object vertexId, int level, Object other) {
            delegate.linkAll(vertexId, List.of(other), level);
        }

        @Override
        public List<HnswNeighbor> neighbours(Object vertexId, int level) {
            reads++;
            return delegate.neighbours(vertexId, level);
        }

        @Override
        public void linkAll(Object from, List<Object> to, int level) {
            writes++;
            delegate.linkAll(from, to, level);
        }

        @Override
        public void unlink(String edgeId, Object from, Object to, int level) {
            writes++;
            delegate.unlink(edgeId, from, to, level);
        }
    }

    /** Minimal {@link GraphQuery} recording what the HNSW adapters ask for. */
    private static final class RecordingGraphQuery implements GraphQuery {

        private final Map<String, List<EdgeQueryResult>> edges = new LinkedHashMap<>();
        private final Map<String, List<Double>> vectors = new LinkedHashMap<>();
        private final List<String> readEdgeClasses = new ArrayList<>();

        @Override
        public VertexQueryResult readVertex(Object vertexId, String vertexName, List<String> propertyNames) {
            List<Double> vector = vectors.get(vertexId);
            return vector == null ? null
                    : new VertexQueryResult(vertexName, vertexId, Map.of("vector", vector), 1L, 2L);
        }

        @Override
        public List<EdgeQueryResult> readEdges(Object vertexId, String vertexName, String edgeName,
                                               EdgeSide direction, List<String> propertyNames) {
            readEdgeClasses.add(edgeName);
            return edges.getOrDefault(vertexId + "@" + edgeName, List.of());
        }
    }
}
