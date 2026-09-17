package com.pengcheng.phoenixgraph.integration;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.AerospikeException;
import com.aerospike.client.policy.InfoPolicy;
import com.pengcheng.phoenixgraph.hnsw.CachingHnswStore;
import com.pengcheng.phoenixgraph.hnsw.HeapHnswVectorSource;
import com.pengcheng.phoenixgraph.hnsw.CachedHnswMetadataStore;
import com.pengcheng.phoenixgraph.schema.MetaPropertySchema;
import com.pengcheng.phoenixgraph.schema.PropertyType;
import com.pengcheng.phoenixgraph.schema.VectorIndexConfig;
import com.pengcheng.phoenixgraph.schema.VectorIndexSchemas;
import com.pengcheng.phoenixgraph.hnsw.VectorCodec;
import com.pengcheng.phoenixgraph.storage.aerospike.VertexPropertyValue;
import java.util.LinkedHashMap;
import com.pengcheng.phoenixgraph.hnsw.HnswConfig;
import com.pengcheng.phoenixgraph.hnsw.HnswIndex;
import com.pengcheng.phoenixgraph.hnsw.HnswFilter;
import com.pengcheng.phoenixgraph.hnsw.HnswInsertResult;
import com.pengcheng.phoenixgraph.hnsw.HnswLevels;
import com.pengcheng.phoenixgraph.hnsw.HnswMetadata;
import com.pengcheng.phoenixgraph.hnsw.HnswMetric;
import com.pengcheng.phoenixgraph.hnsw.HnswSearchResult;
import com.pengcheng.phoenixgraph.hnsw.InMemoryHnswStore;
import com.pengcheng.phoenixgraph.query.aerospike.AerospikeGraphQuery;
import com.pengcheng.phoenixgraph.query.aerospike.AerospikeHnswIndexFactory;
import com.pengcheng.phoenixgraph.query.aerospike.AerospikeHnswMetadataStore;
import com.pengcheng.phoenixgraph.query.aerospike.AerospikeHnswStore;
import com.pengcheng.phoenixgraph.query.gremlin.GremlinGraphQuery;
import com.pengcheng.phoenixgraph.query.gremlin.GremlinVertex;
import com.pengcheng.phoenixgraph.query.traversal.NeighborVertexResolver;
import com.pengcheng.phoenixgraph.query.result.Subgraph;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.SchemaConstraints;
import com.pengcheng.phoenixgraph.schema.SchemaValidator;
import com.pengcheng.phoenixgraph.schema.VertexPropertySchema;
import com.pengcheng.phoenixgraph.schema.VertexSchema;
import com.pengcheng.phoenixgraph.schema.json.SchemaJsonCodec;
import com.pengcheng.phoenixgraph.storage.BusinessLog;
import com.pengcheng.phoenixgraph.storage.ErrorLog;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end HNSW test over Aerospike with the SIFT small dataset.
 *
 * <p>It loads the {@code hnsw-sift} schema, writes the 10,000 x 128 base vectors
 * as {@code Vector} vertices ({@code id} + {@code vector} property), builds the
 * layered graph by inserting the rows one by one (row 0 to row N-1) into the
 * {@code vector_<layer>} edge classes, and then measures the search quality
 * against the dataset's exact ground truth.
 *
 * <p>Configuration (all optional):
 * <ul>
 *   <li>{@code AEROSPIKE_HOST/PORT/NAMESPACE} - as for the other integration tests</li>
 *   <li>{@code HNSW_DATASET_DIR} - directory with the siftsmall files
 *       (default {@code .datasets/siftsmall}, fallback {@code /tmp/siftsmall_dl/siftsmall})</li>
 *   <li>{@code HNSW_LIMIT} - number of base rows to index ({@code 0} = all 10000)</li>
 *   <li>{@code HNSW_VERTEX_SET} - Aerospike set holding the vectors (default {@code hnsw_sift_v1})</li>
 *   <li>{@code HNSW_M}, {@code HNSW_EF_CONSTRUCTION}, {@code HNSW_EF_SEARCH}</li>
 *   <li>{@code HNSW_FORCE=true} - drop and rebuild from scratch</li>
 * </ul>
 * The test is skipped when Aerospike or the dataset is unavailable.
 */
class HnswGraphIntegrationTest {

    private static final String METADATA_SET = "hnsw_meta";
    private static final int K = 20;

    @Test
    void buildsAnHnswIndexAndAnswersNearestNeighbourQueries() throws IOException {
        IntegrationConfig config = IntegrationConfig.fromEnv();
        AerospikeClient client = config.connectOrNull();
        assumeTrue(client != null, "Aerospike is not reachable - skipping HNSW integration test");
        try {
            run(client, config);
        } finally {
            client.close();
        }
    }

    /**
     * The write-driven path: a schema that only flags a property
     * {@code "isVector": true} (no layer edge classes at all) gets its layers
     * created while vertices are written and indexed, so the index grows with the
     * data instead of a size known up front.
     */
    @Test
    void growsAVectorIndexFromWritesOnAFreshSchema() throws IOException {
        IntegrationConfig config = IntegrationConfig.fromEnv();
        AerospikeClient client = config.connectOrNull();
        assumeTrue(client != null, "Aerospike is not reachable - skipping HNSW integration test");
        Path directory = datasetDirectory();
        assumeTrue(Files.exists(directory.resolve("siftsmall_base.fvecs")),
                "SIFT small dataset not found in " + directory.toAbsolutePath()
                        + " - run tools/download-siftsmall-dataset.sh");
        try {
            runDynamic(client, config, directory);
        } finally {
            client.close();
        }
    }

