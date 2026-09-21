package com.pengcheng.phoenixgraph.storage.aerospike;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The near-line layout: the pre-partitioning of a day from the vertex's monthly volume, the
 * stable partition an edge id lands in, the {@code _NL_Index_ / _NL_} keys, and the 7-day TTL.
 */
class AerospikeNearlineTest {

    @Test
    void thePartitionCountComesFromTheMonthlyVolume() {
        // the spec's example: 1 亿 a month / 30 / 4000 is about 833, which it rounds to "say 1000"
        assertEquals(834, AerospikeNearline.partitionCount(100_000_000L));
        // no volume (or a tiny vertex) still gets one KV
        assertEquals(1, AerospikeNearline.partitionCount(0));
        assertEquals(1, AerospikeNearline.partitionCount(10));
        // a very hot vertex is clamped to 1000, however big its month
        assertEquals(1000, AerospikeNearline.partitionCount(1_000_000_000L));
        // 4000 a day exactly is one KV
        assertEquals(1, AerospikeNearline.partitionCount(4000L * 30));
    }

    @Test
    void anEdgeAlwaysLandsInTheSamePartitionForAFixedCount() {
        int partitions = 64;
        int first = AerospikeNearline.partitionOf("hub_pay_1", partitions);
        for (int i = 0; i < 100; i++) {
            assertEquals(first, AerospikeNearline.partitionOf("hub_pay_1", partitions),
                    "the partition of an edge id is stable");
        }
        assertTrue(first >= 0 && first < partitions);
        // every partition is reachable across many ids
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            seen.add(AerospikeNearline.partitionOf("edge_" + i, partitions));
        }
        assertEquals(partitions, seen.size(), "hash spreads the edges over every partition");
    }

    @Test
    void theKeysAndSetsFollowTheSpec() {
        assertEquals("1_NL_Index_1_2_20250601",
                AerospikeNearline.indexKey("1", 1, 2, "20250601"));
        assertEquals("1_NL_1_2_20250601_999",
                AerospikeNearline.dataKey("1", 1, 2, "20250601", 999));
        assertEquals("s_edge_nearline", AerospikeNearline.dataSet("s"));
        assertEquals("s_edge_nearline_index", AerospikeNearline.indexSet("s"));
        assertEquals(7 * 24 * 60 * 60, AerospikeNearline.TTL_SECONDS);
        assertEquals("e", AerospikeNearline.EDGES_BIN);
        assertEquals("0", AerospikeNearline.partitionBin(0));
        assertEquals("999", AerospikeNearline.partitionBin(999));
    }

    @Test
    void theDayKeyIsTheUtcDate() {
        assertEquals("20250601", AerospikeNearline.dayOf(
                java.time.LocalDate.of(2025, 6, 1).atStartOfDay(java.time.ZoneOffset.UTC)
                        .toInstant().toEpochMilli()));
    }
}
