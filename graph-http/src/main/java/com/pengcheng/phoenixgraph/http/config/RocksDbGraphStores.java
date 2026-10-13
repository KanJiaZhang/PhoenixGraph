package com.pengcheng.phoenixgraph.http.config;

import com.pengcheng.phoenixgraph.metrics.GraphMetrics;
import com.pengcheng.phoenixgraph.metrics.MetricsStore;
import com.pengcheng.phoenixgraph.query.storage.GraphQuery;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.GraphStorageSchema;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbStoreConfig;
import com.pengcheng.phoenixgraph.storage.rocksdb.edge.RocksDbEdgeGraphQuery;
import com.pengcheng.phoenixgraph.storage.rocksdb.edge.RocksDbHnswIndexFactory;
import com.pengcheng.phoenixgraph.storage.rocksdb.edge.RocksDbEdgeStore;
import com.pengcheng.phoenixgraph.storage.rocksdb.inline.RocksDbInlineGraphQuery;
import com.pengcheng.phoenixgraph.storage.rocksdb.inline.RocksDbInlineStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Opens and owns the RocksDB graph instances this service serves.
 *
 * <p>The storage block of a schema says which scheme a graph uses ({@code RocksDB-Inline} or
 * {@code RocksDB-Edge}) and where its directory is; this class turns that declaration into an
 * open store plus the {@link GraphQuery} the query engines read through, and closes every
 * directory when the service shuts down (RocksDB must be closed cleanly, and only one process
 * may have a directory open).
 *
 * <p>The paths in a schema may be absolute or relative; a configured {@code rocksdbRoot}
 * resolves the relative ones, so the same schema file works on a laptop and inside a container
 * with a mounted volume.
 */
public final class RocksDbGraphStores implements AutoCloseable {

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(RocksDbGraphStores.class.getName());

    private final Path root;
    private final Map<String, AutoCloseable> stores = new LinkedHashMap<>();
    private final Map<String, String> paths = new LinkedHashMap<>();
    private final Map<String, MetricsStore> metricsStores = new LinkedHashMap<>();

    public RocksDbGraphStores() {
        this(null);
    }

    /**
     * Resolves relative schema paths against {@code rocksdbRoot} (may be {@code null}), creating the root when
     * it is missing.
     *
     * <p>RocksDB creates the *last* path component of a graph's directory and refuses to create the ones
     * above it, so a service pointed at a fresh volume fails at start-up with "Failed to create a directory" -
     * which is a deployment detail, not a graph problem. Creating the root here is one line and turns that
     * into "it just works on an empty disk".
     */
    public RocksDbGraphStores(String rocksdbRoot) {
        this.root = rocksdbRoot == null || rocksdbRoot.isBlank() ? null : Path.of(rocksdbRoot);
        if (root != null && !Files.isDirectory(root)) {
            try {
                Files.createDirectories(root);
                LOGGER.info("created the RocksDB root " + root.toAbsolutePath());
            } catch (IOException failure) {
                LOGGER.warning("cannot create the RocksDB root " + root + ": " + failure.getMessage());
            }
        }
    }

    /** Whether a schema asks for a RocksDB scheme. */
    public static boolean isRocksDb(GraphSchema schema) {
        return schema.storage() != null && schema.storage().isRocksDb();
    }

    /**
     * Opens the graph's RocksDB directory with the scheme its schema declares and returns the
     * read path over it.
     *
     * @throws IllegalArgumentException when the schema's storage is not a RocksDB scheme
     */
    public GraphQuery open(GraphSchema schema) {
        return open(schema, null);
    }

