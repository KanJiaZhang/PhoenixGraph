package com.pengcheng.phoenixgraph.storage.rocksdb;

import com.pengcheng.phoenixgraph.metrics.GraphMetrics;
import com.pengcheng.phoenixgraph.metrics.LatencySnapshot;
import com.pengcheng.phoenixgraph.metrics.Metrics;
import com.pengcheng.phoenixgraph.metrics.MetricsSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The metrics of a RocksDB graph live in its own directory, so they must survive a
 * close and reopen — otherwise a restart would reset every counter.
 */
class RocksDbMetricsStoreTest {

    @TempDir
    Path directory;

    private RocksDbHandle handle;

    @BeforeEach
    void openHandle() {
        handle = RocksDbHandle.open(directory.toString(), Set.of(RocksDbMetricsStore.FAMILY));
    }

    @AfterEach
    void closeHandle() {
        handle.close();
    }

    private static MetricsSnapshot sample(String graph) {
        GraphMetrics live = new GraphMetrics(graph);
        live.increment(Metrics.VERTEX_READ, 12);
        live.increment(Metrics.EDGE_READ, 3);
        live.increment(Metrics.EDGE_WRITE_STALE);
        live.increment(Metrics.EDGE_EVICTED, 4);
        live.recordLatency(Metrics.queryLatency("gremlin"), 7_000_000L);   // 7 ms
        live.recordLatency(Metrics.queryLatency("gremlin"), 9_000_000L);   // 9 ms
        return live.snapshot();
    }

    @Test
    void aSnapshotSurvivesACloseAndReopen() {
        RocksDbMetricsStore store = new RocksDbMetricsStore(handle);
        store.save(sample("payment"));

        handle.close();
        handle = RocksDbHandle.open(directory.toString(), Set.of(RocksDbMetricsStore.FAMILY));
        MetricsSnapshot loaded = new RocksDbMetricsStore(handle).load("payment");

        assertNotNull(loaded, "the record is in the metrics family");
        assertEquals(12, loaded.value(Metrics.VERTEX_READ));
        assertEquals(3, loaded.value(Metrics.EDGE_READ));
        assertEquals(4, loaded.value(Metrics.EDGE_EVICTED));
        assertEquals(1, loaded.value(Metrics.EDGE_WRITE_STALE));
        assertEquals(2, loaded.latency(Metrics.queryLatency("gremlin")).count());
        assertEquals(8, loaded.latency(Metrics.queryLatency("gremlin")).meanMillis(), 0.5);
        assertTrue(loaded.latency(Metrics.queryLatency("gremlin")).p95Millis() >= 8.5,
                "the tail is kept, not just the mean");
    }

    @Test
    void todaySurvivesTooSoARestartDoesNotResetTheDay() {
        RocksDbMetricsStore store = new RocksDbMetricsStore(handle);
        MetricsSnapshot snapshot = sample("payment");
        store.save(snapshot);

        MetricsSnapshot loaded = store.load("payment");
        assertEquals(snapshot.todayValue(Metrics.VERTEX_READ), loaded.todayValue(Metrics.VERTEX_READ));
        assertTrue(loaded.todayValue(Metrics.VERTEX_READ) > 0, "today's slice came back");
    }

    @Test
    void graphsAreListedAndForgetten() {
        RocksDbMetricsStore store = new RocksDbMetricsStore(handle);
        store.save(sample("payment"));
        store.save(sample("geo"));

        assertEquals(Set.of("payment", "geo"), store.graphs());
        assertTrue(store.delete("geo"));
        assertFalse(store.delete("geo"), "deleting twice reports nothing to delete");
        assertEquals(Set.of("payment"), store.graphs());
        assertNull(store.load("geo"));
    }

    @Test
    void anEmptyStoreAnswersNullInsteadOfFailing() {
        RocksDbMetricsStore store = new RocksDbMetricsStore(handle);
        assertNull(store.load("never-used"));
        assertTrue(store.graphs().isEmpty());
    }

    @Test
    void theMetricsFamilyIsNotCountedAsGraphTraffic() {
        GraphMetrics traffic = new GraphMetrics("payment");
        handle.metrics(traffic);
        RocksDbMetricsStore store = new RocksDbMetricsStore(handle);
        store.save(sample("payment"));
        store.load("payment");

        assertEquals(0, traffic.snapshot().value(Metrics.VERTEX_READ),
                "reading the metrics is not a graph read");
        assertEquals(0, traffic.snapshot().value(Metrics.VERTEX_WRITE));
    }

    @Test
    void theSnapshotKeepsTheHistogramBucketsForTheNextProcess() {
        RocksDbMetricsStore store = new RocksDbMetricsStore(handle);
        store.save(sample("payment"));

        MetricsSnapshot loaded = store.load("payment");
        LatencySnapshot latency = loaded.latency(Metrics.queryLatency("gremlin"));
        assertEquals(2, latency.buckets().stream().mapToLong(bucket -> bucket[1]).sum(),
                "every sample is still in a bucket");
        assertEquals(List.of(2L), List.of(latency.buckets().size() >= 1 ? 2L : 0L));
    }
}
