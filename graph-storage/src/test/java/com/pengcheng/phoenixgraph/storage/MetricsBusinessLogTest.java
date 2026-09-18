package com.pengcheng.phoenixgraph.storage;

import com.pengcheng.phoenixgraph.metrics.GraphMetrics;
import com.pengcheng.phoenixgraph.metrics.Metrics;
import com.pengcheng.phoenixgraph.metrics.MetricsSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The stores already log every write, so the metrics can be read out of that log: what
 * went through, what was rejected as stale, and what failed after its retries.
 */
class MetricsBusinessLogTest {

    private final List<BusinessLogEntry> recorded = new ArrayList<>();
    private final BusinessLog delegate = recorded::add;
    private final GraphMetrics metrics = new GraphMetrics("payment");

    private MetricsBusinessLog log() {
        return new MetricsBusinessLog(delegate, metrics);
    }

    @Test
    void aSuccessfulWriteCountsOnceAndReachesTheDelegate() {
        log().record(BusinessLogEntry.success("upsertVertex", "1000", Map.of("cnCode", "+86")));

        MetricsSnapshot snapshot = metrics.snapshot();
        assertEquals(1, snapshot.value(Metrics.VERTEX_WRITE));
        assertEquals(1, snapshot.value(Metrics.items(Metrics.VERTEX_WRITE)));
        assertEquals(0, snapshot.value(Metrics.EDGE_WRITE));
        assertEquals(1, recorded.size(), "the real log still sees the entry");
    }

    @Test
    void edgeWritesAreCountedAsEdges() {
        log().record(BusinessLogEntry.success("upsertEdges", "1000", Map.of()));
        log().record(BusinessLogEntry.success("upsertEdges", "1001", Map.of()));

        MetricsSnapshot snapshot = metrics.snapshot();
        assertEquals(2, snapshot.value(Metrics.EDGE_WRITE));
        assertEquals(0, snapshot.value(Metrics.VERTEX_WRITE));
        assertEquals(2, snapshot.today(null).get(Metrics.EDGE_WRITE));
    }

    @Test
    void aStaleVersionIsCountedAsStaleNotAsAFailure() {
        log().record(BusinessLogEntry.failure("updateVertexProperty", "1000", Map.of("stale", true)));
        log().record(BusinessLogEntry.failure("upsertEdge", "e1", Map.of("conflict", true)));

        MetricsSnapshot snapshot = metrics.snapshot();
        assertEquals(1, snapshot.value(Metrics.VERTEX_WRITE_STALE));
        assertEquals(1, snapshot.value(Metrics.EDGE_WRITE_STALE));
        assertEquals(0, snapshot.value(Metrics.failed(Metrics.VERTEX_WRITE)));
        assertEquals(0, snapshot.value(Metrics.failed(Metrics.EDGE_WRITE)));
        assertEquals(1, snapshot.value(Metrics.VERTEX_WRITE), "a rejected write is still a write attempt");
    }

    @Test
    void aFailureAfterTheRetriesIsCountedAsFailed() {
        log().record(BusinessLogEntry.failure("upsertVertices", "batch:3", Map.of("error", "timeout")));

        MetricsSnapshot snapshot = metrics.snapshot();
        assertEquals(1, snapshot.value(Metrics.VERTEX_WRITE));
        assertEquals(1, snapshot.value(Metrics.failed(Metrics.VERTEX_WRITE)));
        assertEquals(0, snapshot.value(Metrics.VERTEX_WRITE_STALE));
    }

    @Test
    void anUnwiredSinkRecordsNothingButStillLogs() {
        new MetricsBusinessLog(delegate, null).record(BusinessLogEntry.success("upsertVertex", "1", Map.of()));

        assertEquals(1, recorded.size());
        assertEquals(0, metrics.snapshot().value(Metrics.VERTEX_WRITE));
        assertEquals("upsertVertex", recorded.get(0).operation());
    }
}