    private void runDynamic(AerospikeClient client, IntegrationConfig config, Path directory) throws IOException {
        int limit = intEnv("HNSW_DYNAMIC_LIMIT", 600);
        HnswDatasetLoader.Dataset dataset = HnswDatasetLoader.load(directory, limit);
        int size = dataset.size();
        String set = env("HNSW_DYNAMIC_SET", "hnsw_dynamic_v1");
        GraphSchema schema = writableVectorSchema();
        assertTrue(new SchemaValidator().validate(schema, SchemaConstraints.defaults()).isEmpty(),
                "the vector-only schema must be valid");
        assertEquals(0, VectorIndexSchemas.declaredLevels(schema, "Doc", "embedding"),
                "the schema declares the vector property only");

        BusinessLog silentBusiness = entry -> {
        };
        ErrorLog silentErrors = entry -> {
        };
        AerospikeVertexStore vertexStore = new AerospikeVertexStore(client, config.namespace(), set,
                silentBusiness, silentErrors, 10);
        AerospikeEdgeStore edgeStore = new AerospikeEdgeStore(client, config.namespace(), set, schema,
                silentBusiness, silentErrors, 10);
        AerospikeHnswMetadataStore metadataRecords = new AerospikeHnswMetadataStore(client,
                config.namespace(), METADATA_SET,
                AerospikeHnswIndexFactory.metadataRecordKey(schema, "Doc", "embedding"));
        // always start clean: re-running the scenario must not index the same ids twice
        truncate(client, config.namespace(), set);
        metadataRecords.delete();

        VectorIndexSchemas.VectorProperty property =
                VectorIndexSchemas.vectorProperty(schema, "Doc", "embedding");
        assertEquals(new VectorIndexConfig("COSINE", 8, 64, 24).metric(), property.config().metric());
        HnswIndex index = AerospikeHnswIndexFactory.open(
                new AerospikeGraphQuery(vertexStore, edgeStore, schema), edgeStore, schema, client,
                config.namespace(), METADATA_SET, property, CachedHnswMetadataStore.DEFAULT_TTL);
        assertTrue(index.dynamicSize(), "a write-driven index follows the data");
        assertEquals(0, index.topLevel(), "an empty index starts at layer 0");

        System.out.println("[hnsw-dyn] dataset : " + size + " x " + dataset.vectors().get(0).length
                + " vectors, set=" + set + ", schema declares no layer edge classes");
        System.out.println("[hnsw-dyn] config  : schema -> " + index.config().describe());

        // the write path: store the vertex, then index its vector
        long start = System.currentTimeMillis();
        int[] perLevel = new int[64];
        for (int row = 0; row < size; row++) {
            String id = HnswDatasetLoader.Dataset.idOf(row);
            // exactly what the REST write path does: store the vertex, then index it
            long now = System.currentTimeMillis();
            Map<Integer, VertexPropertyValue> properties = new LinkedHashMap<>();
            properties.put(1, new VertexPropertyValue("doc-" + row, now));
            properties.put(2, new VertexPropertyValue(VectorCodec.toList(dataset.vectors().get(row)), now));
            vertexStore.upsertVertex(id, properties, now, now);
            HnswInsertResult result = index.insert(id);
            if (result.level() < perLevel.length) {
                perLevel[result.level()]++;
            }
        }
        index.flush();
        double seconds = (System.currentTimeMillis() - start) / 1000.0;
        int expectedTop = HnswLevels.topLevel(size);
        System.out.printf("[hnsw-dyn] indexed %d vertices in %.1fs (%.1f/s), levels %s%n",
                size, seconds, size / seconds, histogram(perLevel, size));

        // the layers were created on demand, one per doubling of the dataset
        assertEquals(expectedTop + 1, VectorIndexSchemas.declaredLevels(schema, "Doc", "embedding"));
        assertEquals(expectedTop, index.topLevel());
        for (int level = 0; level <= expectedTop; level++) {
            assertNotNull(schema.edgeSchema("embedding_" + level), "layer " + level + " was created");
        }
        // and the metadata record holds the entry points for the next reader
        HnswMetadata metadata = metadataRecords.read();
        assertNotNull(metadata, "the index published its entry points");
        assertEquals(size, metadata.size());
        assertEquals(expectedTop, metadata.topLevel());
        assertEquals(HnswMetric.COSINE, metadata.metric());
        assertEquals(64, metadata.efConstruction());
        assertEquals(24, metadata.efSearch());
        assertFalse(metadata.entryPoints().isEmpty());
        System.out.println("[hnsw-dyn] metadata : " + metadata.entryPoints()
                + " (layers=0.." + metadata.topLevel() + ")");

        // a reader that only knows the metadata (every query process) searches the same graph
        HnswIndex cold = AerospikeHnswIndexFactory.open(
                new AerospikeGraphQuery(vertexStore, edgeStore, schema), edgeStore, schema, client,
                config.namespace(), METADATA_SET, property, CachedHnswMetadataStore.DEFAULT_TTL);
        List<Set<Long>> exact = new ArrayList<>(dataset.queries().size());
        for (float[] query : dataset.queries()) {
            exact.add(bruteForce(dataset, query, K, null));
        }
        double recall = recallAt(cold, dataset, K, 24, exact);
        System.out.printf("[hnsw-dyn] cold search: recall@%d=%.3f (ef=24)%n", K, recall);
        assertTrue(recall >= 0.85, "recall@" + K + " = " + recall);

        // the query step finds the neighbours of a written vertex
        GremlinGraphQuery gremlin = new GremlinGraphQuery(
                new AerospikeGraphQuery(vertexStore, edgeStore, schema), NeighborVertexResolver.from(schema));
        gremlin.hnswIndex(cold);
        long row = size / 2;
        List<Object> found = gremlin.execute("g.V('" + row + "', 'Doc').nearest(" + K + ")");
        assertEquals(K, found.size());
        Set<Object> ids = new HashSet<>();
        for (Object traverser : found) {
            GremlinVertex vertex = (GremlinVertex) traverser;
            assertNotNull(vertex.properties().get("embedding"), "the embedding is materialised");
            ids.add(vertex.id());
        }
        Set<Long> expected = bruteForce(dataset, dataset.vectors().get((int) row), K, row);
        long hits = expected.stream().filter(wanted -> ids.contains(String.valueOf(wanted))).count();
        System.out.println("[hnsw-dyn] gremlin   : g.V('" + row + "', 'Doc').nearest(" + K + ") -> "
                + hits + "/" + K + " exact neighbours");
        assertTrue(hits >= K * 0.85, "gremlin recall = " + hits + "/" + K);
    }

