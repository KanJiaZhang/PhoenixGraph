package com.pengcheng.phoenixgraph.http.service;

import com.pengcheng.phoenixgraph.metrics.LatencySnapshot;
import com.pengcheng.phoenixgraph.metrics.MetricsSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The exposition format, checked as a format: what a scraper parses is names, labels and numbers, so the
 * test asserts those - not that some string contains some other string.
 */
class PrometheusFormatTest {

    private static MetricsSnapshot snapshot() {
        Map<String, Long> counters = new LinkedHashMap<>();
        counters.put("storage.vertexRead", 1200L);
        counters.put("storage.edge-read", 34L);
        Map<String, Map<String, Long>> days = Map.of("storage.vertexRead", Map.of("2026-10-13", 12L));
        Map<String, LatencySnapshot> latencies = Map.of("query.latency",
                new LatencySnapshot(10, 5_000_000, 900_000, 0.5, 0.4, 0.8, 0.9, 0.9, List.of()));
        return new MetricsSnapshot("payment-graph", Instant.parse("2026-10-13T05:00:00Z"), counters, days, latencies);
    }

    @Test
    void countersBecomePrometheusNamesWithALabelPerGraph() {
        String text = PrometheusFormat.of(snapshot());

        assertTrue(text.contains("phoenixgraph_storage_vertexread_total{graph=\"payment-graph\"} 1200"), text);
        assertTrue(text.contains("phoenixgraph_storage_edge_read_total{graph=\"payment-graph\"} 34"), text);
        assertTrue(text.contains("# TYPE phoenixgraph_storage_vertexread_total counter"));
    }

    @Test
    void dailySlicesComeAlongSoAnAlertCanSeeMovement() {
        String text = PrometheusFormat.of(snapshot());
        assertTrue(text.contains("phoenixgraph_storage_vertexread_total_daily{graph=\"payment-graph\",2026-10-13=\"12\"} 1"),
                text);
    }

    @Test
    void failureCountersAreExportedEvenWhileTheyAreZero() {
        String text = PrometheusFormat.of(snapshot());
        // a rule cannot fire on a metric that appears only after the first failure
        assertTrue(text.contains("phoenixgraph_storage_edge_read_failed_total{graph=\"payment-graph\"} 0"), text);
        assertTrue(text.contains("phoenixgraph_storage_vertex_read_failed_total{graph=\"payment-graph\"} 0"));
        assertTrue(text.contains("phoenixgraph_storage_kv_write_failed_total{graph=\"payment-graph\"} 0"));
    }

    @Test
    void latenciesAreExportedAsTheSummaryGaugesTheEngineComputes() {
        String text = PrometheusFormat.of(snapshot());
        for (String suffix : new String[] {"_count", "_mean_millis", "_p50_millis", "_p95_millis", "_p99_millis", "_max_millis"}) {
            assertTrue(text.contains("phoenixgraph_query_latency" + suffix + "{graph=\"payment-graph\"}"),
                    suffix + " missing from\n" + text);
        }
        assertTrue(text.contains("phoenixgraph_query_latency_p99_millis{graph=\"payment-graph\"} 0.9"), text);
    }

    @Test
    void everyLineIsEitherACommentOrNameLabelsValue() {
        String text = PrometheusFormat.of(snapshot()) + PrometheusFormat.ofInstance(Map.of("requests.refused", 3L));
        int lines = 0;
        for (String line : text.split("\n")) {
            if (line.isBlank() || line.startsWith("#")) continue;
            lines += 1;
            int space = line.lastIndexOf(' ');
            assertTrue(space > 0, "no value: " + line);
            String name = line.substring(0, space);
            String value = line.substring(space + 1);
            // the value must parse as a number, and the name must be a metric name with an optional label block
            Double.parseDouble(value);
            int brace = name.indexOf('{');
            String bare = brace < 0 ? name : name.substring(0, brace);
            assertTrue(bare.matches("[a-zA-Z_:][a-zA-Z0-9_:]*"), "bad metric name: " + name);
            assertTrue(brace < 0 || (name.endsWith("}") && name.indexOf('}') > brace), "bad label block: " + name);
        }
        assertTrue(lines >= 10, "the exposition exported something: " + lines + " lines");
    }
    @Test
    void aGraphNameWithAQuoteCannotBreakOutOfTheLabel() {
        Map<String, Long> counters = Map.of("queries.total", 1L);
        MetricsSnapshot odd = new MetricsSnapshot("we\"ird", Instant.EPOCH, counters, Map.of(), Map.of());
        String text = PrometheusFormat.of(odd);
        assertTrue(text.contains("graph=\"we\\\"ird\""), text);
    }

    @Test
    void theGateCountersAreExposedWithoutAGraphLabel() {
        String text = PrometheusFormat.ofInstance(Map.of("requests.admitted", 7L, "requests.refused", 2L));
        assertTrue(text.contains("phoenixgraph_requests_admitted_total 7"), text);
        assertTrue(text.contains("phoenixgraph_requests_refused_total 2"), text);
    }

    @Test
    void metricNamesAreLowercasedAndPunctuationFree() {
        assertEquals("storage_vertexread", PrometheusFormat.metricName("storage.vertexRead"));
        assertEquals("edge_read", PrometheusFormat.metricName("edge-read"));
        assertEquals("a_b_c", PrometheusFormat.metricName("__a__b__c__"));
    }
}
