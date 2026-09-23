package com.pengcheng.phoenixgraph.integration;

import com.pengcheng.phoenixgraph.metrics.Metrics;
import com.pengcheng.phoenixgraph.query.gremlin.GremlinGraphQuery;
import com.pengcheng.phoenixgraph.query.traversal.NeighborVertexResolver;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;
import com.pengcheng.phoenixgraph.metrics.GraphMetrics;
import com.pengcheng.phoenixgraph.query.storage.GraphQuery;
import com.pengcheng.phoenixgraph.query.storage.VertexQueryResult;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbStoreConfig;
import com.pengcheng.phoenixgraph.storage.rocksdb.edge.RocksDbEdgeGraphQuery;
import com.pengcheng.phoenixgraph.storage.rocksdb.edge.RocksDbEdgeStore;
import com.pengcheng.phoenixgraph.storage.rocksdb.edge.RocksDbHnswIndexFactory;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The same two-hop projection benchmark as {@link AerospikeHundredSeedsValuesBenchmarkTest}, on the graph
 * served by <b>RocksDB-Edge</b> ({@code payment-graph-rocksdb-edge}).
 *
 * <p>Two things differ from the Aerospike run, and both are properties of the data rather than of the
 * measurement:
 * <ul>
 *   <li>the demo data holds <b>51</b> accounts (the hub and fifty), not a hundred: every valid id is used
 *       and the rounds are raised to sixteen, so the workload is 816 executions - the same 500..1000
 *       window with a different split;</li>
 *   <li>its account ids are <b>numbers</b> ({@code 135}, {@code 1000..1049}) while the query writes
 *       {@code g.V($id,'Account')} with the id as text: the store resolves both to the same record, which is
 *       also what the id-form tolerance elsewhere in the engine is for.</li>
 * </ul>
 *
 * <p>A running service locks the demo directory, so the benchmark works on a copy and leaves the served
 * graph untouched.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RocksDbEdgeSeededValuesBenchmarkTest extends TwoHopValuesBenchmark {

    private Path directory;
    private RocksDbEdgeStore store;
    private GraphSchema schema;

    @Override
    protected int seedTarget() {
        return 100; // the graph has 51: the harness keeps every valid one
    }

    @Override
    protected int rounds() {
        return 16; // 51 x 16 = 816, inside the 500..1000 window
    }

    @Override
    protected String label() {
        return "payment-graph-rocksdb-edge (RocksDB-Edge)";
    }

    @Override
    protected GraphSchema schema() {
        prepare();
        return schema;
    }

    @Override
    protected GraphQuery openStore(GraphMetrics metrics) {
        prepare();
        return new RocksDbEdgeGraphQuery(store.metrics(metrics), schema);
    }

    @Override
    protected void closeStore() {
        if (store != null) {
            store.close();
        }
        if (directory != null) {
            deleteRecursively(directory);
        }
    }

    /** The account ids of the vertex type, sorted so two runs measure the same workload. */
    @Override
    protected List<String> candidateIds() {
        prepare();
        TreeSet<String> scanned = new TreeSet<>();
        for (VertexQueryResult vertex : new RocksDbEdgeGraphQuery(store, schema)
                .scanVertices("Account", List.of())) {
            scanned.add(String.valueOf(vertex.vertexId()));
        }
        return List.copyOf(scanned);
    }

    /** Copies the demo directory (a service may hold the original) and opens the store over the copy. */
    private void prepare() {
        if (store != null) {
            return;
        }
        Path source = Path.of("..", ".rocksdb", "payment-graph-rocksdb-edge");
        assumeTrue(Files.isDirectory(source), "the RocksDB demo data is not loaded: " + source);
        try {
            directory = Files.createTempDirectory("phoenixgraph-bench-rocksdb-");
            copyDirectory(source, directory);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot copy " + source.toAbsolutePath(), e);
        }
        schema = schemaFrom("payment-graph-rocksdb-edge.json");
        // the copy carries a column family per HNSW layer of the vector index, and RocksDB opens every
        // family a directory holds: the layer classes have to be in the schema before the open
        RocksDbHnswIndexFactory.declareLayers(schema);
        store = RocksDbEdgeStore.open(schema, RocksDbStoreConfig.of(directory.toString()));
    }

    /**
     * The store's own edge-list cache, measured: it sits <b>below</b> the engine's per-query cache, so when it
     * serves a traversal the counters show no edge reads at all and the report would credit the engine for
     * what the store did. The same workload runs with that cache switched off, on a second copy of the data,
     * and the two are printed next to each other.
     */
    @Override
    protected List<String> measureStorageSpecific() {
        prepare();
        Path plain = null;
        try {
            plain = Files.createTempDirectory("phoenixgraph-bench-rocksdb-nocache-");
            copyDirectory(Path.of("..", ".rocksdb", "payment-graph-rocksdb-edge"), plain);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot copy the demo directory", e);
        }
        GraphMetrics uncachedMetrics = new GraphMetrics(label() + "-uncached");
        List<String> lines = new ArrayList<>();
        try (RocksDbEdgeStore uncached = RocksDbEdgeStore.open(schema,
                RocksDbStoreConfig.of(plain.toString()).withCacheEdgeLists(false))) {
            GremlinGraphQuery engine = new GremlinGraphQuery(
                    new RocksDbEdgeGraphQuery(uncached.metrics(uncachedMetrics), schema),
                    NeighborVertexResolver.from(schema));
            List<String> ids = accountIds();
            for (String id : ids) {
                engine.execute(query(id)); // one warm-up round, which is what fills the store's cache
            }
            Map<String, Long> before = new TreeMap<>(uncachedMetrics.snapshot().counters());
            long started = System.nanoTime();
            for (String id : ids) {
                engine.execute(query(id));
            }
            long nanos = System.nanoTime() - started;
            Map<String, Long> after = new TreeMap<>(uncachedMetrics.snapshot().counters());

            long calls = delta(before, after, Metrics.KV_READ_CALL);
            long items = delta(before, after, Metrics.KV_READ_ITEMS);
            long edgeReads = delta(before, after, Metrics.EDGE_READ);
            lines.add(String.format(Locale.ROOT,
                    "  store edge cache off: %.2f ms/query, %.1f kv reads/query (%d edge records), %.1f records/query%n",
                    nanos / 1e6 / ids.size(), (double) calls / ids.size(), edgeReads,
                    (double) items / ids.size()));
            Execution cached = measure(ids.get(0), engineWithStoreCache());
            lines.add(String.format(Locale.ROOT,
                    "  store edge cache on:  %.2f ms/query for %s, %.1f kv reads/query"
                            + " (the edges are served from memory, so they cost no reads)%n",
                    cached.nanos() / 1e6, ids.get(0),
                    (double) cached.traffic().getOrDefault(Metrics.KV_READ_CALL, 0L)));
        } finally {
            if (plain != null) {
                deleteRecursively(plain);
            }
        }
        return lines;
    }

    private long delta(Map<String, Long> before, Map<String, Long> after, String metric) {
        return after.getOrDefault(metric, 0L) - before.getOrDefault(metric, 0L);
    }

    /** The engine of the benchmark itself (whose store caches its edge lists). */
    private GremlinGraphQuery engineWithStoreCache() {
        return new GremlinGraphQuery(new RocksDbEdgeGraphQuery(store, schema),
                NeighborVertexResolver.from(schema));
    }

    private static void copyDirectory(Path from, Path to) throws IOException {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path file : files.toList()) {
                if (file.getFileName().toString().equals("LOCK")) {
                    continue; // held by whoever has the original open
                }
                Path target = to.resolve(from.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteRecursively(Path directory) {
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // a temporary directory: what is left behind is not worth failing a benchmark for
                }
            }
        } catch (IOException ignored) {
            // same
        }
    }
}
