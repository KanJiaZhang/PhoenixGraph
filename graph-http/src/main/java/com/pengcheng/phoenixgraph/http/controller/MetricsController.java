package com.pengcheng.phoenixgraph.http.controller;

import com.pengcheng.phoenixgraph.http.dto.MetricsView;
import com.pengcheng.phoenixgraph.metrics.MetricsRegistry;
import com.pengcheng.phoenixgraph.metrics.MetricsSnapshot;
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
 */
@RestController
@RequestMapping("/api/v1/metrics")
public class MetricsController {

    private final MetricsRegistry metrics;

    public MetricsController(MetricsRegistry metrics) {
        this.metrics = metrics;
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
}
