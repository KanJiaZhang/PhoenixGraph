package com.pengcheng.phoenixgraph.http.service;

import com.pengcheng.phoenixgraph.metrics.LatencySnapshot;
import com.pengcheng.phoenixgraph.metrics.MetricsSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Formats the metrics the engine already keeps as Prometheus text exposition.
 *
 * <p>The engine's own counters were served as JSON for the dashboard, which is the right shape for a human
 * and the wrong one for a scraper: a monitoring system needs stable metric names, a type line, and labels
 * instead of nesting. Nothing new is measured here - the same counters and histograms are re-labelled - so
 * the dashboard and Prometheus can never disagree about a number.
 *
 * <p>Two decisions worth stating:
 * <ul>
 *   <li><b>counters</b> become {@code phoenixgraph_<name>_total{graph="..."}} with the dots and dashes of the
 *       internal names mapped to underscores, so "storage.vertexRead" becomes
 *       {@code phoenixgraph_storage_vertexread_total};</li>
 *   <li><b>histograms</b> are exported as the four summary gauges the engine computes (mean/p50/p95/p99 plus
 *       max and count) rather than as Prometheus buckets: the internal histogram's bucket edges are not
 *       stable enough to publish, and a p99 you can compare across versions beats one you cannot.</li>
 * </ul>
 */
public final class PrometheusFormat {

    /**
     * Counters that describe something going wrong. Exported as a zero rather than omitted, so a rule can tell
     * "nothing has failed" from "this metric does not exist".
     */
    private static final java.util.List<String> ALWAYS_EXPORTED_FAILURES = java.util.List.of(
            "storage.vertex.read.failed", "storage.edge.read.failed",
            "storage.vertex.write.failed", "storage.edge.write.failed",
            "storage.kv.read.failed", "storage.kv.write.failed");

    private PrometheusFormat() {
    }

    /** One snapshot as exposition text. */
    public static String of(MetricsSnapshot snapshot) {
        String graph = escape(snapshot.graph());
        StringBuilder out = new StringBuilder();

        out.append("# HELP phoenixgraph_uptime_info Metrics scraped from the graph's own registry.\n");
        out.append("# HELP phoenixgraph_query_latency_language_*_p99_millis Query latency percentiles. These are "
                + "cumulative (since the histogram started), so a threshold alert on them does not clear after "
                + "one slow query: docs/DATA-QUALITY.md section 12 has the open defect and the windowed view "
                + "LatencyStats already computes.\n");
        out.append("# TYPE phoenixgraph_uptime_info gauge\n");
        out.append("phoenixgraph_uptime_info{graph=\"").append(graph).append("\",updated_at=\"")
                .append(snapshot.updatedAt()).append("\"} 1\n");

        // Failure counters are exported even when they are still zero. A series that only appears once
        // something has already gone wrong cannot be alerted on: "no data" and "no failures" look the same to
        // a rule, and the rule would stay silent through exactly the incident it exists for.
        Map<String, Long> counters = new TreeMap<>(snapshot.counters());
        for (String failure : ALWAYS_EXPORTED_FAILURES) {
            counters.putIfAbsent(failure, 0L);
        }
        for (Map.Entry<String, Long> entry : counters.entrySet()) {
            String name = "phoenixgraph_" + metricName(entry.getKey()) + "_total";
            out.append("# TYPE ").append(name).append(" counter\n");
            out.append(name).append("{graph=\"").append(graph).append("\"} ").append(entry.getValue()).append('\n');
            Map<String, Long> days = snapshot.days().get(entry.getKey());
            if (days != null && !days.isEmpty()) {
                // today's slice is what an operator alerts on ("is it still moving?"), the total is history
                List<String> slices = new ArrayList<>(new TreeMap<>(days).entrySet().stream()
                        .map((day) -> day.getKey() + "=\"" + day.getValue() + "\"").toList());
                out.append("# TYPE ").append(name).append("_daily gauge\n");
                out.append(name).append("_daily{graph=\"").append(graph).append("\",")
                        .append(String.join(",", slices)).append("} 1\n");
            }
        }

        Map<String, LatencySnapshot> latencies = new TreeMap<>(snapshot.latencies());
        for (Map.Entry<String, LatencySnapshot> entry : latencies.entrySet()) {
            String base = "phoenixgraph_" + metricName(entry.getKey());
            LatencySnapshot latency = entry.getValue();
            summary(out, base + "_count", "gauge", graph, latency.count());
            summary(out, base + "_mean_millis", "gauge", graph, latency.meanMillis());
            summary(out, base + "_p50_millis", "gauge", graph, latency.p50Millis());
            summary(out, base + "_p95_millis", "gauge", graph, latency.p95Millis());
            summary(out, base + "_p99_millis", "gauge", graph, latency.p99Millis());
            summary(out, base + "_max_millis", "gauge", graph, latency.maxMillis());
        }
        return out.toString();
    }

    /**
     * The instance's own numbers: the request gate's counters, and how much heap the JVM is using.
     *
     * <p>The heap is read at scrape time rather than counted, because it is a *gauge*: a value that goes down is
     * not a counter that lost data, and a capacity plan needs to see it move with the load (docs/CAPACITY.md).
     */
    public static String ofInstance(Map<String, Long> counters) {
        StringBuilder out = new StringBuilder();
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        out.append("# HELP phoenixgraph_jvm_heap_used_bytes Heap in use by this instance (gauge, read at scrape).\n");
        out.append("# TYPE phoenixgraph_jvm_heap_used_bytes gauge\n");
        out.append("phoenixgraph_jvm_heap_used_bytes ").append(used).append('\n');
        out.append("# TYPE phoenixgraph_jvm_heap_max_bytes gauge\n");
        out.append("phoenixgraph_jvm_heap_max_bytes ").append(runtime.maxMemory()).append('\n');
        for (Map.Entry<String, Long> entry : new TreeMap<>(counters).entrySet()) {
            String name = "phoenixgraph_" + metricName(entry.getKey()) + "_total";
            out.append("# TYPE ").append(name).append(" counter\n");
            out.append(name).append(' ').append(entry.getValue()).append('\n');
        }
        return out.toString();
    }

    private static void summary(StringBuilder out, String name, String type, String graph, Number value) {
        out.append("# TYPE ").append(name).append(' ').append(type).append('\n');
        out.append(name).append("{graph=\"").append(graph).append("\"} ").append(number(value)).append('\n');
    }

    private static String number(Number value) {
        if (value instanceof Double || value instanceof Float) {
            double asDouble = value.doubleValue();
            return Double.isFinite(asDouble) ? Double.toString(asDouble) : "0";
        }
        return value.toString();
    }

    /** "storage.vertexRead" -&gt; "storage_vertexread", which is what a Prometheus name allows. */
    static String metricName(String raw) {
        return raw.toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
