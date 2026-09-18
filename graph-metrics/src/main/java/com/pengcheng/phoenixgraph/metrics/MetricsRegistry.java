package com.pengcheng.phoenixgraph.metrics;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Keeps one {@link GraphMetrics} per graph and persists the snapshots through a
 * {@link MetricsStore}.
 *
 * <p>The first time a graph is asked for, its stored snapshot (if any) is folded in, so
 * counters keep counting across restarts and "today" survives one. Snapshots are saved
 * on a timer (every {@code flushInterval}) and on {@link #close()}, which the HTTP
 * application hook does on shutdown.
 *
 * <p>Different graphs can persist through different stores: the Aerospike graphs write
 * into a system set, the RocksDB graphs into their own directory. {@link #bind} selects
 * the store per graph, everything else uses the default.
 */
public final class MetricsRegistry implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(MetricsRegistry.class.getName());

    /** How many daily slices to keep per counter. */
    public static final int KEEP_DAYS = 90;

    private final MetricsStore defaultStore;
    private final Map<String, GraphMetrics> metrics = new ConcurrentHashMap<>();
    private final Map<String, MetricsStore> stores = new ConcurrentHashMap<>();
    private final Set<String> loaded = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService flusher;

    public MetricsRegistry(MetricsStore defaultStore) {
        this(defaultStore, Duration.ofSeconds(30));
    }

    public MetricsRegistry(MetricsStore defaultStore, Duration flushInterval) {
        this.defaultStore = defaultStore == null ? new InMemoryMetricsStore() : defaultStore;
        if (flushInterval == null || flushInterval.isZero() || flushInterval.isNegative()) {
            this.flusher = null;
        } else {
            this.flusher = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "graph-metrics-flush");
                thread.setDaemon(true);
                return thread;
            });
            long millis = Math.max(1_000L, flushInterval.toMillis());
            this.flusher.scheduleWithFixedDelay(this::flushAll, millis, millis, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Uses a store for one graph (Aerospike and RocksDB graphs can coexist).
     *
     * <p>Binding often happens after the graph's metrics were first asked for (the stores
     * need the metrics before the store that persists them can be opened), so a snapshot
     * already sitting in the new store is folded in right away - otherwise a restart
     * would read from an empty store and report zeros.
     */
    public void bind(String graph, MetricsStore store) {
        if (graph == null || store == null) {
            return;
        }
        stores.put(graph, store);
        GraphMetrics live = metrics.get(graph);
        if (live != null) {
            restore(graph, live);
        }
    }

    /** Loads a graph's stored snapshot into its live metrics, once. */
    private void restore(String graph, GraphMetrics live) {
        try {
            MetricsSnapshot stored = storeOf(graph).load(graph);
            if (stored != null) {
                live.merge(stored);
                LOG.log(Level.INFO, "metrics of graph {0} restored from storage: {1} counters, {2} histograms",
                        new Object[]{graph, stored.counters().size(), stored.latencies().size()});
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "cannot restore metrics of graph " + graph, e);
        }
    }

    /** The store a graph persists through. */
    public MetricsStore storeOf(String graph) {
        return stores.getOrDefault(graph, defaultStore);
    }

    /** The metrics of a graph, loading its stored snapshot the first time. */
    public GraphMetrics forGraph(String graph) {
        GraphMetrics found = metrics.get(graph);
        if (found != null) {
            return found;
        }
        GraphMetrics created = metrics.computeIfAbsent(graph, GraphMetrics::new);
        if (loaded.add(graph)) {
            restore(graph, created);
        }
        return created;
    }

    /** The live snapshot of a graph. */
    public MetricsSnapshot snapshot(String graph) {
        return forGraph(graph).snapshot();
    }

    /** Every graph that recorded something. */
    public Set<String> graphs() {
        return Set.copyOf(metrics.keySet());
    }

    /** Saves one graph's snapshot. */
    public void flush(String graph) {
        GraphMetrics live = forGraph(graph);
        live.retainDays(KEEP_DAYS);
        MetricsSnapshot snapshot = live.snapshot();
        try {
            storeOf(graph).save(snapshot);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "cannot save metrics of graph " + graph, e);
        }
    }

    /** Saves every graph's snapshot. */
    public void flushAll() {
        for (String graph : graphs()) {
            flush(graph);
        }
    }

    /** Clears a graph's counters and removes its stored snapshot. */
    public void reset(String graph) {
        // clear the graph's instance rather than replacing it: the stores hold it
        forGraph(graph).reset();
        loaded.add(graph);
        try {
            storeOf(graph).delete(graph);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "cannot delete metrics of graph " + graph, e);
        }
    }

    /** A copy of every graph's live snapshot, keyed by graph name. */
    public Map<String, MetricsSnapshot> snapshotAll() {
        Map<String, MetricsSnapshot> out = new TreeMap<>();
        metrics.keySet().forEach(graph -> out.put(graph, snapshot(graph)));
        return out;
    }

    @Override
    public void close() {
        if (flusher != null) {
            flusher.shutdownNow();
        }
        flushAll();
    }
}
