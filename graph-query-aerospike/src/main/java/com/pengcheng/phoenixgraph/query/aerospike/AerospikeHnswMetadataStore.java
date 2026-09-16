package com.pengcheng.phoenixgraph.query.aerospike;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Bin;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.aerospike.client.Value;
import com.pengcheng.phoenixgraph.hnsw.HnswEntryPoint;
import com.pengcheng.phoenixgraph.hnsw.HnswMetadata;
import com.pengcheng.phoenixgraph.hnsw.HnswMetadataStore;
import com.pengcheng.phoenixgraph.hnsw.HnswMetric;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Aerospike {@link HnswMetadataStore}: the index description and its entry points
 * live in one dedicated record (one per indexed vertex type), so a query process
 * can start an HNSW search without touching the graph.
 *
 * <p>The record carries the build parameters, the metric and the entry points -
 * each entry point is a vertex id plus the layer it reaches, at most
 * {@value com.pengcheng.phoenixgraph.hnsw.HnswLevels#MAX_ENTRY_POINTS} of them,
 * highest layer first. The ids keep their stored type (a long id comes back as a
 * long), which matters because the id is the vertex record key.
 */
public final class AerospikeHnswMetadataStore implements HnswMetadataStore {

    private static final String BIN_VERSION = "version";
    private static final String BIN_VERTEX = "vertex";
    private static final String BIN_VECTOR_PROPERTY = "vectorProperty";
    private static final String BIN_METRIC = "metric";
    private static final String BIN_M = "m";
    private static final String BIN_EF_CONSTRUCTION = "efConstruction";
    private static final String BIN_EF_SEARCH = "efSearch";
    private static final String BIN_DIMENSIONS = "dimensions";
    private static final String BIN_SIZE = "size";
    private static final String BIN_TOP_LEVEL = "topLevel";
    private static final String BIN_ENTRY_IDS = "entryIds";
    private static final String BIN_ENTRY_LEVELS = "entryLevels";
    private static final String BIN_UPDATED_AT = "updatedAt";

    private final AerospikeClient client;
    private final Key key;

    /**
     * @param client    connected Aerospike client
     * @param namespace namespace holding the graph
     * @param set       set holding the metadata record (e.g. {@code hnsw_meta})
     * @param recordKey full key of the metadata record: {@code hnsw:Vector} for the
     *                  first vector property of a vertex type, or
     *                  {@code hnsw:Vector:embedding} when a vertex type carries more
     *                  than one vector property
     */
    public AerospikeHnswMetadataStore(AerospikeClient client, String namespace, String set, String recordKey) {
        this.client = Objects.requireNonNull(client, "client");
        this.key = new Key(namespace, set, Value.get(Objects.requireNonNull(recordKey, "recordKey")));
    }

    /** Store for the first vector property of a vertex type (key {@code hnsw:<vertexName>}). */
    public static AerospikeHnswMetadataStore forVertex(AerospikeClient client, String namespace, String set,
                                                      String vertexName) {
        return new AerospikeHnswMetadataStore(client, namespace, set, "hnsw:" + vertexName);
    }

    @Override
    public HnswMetadata read() {
        Record record = client.get(null, key);
        if (record == null || record.bins == null || record.bins.isEmpty()) {
            return null;
        }
        List<HnswEntryPoint> entryPoints = new ArrayList<>();
        Object ids = record.getValue(BIN_ENTRY_IDS);
        Object levels = record.getValue(BIN_ENTRY_LEVELS);
        if (ids instanceof List<?> idList && levels instanceof List<?> levelList) {
            for (int i = 0; i < idList.size() && i < levelList.size(); i++) {
                Object level = levelList.get(i);
                entryPoints.add(new HnswEntryPoint(idList.get(i),
                        level instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(level))));
            }
        }
        return new HnswMetadata(intBin(record, BIN_VERSION, HnswMetadata.CURRENT_VERSION),
                stringBin(record, BIN_VERTEX),
                stringBin(record, BIN_VECTOR_PROPERTY),
                HnswMetric.of(stringBin(record, BIN_METRIC)),
                intBin(record, BIN_M, 15),
                intBin(record, BIN_EF_CONSTRUCTION, 100),
                intBin(record, BIN_EF_SEARCH, 50),
                intBin(record, BIN_DIMENSIONS, 0),
                longBin(record, BIN_SIZE, 0L),
                intBin(record, BIN_TOP_LEVEL, 0),
                entryPoints,
                longBin(record, BIN_UPDATED_AT, 0L));
    }

    @Override
    public void write(HnswMetadata metadata) {
        List<Object> ids = new ArrayList<>();
        List<Long> levels = new ArrayList<>();
        for (HnswEntryPoint point : metadata.entryPoints()) {
            ids.add(point.vertexId());
            levels.add((long) point.level());
        }
        client.put(null, key,
                new Bin(BIN_VERSION, metadata.version()),
                new Bin(BIN_VERTEX, metadata.vertexName()),
                new Bin(BIN_VECTOR_PROPERTY, metadata.vectorProperty()),
                new Bin(BIN_METRIC, metadata.metric().name()),
                new Bin(BIN_M, metadata.m()),
                new Bin(BIN_EF_CONSTRUCTION, metadata.efConstruction()),
                new Bin(BIN_EF_SEARCH, metadata.efSearch()),
                new Bin(BIN_DIMENSIONS, metadata.dimensions()),
                new Bin(BIN_SIZE, metadata.size()),
                new Bin(BIN_TOP_LEVEL, metadata.topLevel()),
                new Bin(BIN_ENTRY_IDS, ids),
                new Bin(BIN_ENTRY_LEVELS, levels),
                new Bin(BIN_UPDATED_AT, metadata.updatedAt()));
    }

    /** Deletes the metadata record (a rebuild with {@code overwrite} starts from scratch). */
    public void delete() {
        client.delete(null, key);
    }

    /** The record key, for diagnostics. */
    public String describeKey() {
        return key.namespace + "/" + key.setName + "/" + key.userKey;
    }

    private static String stringBin(Record record, String bin) {
        Object value = record.getValue(bin);
        return value == null ? null : String.valueOf(value);
    }

    private static int intBin(Record record, String bin, int defaultValue) {
        Object value = record.getValue(bin);
        return value instanceof Number number ? number.intValue() : defaultValue;
    }

    private static long longBin(Record record, String bin, long defaultValue) {
        Object value = record.getValue(bin);
        return value instanceof Number number ? number.longValue() : defaultValue;
    }
}
