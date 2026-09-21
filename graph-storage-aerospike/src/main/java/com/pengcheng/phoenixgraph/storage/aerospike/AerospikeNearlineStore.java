package com.pengcheng.phoenixgraph.storage.aerospike;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Bin;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.aerospike.client.policy.BatchPolicy;
import com.aerospike.client.policy.WritePolicy;
import com.pengcheng.phoenixgraph.metrics.MetricsSink;
import com.pengcheng.phoenixgraph.metrics.MutableMetricsSink;
import com.pengcheng.phoenixgraph.storage.TimeRange;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The near-line tier of the Aerospike-Edge scheme: one index KV per (vertex, edge class,
 * direction, day) whose bins address the data KVs of that day, each data KV holding up to
 * {@link AerospikeNearline#EDGES_PER_KV} edges.
 *
 * <p>How many data KVs a day is cut into is pre-partitioned by the offline job from the vertex's
 * monthly volume ({@link AerospikeNearline#partitionCount}), so a stable volume means a stable
 * number of KVs. A new near-line edge is appended to the partition {@code hash(edgeId) %
 * partitionCount}; every near-line KV carries the spec's 7-day TTL, so old near-line data expires
 * on its own and the cold tables (written by the offline job) are the only long-term home.
 *
 * <p>The stored edge value is the same {@code [lastUpdatedTime, createdTime, peer, properties]}
 * shape the inline bins and the cold chunks use, so a merge of the three compares like with like.
 */
public class AerospikeNearlineStore {

    private final AerospikeClient client;
    /** The same client, with every round trip counted into {@link #metrics}. */
    private final AerospikeOperations db;
    /** Where this store records its traffic; the graph's metrics are wired in later. */
    private final MutableMetricsSink metrics = new MutableMetricsSink();
    private final String namespace;
    private final String baseSet;

    public AerospikeNearlineStore(AerospikeClient client, String namespace, String baseSet) {
        this.client = Objects.requireNonNull(client, "client");
        this.db = AerospikeOperations.metered(this.client, metrics);
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.baseSet = Objects.requireNonNull(baseSet, "baseSet");
    }

    private static WritePolicy ttlPolicy() {
        WritePolicy policy = new WritePolicy();
        policy.expiration = AerospikeNearline.TTL_SECONDS;
        return policy;
    }

    // --- writing ---

    /**
     * Pre-partitions a vertex's near-line day for one edge class and direction: writes the index
     * KV with {@code partitions} bins, each holding the key of one data KV. Returns the partition
     * count actually used (clamped to {@code [1, MAX_PARTITIONS]}).
     */
    public int prePartition(Object vertexId, int edgeSchemaId, int directionCode, String day,
                            int partitions) {
        int count = Math.min(AerospikeNearline.MAX_PARTITIONS, Math.max(1, partitions));
        List<Bin> bins = new ArrayList<>(count + 1);
        bins.add(new Bin(AerospikeNearline.PARTITION_COUNT_BIN, count));
        for (int i = 0; i < count; i++) {
            bins.add(new Bin(AerospikeNearline.partitionBin(i),
                    AerospikeNearline.dataKey(vertexId, edgeSchemaId, directionCode, day, i)));
        }
        db.put(ttlPolicy(), new Key(namespace, AerospikeNearline.indexSet(baseSet),
                AerospikeNearline.indexKey(vertexId, edgeSchemaId, directionCode, day)),
                bins.toArray(new Bin[0]));
        return count;
    }

    /**
     * Appends near-line edges to the vertex's day for one edge class and direction. The edges are
     * grouped by their partition and each partition's data KV is read and written once; a
     * partition's KV is created when it does not exist yet. Edges are keyed by edge id, so a
     * re-written edge replaces its older copy.
     *
     * @param edgeIdToValue one serialized edge value per edge id (the inline/cold shape)
     * @return how many data KVs were written
     */
    public int write(Object vertexId, int edgeSchemaId, int directionCode, long createdTime,
                     Map<String, List<Object>> edgeIdToValue) {
        Objects.requireNonNull(vertexId, "vertexId");
        if (edgeIdToValue == null || edgeIdToValue.isEmpty()) {
            return 0;
        }
        String day = AerospikeNearline.dayOf(createdTime);
        int partitions = partitions(vertexId, edgeSchemaId, directionCode, day);
        Map<Integer, Map<String, List<Object>>> byPartition = new LinkedHashMap<>();
        for (Map.Entry<String, List<Object>> edge : edgeIdToValue.entrySet()) {
            byPartition.computeIfAbsent(
                    AerospikeNearline.partitionOf(edge.getKey(), partitions), k -> new LinkedHashMap<>())
                    .put(edge.getKey(), edge.getValue());
        }
        int written = 0;
        for (Map.Entry<Integer, Map<String, List<Object>>> entry : byPartition.entrySet()) {
            append(vertexId, edgeSchemaId, directionCode, day, entry.getKey(), entry.getValue());
            written++;
        }
        return written;
    }

    private int partitions(Object vertexId, int edgeSchemaId, int directionCode, String day) {
        Record index = db.get(null, new Key(namespace, AerospikeNearline.indexSet(baseSet),
                AerospikeNearline.indexKey(vertexId, edgeSchemaId, directionCode, day)),
                AerospikeNearline.PARTITION_COUNT_BIN);
        if (index != null && index.bins != null) {
            Object raw = index.bins.get(AerospikeNearline.PARTITION_COUNT_BIN);
            if (raw instanceof Number number) {
                return Math.max(1, number.intValue());
            }
        }
        // no index yet: a single partition (the offline pre-partitioning replaces this later)
        return prePartition(vertexId, edgeSchemaId, directionCode, day, 1);
    }

    @SuppressWarnings("unchecked")
    private void append(Object vertexId, int edgeSchemaId, int directionCode, String day,
                        int partition, Map<String, List<Object>> added) {
        Key key = new Key(namespace, AerospikeNearline.dataSet(baseSet),
                AerospikeNearline.dataKey(vertexId, edgeSchemaId, directionCode, day, partition));
        Record record = db.get(null, key, AerospikeNearline.EDGES_BIN);
        Map<String, Object> edges = new LinkedHashMap<>();
        if (record != null && record.bins != null
                && record.bins.get(AerospikeNearline.EDGES_BIN) instanceof Map<?, ?> existing) {
            edges.putAll((Map<String, Object>) existing);
        }
        edges.putAll(added);
        db.put(ttlPolicy(), key, new Bin(AerospikeNearline.EDGES_BIN, edges));
    }

    // --- reading ---

    /** Whether the vertex has near-line data of this class and direction around {@code nowMillis}. */
    public boolean hasData(Object vertexId, int edgeSchemaId, int directionCode, long nowMillis) {
        String today = AerospikeNearline.dayOf(nowMillis);
        String yesterday = AerospikeNearline.dayOf(nowMillis - 24L * 60 * 60 * 1000);
        return indexExists(vertexId, edgeSchemaId, directionCode, today)
                || indexExists(vertexId, edgeSchemaId, directionCode, yesterday);
    }

    private boolean indexExists(Object vertexId, int edgeSchemaId, int directionCode, String day) {
        Record record = db.get(null, new Key(namespace, AerospikeNearline.indexSet(baseSet),
                AerospikeNearline.indexKey(vertexId, edgeSchemaId, directionCode, day)),
                AerospikeNearline.PARTITION_COUNT_BIN);
        return record != null;
    }

    /**
     * Reads the near-line edges of one vertex, edge class and direction inside a creation-time
     * window, merged by edge id.
     *
     * <p>The window's days are looked up, each day's index gives its data keys, and the data KVs
     * are read with one native batch get - so a day cut into 1000 partitions is 1000 records read
     * at once, not 1000 round trips.
     */
    public List<StoredEdgeValue> readWindow(Object vertexId, int edgeSchemaId, int directionCode,
                                            TimeRange range) {
        Objects.requireNonNull(vertexId, "vertexId");
        if (range == null || range.isUnbounded()) {
            return List.of();
        }
        long dayMillis = 24L * 60 * 60 * 1000;
        List<StoredEdgeValue> edges = new ArrayList<>();
        for (long day = range.fromTime(); day <= range.toTime(); day += dayMillis) {
            edges.addAll(readDay(vertexId, edgeSchemaId, directionCode, AerospikeNearline.dayOf(day)));
        }
        // the last partial day is already covered by the day it belongs to
        edges.removeIf(edge -> !range.contains(edge.createdTime()));
        return List.copyOf(edges);
    }

    private List<StoredEdgeValue> readDay(Object vertexId, int edgeSchemaId, int directionCode,
                                          String day) {
        Record index = db.get(null, new Key(namespace, AerospikeNearline.indexSet(baseSet),
                AerospikeNearline.indexKey(vertexId, edgeSchemaId, directionCode, day)));
        if (index == null || index.bins == null) {
            return List.of();
        }
        Object rawCount = index.bins.get(AerospikeNearline.PARTITION_COUNT_BIN);
        int partitions = rawCount instanceof Number number ? Math.max(1, number.intValue()) : 1;
        Key[] keys = new Key[partitions];
        for (int i = 0; i < partitions; i++) {
            keys[i] = new Key(namespace, AerospikeNearline.dataSet(baseSet),
                    AerospikeNearline.dataKey(vertexId, edgeSchemaId, directionCode, day, i));
        }
        Record[] records = db.get(new BatchPolicy(), keys, AerospikeNearline.EDGES_BIN);
        List<StoredEdgeValue> edges = new ArrayList<>();
        for (Record record : records) {
            if (record == null || record.bins == null) {
                continue;
            }
            Object raw = record.bins.get(AerospikeNearline.EDGES_BIN);
            if (raw instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    edges.add(AerospikeEdgeStore.deserializeEdgeValue(
                            String.valueOf(entry.getKey()), entry.getValue()));
                }
            }
        }
        return edges;
    }
    /**
     * Points this store's metrics at a graph's counters. Called while wiring the
     * application (and by tests); until then the store records nothing.
     */
    public AerospikeNearlineStore metrics(MetricsSink sink) {
        metrics.target(sink);
        return this;
    }
}
