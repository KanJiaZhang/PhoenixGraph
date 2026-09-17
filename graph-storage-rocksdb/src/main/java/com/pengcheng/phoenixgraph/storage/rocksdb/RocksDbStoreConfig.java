package com.pengcheng.phoenixgraph.storage.rocksdb;

import java.util.Objects;

/**
 * Settings of one RocksDB graph instance (one directory on disk).
 *
 * @param path             the RocksDB directory
 * @param regionCount      how many regions the key space is divided into; a key is
 *                         placed in region {@code floorMod(hash(key), regionCount)}. The
 *                         region is derived from the fixed-width hash prefix that every
 *                         key starts with, so keys of one region are physically adjacent
 * @param maxVersions      how many versions of a property value are kept; {@code 1}
 *                         (the default) keeps only the current one
 * @param createIfMissing  whether opening creates the directory
 * @param cacheEdgeLists   whether decoded edge lists are cached in front of RocksDB
 */
public record RocksDbStoreConfig(String path, int regionCount, int maxVersions,
                                 boolean createIfMissing, boolean cacheEdgeLists) {

    /** The default number of key-space regions. */
    public static final int DEFAULT_REGION_COUNT = 512;

    /** The default number of property versions kept per property. */
    public static final int DEFAULT_MAX_VERSIONS = 1;

    public RocksDbStoreConfig {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("A RocksDB path is required");
        }
        if (regionCount <= 0) {
            throw new IllegalArgumentException("regionCount must be positive, got " + regionCount);
        }
        if (maxVersions <= 0) {
            throw new IllegalArgumentException("maxVersions must be positive, got " + maxVersions);
        }
    }

    /** Default settings for a graph directory: 512 regions, one version, edges cached. */
    public static RocksDbStoreConfig of(String path) {
        return new RocksDbStoreConfig(path, DEFAULT_REGION_COUNT, DEFAULT_MAX_VERSIONS, true, true);
    }

    /** Default settings without an edge cache (used by tests and pure storage checks). */
    public static RocksDbStoreConfig plain(String path) {
        return new RocksDbStoreConfig(path, DEFAULT_REGION_COUNT, DEFAULT_MAX_VERSIONS, true, false);
    }

    public RocksDbStoreConfig withRegionCount(int regions) {
        return new RocksDbStoreConfig(path, regions, maxVersions, createIfMissing, cacheEdgeLists);
    }

    public RocksDbStoreConfig withMaxVersions(int versions) {
        return new RocksDbStoreConfig(path, regionCount, versions, createIfMissing, cacheEdgeLists);
    }

    public RocksDbStoreConfig withCacheEdgeLists(boolean cache) {
        return new RocksDbStoreConfig(path, regionCount, maxVersions, createIfMissing, cache);
    }

    public RocksDbStoreConfig withPath(String newPath) {
        return new RocksDbStoreConfig(Objects.requireNonNull(newPath, "path"), regionCount, maxVersions,
                createIfMissing, cacheEdgeLists);
    }
}
