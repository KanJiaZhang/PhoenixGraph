package com.pengcheng.phoenixgraph.http.controller;

import com.pengcheng.phoenixgraph.http.dto.MetricsView;
import com.pengcheng.phoenixgraph.http.service.PrometheusFormat;
import com.pengcheng.phoenixgraph.http.service.RequestLimits;
import com.pengcheng.phoenixgraph.metrics.MetricsRegistry;
import com.pengcheng.phoenixgraph.metrics.MetricsSnapshot;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serves the system metrics a graph collects: storage traffic, write quality and query
 * load with the latency percentiles.
 *
 * <p>{@code GET /api/v1/metrics} answers with every graph that has recorded something,
 * {@code GET /api/v1/metrics/{graph}} with one graph in the grouped shape the dashboard
 * reads. {@code POST .../flush} writes the numbers through to storage (the timer does it
 * anyway) and {@code POST .../reset} starts a graph's counters over.
 *
 * <p>The same numbers are served in <b>Prometheus text format</b> at {@code /prometheus} (every graph, plus
 * the request gate's counters) and {@code /{graph}/prometheus}, so a monitoring system and the dashboard
 * read one source of truth instead of two measurements of it.
 */
@RestController
@RequestMapping("/api/v1/metrics")
public class MetricsController {

    private final MetricsRegistry metrics;
    private final RequestLimits limits;

    public MetricsController(MetricsRegistry metrics, RequestLimits limits) {
        this.metrics = metrics;
        this.limits = limits;
    }

    /** Every graph with metrics, in the grouped view, plus the flat counter dump. */
    @GetMapping
    public Map<String, Object> all() {
        Map<String, MetricsSnapshot> snapshots = metrics.snapshotAll();
        List<MetricsView> views = snapshots.values().stream().map(MetricsView::of).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("graphs", views);
        Map<String, Object> counters = new LinkedHashMap<>();
        snapshots.forEach((graph, snapshot) -> counters.put(graph, snapshot.counters()));
        out.put("counters", counters);
        return out;
    }

    /** One graph in the grouped view; the flat counters come along for the table view. */
    @GetMapping("/{graph}")
    public ResponseEntity<Map<String, Object>> one(@PathVariable("graph") String graph) {
        if (!metrics.graphs().contains(graph) && metrics.storeOf(graph).load(graph) == null) {
            return ResponseEntity.notFound().build();
        }
        MetricsView view = MetricsView.of(metrics.snapshot(graph));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("graph", view.graph());
        out.put("updatedAt", view.updatedAt());
        out.put("storage", view.storage());
        out.put("quality", view.quality());
        out.put("queries", view.queries());
        out.put("counters", view.counters());
        return ResponseEntity.ok(out);
    }

    /** Writes a graph's numbers through to storage now instead of waiting for the timer. */
    @PostMapping("/{graph}/flush")
    public Map<String, Object> flush(@PathVariable("graph") String graph) {
        metrics.flush(graph);
        return Map.of("flushed", graph, "counters", metrics.snapshot(graph).counters().size());
    }

    /** Clears a graph's counters (and its stored snapshot). */
    @PostMapping("/{graph}/reset")
    public Map<String, Object> reset(@PathVariable("graph") String graph) {
        metrics.reset(graph);
        return Map.of("reset", graph);
    }

    /**
     * Every graph in Prometheus text exposition format, plus the request gate's own counters.
     *
     * <p>This is the endpoint a scraper uses; the JSON endpoints above stay for the dashboard. The gate's
     * counters are here rather than in a graph's snapshot because admission happens before a graph is known,
     * and "how many requests were refused" is exactly the number an alert wants.
     */
    @GetMapping(value = "/prometheus", produces = MediaType.TEXT_PLAIN_VALUE)
    public String prometheus() {
        StringBuilder out = new StringBuilder();
        for (MetricsSnapshot snapshot : metrics.snapshotAll().values()) {
            out.append(PrometheusFormat.of(snapshot));
        }
        out.append(PrometheusFormat.ofInstance(limits.counters()));
        return out.toString();
    }

    /** One graph in Prometheus text exposition format. */
    @GetMapping(value = "/{graph}/prometheus", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> prometheusOne(@PathVariable("graph") String graph) {
        if (!metrics.graphs().contains(graph) && metrics.storeOf(graph).load(graph) == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(PrometheusFormat.of(metrics.snapshot(graph)));
    }
}
