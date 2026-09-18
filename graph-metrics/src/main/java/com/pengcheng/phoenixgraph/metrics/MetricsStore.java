package com.pengcheng.phoenixgraph.metrics;

import java.util.Set;

/**
 * Where a graph's system metrics are persisted.
 *
 * <p>Every storage scheme that wants metrics to survive a restart implements this: the
 * Aerospike scheme writes one record per graph into a system set, the RocksDB schemes
 * write one key per graph into a metrics column family. A scheme that does not care
 * simply is not wired to one, and the in-memory store keeps the numbers for the
 * lifetime of the process.
 */
public interface MetricsStore {

    /** Saves the whole snapshot, replacing whatever was there for this graph. */
    void save(MetricsSnapshot snapshot);

    /** Loads the last snapshot of a graph, or {@code null} when there is none. */
    MetricsSnapshot load(String graph);

    /** The graphs that have a stored snapshot. */
    Set<String> graphs();

    /** Forgets a graph's snapshot. */
    boolean delete(String graph);
}
