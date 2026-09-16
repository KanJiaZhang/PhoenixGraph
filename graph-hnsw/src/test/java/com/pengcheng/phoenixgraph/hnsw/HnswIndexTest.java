package com.pengcheng.phoenixgraph.hnsw;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Build/search behaviour of the layered HNSW index over the in-memory store. */
class HnswIndexTest {

    private static final int DIMENSIONS = 32;

    // ------------------------------------------------------------ layer scheme

    @Test
    void layerSchemeFollowsTheDatasetSize() {
        // 4096 = 2^12 -> the 13 layers 0..12 (vector_0 .. vector_12)
        assertEquals(12, HnswLevels.topLevel(4096));
        assertEquals(13, HnswLevels.layerCount(4096));
        assertEquals("vector_0", HnswLevels.edgeName(0));
        assertEquals("vector_12", HnswLevels.edgeName(12));
        assertEquals(12, HnswLevels.levelOfEdgeName("vector_12"));
        assertEquals(-1, HnswLevels.levelOfEdgeName("Payment"));
        // 10000 -> ceil(log2(10000)) = 14
        assertEquals(14, HnswLevels.topLevel(10_000));
        assertEquals(1, HnswLevels.topLevel(2));
        assertEquals(0, HnswLevels.topLevel(1));
    }

    @Test
    void levelsDecayExponentiallySoTheTopLayerHoldsAboutOneVertex() {
        int top = HnswLevels.topLevel(4096);
        int[] counts = new int[top + 1];
        for (int i = 0; i < 4096; i++) {
            counts[HnswLevels.levelFor("v" + i, top)]++;
        }
        // P(level >= l) = 2^-l: ~half the points stay on layer 0, ~size/2^l reach layer l
        assertTrue(Math.abs(counts[0] - 4096 / 2.0) < 4096 / 8.0, "layer 0 holds about half: " + counts[0]);
        assertTrue(Math.abs(counts[1] - 4096 / 4.0) < 4096 / 8.0, "layer 1 holds about a quarter: " + counts[1]);
        assertTrue(counts[top] <= 6, "the top layer holds a handful of vertices, got " + counts[top]);
        assertTrue(counts[top - 1] >= 1, "the layer below the top is populated");
        // deterministic: same id -> same layer
        assertEquals(HnswLevels.levelFor("v7", top), HnswLevels.levelFor("v7", top));
    }

    // ------------------------------------------------------------------ insert

    @Test
    void insertLinksNeighboursOnEveryLayerWithinTheDegreeCap() {
        Dataset dataset = Dataset.clustered(1000, 8, 7L);
        InMemoryHnswStore store = new InMemoryHnswStore();
        HnswIndex index = index(store, dataset, 1000);

        List<HnswInsertResult> results = index.insertAll(dataset.ids());
        assertEquals(1000, results.size());
        assertEquals(1000, index.stats().size());
        assertTrue(index.stats().entryPoints().size() <= HnswLevels.MAX_ENTRY_POINTS);
        assertEquals(index.stats().entryPoints().stream().mapToInt(HnswEntryPoint::level).max().orElse(-1),
                index.stats().maxEntryLevel());
        // every point is reachable on layer 0 and no vertex exceeds the degree cap
        assertEquals(1000, store.verticesOn(0));
        assertTrue(store.maxDegree() <= index.config().maxLinksLevel0(),
                "max degree " + store.maxDegree() + " <= " + index.config().maxLinksLevel0());
        assertTrue(store.links() > 0);
        // one layer per half of the points: layer 0 keeps everything, layer 1 about a half
        assertTrue(store.verticesOn(1) > 250 && store.verticesOn(1) < 750, "layer 1: " + store.verticesOn(1));
    }

    @Test
    void everyVertexReachesItsLayerThroughBidirectionalEdges() {
        Dataset dataset = Dataset.clustered(300, 8, 11L);
        InMemoryHnswStore store = new InMemoryHnswStore();
        HnswIndex index = index(store, dataset, 300);
        index.insertAll(dataset.ids());

        // layer 0 must be a connected graph (BFS from the entry point reaches everything)
        Set<Object> seen = new HashSet<>();
        List<Object> frontier = new ArrayList<>();
        Object entry = index.entryPoints().get(0).vertexId();
        seen.add(entry);
        frontier.add(entry);
        while (!frontier.isEmpty()) {
            List<Object> next = new ArrayList<>();
            for (Object id : frontier) {
                for (HnswNeighbor neighbour : store.neighbours(id, 0)) {
                    if (seen.add(neighbour.vertexId())) {
                        next.add(neighbour.vertexId());
                    }
                }
            }
            frontier = next;
        }
        assertEquals(dataset.ids().size(), seen.size(), "layer 0 is connected");
    }

