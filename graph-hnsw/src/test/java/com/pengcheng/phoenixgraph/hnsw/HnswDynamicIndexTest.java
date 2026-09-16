package com.pengcheng.phoenixgraph.hnsw;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Write-driven behaviour of the index: the layer count follows the dataset and the
 * missing layer edge classes are created on demand, while the entry points live in
 * the metadata record and are picked up by other readers (through the cached store).
 */
class HnswDynamicIndexTest {

    private static final int DIMENSIONS = 12;

    @Test
    void layersGrowWithTheDatasetAndAreProvisionedOnDemand() {
        List<String> created = new ArrayList<>();
        HnswLayerProvisioner provisioner = (vertexName, property, level) ->
                created.add(HnswLevels.edgeName(property, level));
        InMemoryHnswStore store = new InMemoryHnswStore();
        HeapHnswVectorSource vectors = randomVectors(5, 3L);
        HnswMetadataStore registry = HnswMetadataStore.inMemory();

        HnswIndex index = HnswIndex.builder()
                .vertexName("Vector").vectorProperty("vector")
                .store(store).vectors(vectors).metadataStore(registry)
                .config(HnswConfig.defaults())
                .dynamicSize(true).provisioner(provisioner)
                .build();
        assertEquals(0, index.topLevel(), "an empty index starts at layer 0");
        assertEquals(0, index.stats().size());

        // ceil(log2(n)) layers: 1 vertex -> 0, 2 -> 1, 3 -> 2, 5 -> 3
        for (Object id : vectors.ids()) {
            index.insert(id);
        }
        assertEquals(5, index.stats().size());
        assertEquals(3, index.topLevel());
        assertEquals(List.of("vector_0", "vector_1", "vector_2", "vector_3"), created);
        // the metadata record keeps the layer count for the next reader
        index.flush();
        assertEquals(3, registry.read().topLevel());
        assertEquals(5, registry.read().size());

        // and the graph is searchable (a vertex's own vector is closest to itself)
        Object probe = vectors.ids().get(2);
        assertEquals(probe, index.search(vectors.vector(probe), 1).get(0).vertexId());
    }

    @Test
    void aFixedSizeIndexDoesNotGrow() {
        HeapHnswVectorSource vectors = randomVectors(10, 4L);
        // no provisioner: a loader builds against a schema that already declares the layers
        HnswIndex index = HnswIndex.builder()
                .vertexName("Vector").store(new InMemoryHnswStore()).vectors(vectors)
                .config(HnswConfig.defaults()).size(4).dynamicSize(false)
                .build();
        assertFalse(index.dynamicSize());
        assertEquals(2, index.topLevel());
        index.insertAll(vectors.ids());
        assertEquals(2, index.topLevel(), "a fixed index keeps the layer count of its size");
        assertEquals(10, index.stats().size());
    }

    @Test
    void entryPointsOfAnotherWriterArePickedUpOnSearch() {
        InMemoryHnswStore store = new InMemoryHnswStore();
        HeapHnswVectorSource vectors = randomVectors(300, 5L);
        HnswMetadataStore registry = HnswMetadataStore.inMemory();

        // a reader that opened the index before anything was inserted (always-fresh view)
        HnswIndex reader = HnswIndex.builder()
                .vertexName("Vector").vectorProperty("vector")
                .store(store).vectors(vectors)
                .metadataStore(new CachedHnswMetadataStore(registry, Duration.ZERO))
                .config(HnswConfig.defaults()).dynamicSize(true)
                .build();
        assertEquals(List.of(), reader.entryPoints());
        assertEquals(List.of(), reader.search(vectors.vector(vectors.ids().get(0)), 3));

        // a second process indexes the data and publishes its entry points
        HnswIndex writer = HnswIndex.builder()
                .vertexName("Vector").vectorProperty("vector")
                .store(store).vectors(vectors)
                .metadataStore(new CachedHnswMetadataStore(registry, Duration.ZERO))
                .config(HnswConfig.defaults()).dynamicSize(true)
                .build();
        writer.insertAll(vectors.ids());
        writer.flush();
        assertFalse(writer.entryPoints().isEmpty());

        // ... and the reader finds them without being reopened
        Object probe = vectors.ids().get(7);
        List<HnswSearchResult> found = reader.search(vectors.vector(probe), 5);
        assertEquals(probe, found.get(0).vertexId());
        assertEquals(writer.entryPoints(), reader.entryPoints());
        assertEquals(300, reader.stats().size());
    }

    @Test
    void theMetadataViewIsReusedUntilItsTtlExpires() {
        CountingMetadataStore delegate = new CountingMetadataStore();
        delegate.value = metadata(3, 42);
        CachedHnswMetadataStore cache = new CachedHnswMetadataStore(delegate, Duration.ofMillis(80));

        assertNotNull(cache.read());
        cache.read();
        cache.read();
        assertEquals(1, delegate.reads, "reads within the TTL are served from memory");
        assertFalse(cache.stale());

        try {
            Thread.sleep(120);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertTrue(cache.stale());
        cache.read();
        assertEquals(2, delegate.reads, "an expired view is refreshed once");

        HnswMetadata updated = metadata(4, 99);
        cache.write(updated);
        assertEquals(1, delegate.writes);
        assertEquals(updated, cache.read());
        assertEquals(2, delegate.reads, "a write updates the cached view in place");

        cache.invalidate();
        assertTrue(cache.stale());
    }

    // ------------------------------------------------------------------ helpers

    private static HnswMetadata metadata(int topLevel, long size) {
        return new HnswMetadata(1, "Vector", "vector", HnswMetric.L2, 15, 100, 30, DIMENSIONS, size,
                topLevel, List.of(new HnswEntryPoint("7", topLevel)), 1L);
    }

    private static HeapHnswVectorSource randomVectors(int count, long seed) {
        Random random = new Random(seed);
        HeapHnswVectorSource vectors = new HeapHnswVectorSource();
        for (int i = 0; i < count; i++) {
            float[] vector = new float[DIMENSIONS];
            for (int d = 0; d < DIMENSIONS; d++) {
                vector[d] = (float) random.nextGaussian();
            }
            vectors.put("v" + i, vector);
        }
        return vectors;
    }

    /** Metadata store counting how often it is hit. */
    private static final class CountingMetadataStore implements HnswMetadataStore {

        private HnswMetadata value;
        private int reads;
        private int writes;

        @Override
        public HnswMetadata read() {
            reads++;
            return value;
        }

        @Override
        public void write(HnswMetadata metadata) {
            writes++;
            value = metadata;
        }
    }
}
