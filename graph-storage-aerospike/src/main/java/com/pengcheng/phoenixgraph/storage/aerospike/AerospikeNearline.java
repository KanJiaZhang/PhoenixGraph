package com.pengcheng.phoenixgraph.storage.aerospike;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * The layout of the near-line tier of the Aerospike-Edge scheme.
 *
 * <p>A big vertex's edges are spread over three tiers: <b>online</b> (the newest few live in the
 * inline {@code ve_<schema>_<dir>} bin, bounded by the schema's {@code maxInDegree} /
 * {@code maxOutDegree}), <b>near-line</b> (the last 24 hours, in their own key-values that expire
 * after 7 days) and <b>cold</b> (everything older, in the aggregated {@code Edge_Out} /
 * {@code Edge_In} tables written by the offline job).
 *
 * <p>The near-line tier is pre-partitioned by the vertex's volume, so a vertex with a stable
 * monthly volume is spread over a fixed number of key-values that neither grow nor shrink day to
 * day. The layout mirrors the user's spec:
 *
 * <pre>
 * index KV   {@code {vertexId}_NL_Index_{edgeSchemaId}_{dir}_{yyyyMMdd}}   bins 0..n-1 hold a data-key each, bin n is the count
 * data KV    {@code {vertexId}_NL_{edgeSchemaId}_{dir}_{yyyyMMdd}_{i}}      bin {@code e} = a CDT map edgeId -> serialized edge
 * </pre>
 *
 * <p>The edge class and direction are part of the key for the same reason the cold tables are
 * split per class and direction: the edges of one vertex are not one list, they are one list per
 * (edge class, direction), and a read asks for one of those. (The spec's {@code 1_NL_Index_Date}
 * generalises the vertex id {@code 1} to that triple.)
 *
 * <p>How many key-values a day is cut into comes from the monthly volume: {@code ceil(monthlyVolume
 * / 30 / EDGES_PER_KV)} (1 亿 / 30 / 4000 is about 833, which the spec rounds to "say 1000"),
 * capped at {@value #MAX_PARTITIONS} because the index is one record whose bins address the
 * partitions. An edge lands in the partition of {@code hash(edgeId) % partitionCount}.
 */
public final class AerospikeNearline {

    /** How many edges one near-line data KV holds. */
    public static final int EDGES_PER_KV = 4_000;

    /** How many partitions the index record can address (one bin per partition). */
    public static final int MAX_PARTITIONS = 1_000;

    /** How long a near-line KV stays before Aerospike expires it (the spec's "7 days is enough"). */
    public static final int TTL_SECONDS = 7 * 24 * 60 * 60;

    /** The data KV bin holding the edges (same name and shape as a cold level-0 chunk). */
    public static final String EDGES_BIN = AerospikeEdgeChunks.EDGES_BIN;

    /** The index bin holding how many partitions a day is cut into. */
    public static final String PARTITION_COUNT_BIN = "n";

    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private AerospikeNearline() {
    }

    /** The day (UTC {@code yyyyMMdd}) an epoch-millis instant falls on. */
    public static String dayOf(long epochMillis) {
        return DAY.format(Instant.ofEpochMilli(epochMillis));
    }

    // --- sets ---

    /** The set holding the near-line data KVs of a graph. */
    public static String dataSet(String baseSet) {
        return baseSet + "_edge_nearline";
    }

    /** The set holding the near-line index KVs of a graph. */
    public static String indexSet(String baseSet) {
        return baseSet + "_edge_nearline_index";
    }

    // --- keys ---

    /** The key of the index KV of one vertex, edge class, direction and day. */
    public static String indexKey(Object vertexId, int edgeSchemaId, int directionCode, String day) {
        return vertexId + "_NL_Index_" + edgeSchemaId + "_" + directionCode + "_" + day;
    }

    /** The key of one near-line data KV (one partition of one vertex, class, direction and day). */
    public static String dataKey(Object vertexId, int edgeSchemaId, int directionCode, String day,
                                 int partition) {
        return vertexId + "_NL_" + edgeSchemaId + "_" + directionCode + "_" + day + "_" + partition;
    }

    /** A partition bin name of the index (bins are addressed 0..n-1). */
    public static String partitionBin(int partition) {
        return String.valueOf(partition);
    }

    /**
     * How many KVs a vertex's near-line day is cut into, from its recent monthly volume
     * ({@code ceil(monthlyVolume / 30 / EDGES_PER_KV)}), clamped to {@code [1, MAX_PARTITIONS]}.
     */
    public static int partitionCount(long monthlyVolume) {
        if (monthlyVolume <= 0) {
            return 1;
        }
        long needed = (monthlyVolume / 30 + EDGES_PER_KV - 1) / EDGES_PER_KV;
        return (int) Math.min(MAX_PARTITIONS, Math.max(1, needed));
    }

    /** The partition an edge id lands in (stable while the partition count is stable). */
    public static int partitionOf(Object edgeId, int partitionCount) {
        int count = Math.max(1, partitionCount);
        return Math.floorMod(String.valueOf(edgeId).hashCode(), count);
    }
}