    /** A schema with a single vector property and no layer edge classes. */
    private static GraphSchema writableVectorSchema() {
        GraphSchema schema = new GraphSchema(7, "hnsw-dynamic", "written vectors");
        schema.vertex(new VertexSchema(1, "Doc", "A document with an embedding",
                new VertexPropertySchema(0, "id", "Doc id", PropertyType.ScalarType.STRING),
                MetaPropertySchema.createdTime(), MetaPropertySchema.lastUpdatedTime())
                .property(new VertexPropertySchema(1, "title", "Doc title", PropertyType.ScalarType.STRING)
                        .display(true))
                .property(new VertexPropertySchema(2, "embedding", "The embedding",
                        new PropertyType.ListType(PropertyType.ScalarType.DOUBLE))
                        // the schema only says "this is a vector": the index tunables are here too
                        .vector(new VectorIndexConfig("COSINE", 8, 64, 24))));
        return schema;
    }

    private void run(AerospikeClient client, IntegrationConfig config) throws IOException {
        Path directory = datasetDirectory();
        assumeTrue(Files.exists(directory.resolve("siftsmall_base.fvecs")),
                "SIFT small dataset not found in " + directory.toAbsolutePath()
                        + " - run tools/download-siftsmall-dataset.sh");

        GraphSchema schema = loadSchema();
        List<VectorIndexSchemas.VectorProperty> vectorProperties = AerospikeHnswIndexFactory.vectorProperties(schema);
        assertEquals(1, vectorProperties.size());
        assertEquals("Vector", vectorProperties.get(0).vertexName());
        assertEquals("vector", vectorProperties.get(0).propertyName());
        assertTrue(vectorProperties.get(0).config().sameAs(VectorIndexConfig.DEFAULT), "schema defaults");
        assertTrue(new SchemaValidator().validate(schema, SchemaConstraints.defaults()).isEmpty(),
                "the generated HNSW schema must be valid");

        int limit = intEnv("HNSW_LIMIT", 0);
        HnswDatasetLoader.Dataset dataset = HnswDatasetLoader.load(directory, limit);
        int size = dataset.size();
        assertTrue(size >= 100, "need at least 100 vectors, got " + size);
        int topLevel = HnswLevels.topLevel(size);
        HnswConfig hnswConfig = new HnswConfig(HnswMetric.L2, intEnv("HNSW_M", 15),
                intEnv("HNSW_EF_CONSTRUCTION", 100), intEnv("HNSW_EF_SEARCH", 50),
                intEnv("HNSW_M", 15), 2 * intEnv("HNSW_M", 15));

        String set = env("HNSW_VERTEX_SET", "hnsw_sift_v1");
        // silent logs (a build rewrites hundreds of thousands of edge bins) and more
        // write attempts: many links land in the same per-vertex layer bin
        BusinessLog silentBusiness = entry -> {
        };
        ErrorLog silentErrors = entry -> {
        };
        int attempts = 10;
        AerospikeVertexStore vertexStore =
                new AerospikeVertexStore(client, config.namespace(), set, silentBusiness, silentErrors, attempts);
        AerospikeEdgeStore edgeStore =
                new AerospikeEdgeStore(client, config.namespace(), set, schema, silentBusiness, silentErrors, attempts);
        AerospikeHnswMetadataStore metadataStore =
                AerospikeHnswMetadataStore.forVertex(client, config.namespace(), METADATA_SET, "Vector");

        System.out.println("[hnsw] dataset     : " + size + " x " + dataset.vectors().get(0).length
                + " vectors from " + directory);
        System.out.println("[hnsw] layers      : 0.." + topLevel + " (" + (topLevel + 1)
                + " edge classes vector_0..vector_" + topLevel + ")");
        System.out.println("[hnsw] index       : " + hnswConfig.describe());
        System.out.println("[hnsw] storage     : namespace=" + config.namespace() + " set=" + set
                + " metadata=" + metadataStore.describeKey());

        boolean force = "true".equalsIgnoreCase(env("HNSW_FORCE", "false"));
        boolean verticesPresent = vertexStore.readVertex(HnswDatasetLoader.Dataset.idOf(0)) != null
                && vertexStore.readVertex(HnswDatasetLoader.Dataset.idOf(size - 1)) != null;
        HnswMetadata existing = metadataStore.read();
        if (existing != null && existing.size() > size) {
            System.out.println("[hnsw] index holds " + existing.size() + " vertices (" + (existing.size() - size)
                    + " written after the load)");
        }
        // An index is reusable when its vectors, its size and its metadata all match
        // the request; anything else (first run, interrupted build, different limit)
        // restarts from a clean set so no stale layer edges survive. Vertices written
        // *after* the load (the write path indexes them) are fine: they only add to
        // the graph, so the metadata may be slightly ahead of the dataset.
        boolean upToDate = !force && existing != null && existing.size() >= size && verticesPresent;

        HnswIndex index;
        if (upToDate) {
            index = indexOf(client, config, vertexStore, edgeStore, schema);
            System.out.println("[hnsw] reused the existing index: " + index.stats().describe());
        } else {
            truncate(client, config.namespace(), set);
            long start = System.currentTimeMillis();
            int written = HnswDatasetLoader.seedVertices(vertexStore, schema.vertexSchema("Vector"),
                    dataset.vectors(), 500, rows -> {
                        if (rows % 2500 == 0) {
                            System.out.println("[hnsw] seeded " + rows + "/" + size + " vectors");
                        }
                    });
            System.out.printf("[hnsw] seeded %d vertices in %.1fs%n", written,
                    (System.currentTimeMillis() - start) / 1000.0);
            index = build(edgeStore, schema, metadataStore, dataset, hnswConfig, topLevel);
        }

        verifyLayeredStructure(index, dataset, topLevel);
        long bruteStart = System.currentTimeMillis();
        List<Set<Long>> exact = exactNeighbours(dataset, K);
        System.out.printf("[hnsw] exact neighbours of %d queries computed by brute force in %.1fs%n",
                exact.size(), (System.currentTimeMillis() - bruteStart) / 1000.0);
        verifyGroundTruthAgrees(dataset, exact);
        verifySearchQuality(index, dataset, hnswConfig, "warm", exact);
        verifyColdReader(client, config, vertexStore, edgeStore, schema, metadataStore, dataset, hnswConfig, exact);
        verifyFilteredSearch(client, config, vertexStore, edgeStore, schema, dataset, hnswConfig);
        // after the attributes (a fresh write, which stores the record key)
        verifyScanReloadsTheDataset(vertexStore, size);
        verifyGremlinSearch(client, config, vertexStore, edgeStore, schema, dataset, size);
    }

