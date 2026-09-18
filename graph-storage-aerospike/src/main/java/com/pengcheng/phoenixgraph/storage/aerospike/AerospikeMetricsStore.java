package com.pengcheng.phoenixgraph.storage.aerospike;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.aerospike.client.ScanCallback;
import com.aerospike.client.policy.ScanPolicy;
import com.pengcheng.phoenixgraph.metrics.LatencySnapshot;
import com.pengcheng.phoenixgraph.metrics.MetricsSnapshot;
import com.pengcheng.phoenixgraph.metrics.MetricsStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Persists a graph's system metrics in an Aerospike system set (one record per graph).
 *
 * <p>The record keeps the totals, the per-day slices and the latency histograms: totals
 * survive a restart, "today" keeps counting from where it was, and the percentiles a
 * dashboard shows are the ones measured before the restart. Metrics are written on a
 * timer (and on shutdown) rather than per operation, so a counter bump never waits for
 * the database.
 */
public final class AerospikeMetricsStore implements MetricsStore {

    /** The set that holds the metric records, next to the graph's own data. */
    public static final String DEFAULT_SET = "graph_metrics";

    private static final String UPDATED_AT_BIN = "updated_at";
    private static final String COUNTERS_BIN = "counters";
    private static final String DAYS_BIN = "counter_days";
    private static final String LATENCY_BUCKETS_BIN = "latency_buckets";
    private static final String LATENCY_TOTALS_BIN = "latency_totals";

    private final AerospikeClient client;
    private final String namespace;
    private final String set;

    public AerospikeMetricsStore(AerospikeClient client, String namespace) {
        this(client, namespace, DEFAULT_SET);
    }

    public AerospikeMetricsStore(AerospikeClient client, String namespace, String set) {
        this.client = java.util.Objects.requireNonNull(client, "client");
        this.namespace = java.util.Objects.requireNonNull(namespace, "namespace");
        this.set = set == null || set.isBlank() ? DEFAULT_SET : set;
    }

    private Key key(String graph) {
        return new Key(namespace, set, "metrics:" + graph);
    }

    @Override
    public void save(MetricsSnapshot snapshot) {
        if (snapshot == null || snapshot.graph() == null) {
            return;
        }
        client.put(null, key(snapshot.graph()),
                new com.aerospike.client.Bin(UPDATED_AT_BIN, snapshot.updatedAt().toEpochMilli()),
                new com.aerospike.client.Bin(COUNTERS_BIN, new LinkedHashMap<>(snapshot.counters())),
                new com.aerospike.client.Bin(DAYS_BIN, copyDays(snapshot)),
                new com.aerospike.client.Bin(LATENCY_BUCKETS_BIN, latencyBuckets(snapshot)),
                new com.aerospike.client.Bin(LATENCY_TOTALS_BIN, latencyTotals(snapshot)));
    }

    @Override
    public MetricsSnapshot load(String graph) {
        Record record = client.get(null, key(graph));
        if (record == null || record.bins == null) {
            return null;
        }
        Map<String, Long> counters = longs(record.bins.get(COUNTERS_BIN));
        Map<String, Map<String, Long>> days = nestedLongs(record.bins.get(DAYS_BIN));
        Map<String, LatencySnapshot> latencies = readLatencies(record.bins);
        long updatedAt = record.bins.get(UPDATED_AT_BIN) instanceof Number number
                ? number.longValue() : System.currentTimeMillis();
        return new MetricsSnapshot(graph, Instant.ofEpochMilli(updatedAt), counters, days, latencies);
    }

    @Override
    public Set<String> graphs() {
        Set<String> graphs = new TreeSet<>();
        client.scanAll(new ScanPolicy(), namespace, set, (ScanCallback) (key, record) -> {
            Object name = key.userKey == null ? null : key.userKey.getObject();
            if (name instanceof String text && text.startsWith("metrics:")) {
                graphs.add(text.substring("metrics:".length()));
            }
        });
        return graphs;
    }

    @Override
    public boolean delete(String graph) {
        return client.delete(null, key(graph));
    }

    // ------------------------------------------------------------------ encoding

    private static Map<String, Map<String, Long>> copyDays(MetricsSnapshot snapshot) {
        Map<String, Map<String, Long>> out = new LinkedHashMap<>();
        snapshot.days().forEach((metric, byDay) -> out.put(metric, new LinkedHashMap<>(byDay)));
        return out;
    }

    private static Map<String, Map<String, Long>> latencyBuckets(MetricsSnapshot snapshot) {
        Map<String, Map<String, Long>> out = new LinkedHashMap<>();
        snapshot.latencies().forEach((metric, latency) -> {
            Map<String, Long> buckets = new LinkedHashMap<>();
            for (long[] bucket : latency.buckets()) {
                buckets.put(String.valueOf(bucket[0]), bucket[1]);
            }
            out.put(metric, buckets);
        });
        return out;
    }

    private static Map<String, Map<String, Long>> latencyTotals(MetricsSnapshot snapshot) {
        Map<String, Map<String, Long>> out = new LinkedHashMap<>();
        snapshot.latencies().forEach((metric, latency) -> out.put(metric, Map.of(
                "count", latency.count(), "sum", latency.sumNanos(), "max", latency.maxNanos())));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Long> longs(Object value) {
        Map<String, Long> out = new TreeMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, count) -> {
                if (key != null && count instanceof Number number) {
                    out.put(String.valueOf(key), number.longValue());
                }
            });
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Long>> nestedLongs(Object value) {
        Map<String, Map<String, Long>> out = new TreeMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, inner) -> {
                if (key != null) {
                    out.put(String.valueOf(key), longs(inner));
                }
            });
        }
        return out;
    }

    private static Map<String, LatencySnapshot> readLatencies(Map<String, Object> bins) {
        Map<String, Map<String, Long>> buckets = nestedLongs(bins.get(LATENCY_BUCKETS_BIN));
        Map<String, Map<String, Long>> totals = nestedLongs(bins.get(LATENCY_TOTALS_BIN));
        Map<String, LatencySnapshot> out = new TreeMap<>();
        for (Map.Entry<String, Map<String, Long>> entry : buckets.entrySet()) {
            Map<String, Long> aggregate = totals.getOrDefault(entry.getKey(), Map.of());
            long count = aggregate.getOrDefault("count", 0L);
            long sum = aggregate.getOrDefault("sum", 0L);
            long max = aggregate.getOrDefault("max", 0L);
            List<long[]> raw = new ArrayList<>();
            Map<String, Long> byBucket = new TreeMap<>(entry.getValue());
            byBucket.forEach((bucket, hits) -> {
                try {
                    raw.add(new long[]{Long.parseLong(bucket), hits});
                } catch (NumberFormatException ignored) {
                    // a bucket we cannot read is a bucket we do not count
                }
            });
            var stats = com.pengcheng.phoenixgraph.metrics.LatencyStats.of(count, sum, max, raw);
            out.put(entry.getKey(), LatencySnapshot.of(stats));
        }
        return out;
    }
}