    @Test
    void insertWithoutAVectorFails() {
        Dataset dataset = Dataset.clustered(10, 2, 3L);
        HnswIndex index = index(new InMemoryHnswStore(), dataset, 10);
        assertThrows(IllegalArgumentException.class, () -> index.insert("missing"));
    }

    @Test
    void aNewIndexNeedsASize() {
        HnswIndex.Builder builder = HnswIndex.builder()
                .vertexName("Vector")
                .store(new InMemoryHnswStore())
                .vectors(new HeapHnswVectorSource());
        assertThrows(IllegalStateException.class, builder::build);
    }

    // ------------------------------------------------------------------ search

    @Test
    void searchRecallsTheExactNeighbours() {
        Dataset dataset = Dataset.clustered(2000, 20, 17L);
        InMemoryHnswStore store = new InMemoryHnswStore();
        HnswIndex index = index(store, dataset, 2000);
        index.insertAll(dataset.ids());

        double recall = 0;
        int queries = 50;
        int k = 10;
        for (int i = 0; i < queries; i++) {
            Object id = dataset.ids().get(i * 7 % dataset.ids().size());
            List<HnswSearchResult> found = index.searchById(id, k, 64);
            Set<Object> expected = bruteForce(dataset, dataset.vector(id), k, id);
            long hits = found.stream().filter(r -> expected.contains(r.vertexId())).count();
            recall += (double) hits / k;
        }
        recall /= queries;
        assertTrue(recall >= 0.9, "recall@" + k + " = " + recall);
    }

    @Test
    void searchByIdExcludesTheQueryVertexAndReturnsKResults() {
        Dataset dataset = Dataset.clustered(500, 10, 23L);
        HnswIndex index = index(new InMemoryHnswStore(), dataset, 500);
        index.insertAll(dataset.ids());

        Object id = dataset.ids().get(3);
        List<HnswSearchResult> found = index.searchById(id, 20);
        assertEquals(20, found.size());
        assertTrue(found.stream().noneMatch(r -> r.vertexId().equals(id)));
        // sorted by increasing distance
        for (int i = 1; i < found.size(); i++) {
            assertTrue(found.get(i - 1).distance() <= found.get(i).distance());
        }
    }

    @Test
    void searchOnAnEmptyIndexReturnsNothing() {
        Dataset dataset = Dataset.clustered(10, 2, 5L);
        HnswIndex index = index(new InMemoryHnswStore(), dataset, 10);
        assertEquals(List.of(), index.search(dataset.vector(dataset.ids().get(0)), 5));
    }

    @Test
    void searchFindsNeighboursOfAVectorThatWasNeverInserted() {
        Dataset dataset = Dataset.clustered(800, 10, 31L);
        HnswIndex index = index(new InMemoryHnswStore(), dataset, 800);
        index.insertAll(dataset.ids());

        float[] probe = dataset.vector(dataset.ids().get(11));
        List<HnswSearchResult> found = index.search(probe, 5);
        assertEquals(dataset.ids().get(11), found.get(0).vertexId());
    }

    // -------------------------------------------------------------- registry

    @Test
    void entryPointsAndMetadataSurviveAReopen() {
        Dataset dataset = Dataset.clustered(600, 10, 37L);
        InMemoryHnswStore store = new InMemoryHnswStore();
        HnswMetadataStore registry = HnswMetadataStore.inMemory();
        HnswIndex built = HnswIndex.builder()
                .vertexName("Vector").vectorProperty("vector")
                .store(store).vectors(dataset).metadataStore(registry)
                .config(HnswConfig.defaults().withMetric(HnswMetric.L2))
                .size(600).build();
        built.insertAll(dataset.ids());
        built.flush();

        HnswMetadata metadata = registry.read();
        assertNotNull(metadata);
        assertEquals("Vector", metadata.vertexName());
        assertEquals("vector", metadata.vectorProperty());
        assertEquals(600, metadata.size());
        assertEquals(10, metadata.topLevel());
        assertTrue(!metadata.entryPoints().isEmpty());
        assertTrue(metadata.entryPoints().size() <= HnswLevels.MAX_ENTRY_POINTS);

        // a fresh index (another process) picks the entry points up and can search
        HnswIndex reopened = HnswIndex.builder()
                .vertexName("Vector").vectorProperty("vector")
                .store(store).vectors(dataset).metadataStore(registry)
                .config(HnswConfig.defaults().withEfSearch(48))
                .build();
        assertEquals(600, reopened.stats().size());
        assertEquals(built.entryPoints(), reopened.entryPoints());
        assertEquals(48, reopened.config().efSearch());

        Object id = dataset.ids().get(42);
        assertEquals(built.searchById(id, 8).size(), reopened.searchById(id, 8).size());
        assertEquals(index(built, id, 8), index(reopened, id, 8));
    }