    /** Inserts the dataset row by row into the layered graph. */
    private HnswIndex build(AerospikeEdgeStore edgeStore, GraphSchema schema,
                            AerospikeHnswMetadataStore metadataStore, HnswDatasetLoader.Dataset dataset,
                            HnswConfig config, int topLevel) {
        HeapHnswVectorSource vectors = new HeapHnswVectorSource();
        for (int row = 0; row < dataset.size(); row++) {
            vectors.put(HnswDatasetLoader.Dataset.idOf(row), dataset.vectors().get(row));
        }
        // the build reads neighbour lists far more often than it writes them, so the
        // storage store is wrapped in a write-through cache
        AerospikeHnswStore storage = new AerospikeHnswStore(edgeStore, schema, "Vector", "vector");
        HnswIndex index = HnswIndex.builder()
                .vertexName("Vector")
                .vectorProperty("vector")
                .store(new CachingHnswStore(storage))
                .vectors(vectors)
                .metadataStore(metadataStore)
                .config(config)
                .size(dataset.size())
                .overwrite(true)
                .metadataWriteInterval(200)
                .build();

        int[] perLevel = new int[topLevel + 1];
        long links = 0;
        long pruned = 0;
        long start = System.currentTimeMillis();
        List<HnswInsertResult> results = new ArrayList<>(dataset.size());
        for (int row = 0; row < dataset.size(); row++) {
            HnswInsertResult result = index.insert(HnswDatasetLoader.Dataset.idOf(row));
            results.add(result);
            if (result.level() < perLevel.length) {
                perLevel[result.level()]++;
            }
            links += result.links();
            pruned += result.pruned();
            if ((row + 1) % 500 == 0) {
                double elapsed = (System.currentTimeMillis() - start) / 1000.0;
                System.out.printf("[hnsw] inserted %d/%d (%.1fs, %.1f/s, links=%d, pruned=%d)%n",
                        row + 1, dataset.size(), elapsed, (row + 1) / elapsed, links, pruned);
            }
        }
        index.flush();
        double seconds = (System.currentTimeMillis() - start) / 1000.0;
        System.out.printf("[hnsw] built %d vertices in %.1fs (%.1f inserts/s): links=%d, pruned=%d, distances=%d%n",
                dataset.size(), seconds, dataset.size() / seconds, links, pruned, index.stats().distances());
        System.out.println("[hnsw] level histogram   : " + histogram(perLevel, dataset.size()));

        // the decaying draw must put about a half of the rows on layer 0 and a
        // handful on the top layer (the entry point a search starts from)
        assertTrue(perLevel[0] > dataset.size() / 4 && perLevel[0] < dataset.size() * 3 / 4,
                "layer 0 holds about half the vertices: " + perLevel[0]);
        assertTrue(perLevel[topLevel] <= 8, "the top layer holds a few vertices: " + perLevel[topLevel]);
        HnswMetadata metadata = metadataStore.read();
        assertEquals(dataset.size(), metadata.size());
        assertEquals(topLevel, metadata.topLevel());
        assertTrue(metadata.entryPoints().size() <= HnswLevels.MAX_ENTRY_POINTS);
        assertTrue(metadata.entryPoints().get(0).level() >= 1);
        System.out.println("[hnsw] entry points: " + metadata.entryPoints());
        return index;
    }

