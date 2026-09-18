package com.pengcheng.phoenixgraph.metrics;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsRegistryTest {

    @Test
    void flushingSavesAndTheNextProcessPicksTheNumbersUp() {
        InMemoryMetricsStore store = new InMemoryMetricsStore();
        try (MetricsRegistry first = new MetricsRegistry(store, Duration.ZERO)) {
            first.forGraph("payment").increment(Metrics.VERTEX_WRITE, 4);
            first.forGraph("payment").recordLatency(Metrics.QUERY_LATENCY, 3_000_000L);
            first.flush("payment");
        }

        assertNotNull(store.load("payment"), "the snapshot is in the store");

        try (MetricsRegistry second = new MetricsRegistry(store, Duration.ZERO)) {
            MetricsSnapshot restored = second.snapshot("payment");
            assertEquals(4, restored.value(Metrics.VERTEX_WRITE));
            assertEquals(1, restored.latency(Metrics.QUERY_LATENCY).count());

            // and counting goes on from there
            second.forGraph("payment").increment(Metrics.VERTEX_WRITE);
            assertEquals(5, second.snapshot("payment").value(Metrics.VERTEX_WRITE));
        }
    }

    @Test
    void aGraphCanPersistThroughItsOwnStore() {
        InMemoryMetricsStore global = new InMemoryMetricsStore();
        InMemoryMetricsStore own = new InMemoryMetricsStore();
        try (MetricsRegistry registry = new MetricsRegistry(global, Duration.ZERO)) {
            registry.bind("rocksdb-graph", own);
            registry.forGraph("rocksdb-graph").increment(Metrics.EDGE_READ, 2);
            registry.forGraph("aerospike-graph").increment(Metrics.EDGE_READ, 9);
            registry.flushAll();

            assertEquals(2, own.load("rocksdb-graph").value(Metrics.EDGE_READ));
            assertNull(own.load("aerospike-graph"), "the other graph does not use this store");
            assertEquals(9, global.load("aerospike-graph").value(Metrics.EDGE_READ));
            assertEquals(2, registry.storeOf("rocksdb-graph") == own ? 2 : 0);
            assertTrue(registry.graphs().containsAll(java.util.Set.of("rocksdb-graph", "aerospike-graph")));
        }
    }

    @Test
    void resetForgetsBothTheCountersAndTheStoredSnapshot() {
        InMemoryMetricsStore store = new InMemoryMetricsStore();
        try (MetricsRegistry registry = new MetricsRegistry(store, Duration.ZERO)) {
            registry.forGraph("payment").increment(Metrics.QUERY_ITEMS, 3);
            registry.flush("payment");
            assertEquals(3, store.load("payment").value(Metrics.QUERY_ITEMS));

            registry.reset("payment");
            assertEquals(0, registry.snapshot("payment").value(Metrics.QUERY_ITEMS));
            assertNull(store.load("payment"));
            assertFalse(store.graphs().contains("payment"));
        }
    }

    @Test
    void bindingAStoreAfterTheGraphWasUsedStillRestoresItsSnapshot() {
        InMemoryMetricsStore first = new InMemoryMetricsStore();
        InMemoryMetricsStore own = new InMemoryMetricsStore();
        // a snapshot that was saved by an earlier run
        GraphMetrics earlier = new GraphMetrics("payment");
        earlier.increment(Metrics.EDGE_WRITE, 42);
        own.save(earlier.snapshot());

        try (MetricsRegistry registry = new MetricsRegistry(first, Duration.ZERO)) {
            // the stores ask for the metrics before the graph's own store is known
            registry.forGraph("payment").increment(Metrics.VERTEX_READ, 3);
            registry.bind("payment", own);

            MetricsSnapshot snapshot = registry.snapshot("payment");
            assertEquals(42, snapshot.value(Metrics.EDGE_WRITE), "the earlier run's counters came back");
            assertEquals(3, snapshot.value(Metrics.VERTEX_READ), "and the live ones are still there");
        }
    }

    @Test
    void resettingAGraphKeepsTheSinksThatAlreadyPointAtIt() {
        try (MetricsRegistry registry = new MetricsRegistry(null, Duration.ZERO)) {
            GraphMetrics takenBeforeReset = registry.forGraph("payment");
            takenBeforeReset.increment(Metrics.VERTEX_READ, 5);
            registry.reset("payment");

            // a store that captured the instance before the reset must still be counted
            takenBeforeReset.increment(Metrics.VERTEX_READ, 2);
            assertEquals(2, registry.snapshot("payment").value(Metrics.VERTEX_READ),
                    "the reset cleared the counters instead of orphaning the instance");
        }
    }

    @Test
    void everyGraphAppearsInTheAggregateView() {
        try (MetricsRegistry registry = new MetricsRegistry(null, Duration.ZERO)) {
            registry.forGraph("a").increment(Metrics.EDGE_EVICTED);
            registry.forGraph("b").increment(Metrics.EDGE_EVICTED, 2);
            var all = registry.snapshotAll();
            assertEquals(2, all.size());
            assertEquals(1, all.get("a").value(Metrics.EDGE_EVICTED));
            assertEquals(2, all.get("b").today(null).get(Metrics.EDGE_EVICTED));
        }
    }
}
