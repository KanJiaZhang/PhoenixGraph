package com.pengcheng.phoenixgraph.http.controller;

import com.pengcheng.phoenixgraph.http.dto.MetricsView;
import com.pengcheng.phoenixgraph.metrics.GraphMetrics;
import com.pengcheng.phoenixgraph.metrics.InMemoryMetricsStore;
import com.pengcheng.phoenixgraph.metrics.Metrics;
import com.pengcheng.phoenixgraph.metrics.MetricsRegistry;
import com.pengcheng.phoenixgraph.metrics.MetricsSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The metrics endpoint answers in the grouped shape a dashboard reads: storage traffic,
 * write quality, and the query load with its percentiles next to the counts.
 */
class MetricsControllerTest {

    private static MetricsRegistry registry() {
        MetricsRegistry registry = new MetricsRegistry(new InMemoryMetricsStore(), Duration.ZERO);
        GraphMetrics graph = registry.forGraph("payment-graph");
        graph.increment(Metrics.VERTEX_READ, 30);
        graph.increment(Metrics.items(Metrics.VERTEX_READ), 300);
        graph.increment(Metrics.EDGE_READ, 12);
        graph.increment(Metrics.EDGE_READ_FAILED, 2);
        graph.increment(Metrics.EDGE_EVICTED, 5);
        graph.increment(Metrics.EDGE_WRITE_STALE, 3);
        graph.increment(Metrics.queryCount("gremlin", Metrics.SOURCE_UI), 4);
        graph.increment(Metrics.queryCount("cypher", Metrics.SOURCE_HTTP));
        graph.increment(Metrics.QUERY_VERTEX_READ, 300);
        graph.increment(Metrics.QUERY_EDGE_READ, 90);
        graph.recordLatency(Metrics.queryLatency("gremlin"), 12_000_000L);
        graph.recordLatency(Metrics.queryLatency("gremlin"), 40_000_000L);
        return registry;
    }

    @Test
    void oneGraphComesBackGroupedWithTotalsAndToday() {
        Map<String, Object> body = new MetricsController(registry()).one("payment-graph").getBody();

        assertEquals("payment-graph", body.get("graph"));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Long>> storage = (Map<String, Map<String, Long>>) body.get("storage");
        assertEquals(30L, storage.get("vertexRead").get("total"));
        assertEquals(300L, storage.get("vertexRead").get("itemsTotal"));
        assertEquals(30L, storage.get("vertexRead").get("today"), "today's slice is served next to the total");
        assertEquals(2L, storage.get("edgeRead").get("failedTotal"));

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Long>> quality = (Map<String, Map<String, Long>>) body.get("quality");
        assertEquals(5L, quality.get("evicted").get("total"));
        assertEquals(3L, quality.get("staleEdgeWrites").get("total"));
    }

    @Test
    void theQuerySectionCarriesTheSourcesTheTranslationAndThePercentiles() {
        @SuppressWarnings("unchecked")
        Map<String, Object> queries = (Map<String, Object>)
                new MetricsController(registry()).one("payment-graph").getBody().get("queries");

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Long>> byLanguage = (Map<String, Map<String, Long>>) queries.get("byLanguageAndSource");
        assertEquals(4L, byLanguage.get("gremlin").get("ui"));
        assertEquals(1L, byLanguage.get("cypher").get("http"));

        @SuppressWarnings("unchecked")
        Map<String, Long> translated = (Map<String, Long>) queries.get("translated");
        assertEquals(300L, translated.get("vertexReads"));
        assertEquals(90L, translated.get("edgeReads"));

        @SuppressWarnings("unchecked")
        Map<String, Object> latency = (Map<String, Object>) queries.get("latency");
        assertEquals(2L, latency.get("count"));
        assertEquals(26.0, (double) latency.get("meanMs"), 1.0);
        assertTrue(((Number) latency.get("p95Ms")).doubleValue() >= 35,
                "the tail is reported, not just the mean");
    }

    @Test
    void everyGraphIsListedAndTheFlatCountersComeAlong() {
        Map<String, Object> body = new MetricsController(registry()).all();

        @SuppressWarnings("unchecked")
        List<MetricsView> graphs = (List<MetricsView>) body.get("graphs");
        assertEquals(1, graphs.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> counters = (Map<String, Object>) body.get("counters");
        assertEquals(1, counters.size());
        assertFalse(((Map<?, ?>) counters.get("payment-graph")).isEmpty());
    }

    @Test
    void anUnknownGraphIsNotFound() {
        assertEquals(404, new MetricsController(registry()).one("nope").getStatusCode().value());
    }

    @Test
    void flushAndResetActOnTheRegistry() {
        MetricsRegistry registry = registry();
        MetricsController controller = new MetricsController(registry);

        assertEquals(Map.of("flushed", "payment-graph", "counters", registry.snapshot("payment-graph")
                .counters().size()), controller.flush("payment-graph"));
        assertEquals(Map.of("reset", "payment-graph"), controller.reset("payment-graph"));
        MetricsSnapshot after = registry.snapshot("payment-graph");
        assertEquals(0, after.value(Metrics.VERTEX_READ), "reset starts the counters over");
    }
}
