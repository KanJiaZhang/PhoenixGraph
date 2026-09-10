package com.pengcheng.phoenixgraph.storage;

import java.util.Map;

/**
 * Low-level columnar key/value store abstraction for NoSQL backends (Aerospike,
 * RocksDB, HBase, ...). A record is identified by a key and holds a set of
 * columns (bins).
 */
public interface ColumnStore {

    /** Upserts a full set of columns for the given key. */
    void put(Object key, Map<String, Object> columns);

    /** Reads all columns for the given key, or {@code null} if absent. */
    Map<String, Object> get(Object key);

    /** Upserts a single column for the given key. */
    void putColumn(Object key, String column, Object value);

    /** Reads a single column for the given key, or {@code null} if absent. */
    Object getColumn(Object key, String column);

    /** Removes a single column for the given key. */
    void removeColumn(Object key, String column);

    /** Removes the whole record for the given key. */
    void remove(Object key);
}
