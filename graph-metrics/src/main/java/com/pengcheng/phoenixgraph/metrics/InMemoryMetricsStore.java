package com.pengcheng.phoenixgraph.metrics;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link MetricsStore} that keeps snapshots in this process only.
 *
 * <p>It is the default for graphs whose storage has no metrics persistence (or for
 * tests): counters still work and are queryable, they just start from zero after a
 * restart.
 */
public final class InMemoryMetricsStore implements MetricsStore {

    private final Map<String, MetricsSnapshot> snapshots = new ConcurrentHashMap<>();

    @Override
    public void save(MetricsSnapshot snapshot) {
        if (snapshot == null || snapshot.graph() == null) {
            return;
        }
        snapshots.put(snapshot.graph(), snapshot);
    }

    @Override
    public MetricsSnapshot load(String graph) {
        return snapshots.get(graph);
    }

    @Override
    public Set<String> graphs() {
        return Set.copyOf(snapshots.keySet());
    }

    @Override
    public boolean delete(String graph) {
        return snapshots.remove(graph) != null;
    }
}