    /**
     * Opens a graph's directory and, when {@code metrics} is given, counts its key-value
     * traffic and remembers where the graph's metrics are persisted (inside the same
     * directory, so nothing else has to be running).
     */
    public GraphQuery open(GraphSchema schema, GraphMetrics metrics) {
        GraphStorageSchema storage = schema.storage();
        if (storage == null || !storage.isRocksDb()) {
            throw new IllegalArgumentException("Graph '" + schema.name() + "' is not served by RocksDB");
        }
        String path = resolve(storage);
        RocksDbStoreConfig config = new RocksDbStoreConfig(path, storage.regionCount(),
                storage.maxVersions(), true, storage.cacheEdges());
        String name = schema.name();
        return switch (storage.type()) {
            case ROCKSDB_INLINE -> {
                RocksDbInlineStore store = RocksDbInlineStore.open(schema, config);
                register(name, path, store);
                if (metrics != null) {
                    store.metrics(metrics);
                    metricsStores.put(name, store.metricsStore());
                }
                yield new RocksDbInlineGraphQuery(store, schema);
            }
            case ROCKSDB_EDGE -> {
                // a directory that was indexed once keeps a column family per HNSW layer, and RocksDB opens
                // all of them or none: the layer edge classes have to be in the schema before the open
                RocksDbHnswIndexFactory.declareLayers(schema);
                RocksDbEdgeStore store = RocksDbEdgeStore.open(schema, config);
                register(name, path, store);
                if (metrics != null) {
                    store.metrics(metrics);
                    metricsStores.put(name, store.metricsStore());
                }
                yield new RocksDbEdgeGraphQuery(store, schema);
            }
            default -> throw new IllegalArgumentException(
                    "Graph '" + name + "' declares storage " + storage.type().configName()
                            + ", which is not a RocksDB scheme");
        };
    }

    private void register(String graphName, String path, AutoCloseable store) {
        AutoCloseable previous = stores.put(graphName, store);
        if (previous != null) {
            closeQuietly(previous);
        }
        paths.put(graphName, path);
    }

    /**
     * Snapshots a graph's directory into {@code target}.
     *
     * <p>Called by the checkpoint endpoint (an operator action) rather than by a timer: whether a copy belongs
     * on the same disk, on a mounted volume or on another host is a deployment decision, and a background job
     * that picks one of them would be a policy nobody asked for.
     *
     * @return the number of files the checkpoint holds
     * @throws IllegalArgumentException when the graph is not a RocksDB graph (an Aerospike graph is backed up
     *                                  with the database's own tooling, which this process cannot reach)
     */
    public int checkpoint(String graphName, Path target) {
        AutoCloseable store = stores.get(graphName);
        if (store instanceof RocksDbInlineStore inline) {
            return inline.checkpoint(target);
        }
        if (store instanceof RocksDbEdgeStore edge) {
            return edge.checkpoint(target);
        }
        throw new IllegalArgumentException("Graph '" + graphName + "' is not served by RocksDB, so it has no "
                + "directory to checkpoint; an Aerospike graph is backed up with asbackup (see docs/OPERATIONS.md)");
    }

    /** Whether a graph has a directory this class can snapshot. */
    public boolean canCheckpoint(String graphName) {
        AutoCloseable store = stores.get(graphName);
        return store instanceof RocksDbInlineStore || store instanceof RocksDbEdgeStore;
    }

    /** Where a graph's metrics are persisted, or {@code null} when it is not a RocksDB graph. */
    public MetricsStore metricsStore(String graphName) {
        return metricsStores.get(graphName);
    }

    /** The directory opened for a graph, or {@code null} when it is not a RocksDB graph. */
    public String pathOf(String graphName) {
        return paths.get(graphName);
    }

    /**
     * The RocksDB edge store of a graph, or {@code null} when the graph is not served by that scheme.
     *
     * <p>The write side of anything that lives in the graph's own directory but is not a vertex or an edge
     * of the schema - today a vector index, whose layers are created while it is built - needs the store,
     * not just the read path.
     */
    public RocksDbEdgeStore edgeStore(String graphName) {
        AutoCloseable store = stores.get(graphName);
        return store instanceof RocksDbEdgeStore edge ? edge : null;
    }

    /** The graphs served by RocksDB, in configuration order. */
    public List<String> graphs() {
        return List.copyOf(paths.keySet());
    }

    /** Creates the directory of a graph when it does not exist yet (used before a load). */
    public String prepareDirectory(GraphSchema schema) {
        String path = resolve(schema.storage());
        try {
            Files.createDirectories(Path.of(path));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create the RocksDB directory " + path, e);
        }
        return path;
    }

    private String resolve(GraphStorageSchema storage) {
        String path = storage.requireRocksDbPath();
        Path candidate = Path.of(path);
        if (root == null || candidate.isAbsolute()) {
            return candidate.toString();
        }
        return root.resolve(candidate).toString();
    }

    @Override
    public void close() {
        for (AutoCloseable store : stores.values()) {
            closeQuietly(store);
        }
        stores.clear();
        paths.clear();
    }

    private static void closeQuietly(AutoCloseable store) {
        try {
            store.close();
        } catch (Exception ignored) {
            // shutting down: a failed close must not hide the shutdown itself
        }
    }
}
