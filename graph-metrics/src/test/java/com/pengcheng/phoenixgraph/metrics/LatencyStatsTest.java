package com.pengcheng.phoenixgraph.metrics;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LatencyStatsTest {

    @Test
    void percentilesFollowTheRecordedDistribution() {
        LatencyStats stats = new LatencyStats();
        // 1..100 ms, one sample each: p50 ≈ 50 ms, p95 ≈ 95 ms, p99 ≈ 99 ms
        for (int millis = 1; millis <= 100; millis++) {
            stats.record(millis * 1_000_000L);
        }

        assertEquals(100, stats.count());
        assertEquals(50.5, stats.meanMillis(), 1.5);
        // the grid puts every sample within ±5 % of its bucket bounds
        assertEquals(50, stats.percentileMillis(0.5), 4);
        assertEquals(95, stats.percentileMillis(0.95), 5);
        assertEquals(99, stats.percentileMillis(0.99), 5);
        assertEquals(100, stats.maxMillis(), 0.001);
    }

    @Test
    void smallLatenciesStaySharpAndAnEmptyHistogramIsZero() {
        LatencyStats stats = new LatencyStats();
        assertEquals(0, stats.percentileMillis(0.95));
        assertEquals(0, stats.meanMillis());

        stats.record(120_000L);   // 0.12 ms
        stats.record(180_000L);   // 0.18 ms
        assertEquals(0.15, stats.meanMillis(), 1e-9);
        assertTrue(stats.percentileMillis(0.5) <= 0.2, "sub-millisecond latencies are not rounded to 0");
    }

    @Test
    void aHistogramSurvivesTheSnapshotRoundTrip() {
        LatencyStats stats = new LatencyStats();
        for (int i = 0; i < 10; i++) {
            stats.record(2_000_000L);
        }
        stats.record(500_000_000L); // 500 ms

        LatencySnapshot snapshot = LatencySnapshot.of(stats);
        assertEquals(11, snapshot.count());
        assertEquals(500, snapshot.maxMillis(), 1);
        assertEquals(47.3, snapshot.meanMillis(), 1);
        assertTrue(snapshot.p50Millis() <= 3, "the median is the 2 ms cluster");

        LatencyStats restored = snapshot.toStats();
        assertEquals(stats.count(), restored.count());
        assertEquals(snapshot.meanMillis(), restored.meanMillis(), 0.5);
        assertEquals(snapshot.p95Millis(), restored.percentileMillis(0.95), 1);
    }

    @Test
    void mergingAddsTheSamplesUp() {
        LatencyStats first = new LatencyStats();
        LatencyStats second = new LatencyStats();
        first.record(1_000_000L);
        second.record(3_000_000L);

        first.merge(second);
        assertEquals(2, first.count());
        assertEquals(2.0, first.meanMillis(), 1e-9);
        assertEquals(3.0, first.maxMillis(), 1e-9);
    }

    @Test
    void theSnapshotSummarisesItself() {
        LatencyStats stats = new LatencyStats();
        stats.record(1_500_000L);
        Map<String, Object> summary = LatencySnapshot.of(stats).summary();
        assertEquals(1L, summary.get("count"));
        assertEquals(1.5, (double) summary.get("meanMs"), 1e-9);
        assertEquals(1L, ((long[]) LatencySnapshot.of(stats).buckets().get(0))[1], "one sample in one bucket");
    }
}
