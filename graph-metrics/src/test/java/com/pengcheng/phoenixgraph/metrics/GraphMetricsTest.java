package com.pengcheng.phoenixgraph.metrics;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphMetricsTest {

    @Test
    void countersKeepATotalAndTodaysSlice() {
        GraphMetrics metrics = new GraphMetrics("payment");
        String day = metrics.currentDay();

        metrics.increment(Metrics.VERTEX_READ);
        metrics.increment(Metrics.VERTEX_READ);
        metrics.increment(Metrics.VERTEX_READ_ITEMS, 7);

        MetricsSnapshot snapshot = metrics.snapshot();
        assertEquals(2, snapshot.value(Metrics.VERTEX_READ));
        assertEquals(2, snapshot.todayValue(Metrics.VERTEX_READ, day));
        assertEquals(7, snapshot.value(Metrics.VERTEX_READ_ITEMS));
        assertEquals(LocalDate.now().toString(), day, "the day is the local date");
    }

    @Test
    void taggedCountersAreSlicedPerLanguageAndSource() {
        GraphMetrics metrics = new GraphMetrics("payment");
        metrics.increment(Metrics.queryCount("gremlin", Metrics.SOURCE_UI));
        metrics.increment(Metrics.queryCount("gremlin", Metrics.SOURCE_UI));
        metrics.increment(Metrics.queryCount("cypher", Metrics.SOURCE_HTTP));

        MetricsSnapshot snapshot = metrics.snapshot();
        assertEquals(2, snapshot.value("query.count{language=gremlin,source=ui}"));
        assertEquals(1, snapshot.value("query.count{language=cypher,source=http}"));
        assertEquals(3, snapshot.matching("query.count").values().stream().mapToLong(Long::longValue).sum());
        assertEquals(2, snapshot.today(null).get("query.count{language=gremlin,source=ui}"));
    }

    @Test
    void aSnapshotRestoresIntoFreshMetrics() {
        GraphMetrics first = new GraphMetrics("payment");
        first.increment(Metrics.EDGE_WRITE, 5);
        first.increment(Metrics.VERTEX_WRITE_STALE);
        first.recordLatency(Metrics.QUERY_LATENCY, 4_000_000L); // 4 ms

        GraphMetrics second = new GraphMetrics("payment");
        second.merge(first.snapshot());

        MetricsSnapshot snapshot = second.snapshot();
        assertEquals(5, snapshot.value(Metrics.EDGE_WRITE));
        assertEquals(1, snapshot.value(Metrics.VERTEX_WRITE_STALE));
        assertEquals(1, snapshot.latency(Metrics.QUERY_LATENCY).count());
        // the cumulative view is what a snapshot carries; the windowed percentile (the one alerts read) is
        // deliberately empty for restored history - see LatencyStats.merge
        LatencyStats restored = snapshot.latency(Metrics.QUERY_LATENCY).toStats();
        assertTrue(restored.cumulativePercentileMillis(0.95) >= 3.9, "≈4 ms survived the round trip");
        assertEquals(0, restored.percentileMillis(0.95), 1e-9, "and it is not reported as current latency");
        assertEquals(0, snapshot.latency("never.recorded").count(), "unknown histograms read as empty");
    }

    @Test
    void dailySlicesCanBePrunedWithoutLosingTotals() {
        GraphMetrics metrics = new GraphMetrics("payment");
        metrics.increment(Metrics.VERTEX_WRITE, 3);
        MetricsSnapshot before = metrics.snapshot();
        assertFalse(before.days().isEmpty(), "today has a slice");

        // pretend the slices are ancient: rewriting them through a merge lets us prune them
        GraphMetrics aged = new GraphMetrics("payment");
        aged.merge(new MetricsSnapshot("payment", java.time.Instant.now(), java.util.Map.of(),
                java.util.Map.of(Metrics.VERTEX_WRITE, java.util.Map.of("2001-01-01", 9L)), java.util.Map.of()));
        assertEquals(1, aged.retainDays(90), "the 2001 slice is dropped");

        MetricsSnapshot after = aged.snapshot();
        assertEquals(9, after.value(Metrics.VERTEX_WRITE), "the total stays");
        assertEquals(0, after.todayValue(Metrics.VERTEX_WRITE, "2001-01-01"));
    }

    @Test
    void theNoopSinkRecordsNothing() {
        GraphMetrics noop = GraphMetrics.noop();
        noop.increment(Metrics.VERTEX_READ, 5);
        noop.recordLatency(Metrics.QUERY_LATENCY, 1_000_000L);

        assertFalse(noop.recording());
        assertEquals(0, noop.snapshot().value(Metrics.VERTEX_READ));
        assertTrue(noop.snapshot().latencies().isEmpty());
    }

    @Test
    void metricNamesCarryOptionalTags() {
        assertEquals("storage.vertex.read", Metrics.tagged(Metrics.VERTEX_READ, "type", null));
        assertEquals("storage.vertex.read{type=Account}",
                Metrics.tagged(Metrics.VERTEX_READ, "type", "Account"));
        assertEquals("query.count", Metrics.baseName("query.count{a=b}"));
        assertTrue(Metrics.isLatency("query.latency{language=gremlin}"));
        assertFalse(Metrics.isLatency(Metrics.QUERY_COUNT));
        assertEquals(List.of(), new GraphMetrics("g").snapshot().daysOf("nope"));
    }
}
