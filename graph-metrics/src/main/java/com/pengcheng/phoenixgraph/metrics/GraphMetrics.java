package com.pengcheng.phoenixgraph.metrics;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * The live system metrics of one graph: monotonic counters plus latency histograms.
 *
 * <p>Every counter keeps two numbers: the total since the graph was first used and a
 * per-day slice, so a dashboard can show both "since the beginning" and "today". The
 * day is resolved through a one-second cache, which keeps the hot path free of
 * {@link LocalDate#now()} allocations.
 *
 * <p>Instances are thread safe: counters are {@link LongAdder}s and the maps are
 * concurrent, so the storage and query paths can record from any thread (parallel
 * seed reads included) without contention.
 */
public class GraphMetrics implements MetricsSink {

    private static final long DAY_CACHE_MILLIS = 1_000L;

    private final String graph;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final Map<String, LatencyStats> latencies = new ConcurrentHashMap<>();
    private volatile long dayCheckedAt;
    private volatile String today = LocalDate.now().toString();

    /** A shared sink that drops everything, for stores built without metrics. */
    private static final GraphMetrics NOOP = new GraphMetrics("none") {
        @Override
        public void increment(String metric, long delta) {
        }

        @Override
        public void recordLatency(String metric, long nanos) {
        }
    };

    public GraphMetrics(String graph) {
        this.graph = Objects.requireNonNull(graph, "graph");
    }

    /** A sink that forgets everything; stores default to it so metrics stay optional. */
    public static GraphMetrics noop() {
        return NOOP;
    }

    public String graph() {
        return graph;
    }

    /** Whether this instance actually records anything. */
    public boolean recording() {
        return this != NOOP;
    }

    // ------------------------------------------------------------------ recording

    @Override
    public void increment(String metric) {
        increment(metric, 1L);
    }

    /** Adds {@code delta} to a counter, both in total and in today's slice. */
    @Override
    public void increment(String metric, long delta) {
        if (delta == 0) {
            return;
        }
        Counter counter = counters.computeIfAbsent(metric, key -> new Counter());
        counter.total.add(delta);
        String day = currentDay();
        counter.days.computeIfAbsent(day, key -> new LongAdder()).add(delta);
    }

    /** The total of a counter (0 when it was never recorded). */
    public long value(String metric) {
        Counter counter = counters.get(metric);
        return counter == null ? 0L : counter.total.sum();
    }

    /** Records a latency sample (nanoseconds) in the metric's histogram. */
    @Override
    public void recordLatency(String metric, long nanos) {
        latencies.computeIfAbsent(metric, key -> new LatencyStats()).record(Math.max(0L, nanos));
    }

    /** Records a latency sample, in milliseconds. */
    public void recordLatencyMillis(String metric, long millis) {
        recordLatency(metric, millis * 1_000_000L);
    }

    /** The day used for today's slice; refreshed at most once per second. */
    public String currentDay() {
        long now = System.currentTimeMillis();
        if (now - dayCheckedAt >= DAY_CACHE_MILLIS) {
            dayCheckedAt = now;
            today = LocalDate.now().toString();
        }
        return today;
    }

    // ----------------------------------------------------------------- snapshots

    /** An immutable copy of every counter and histogram. */
    public MetricsSnapshot snapshot() {
        Map<String, Long> totals = new TreeMap<>();
        Map<String, Map<String, Long>> days = new TreeMap<>();
        for (Map.Entry<String, Counter> entry : counters.entrySet()) {
            long total = entry.getValue().total.sum();
            if (total != 0) {
                totals.put(entry.getKey(), total);
            }
            Map<String, Long> byDay = new TreeMap<>();
            entry.getValue().days.forEach((day, value) -> {
                long count = value.sum();
                if (count != 0) {
                    byDay.put(day, count);
                }
            });
            if (!byDay.isEmpty()) {
                days.put(entry.getKey(), byDay);
            }
        }
        Map<String, LatencySnapshot> latencySnapshots = new TreeMap<>();
        latencies.forEach((name, stats) -> {
            if (stats.count() != 0) {
                latencySnapshots.put(name, LatencySnapshot.of(stats));
            }
        });
        return new MetricsSnapshot(graph, java.time.Instant.now(), totals, days, latencySnapshots);
    }

    /** Folds a snapshot (usually the one loaded from storage) into these metrics. */
    public void merge(MetricsSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        snapshot.counters().forEach((metric, value) -> {
            Counter counter = counters.computeIfAbsent(metric, key -> new Counter());
            counter.total.add(value);
        });
        snapshot.days().forEach((metric, byDay) -> {
            Counter counter = counters.computeIfAbsent(metric, key -> new Counter());
            byDay.forEach((day, value) -> counter.days.computeIfAbsent(day, key -> new LongAdder()).add(value));
        });
        snapshot.latencies().forEach((metric, value) ->
                latencies.computeIfAbsent(metric, key -> new LatencyStats()).merge(value.toStats()));
    }

    /**
     * Clears every counter and histogram in place.
     *
     * <p>In place matters: the stores hold this instance, so replacing it would leave
     * them counting into an object nobody reads any more.
     */
    public void reset() {
        counters.clear();
        latencies.clear();
        today = LocalDate.now().toString();
    }

    /** Drops daily slices older than {@code keepDays}, keeping the totals. */
    public int retainDays(int keepDays) {
        String cutoff = LocalDate.now().minusDays(Math.max(0, keepDays)).toString();
        int removed = 0;
        for (Counter counter : counters.values()) {
            List<String> old = new ArrayList<>();
            for (String day : counter.days.keySet()) {
                if (day.compareTo(cutoff) < 0) {
                    old.add(day);
                }
            }
            old.forEach(counter.days::remove);
            removed += old.size();
        }
        return removed;
    }

    /** The metrics that have been recorded at least once (for tests and the UI). */
    public Set<String> metricNames() {
        return Collections.unmodifiableSet(new LinkedHashMap<>(counters).keySet());
    }

    /** One counter: a total plus a per-day split. */
    private static final class Counter {
        private final LongAdder total = new LongAdder();
        private final Map<String, LongAdder> days = new ConcurrentHashMap<>();
    }
}
