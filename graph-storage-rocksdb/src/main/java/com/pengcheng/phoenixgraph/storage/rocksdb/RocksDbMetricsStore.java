package com.pengcheng.phoenixgraph.storage.rocksdb;

import com.pengcheng.phoenixgraph.metrics.LatencySnapshot;
import com.pengcheng.phoenixgraph.metrics.LatencyStats;
import com.pengcheng.phoenixgraph.metrics.MetricsSnapshot;
import com.pengcheng.phoenixgraph.metrics.MetricsStore;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Persists a graph's system metrics in a {@value #FAMILY} column family of the graph's own
 * RocksDB directory — one key per graph, one text line per number.
 *
 * <p>Keeping the metrics next to the data means a RocksDB graph needs no second store (and
 * no second server): the counters, the daily slices and the latency buckets travel with
 * the directory, so a restart resumes counting where it stopped and the histograms keep
 * their samples.
 *
 * <p>The format is deliberately readable:
 * <pre>
 * c &lt;metric&gt; &lt;total&gt;                     counter total
 * d &lt;metric&gt; &lt;day&gt; &lt;value&gt;              counter slice for one day
 * l &lt;metric&gt; &lt;count&gt; &lt;sumNanos&gt; &lt;maxNanos&gt; &lt;bucket&gt;:&lt;hits&gt;,…
 * </pre>
 */
public final class RocksDbMetricsStore implements MetricsStore {

    /** The column family that holds the metric records. */
    public static final String FAMILY = "metrics";

    private static final char COUNTER = 'c';
    private static final char DAY = 'd';
    private static final char LATENCY = 'l';

    private final RocksDbHandle handle;

    public RocksDbMetricsStore(RocksDbHandle handle) {
        this.handle = java.util.Objects.requireNonNull(handle, "handle");
    }

    /** The column family this store needs; add it when opening the directory. */
    public static String family() {
        return FAMILY;
    }

    @Override
    public void save(MetricsSnapshot snapshot) {
        if (snapshot == null || snapshot.graph() == null) {
            return;
        }
        StringBuilder text = new StringBuilder();
        snapshot.counters().forEach((metric, value) ->
                text.append(COUNTER).append(' ').append(metric).append(' ').append(value).append('\n'));
        snapshot.days().forEach((metric, byDay) -> byDay.forEach((day, value) ->
                text.append(DAY).append(' ').append(metric).append(' ').append(day).append(' ').append(value)
                        .append('\n')));
        snapshot.latencies().forEach((metric, latency) -> {
            text.append(LATENCY).append(' ').append(metric).append(' ').append(latency.count()).append(' ')
                    .append(latency.sumNanos()).append(' ').append(latency.maxNanos()).append(' ');
            boolean first = true;
            for (long[] bucket : latency.buckets()) {
                if (!first) {
                    text.append(',');
                }
                text.append(bucket[0]).append(':').append(bucket[1]);
                first = false;
            }
            text.append('\n');
        });
        handle.put(FAMILY, key(snapshot.graph()), text.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public MetricsSnapshot load(String graph) {
        byte[] value = handle.get(FAMILY, key(graph));
        if (value == null) {
            return null;
        }
        Map<String, Long> counters = new TreeMap<>();
        Map<String, Map<String, Long>> days = new TreeMap<>();
        Map<String, LatencySnapshot> latencies = new TreeMap<>();
        Map<String, long[]> latencyAggregates = new TreeMap<>();
        Map<String, List<long[]>> latencyBuckets = new TreeMap<>();

        for (String line : new String(value, StandardCharsets.UTF_8).split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            char kind = line.charAt(0);
            String[] parts = line.split(" ", 6);
            if (parts.length < 3) {
                continue;
            }
            String metric = parts[1];
            if (kind == COUNTER) {
                counters.put(metric, parse(parts[2]));
            } else if (kind == DAY && parts.length >= 4) {
                days.computeIfAbsent(metric, key -> new TreeMap<>()).put(parts[2], parse(parts[3]));
            } else if (kind == LATENCY && parts.length >= 6) {
                latencyAggregates.put(metric, new long[]{parse(parts[2]), parse(parts[3]), parse(parts[4])});
                List<long[]> buckets = new ArrayList<>();
                for (String pair : parts[5].split(",")) {
                    int colon = pair.indexOf(':');
                    if (colon > 0) {
                        buckets.add(new long[]{parse(pair.substring(0, colon)), parse(pair.substring(colon + 1))});
                    }
                }
                latencyBuckets.put(metric, buckets);
            }
        }
        latencyAggregates.forEach((metric, aggregate) -> latencies.put(metric, LatencySnapshot.of(
                LatencyStats.of(aggregate[0], aggregate[1], aggregate[2],
                        latencyBuckets.getOrDefault(metric, List.of())))));
        return new MetricsSnapshot(graph, Instant.now(), counters, days, latencies);
    }

    @Override
    public Set<String> graphs() {
        Set<String> graphs = new TreeSet<>();
        try (RocksDbHandle.RocksDbRange range = handle.iterator(FAMILY, null, null)) {
            while (range.isValid()) {
                graphs.add(new String(range.key(), StandardCharsets.UTF_8));
                range.next();
            }
        }
        return graphs;
    }

    @Override
    public boolean delete(String graph) {
        byte[] key = key(graph);
        if (handle.get(FAMILY, key) == null) {
            return false;
        }
        handle.delete(FAMILY, key);
        return true;
    }

    private static byte[] key(String graph) {
        return graph.getBytes(StandardCharsets.UTF_8);
    }

    private static long parse(String text) {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
