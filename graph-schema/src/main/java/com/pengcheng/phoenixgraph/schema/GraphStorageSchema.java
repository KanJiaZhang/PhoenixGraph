package com.pengcheng.phoenixgraph.schema;

import java.util.Objects;

/**
 * The storage settings a graph schema declares: which implementation serves the graph and
 * what that implementation needs to open it.
 *
 * <p>A schema without a storage block is served by Aerospike ({@link #AEROSPIKE}) - the
 * settings that describe where Aerospike lives (host, port, namespace, set) belong to the
 * deployment, not to the graph definition, so the Aerospike default carries none of them.
 * A RocksDB scheme needs a {@code path} (its directory) and can tune the key-space
 * {@code regionCount}, how many property {@code versions} are kept and whether decoded edge
 * lists are cached.
 *
 * @param type        the storage implementation
 * @param path        the RocksDB directory (RocksDB schemes only)
 * @param regionCount how many regions the key space is divided into (RocksDB only, 512 by default)
 * @param maxVersions how many versions of a property value are kept (RocksDB only, 1 by default)
 * @param cacheEdges  whether decoded edge lists are cached (RocksDB only, on by default)
 */
public record GraphStorageSchema(GraphStorageType type, String path, int regionCount, int maxVersions,
                                 boolean cacheEdges) {

    /** The default number of key-space regions. */
    public static final int DEFAULT_REGION_COUNT = 512;

    /** The default number of property versions kept. */
    public static final int DEFAULT_MAX_VERSIONS = 1;

    /** The Aerospike default: no graph-local settings. */
    public static final GraphStorageSchema AEROSPIKE =
            new GraphStorageSchema(GraphStorageType.AEROSPIKE, null, DEFAULT_REGION_COUNT,
                    DEFAULT_MAX_VERSIONS, true);

    public GraphStorageSchema {
        Objects.requireNonNull(type, "type");
        if (regionCount <= 0) {
            throw new IllegalArgumentException("regionCount must be positive, got " + regionCount);
        }
        if (maxVersions <= 0) {
            throw new IllegalArgumentException("maxVersions must be positive, got " + maxVersions);
        }
        if (type.isAerospike()) {
            path = null;
        }
    }

    /** The Aerospike default (a schema without a storage block). */
    public static GraphStorageSchema aerospike() {
        return AEROSPIKE;
    }

    /** An Aerospike graph whose edges live in the aggregated {@code Edge_Out}/{@code Edge_In} tables. */
    public static GraphStorageSchema aerospikeEdge() {
        return new GraphStorageSchema(GraphStorageType.AEROSPIKE_EDGE, null,
                DEFAULT_REGION_COUNT, DEFAULT_MAX_VERSIONS, true);
    }

    /** A RocksDB-Inline graph at a directory. */
    public static GraphStorageSchema rocksDbInline(String path) {
        return new GraphStorageSchema(GraphStorageType.ROCKSDB_INLINE, requirePath(path),
                DEFAULT_REGION_COUNT, DEFAULT_MAX_VERSIONS, true);
    }

    /** A RocksDB-Edge graph at a directory. */
    public static GraphStorageSchema rocksDbEdge(String path) {
        return new GraphStorageSchema(GraphStorageType.ROCKSDB_EDGE, requirePath(path),
                DEFAULT_REGION_COUNT, DEFAULT_MAX_VERSIONS, true);
    }

    /** Builds a RocksDB scheme from parsed settings. */
    public static GraphStorageSchema rocksDb(GraphStorageType type, String path, int regionCount,
                                             int maxVersions, boolean cacheEdges) {
        if (!type.isRocksDb()) {
            throw new IllegalArgumentException(type.configName() + " is not a RocksDB storage type");
        }
        return new GraphStorageSchema(type, requirePath(path), regionCount, maxVersions, cacheEdges);
    }

    private static String requirePath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("A RocksDB storage needs a path");
        }
        return path;
    }

    /** Whether this graph is served by Aerospike. */
    public boolean isAerospike() {
        return type.isAerospike();
    }

    /** Whether this is the Aerospike storage with the aggregated edge tables. */
    public boolean isAerospikeEdge() {
        return type == GraphStorageType.AEROSPIKE_EDGE;
    }

    /** Whether this graph is served by a RocksDB scheme. */
    public boolean isRocksDb() {
        return type.isRocksDb();
    }

    /** The RocksDB path, failing loudly when the storage is not RocksDB. */
    public String requireRocksDbPath() {
        if (!isRocksDb() || path == null || path.isBlank()) {
            throw new IllegalStateException("Storage " + type.configName() + " has no RocksDB path");
        }
        return path;
    }

    public GraphStorageSchema withRegionCount(int regions) {
        return new GraphStorageSchema(type, path, regions, maxVersions, cacheEdges);
    }

    public GraphStorageSchema withMaxVersions(int versions) {
        return new GraphStorageSchema(type, path, regionCount, versions, cacheEdges);
    }

    public GraphStorageSchema withCacheEdges(boolean cache) {
        return new GraphStorageSchema(type, path, regionCount, maxVersions, cache);
    }
}