    /** Layer 0 contains every vertex, degrees respect the caps and the entry point is on top. */
    private void verifyLayeredStructure(HnswIndex index, HnswDatasetLoader.Dataset dataset, int topLevel) {
        int sampled = 0;
        int maxDegree = 0;
        int isolated = 0;
        for (int row = 0; row < dataset.size(); row += Math.max(1, dataset.size() / 200)) {
            List<com.pengcheng.phoenixgraph.hnsw.HnswNeighbor> neighbours =
                    index.neighbours(HnswDatasetLoader.Dataset.idOf(row), 0);
            sampled++;
            if (neighbours.isEmpty()) {
                isolated++;
            }
            maxDegree = Math.max(maxDegree, neighbours.size());
        }
        System.out.println("[hnsw] layer 0 sample: " + sampled + " vertices, max degree " + maxDegree
                + ", isolated " + isolated);
        assertTrue(isolated <= sampled / 20, "layer 0 is connected: " + isolated + " isolated of " + sampled);
        assertTrue(maxDegree <= index.config().maxLinksLevel0(),
                "degree " + maxDegree + " <= " + index.config().maxLinksLevel0());

        // every layer from the entry point down to layer 1 must be walkable
        Object entry = index.entryPoints().get(0).vertexId();
        for (int level = 1; level <= topLevel; level++) {
            Object probe = entry;
            for (int i = 0; i < 5; i++) {
                List<com.pengcheng.phoenixgraph.hnsw.HnswNeighbor> neighbours = index.neighbours(probe, level);
                if (neighbours.isEmpty()) {
                    // vertices below the layer have no edges there: that is expected
                    break;
                }
                assertTrue(neighbours.size() <= index.config().maxLinksFor(level),
                        "layer " + level + " degree " + neighbours.size());
                probe = neighbours.get(0).vertexId();
            }
        }
    }

    /** Recall of the graph search against the exact ground truth of the dataset. */
    private void verifySearchQuality(HnswIndex index, HnswDatasetLoader.Dataset dataset, HnswConfig config,
                                     String label, List<Set<Long>> exact) {
        double recall10 = recallAt(index, dataset, 10, config.efSearch(), exact);
        double recall20 = recallAt(index, dataset, K, config.efSearch(), exact);
        double recall20Big = recallAt(index, dataset, K, Math.max(config.efSearch(), 200), exact);
        Latency latency = latencyAt(index, dataset, K, config.efSearch(), exact.size());
        System.out.printf("[hnsw] %-4s search: recall@10=%.3f, recall@20=%.3f (ef=%d), recall@20=%.3f (ef=200)"
                        + ", %.1f ms/query (p50 %d, max %d)%n",
                label, recall10, recall20, config.efSearch(), recall20Big,
                latency.mean(), latency.p50(), latency.max());
        assertTrue(recall20 >= 0.85, label + " recall@20 = " + recall20);
        assertTrue(recall20Big >= recall20 - 0.02, "a bigger ef must not lose recall");
    }

    /**
     * The exact top-k of every query over the indexed rows. The dataset ships the
     * ground truth for its full 10,000 rows, so indexing a subset needs its own
     * reference; {@link #verifyGroundTruthAgrees} checks the two against each other
     * when the whole dataset is loaded.
     */
    private List<Set<Long>> exactNeighbours(HnswDatasetLoader.Dataset dataset, int k) {
        List<Set<Long>> exact = new ArrayList<>(dataset.queries().size());
        for (float[] query : dataset.queries()) {
            exact.add(bruteForce(dataset, query, k, null));
        }
        return exact;
    }

    /** Cross-checks the loader: on the full dataset the brute force must reproduce the shipped ground truth. */
    private void verifyGroundTruthAgrees(HnswDatasetLoader.Dataset dataset, List<Set<Long>> exact) {
        int queries = exact.size();
        boolean full = dataset.size() == 10_000 && dataset.groundTruth().size() >= queries;
        if (!full) {
            System.out.println("[hnsw] ground-truth cross-check skipped (subset of the dataset)");
            return;
        }
        double agreement = 0;
        for (int i = 0; i < queries; i++) {
            Set<Long> expected = expectedIds(dataset.groundTruth().get(i), K);
            agreement += (double) exact.get(i).stream().filter(expected::contains).count() / K;
        }
        agreement /= queries;
        System.out.printf("[hnsw] brute force vs shipped ground truth: %.3f agreement%n", agreement);
        assertTrue(agreement >= 0.99, "the fvecs/ivecs loader agrees with the dataset ground truth");
    }

    /** A fresh reader that only knows the metadata record must find the same neighbours. */
    private void verifyColdReader(AerospikeClient client, IntegrationConfig config,
                                  AerospikeVertexStore vertexStore, AerospikeEdgeStore edgeStore,
                                  GraphSchema schema, AerospikeHnswMetadataStore metadataStore,
                                  HnswDatasetLoader.Dataset dataset, HnswConfig config2,
                                  List<Set<Long>> exact) {
        AerospikeGraphQuery graphQuery = new AerospikeGraphQuery(vertexStore, edgeStore, schema);
        HnswIndex cold = indexOf(client, config, vertexStore, edgeStore, schema);
        assertNotNull(cold, "the index metadata must be readable from Aerospike");
        assertEquals(dataset.size(), cold.stats().size());
        assertEquals(HnswMetric.L2, cold.config().metric());
        System.out.println("[hnsw] cold reader : " + cold.stats().describe());
        verifySearchQuality(cold, dataset, config2, "cold", exact);

        // metadata round trip: entry points and vector property survive
        HnswMetadata metadata = metadataStore.read();
        assertEquals("Vector", metadata.vertexName());
        assertEquals("vector", metadata.vectorProperty());
        assertEquals(cold.entryPoints(), metadata.entryPoints());
        assertEquals(metadata.entryPoints(), cold.entryPoints());
    }

