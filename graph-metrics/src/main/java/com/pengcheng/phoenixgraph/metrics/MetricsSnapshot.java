package com.pengcheng.phoenixgraph.metrics;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * An immutable picture of a graph's system metrics.
 *
 * <p>Counters are kept twice: {@code counters} holds the total since the graph was
 * first used and {@code days} holds a per-day split, which is what a restart needs to
 * keep reporting today's numbers. The convenience accessors ({@link #today()},
 * {@link #value(String)}, {@link #todayValue(String)}) keep callers from rummaging in
 * the maps.
 *
 * @param graph        the graph these metrics belong to
 * @param updatedAt    when the snapshot was taken
 * @param counters     metric name to total value
 * @param days         metric name to (day to value)
 * @param latencies    latency metric name to its histogram
 */
public record MetricsSnapshot(String graph, Instant updatedAt, Map<String, Long> counters,
                              Map<String, Map<String, Long>> days,
                              Map<String, LatencySnapshot> latencies) {

    public MetricsSnapshot {
        counters = counters == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(withTotalsFromDays(counters, days)));
        days = days == null ? Map.of() : Collections.unmodifiableMap(freezeDays(days));
        latencies = latencies == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(latencies));
    }

    /** A snapshot that carries daily slices but no total still reports a total. */
    private static Map<String, Long> withTotalsFromDays(Map<String, Long> counters, Map<String, Map<String, Long>> days) {
        if (days == null || days.isEmpty()) {
            return counters;
        }
        Map<String, Long> complete = new TreeMap<>(counters);
        days.forEach((metric, byDay) -> {
            if (!complete.containsKey(metric) && byDay != null) {
                complete.put(metric, byDay.values().stream().mapToLong(Long::longValue).sum());
            }
        });
        return complete;
    }

    private static Map<String, Map<String, Long>> freezeDays(Map<String, Map<String, Long>> days) {
        Map<String, Map<String, Long>> copy = new TreeMap<>();
        days.forEach((metric, byDay) -> copy.put(metric, Collections.unmodifiableMap(new TreeMap<>(byDay))));
        return copy;
    }

    /** An empty snapshot, for a graph that never recorded anything. */
    public static MetricsSnapshot empty(String graph) {
        return new MetricsSnapshot(graph, Instant.now(), Map.of(), Map.of(), Map.of());
    }

    /** The total of a counter (0 when it was never recorded). */
    public long value(String metric) {
        return counters.getOrDefault(metric, 0L);
    }

    /** Today's slice of a counter, in local time. */
    public long todayValue(String metric) {
        return todayValue(metric, LocalDate.now().toString());
    }

    /** A given day's slice of a counter. */
    public long todayValue(String metric, String day) {
        Map<String, Long> byDay = days.get(metric);
        return byDay == null ? 0L : byDay.getOrDefault(day, 0L);
    }

    /** Every counter that has a non-zero value today, with the given name prefix. */
    public Map<String, Long> today(String prefix) {
        String day = LocalDate.now().toString();
        Map<String, Long> out = new LinkedHashMap<>();
        new TreeMap<>(counters).keySet().stream()
                .filter(metric -> prefix == null || metric.startsWith(prefix))
                .forEach(metric -> {
                    long value = todayValue(metric, day);
                    if (value != 0) {
                        out.put(metric, value);
                    }
                });
        return out;
    }

    /** Every counter with the given name prefix. */
    public Map<String, Long> matching(String prefix) {
        Map<String, Long> out = new LinkedHashMap<>();
        new TreeMap<>(counters).forEach((metric, value) -> {
            if (prefix == null || metric.startsWith(prefix)) {
                out.put(metric, value);
            }
        });
        return out;
    }

    /** A latency histogram, or an empty one when nothing was recorded. */
    public LatencySnapshot latency(String metric) {
        return latencies.getOrDefault(metric, new LatencySnapshot(0, 0, 0, 0, 0, 0, 0, 0, List.of()));
    }

    /** The aggregate picture: totals, today's slice and the latency summaries. */
    public Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("graph", graph);
        out.put("updatedAt", updatedAt.toString());
        out.put("counters", counters);
        out.put("today", today(null));
        Map<String, Object> latencySummary = new LinkedHashMap<>();
        latencies.forEach((metric, value) -> latencySummary.put(metric, value.summary()));
        out.put("latencies", latencySummary);
        return out;
    }

    /** The days a metric was recorded on, oldest first (for charts). */
    public List<String> daysOf(String metric) {
        Map<String, Long> byDay = days.get(metric);
        return byDay == null ? List.of() : new ArrayList<>(byDay.keySet());
    }
}
