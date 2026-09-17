package com.pengcheng.phoenixgraph.http.config;

import com.pengcheng.phoenixgraph.query.storage.GraphQuery;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.GraphStorageSchema;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbStoreConfig;
import com.pengcheng.phoenixgraph.storage.rocksdb.edge.RocksDbEdgeGraphQuery;
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

    private final Path root;
    private final Map<String, AutoCloseable> stores = new LinkedHashMap<>();
    private final Map<String, String> paths = new LinkedHashMap<>();

    public RocksDbGraphStores() {
        this(null);
    }

    /** Resolves relative schema paths against {@code rocksdbRoot} (may be {@code null}). */
    public RocksDbGraphStores(String rocksdbRoot) {
        this.root = rocksdbRoot == null || rocksdbRoot.isBlank() ? null : Path.of(rocksdbRoot);
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
                yield new RocksDbInlineGraphQuery(store, schema);
            }
            case ROCKSDB_EDGE -> {
                RocksDbEdgeStore store = RocksDbEdgeStore.open(schema, config);
                register(name, path, store);
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

    /** The directory opened for a graph, or {@code null} when it is not a RocksDB graph. */
    public String pathOf(String graphName) {
        return paths.get(graphName);
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