    /** {@code g.V(id, 'Vector').nearest(20)} returns the 20 nearest vectors of that id. */
    private void verifyGremlinSearch(AerospikeClient client, IntegrationConfig config,
                                     AerospikeVertexStore vertexStore, AerospikeEdgeStore edgeStore,
                                     GraphSchema schema, HnswDatasetLoader.Dataset dataset, int size) {
        AerospikeGraphQuery graphQuery = new AerospikeGraphQuery(vertexStore, edgeStore, schema);
        GremlinGraphQuery gremlin = new GremlinGraphQuery(graphQuery, NeighborVertexResolver.from(schema));
        gremlin.hnswIndex(indexOf(client, config, vertexStore, edgeStore, schema));
        assertTrue(!gremlin.hnswIndexes().isEmpty(), "vector search is wired into the engine");

        long row = size / 2;
        String id = String.valueOf(row);
        long start = System.currentTimeMillis();
        List<Object> result = gremlin.execute("g.V('" + id + "', 'Vector').nearest(" + K + ")");
        long elapsed = System.currentTimeMillis() - start;
        assertEquals(K, result.size());
        Set<Object> ids = new HashSet<>();
        for (Object traverser : result) {
            assertTrue(traverser instanceof GremlinVertex, "nearest returns vertices");
            GremlinVertex vertex = (GremlinVertex) traverser;
            assertEquals("Vector", vertex.label());
            assertNotNull(vertex.properties().get("vector"), "the vector property is materialised");
            assertNotNull(vertex.properties().get("name"));
            assertTrue(ids.add(vertex.id()), "no duplicate neighbours");
        }
        assertTrue(!ids.contains(String.valueOf(row)), "the query vertex itself is not returned");

        // the returned order must match the exact nearest neighbours
        Set<Long> expected = bruteForce(dataset, dataset.vectors().get((int) row), K, (long) row);
        // graph ids are strings, the brute force works with row numbers
        long hits = expected.stream().filter(wanted -> ids.contains(String.valueOf(wanted))).count();
        System.out.println("[hnsw] gremlin     : g.V('" + id + "', 'Vector').nearest(" + K + ") -> "
                + result.size() + " vertices in " + elapsed + " ms, " + hits + "/" + K
                + " are exact neighbours: " + ids);
        assertTrue(hits >= K * 0.85, "gremlin recall = " + hits + "/" + K);

        // the subgraph of the query holds the source plus its neighbours, linked by
        // the bottom-layer edges the search walked along
        Subgraph subgraph = gremlin.executeSubgraph("g.V('" + id + "', 'Vector').nearest(" + K + ")");
        assertEquals(K + 1, subgraph.vertices().size());
        // the neighbours are drawn with the bottom-layer links that reach them
        // directly (a neighbour can also be found transitively, so not all of them)
        assertTrue(subgraph.edges().size() >= 1 && subgraph.edges().size() <= K,
                "a handful of layer-0 links, got " + subgraph.edges().size());
        assertTrue(subgraph.edges().stream().allMatch(edge -> edge.source().equals(id)
                || edge.target().equals(id)), "the links connect the query vertex to its neighbours");
        assertTrue(subgraph.edges().stream().allMatch(edge -> ids.contains(edge.source())
                || ids.contains(edge.target())), "every link ends in a returned neighbour");

        // projections and limits still work after the step
        List<Object> names = gremlin.execute("g.V('" + id + "', 'Vector').nearest(" + K + ").values('name')");
        assertEquals(K, names.size());
        assertEquals((long) K, ((Number) gremlin.execute("g.V('" + id + "', 'Vector').nearest(" + K
                + ").count()").get(0)).longValue());
        assertTrue(names.contains(HnswDatasetLoader.nameOf(expected.iterator().next().intValue())),
                "the names of the neighbours are readable");

        // an unknown vertex type has no index: the step must say so
        assertNull(gremlin.hnswIndexes().get("Account"));
    }

    // ---------------------------------------------------------------- helpers

    /** Opens the 'Vector' index of the demo schema the way the service does (schema driven, cached metadata). */
    private static HnswIndex indexOf(AerospikeClient client, IntegrationConfig config,
                                     AerospikeVertexStore vertexStore, AerospikeEdgeStore edgeStore,
                                     GraphSchema schema) {
        return AerospikeHnswIndexFactory.open(
                new AerospikeGraphQuery(vertexStore, edgeStore, schema), edgeStore, schema, client,
                config.namespace(), METADATA_SET,
                VectorIndexSchemas.vectorProperty(schema, "Vector", "vector"), CachedHnswMetadataStore.DEFAULT_TTL);
    }

    /**
     * The rebuild path can reload the dataset with one scan: the ids come from the
     * record keys, which a write stores since the reload feature exists. Records
     * written before that are reachable by walking layer 0 instead (what the rebuild
     * falls back to), so this check runs after the attributes were written.
     */
    private void verifyScanReloadsTheDataset(AerospikeVertexStore vertexStore, int size) {
        int vectorIndex = HnswDatasetLoader.VECTOR_PROPERTY;
        long start = System.currentTimeMillis();
        List<com.pengcheng.phoenixgraph.storage.aerospike.StoredVertex> scanned =
                vertexStore.scanVerticesWithProperty(vectorIndex, List.of(vectorIndex));
        System.out.printf("[hnsw] scan reloaded %d vertices (expected %d) in %.1fs%n",
                scanned.size(), size, (System.currentTimeMillis() - start) / 1000.0);
        assertTrue(scanned.size() >= size, "the scan returns the loaded dataset");
        assertTrue(scanned.stream().allMatch(vertex -> vertex.properties().get(vectorIndex) != null),
                "every scanned vertex carries its vector");
    }

