package com.pengcheng.phoenixgraph.http.dto;

import com.pengcheng.phoenixgraph.metrics.LatencySnapshot;
import com.pengcheng.phoenixgraph.metrics.Metrics;
import com.pengcheng.phoenixgraph.metrics.MetricsSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The shape the metrics dashboard reads.
 *
 * <p>{@link MetricsSnapshot} is a flat dump of every counter, which is right for storage
 * and awkward for a UI: a dashboard wants the numbers grouped the way it shows them
 * (storage traffic, quality of the writes, query load with the percentiles next to it).
 * This view does that grouping, and keeps the totals and today's numbers together so a
 * card can render both without joining anything.
 *
 * @param graph        the graph the numbers belong to
 * @param updatedAt    when they were read
 * @param storage      vertex/edge/geo/key-value traffic, with the failures beside it
 * @param quality      edge evictions, stale updates and failed writes
 * @param queries      query counts by language and source, plus the latency percentiles
 * @param counters     every counter, flat (for a table view or a debug panel)
 */
public record MetricsView(String graph, String updatedAt,
                          Map<String, Map<String, Long>> storage,
                          Map<String, Map<String, Long>> quality,
                          Map<String, Object> queries,
                          Map<String, Long> counters) {

    /** Builds the grouped view of a snapshot. */
    public static MetricsView of(MetricsSnapshot snapshot) {
        Map<String, Map<String, Long>> storage = new LinkedHashMap<>();
        storage.put("vertexRead", pair(snapshot, Metrics.VERTEX_READ, Metrics.VERTEX_READ_ITEMS,
                Metrics.VERTEX_READ_FAILED));
        storage.put("vertexWrite", pair(snapshot, Metrics.VERTEX_WRITE, Metrics.VERTEX_WRITE_ITEMS,
                Metrics.VERTEX_WRITE_FAILED));
        storage.put("edgeRead", pair(snapshot, Metrics.EDGE_READ, Metrics.EDGE_READ_ITEMS,
                Metrics.EDGE_READ_FAILED));
        storage.put("edgeWrite", pair(snapshot, Metrics.EDGE_WRITE, Metrics.EDGE_WRITE_ITEMS,
                Metrics.EDGE_WRITE_FAILED));
        storage.put("keyValue", pair(snapshot, Metrics.KV_READ_CALL, Metrics.KV_READ_ITEMS,
                Metrics.KV_READ_FAILED));

        Map<String, Map<String, Long>> quality = new LinkedHashMap<>();
        quality.put("evicted", pair(snapshot, Metrics.EDGE_EVICTED, null, null));
        quality.put("staleVertexWrites", pair(snapshot, Metrics.VERTEX_WRITE_STALE, null, null));
        quality.put("staleEdgeWrites", pair(snapshot, Metrics.EDGE_WRITE_STALE, null, null));
        quality.put("retries", pair(snapshot, Metrics.WRITE_RETRY, null, null));
        quality.put("deletes", pair(snapshot, Metrics.VERTEX_DELETE, Metrics.EDGE_DELETE, null));

        Map<String, Object> queries = new LinkedHashMap<>();
        queries.put("byLanguageAndSource", groupQueries(snapshot));
        queries.put("items", snapshot.today(Metrics.QUERY_ITEMS));
        queries.put("translated", new LinkedHashMap<>(Map.of(
                "vertexReads", snapshot.value(Metrics.QUERY_VERTEX_READ),
                "edgeReads", snapshot.value(Metrics.QUERY_EDGE_READ),
                "keyValueReads", snapshot.value(Metrics.QUERY_KV_READ),
                "keyValueWrites", snapshot.value(Metrics.QUERY_KV_WRITE),
                "storageItems", snapshot.value(Metrics.QUERY_STORAGE_ITEMS))));
        Map<String, Object> latency = new LinkedHashMap<>();
        snapshot.latencies().forEach((metric, value) -> latency.put(metric, latencyOf(value)));
        snapshot.latencies().keySet().stream()
                .filter(metric -> Metrics.baseName(metric).equals(Metrics.QUERY_LATENCY))
                .forEach(metric -> queries.put("latency", latencyOf(snapshot.latency(metric))));
        queries.put("latencyByLanguage", latency);

        return new MetricsView(snapshot.graph(), snapshot.updatedAt().toString(), storage, quality, queries,
                snapshot.counters());
    }

    /** One counter group: total, today, and the failure/related counter when there is one. */
    private static Map<String, Long> pair(MetricsSnapshot snapshot, String total, String items, String failed) {
        Map<String, Long> out = new LinkedHashMap<>();
        out.put("total", snapshot.value(total));
        out.put("today", snapshot.todayValue(total));
        if (items != null) {
            out.put("itemsTotal", snapshot.value(items));
            out.put("itemsToday", snapshot.todayValue(items));
        }
        if (failed != null) {
            out.put("failedTotal", snapshot.value(failed));
            out.put("failedToday", snapshot.todayValue(failed));
        }
        return out;
    }

    /** Query counts split by language, and inside that by who asked. */
    private static Map<String, Map<String, Long>> groupQueries(MetricsSnapshot snapshot) {
        Map<String, Map<String, Long>> byLanguage = new LinkedHashMap<>();
        snapshot.matching(Metrics.QUERY_COUNT).forEach((metric, value) -> {
            String language = tag(metric, "language");
            String source = tag(metric, "source");
            byLanguage.computeIfAbsent(language == null ? "unknown" : language, key -> new LinkedHashMap<>())
                    .put(source == null ? "unknown" : source, value);
        });
        return byLanguage;
    }

    /** Reads one tag out of {@code name{k=v}}; the counter names carry at most two. */
    static String tag(String metric, String key) {
        int brace = metric.indexOf('{');
        if (brace < 0) {
            return null;
        }
        String body = metric.substring(brace + 1, metric.length() - 1);
        for (String part : body.split(",")) {
            int equals = part.indexOf('=');
            if (equals > 0 && part.substring(0, equals).trim().equals(key)) {
                return part.substring(equals + 1).trim();
            }
        }
        return null;
    }

    private static Map<String, Object> latencyOf(LatencySnapshot latency) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", latency.count());
        out.put("meanMs", round(latency.meanMillis()));
        out.put("p50Ms", round(latency.p50Millis()));
        out.put("p95Ms", round(latency.p95Millis()));
        out.put("p99Ms", round(latency.p99Millis()));
        out.put("maxMs", round(latency.maxMillis()));
        return out;
    }

    private static double round(double value) {
        return Math.round(value * 100d) / 100d;
    }

    /** The counters of a list of metrics, for the "flat" part of the view. */
    static List<String> names() {
        return new ArrayList<>(List.of(Metrics.VERTEX_READ, Metrics.EDGE_READ, Metrics.QUERY_COUNT));
    }
}
