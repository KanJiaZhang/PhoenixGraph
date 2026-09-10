package com.pengcheng.phoenixgraph.storage;

import com.pengcheng.phoenixgraph.core.Graph;

/**
 * A storage engine providing lifecycle management of graphs (databases).
 */
public interface StorageEngine extends AutoCloseable {

    /** Unique name identifying this engine (e.g. "rocksdb", "in-memory"). */
    String name();

    /** Opens (or lazily creates) the graph with the given name. */
    Graph open(String database);

    /** Explicitly creates a new, empty graph with the given name. */
    void create(String database);

    /** Drops the graph with the given name and all of its data. */
    void drop(String database);

    @Override
    void close();
}