    /**
     * Filtered vector search: a predicate on the demo attributes is pushed into the
     * walk, so the answer is "the closest k vectors in the bucket", not "the k closest
     * vectors, of which a few are in the bucket".
     */
    private void verifyFilteredSearch(AerospikeClient client, IntegrationConfig config,
                                      AerospikeVertexStore vertexStore, AerospikeEdgeStore edgeStore,
                                      GraphSchema schema, HnswDatasetLoader.Dataset dataset,
                                      HnswConfig hnswConfig) {
        int size = dataset.size();
        if (!HnswDatasetLoader.attributesLoaded(vertexStore, size)) {
            long seedStart = System.currentTimeMillis();
            int written = HnswDatasetLoader.seedAttributes(vertexStore, schema.vertexSchema("Vector"), size, 200,
                    rows -> {
                        if (rows % 2000 == 0) {
                            System.out.println("[hnsw] attributes on " + rows + "/" + size + " rows");
                        }
                    });
            System.out.printf("[hnsw] loaded the demo attributes on %d rows in %.1fs%n", written,
                    (System.currentTimeMillis() - seedStart) / 1000.0);
        } else {
            System.out.println("[hnsw] demo attributes already present");
        }

        HnswIndex index = AerospikeHnswIndexFactory.open(
                new AerospikeGraphQuery(vertexStore, edgeStore, schema), edgeStore, schema, client,
                config.namespace(), METADATA_SET,
                VectorIndexSchemas.vectorProperty(schema, "Vector", "vector"),
                CachedHnswMetadataStore.DEFAULT_TTL);
        int ef = hnswConfig.efSearch();

        double pushedRecall = 0;
        double postRecall = 0;
        double pushedMillis = 0;
        double postMillis = 0;
        int queries = 0;
        for (int bucket = 0; bucket < HnswDatasetLoader.ATTRIBUTE_VALUES; bucket++) {
            int rowsChecked = 0;
            for (int row = bucket; row < size && rowsChecked < 8; row += HnswDatasetLoader.ATTRIBUTE_VALUES) {
                rowsChecked++;
                queries++;
                float[] query = dataset.vectors().get(row);
                HnswFilter filter = bucketFilter(bucket, 1.0 / HnswDatasetLoader.ATTRIBUTE_VALUES);
                List<Long> expected = exactFiltered(dataset, query, K, bucket);

                long start = System.nanoTime();
                List<HnswSearchResult> filtered = index.search(query, K, ef, filter);
                pushedMillis += (System.nanoTime() - start) / 1e6;
                pushedRecall += (double) filtered.stream()
                        .filter(hit -> expected.contains(asLong(hit.vertexId()))).count()
                        / Math.min(K, expected.size());

                start = System.nanoTime();
                List<HnswSearchResult> plain = index.search(query, K, ef);
                postMillis += (System.nanoTime() - start) / 1e6;
                postRecall += (double) plain.stream()
                        .filter(hit -> expected.contains(asLong(hit.vertexId()))).count()
                        / Math.min(K, expected.size());
            }
        }
        pushedRecall /= queries;
        postRecall /= queries;
        pushedMillis /= queries;
        postMillis /= queries;
        System.out.printf("[hnsw] filtered search (bucket, %.0f%% selectivity, ef=%d, %d queries):%n"
                        + "         pushed down : recall@%d=%.3f, %.1f ms/query%n"
                        + "         post-filter : recall@%d=%.3f, %.1f ms/query%n",
                100.0 / HnswDatasetLoader.ATTRIBUTE_VALUES, ef, queries,
                K, pushedRecall, pushedMillis, K, postRecall, postMillis);
        assertTrue(pushedRecall >= 0.9, "pushed-down filtered recall@" + K + " = " + pushedRecall);
        assertTrue(pushedRecall > postRecall + 0.4,
                "pushing the filter down must beat post-filtering: " + pushedRecall + " vs " + postRecall);

        // the same through the query language: has(...) directly after nearest(...)
        GremlinGraphQuery gremlin = new GremlinGraphQuery(
                new AerospikeGraphQuery(vertexStore, edgeStore, schema), NeighborVertexResolver.from(schema));
        gremlin.hnswIndex(index);
        int bucket = 3;
        long row = bucket + HnswDatasetLoader.ATTRIBUTE_VALUES * 500L;
        List<Object> found = gremlin.execute("g.V('" + row + "', 'Vector').nearest(" + K
                + ").has('bucket', " + bucket + ")");
        assertEquals(K, found.size(), "the walk keeps going until it has k matching neighbours");
        assertTrue(found.stream().allMatch(traverser -> ((GremlinVertex) traverser).properties()
                .get("bucket") instanceof Number number && number.intValue() == bucket),
                "every returned vertex is in the bucket");
        List<Object> byCategory = gremlin.execute("g.V('" + row + "', 'Vector').nearest(" + K
                + ").has('category', '" + HnswDatasetLoader.categoryOf((int) row) + "')");
        assertEquals(K, byCategory.size(), "string attributes work the same way");
        System.out.printf("[hnsw] gremlin has() : g.V('%d','Vector').nearest(%d).has('bucket',%d) -> %d vertices%n",
                row, K, bucket, found.size());
    }

