package com.pengcheng.phoenixgraph.storage.aerospike;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Bin;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.pengcheng.phoenixgraph.metrics.MetricsSink;
import com.pengcheng.phoenixgraph.metrics.MutableMetricsSink;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The per-vertex volume statistics the offline job writes and the near-line pre-partitioning
 * (and the big-vertex test) read.
 *
 * <p>One record per (edge class, direction, vertex) holds the monthly and yearly edge counts in
 * bins named {@code m_yyyyMM} and {@code y_yyyy}. The offline job computes the exact totals over
 * the cold data it already has and writes them absolutely, so a re-run overwrites rather than
 * double-counts:
 *
 * <pre>
 * set   {@code {baseSet}_edge_volume}
 * key   {@code {edgeSchemaId}|{directionCode}|{vertexId}}
 * bins  {@code m_202506}=... {@code m_202507}=... {@code y_2025}=...
 * </pre>
 */
public class AerospikeVolumeStore {

    private static final DateTimeFormatter MONTH =
            DateTimeFormatter.ofPattern("yyyyMM").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter YEAR =
            DateTimeFormatter.ofPattern("yyyy").withZone(ZoneOffset.UTC);

    private final AerospikeClient client;
    /** The same client, with every round trip counted into {@link #metrics}. */
    private final AerospikeOperations db;
    /** Where this store records its traffic; the graph's metrics are wired in later. */
    private final MutableMetricsSink metrics = new MutableMetricsSink();
    private final String namespace;
    private final String baseSet;

    public AerospikeVolumeStore(AerospikeClient client, String namespace, String baseSet) {
        this.client = Objects.requireNonNull(client, "client");
        this.db = AerospikeOperations.metered(this.client, metrics);
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.baseSet = Objects.requireNonNull(baseSet, "baseSet");
    }

    /** The volume record key of one vertex, edge class and direction. */
    public static String volumeKey(int edgeSchemaId, int directionCode, Object vertexId) {
        return edgeSchemaId + "|" + directionCode + "|" + vertexId;
    }

    public static String monthBin(long epochMillis) {
        return "m_" + MONTH.format(Instant.ofEpochMilli(epochMillis));
    }

    public static String yearBin(long epochMillis) {
        return "y_" + YEAR.format(Instant.ofEpochMilli(epochMillis));
    }

    /**
     * Writes the volume bins of one vertex, edge class and direction. Bin names are
     * {@code m_yyyyMM} / {@code y_yyyy}; the caller (the offline job) computes the totals and
     * this overwrites them, so re-running the job is idempotent.
     */
    public void put(int edgeSchemaId, int directionCode, Object vertexId, Map<String, Long> volumes) {
        if (volumes.isEmpty()) {
            return;
        }
        Bin[] bins = new Bin[volumes.size()];
        int i = 0;
        for (Map.Entry<String, Long> entry : volumes.entrySet()) {
            bins[i++] = new Bin(entry.getKey(), entry.getValue());
        }
        db.put(null, new Key(namespace, baseSet + "_edge_volume",
                volumeKey(edgeSchemaId, directionCode, vertexId)), bins);
    }

    /** The count of one month, or {@code 0} when nothing was recorded. */
    public long monthlyVolume(int edgeSchemaId, int directionCode, Object vertexId, String yyyyMM) {
        return number(volume(edgeSchemaId, directionCode, vertexId).get("m_" + yyyyMM));
    }

    /** The count of one year, or {@code 0} when nothing was recorded. */
    public long yearlyVolume(int edgeSchemaId, int directionCode, Object vertexId, String yyyy) {
        return number(volume(edgeSchemaId, directionCode, vertexId).get("y_" + yyyy));
    }

    /**
     * The count of the most recent month that has a value - the input of the near-line
     * pre-partitioning ("pick the latest month's volume").
     */
    public long latestMonthlyVolume(int edgeSchemaId, int directionCode, Object vertexId) {
        Map<String, Long> months = monthlyVolumes(edgeSchemaId, directionCode, vertexId);
        if (months.isEmpty()) {
            return 0;
        }
        return months.values().iterator().next(); // a TreeMap iterates by ascending month
    }

    /** Every month bin, newest first (month {@code yyyyMM} -> count). */
    public Map<String, Long> monthlyVolumes(int edgeSchemaId, int directionCode, Object vertexId) {
        Map<String, Long> months = new TreeMap<>(java.util.Collections.reverseOrder());
        for (Map.Entry<String, Object> entry : volume(edgeSchemaId, directionCode, vertexId).entrySet()) {
            if (entry.getKey().startsWith("m_")) {
                months.put(entry.getKey().substring(2), number(entry.getValue()));
            }
        }
        return months;
    }

    /** Every year bin, newest first (year {@code yyyy} -> count). */
    public Map<String, Long> yearlyVolumes(int edgeSchemaId, int directionCode, Object vertexId) {
        Map<String, Long> years = new TreeMap<>(java.util.Collections.reverseOrder());
        for (Map.Entry<String, Object> entry : volume(edgeSchemaId, directionCode, vertexId).entrySet()) {
            if (entry.getKey().startsWith("y_")) {
                years.put(entry.getKey().substring(2), number(entry.getValue()));
            }
        }
        return years;
    }

    private Map<String, Object> volume(int edgeSchemaId, int directionCode, Object vertexId) {
        Record record = db.get(null, new Key(namespace, baseSet + "_edge_volume",
                volumeKey(edgeSchemaId, directionCode, vertexId)));
        if (record == null || record.bins == null) {
            return Map.of();
        }
        return new LinkedHashMap<>(record.bins);
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
    /**
     * Points this store's metrics at a graph's counters. Called while wiring the
     * application (and by tests); until then the store records nothing.
     */
    public AerospikeVolumeStore metrics(MetricsSink sink) {
        metrics.target(sink);
        return this;
    }
}