    @Test
    void overwriteStartsAFreshIndex() {
        Dataset dataset = Dataset.clustered(100, 4, 41L);
        InMemoryHnswStore store = new InMemoryHnswStore();
        HnswMetadataStore registry = HnswMetadataStore.inMemory();
        HnswIndex first = HnswIndex.builder().vertexName("Vector").store(store).vectors(dataset)
                .metadataStore(registry).size(100).build();
        first.insertAll(dataset.ids());
        first.flush();
        assertEquals(100, registry.read().size());

        HnswIndex fresh = HnswIndex.builder().vertexName("Vector").store(new InMemoryHnswStore())
                .vectors(dataset).metadataStore(registry).size(100).overwrite(true).build();
        assertEquals(0, fresh.stats().size());
        assertEquals(List.of(), fresh.entryPoints());
    }

    @Test
    void pruneDropsTheWorstEdgesOfAnOverfullNeighbour() {
        // a star: the centre is inserted first, then many points around it
        InMemoryHnswStore store = new InMemoryHnswStore();
        HeapHnswVectorSource vectors = new HeapHnswVectorSource();
        Random random = new Random(7);
        int count = 200;
        for (int i = 0; i < count; i++) {
            vectors.put("p" + i, randomVector(random, DIMENSIONS));
        }
        HnswIndex index = HnswIndex.builder().vertexName("Vector").store(store).vectors(vectors)
                .config(HnswConfig.defaults().withM(4)).size(count).build();
        index.insertAll(vectors.ids());
        assertTrue(store.unlinks() > 0, "pruning happened at least once");
        assertTrue(store.maxDegree() <= 2 * 4, "degree cap of m=4 is respected: " + store.maxDegree());
    }

    // ------------------------------------------------------------------ helpers

    private static List<Object> index(HnswIndex index, Object id, int k) {
        List<Object> out = new ArrayList<>();
        for (HnswSearchResult result : index.searchById(id, k)) {
            out.add(result.vertexId());
        }
        return out;
    }

    private static HnswIndex index(HnswStore store, Dataset dataset, long size) {
        return HnswIndex.builder()
                .vertexName("Vector")
                .vectorProperty("vector")
                .store(store)
                .vectors(dataset)
                .config(HnswConfig.defaults())
                .size(size)
                .build();
    }

    private static Set<Object> bruteForce(Dataset dataset, float[] query, int k, Object exclude) {
        List<Object> ids = dataset.ids().stream().filter(id -> !id.equals(exclude)).toList();
        Map<Object, Double> distances = new HashMap<>();
        for (Object id : ids) {
            distances.put(id, HnswMetric.L2.distance(query, dataset.vector(id)));
        }
        return ids.stream()
                .sorted(Comparator.comparingDouble(distances::get))
                .limit(k)
                .collect(java.util.stream.Collectors.toSet());
    }

    private static float[] randomVector(Random random, int dimensions) {
        float[] vector = new float[dimensions];
        for (int i = 0; i < dimensions; i++) {
            vector[i] = (float) random.nextGaussian();
        }
        return vector;
    }

    /** Deterministic clustered vectors: a handful of centres with gaussian noise around them. */
    private static final class Dataset implements HnswVectorSource {

        private final Map<Object, float[]> vectors = new HashMap<>();
        private final List<Object> order = new ArrayList<>();

        private Dataset(int size, int clusters, long seed) {
            Random random = new Random(seed);
            float[][] centres = new float[clusters][];
            for (int c = 0; c < clusters; c++) {
                centres[c] = randomVector(random, DIMENSIONS);
            }
            for (int i = 0; i < size; i++) {
                float[] centre = centres[i % clusters];
                float[] vector = new float[DIMENSIONS];
                for (int d = 0; d < DIMENSIONS; d++) {
                    vector[d] = centre[d] + (float) random.nextGaussian() * 0.1f;
                }
                Object id = "v" + i;
                vectors.put(id, vector);
                order.add(id);
            }
        }

        static Dataset clustered(int size, int clusters, long seed) {
            return new Dataset(size, clusters, seed);
        }

        List<Object> ids() {
            return List.copyOf(order);
        }

        @Override
        public float[] vector(Object vertexId) {
            return vectors.get(vertexId);
        }

        @Override
        public List<float[]> vectors(List<Object> vertexIds) {
            return vertexIds.stream().map(vectors::get).toList();
        }

        @Override
        public int dimensions() {
            return DIMENSIONS;
        }
    }
}