    /** The demo bucket predicate, reading the {@code bucket} property the search loads for it. */
    private static HnswFilter bucketFilter(int bucket, double selectivity) {
        return new HnswFilter() {
            @Override
            public List<String> propertyNames() {
                return List.of("bucket");
            }

            @Override
            public double selectivity() {
                return selectivity;
            }

            @Override
            public boolean test(Object vertexId, Map<String, Object> properties) {
                Object value = properties.get("bucket");
                return value instanceof Number number && number.intValue() == bucket;
            }

            @Override
            public String describe() {
                return "bucket=" + bucket;
            }
        };
    }

    /** Exact nearest neighbours within one bucket (brute force over the dataset). */
    private static List<Long> exactFiltered(HnswDatasetLoader.Dataset dataset, float[] query, int k, int bucket) {
        List<long[]> scored = new ArrayList<>(dataset.size() / HnswDatasetLoader.ATTRIBUTE_VALUES);
        for (int row = bucket; row < dataset.size(); row += HnswDatasetLoader.ATTRIBUTE_VALUES) {
            scored.add(new long[] {HnswMetric.L2.distance(query, dataset.vectors().get(row)) == 0
                    ? 0 : (long) HnswMetric.L2.distance(query, dataset.vectors().get(row)), row});
        }
        scored.sort((a, b) -> Long.compare(a[0], b[0]));
        List<Long> out = new ArrayList<>(k);
        for (int i = 0; i < Math.min(k, scored.size()); i++) {
            out.add(scored.get(i)[1]);
        }
        return out;
    }

    private static GraphSchema loadSchema() throws IOException {
        try (InputStream in = HnswGraphIntegrationTest.class.getResourceAsStream("/hnsw-sift.json")) {
            assertNotNull(in, "hnsw-sift.json must be on the test classpath");
            return new SchemaJsonCodec().fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static Path datasetDirectory() {
        String configured = env("HNSW_DATASET_DIR", "");
        if (!configured.isBlank()) {
            return Paths.get(configured);
        }
        // "" is not set: look in the usual download spots (the script defaults to
        // .datasets/siftsmall; a plain ./mvnw test run also finds a /tmp checkout)
        for (String candidate : List.of(".datasets/siftsmall", "../.datasets/siftsmall",
                System.getProperty("java.io.tmpdir") + "/siftsmall_dl/siftsmall",
                "/tmp/siftsmall_dl/siftsmall", "C:/cygwin64/tmp/siftsmall_dl/siftsmall")) {
            if (Files.exists(Paths.get(candidate, "siftsmall_base.fvecs"))) {
                return Paths.get(candidate);
            }
        }
        return Paths.get(".datasets/siftsmall");
    }

    private static double recallAt(HnswIndex index, HnswDatasetLoader.Dataset dataset, int k, int ef,
                                   List<Set<Long>> exact) {
        double recall = 0;
        for (int i = 0; i < exact.size(); i++) {
            Set<Long> expected = exact.get(i);
            List<HnswSearchResult> found = index.search(dataset.queries().get(i), k, ef);
            recall += (double) found.stream()
                    .filter(hit -> expected.contains(asLong(hit.vertexId()))).count() / k;
        }
        return recall / exact.size();
    }

    private static Latency latencyAt(HnswIndex index, HnswDatasetLoader.Dataset dataset, int k, int ef,
                                     int queries) {
        long[] times = new long[queries];
        for (int i = 0; i < queries; i++) {
            long start = System.nanoTime();
            index.search(dataset.queries().get(i), k, ef);
            times[i] = (System.nanoTime() - start) / 1_000_000;
        }
        long[] sorted = times.clone();
        java.util.Arrays.sort(sorted);
        double mean = java.util.Arrays.stream(times).average().orElse(0);
        return new Latency(mean, sorted[sorted.length / 2], sorted[sorted.length - 1]);
    }

    private record Latency(double mean, long p50, long max) {
    }

    private static Set<Long> bruteForce(HnswDatasetLoader.Dataset dataset, float[] query, int k, Long exclude) {
        Map<Long, Double> distances = new HashMap<>();
        for (int row = 0; row < dataset.size(); row++) {
            long id = row;
            if (exclude != null && exclude == id) {
                continue;
            }
            distances.put(id, HnswMetric.L2.distance(query, dataset.vectors().get(row)));
        }
        Set<Long> out = new HashSet<>();
        distances.entrySet().stream()
                .sorted(Comparator.comparingDouble(Map.Entry::getValue))
                .limit(k)
                .forEach(entry -> out.add(entry.getKey()));
        return out;
    }

    private static Set<Long> expectedIds(int[] groundTruth, int k) {
        Set<Long> ids = new HashSet<>(k);
        for (int i = 0; i < Math.min(k, groundTruth.length); i++) {
            ids.add((long) groundTruth[i]);
        }
        return ids;
    }

    private static Long asLong(Object id) {
        return id instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(id));
    }

    private static String histogram(int[] perLevel, int size) {
        StringBuilder sb = new StringBuilder();
        for (int level = 0; level < perLevel.length; level++) {
            if (perLevel[level] == 0 && level > 0 && perLevel[level - 1] <= 2) {
                break;
            }
            sb.append(level).append(':').append(perLevel[level]).append(' ');
        }
        return sb.toString().trim();
    }

    private static void truncate(AerospikeClient client, String namespace, String set) {
        try {
            client.truncate(new InfoPolicy(), namespace, set, null);
            Thread.sleep(300);
        } catch (AerospikeException e) {
            // truncate may be disabled; upserts then layer on top of the old data
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static int intEnv(String name, int defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value.trim());
    }
}
