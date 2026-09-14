package com.pengcheng.phoenixgraph.storage.aerospike;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.AerospikeException;
import com.aerospike.client.BatchRecord;
import com.aerospike.client.BatchWrite;
import com.aerospike.client.Bin;
import com.aerospike.client.Key;
import com.aerospike.client.Operation;
import com.aerospike.client.Record;
import com.aerospike.client.ResultCode;
import com.aerospike.client.Value;
import com.aerospike.client.policy.BatchPolicy;
import com.aerospike.client.policy.BatchWritePolicy;
import com.aerospike.client.policy.GenerationPolicy;
import com.aerospike.client.policy.RecordExistsAction;
import com.aerospike.client.policy.WritePolicy;
import com.pengcheng.phoenixgraph.core.Property;
import com.pengcheng.phoenixgraph.core.Vertex;
import com.pengcheng.phoenixgraph.schema.MetaPropertySchema;
import com.pengcheng.phoenixgraph.storage.BusinessLog;
import com.pengcheng.phoenixgraph.storage.BusinessLogEntry;
import com.pengcheng.phoenixgraph.storage.ErrorLog;
import com.pengcheng.phoenixgraph.storage.RetryingWriter;
import com.pengcheng.phoenixgraph.storage.VertexStore;
import com.pengcheng.phoenixgraph.storage.WriteOutcome;
import com.pengcheng.phoenixgraph.storage.log.JulBusinessLog;
import com.pengcheng.phoenixgraph.storage.log.JulErrorLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Aerospike-backed vertex storage.
 *
 * <p>Each vertex maps to a single record in its own Aerospike set:
 * <ul>
 *   <li>record key = vertex id</li>
 *   <li>each declared property is stored as two bins:
 *       {@code vp_<index>} (raw value) and {@code vpt_<index>} (the property's
 *       own {@code lastModifiedTime});</li>
 *   <li>the {@code createdTime} and {@code lastUpdatedTime} bins carry the
 *       vertex metadata.</li>
 * </ul>
 *
 * <p>Writes use Aerospike CAS. {@link #upsertVertex} inserts the vertex when it
 * is absent ({@link RecordExistsAction#CREATE_ONLY}) and otherwise updates it
 * optimistically: each property is written only when its incoming
 * {@code lastModifiedTime} is strictly newer than the stored property
 * timestamp, and the whole update is guarded by a record-generation CAS so a
 * concurrent write cannot be lost. A property whose timestamp is not newer is
 * left untouched and only logged.
 *
 * <p>Key writes are executed with bounded retries (three attempts by default).
 * A CAS/version conflict is only recorded in the business log; a write that
 * keeps failing is recorded in the {@link ErrorLog} (a separate business-log
 * sink) for later inspection/enrichment.
 */
public class AerospikeVertexStore implements VertexStore {

    /** Prefix of property value bins. */
    public static final String PROPERTY_BIN_PREFIX = "vp_";
    /** Prefix of per-property {@code lastModifiedTime} bins. */
    public static final String PROPERTY_TIME_BIN_PREFIX = "vpt_";
    /** Index of the {@code createdTime} meta-property bin. */
    public static final int CREATED_TIME_INDEX = MetaPropertySchema.CREATED_TIME_ID; // -2
    /** Index of the {@code lastUpdatedTime} meta-property bin. */
    public static final int LAST_UPDATED_TIME_INDEX = MetaPropertySchema.LAST_UPDATED_TIME_ID; // -1
    /** Bin holding the vertex creation time (epoch millis). */
    public static final String CREATED_TIME_BIN = PROPERTY_BIN_PREFIX + CREATED_TIME_INDEX;
    /** Bin holding the vertex last-update time (epoch millis). */
    public static final String LAST_UPDATED_TIME_BIN = PROPERTY_BIN_PREFIX + LAST_UPDATED_TIME_INDEX;

    /**
     * Sentinel {@code lastUpdatedTime} marking a vertex as expired (soft
     * delete): 1970-01-01T00:00:00Z (epoch 0).
     */
    public static final long EXPIRED_LAST_UPDATED_TIME = 0L;

    private final AerospikeClient client;
    private final String namespace;
    private final String set;
    private final int maxWriteAttempts;
    private final BusinessLog businessLog;
    private final ErrorLog errorLog;
    private final RetryingWriter writer;

    public AerospikeVertexStore(AerospikeClient client, String namespace, String set) {
        this(client, namespace, set, new JulBusinessLog(), new JulErrorLog());
    }

    public AerospikeVertexStore(AerospikeClient client, String namespace, String set,
                                BusinessLog businessLog, ErrorLog errorLog) {
        this(client, namespace, set, businessLog, errorLog, RetryingWriter.DEFAULT_MAX_ATTEMPTS);
    }

    public AerospikeVertexStore(AerospikeClient client, String namespace, String set,
                                BusinessLog businessLog, ErrorLog errorLog, int maxWriteAttempts) {
        this.client = Objects.requireNonNull(client, "client");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.set = Objects.requireNonNull(set, "set");
        this.businessLog = Objects.requireNonNull(businessLog, "businessLog");
        this.errorLog = Objects.requireNonNull(errorLog, "errorLog");
        this.maxWriteAttempts = maxWriteAttempts;
        this.writer = new RetryingWriter(maxWriteAttempts, businessLog, errorLog);
    }

    public AerospikeVertexStore(String host, int port, String namespace, String set) {
        this(new AerospikeClient(host, port), namespace, set);
    }

    /** Returns the value bin name for a property index. */
    public static String propertyBin(int propertyIndex) {
        return PROPERTY_BIN_PREFIX + propertyIndex;
    }

    /** Returns the {@code lastModifiedTime} bin name for a property index. */
    public static String propertyTimeBin(int propertyIndex) {
        return PROPERTY_TIME_BIN_PREFIX + propertyIndex;
    }

    /** Returns {@code true} when {@code incoming} is strictly newer than {@code stored}. */
    static boolean isNewer(long incomingLastModifiedTime, long storedLastModifiedTime) {
        return incomingLastModifiedTime > storedLastModifiedTime;
    }

    /** CAS write policy for vertex creation: only succeeds when the record does not exist. */
    static WritePolicy createOnlyPolicy() {
        WritePolicy policy = new WritePolicy();
        policy.recordExistsAction = RecordExistsAction.CREATE_ONLY;
        return policy;
    }

    /** CAS write policy guarding an update with a record generation. */
    static WritePolicy generationPolicy(int generation) {
        WritePolicy policy = new WritePolicy();
        policy.generationPolicy = GenerationPolicy.EXPECT_GEN_EQUAL;
        policy.generation = generation;
        return policy;
    }

    /** Batch CAS write policy for vertex creation (create-only). */
    static BatchWritePolicy createOnlyBatchPolicy() {
        BatchWritePolicy policy = new BatchWritePolicy();
        policy.recordExistsAction = RecordExistsAction.CREATE_ONLY;
        return policy;
    }

    /** Batch CAS write policy guarding an update with a record generation. */
    static BatchWritePolicy generationBatchPolicy(int generation) {
        BatchWritePolicy policy = new BatchWritePolicy();
        policy.generationPolicy = GenerationPolicy.EXPECT_GEN_EQUAL;
        policy.generation = generation;
        return policy;
    }

    /** Maps an Aerospike result code to a {@link WriteOutcome}. */
    static WriteOutcome toOutcome(int resultCode) {
        if (resultCode == ResultCode.OK) {
            return WriteOutcome.SUCCESS;
        }
        if (isConflictCode(resultCode)) {
            return WriteOutcome.CONFLICT;
        }
        return WriteOutcome.FAILED;
    }

    private static boolean isConflictCode(int resultCode) {
        return resultCode == ResultCode.FILTERED_OUT
                || resultCode == ResultCode.KEY_EXISTS_ERROR
                || resultCode == ResultCode.GENERATION_ERROR;
    }

    /**
     * Upserts a vertex: inserts it when absent, otherwise updates each property
     * whose {@code lastModifiedTime} is strictly newer than the stored
     * property's {@code lastModifiedTime} (per-property CAS). The update is
     * applied atomically with a record-generation CAS; a concurrent write
     * causes a bounded re-read and retry.
     *
     * @return {@link WriteOutcome#SUCCESS} when created or updated,
     *         {@link WriteOutcome#CONFLICT} when every incoming property is
     *         stale (nothing written), {@link WriteOutcome#FAILED} after retries
     */
    public WriteOutcome upsertVertex(Object vertexId, Map<Integer, VertexPropertyValue> properties,
                                     long createdTime, long lastUpdatedTime) {
        Objects.requireNonNull(properties, "properties");
        String target = String.valueOf(vertexId);
        Map<String, Object> payload = upsertPayload(properties, createdTime, lastUpdatedTime);

        Exception last = null;
        for (int attempt = 1; attempt <= maxWriteAttempts; attempt++) {
            Record record;
            try {
                record = client.get(null, key(vertexId));
            } catch (AerospikeException e) {
                last = e;
                continue;
            }

            try {
                if (record == null) {
                    client.operate(createOnlyPolicy(), key(vertexId),
                            buildCreateOps(properties, createdTime, lastUpdatedTime));
                } else {
                    Map<Integer, VertexPropertyValue> toWrite = decideUpdates(vertexId, record, properties);
                    if (toWrite.isEmpty()) {
                        businessLog.record(BusinessLogEntry.failure("upsertVertex", target,
                                conflictDetails(payload, "all properties stale")));
                        return WriteOutcome.CONFLICT;
                    }
                    long newUpdated = Math.max(readLong(record, LAST_UPDATED_TIME_BIN), lastUpdatedTime);
                    boolean missingCreatedTime = record.bins.get(CREATED_TIME_BIN) == null;
                    client.operate(generationPolicy(record.generation), key(vertexId),
                            buildUpdateOps(toWrite, newUpdated, missingCreatedTime ? createdTime : null));
                }
                businessLog.record(BusinessLogEntry.success("upsertVertex", target, payload));
                return WriteOutcome.SUCCESS;
            } catch (AerospikeException e) {
                // retry: create-race, generation-race, or transient failure
                last = e;
            }
        }

        Map<String, Object> details = new LinkedHashMap<>(payload);
        details.put("error", last == null ? "unknown" : last.toString());
        errorLog.record(BusinessLogEntry.failure("upsertVertex", target, details));
        businessLog.record(BusinessLogEntry.failure("upsertVertex", target, payload));
        return WriteOutcome.FAILED;
    }

    /** Convenience overload persisting a core {@link Vertex} instance. */
    public WriteOutcome upsertVertex(Vertex vertex) {
        Objects.requireNonNull(vertex, "vertex");
        Map<Integer, VertexPropertyValue> properties = new LinkedHashMap<>();
        for (Property property : vertex.properties()) {
            properties.put(property.id(),
                    new VertexPropertyValue(property.value(), property.meta().lastUpdatedTime()));
        }
        return upsertVertex(vertex.id(), properties,
                vertex.meta().createdTime(), vertex.meta().lastUpdatedTime());
    }

    /**
     * Batch upsert of vertices. Each vertex is inserted when absent, otherwise
     * updated per property with a per-property {@code lastModifiedTime} CAS and
     * a record-generation CAS. Stale records are reported as
     * {@link WriteOutcome#CONFLICT}.
     */
    public List<WriteOutcome> upsertVertices(List<VertexUpsert> upserts) {
        Objects.requireNonNull(upserts, "upserts");
        if (upserts.isEmpty()) {
            return List.of();
        }

        Key[] keys = new Key[upserts.size()];
        for (int i = 0; i < upserts.size(); i++) {
            keys[i] = key(upserts.get(i).vertexId());
        }

        List<WriteOutcome> outcomes = new ArrayList<>(Collections.nCopies(upserts.size(), null));
        List<BatchRecord> writes = new ArrayList<>();
        List<Integer> writeIndices = new ArrayList<>();

        WriteOutcome batchOutcome = writer.execute("upsertVertices", "batch:" + upserts.size(),
                Map.of("count", upserts.size()), () -> {
            Collections.fill(outcomes, null);
            writes.clear();
            writeIndices.clear();

            Record[] records = client.get(new BatchPolicy(), keys);
            for (int i = 0; i < upserts.size(); i++) {
                VertexUpsert upsert = upserts.get(i);
                Record record = records[i];
                if (record == null) {
                    writes.add(new BatchWrite(createOnlyBatchPolicy(), keys[i],
                            buildCreateOps(upsert.properties(), upsert.createdTime(), upsert.lastUpdatedTime())));
                    writeIndices.add(i);
                } else {
                    Map<Integer, VertexPropertyValue> toWrite = decideUpdates(upsert.vertexId(), record, upsert.properties());
                    if (toWrite.isEmpty()) {
                        outcomes.set(i, WriteOutcome.CONFLICT);
                    } else {
                        long newUpdated = Math.max(readLong(record, LAST_UPDATED_TIME_BIN), upsert.lastUpdatedTime());
                        boolean missingCreatedTime = record.bins.get(CREATED_TIME_BIN) == null;
                        writes.add(new BatchWrite(generationBatchPolicy(record.generation), keys[i],
                                buildUpdateOps(toWrite, newUpdated, missingCreatedTime ? upsert.createdTime() : null)));
                        writeIndices.add(i);
                    }
                }
            }
            if (!writes.isEmpty()) {
                client.operate(new BatchPolicy(), writes);
            }
        });

        if (batchOutcome != WriteOutcome.SUCCESS) {
            for (int i = 0; i < outcomes.size(); i++) {
                if (outcomes.get(i) == null) {
                    outcomes.set(i, WriteOutcome.FAILED);
                }
            }
            return outcomes;
        }

        for (int j = 0; j < writes.size(); j++) {
            outcomes.set(writeIndices.get(j), toOutcome(writes.get(j).resultCode));
        }
        for (int i = 0; i < outcomes.size(); i++) {
            if (outcomes.get(i) == null) {
                outcomes.set(i, WriteOutcome.FAILED);
            }
            logBatchRecord(upserts.get(i).vertexId(), outcomes.get(i));
        }
        return outcomes;
    }

    /** Decides which incoming properties are newer than the stored ones, logging stale properties. */
    private Map<Integer, VertexPropertyValue> decideUpdates(Object vertexId, Record record,
                                                            Map<Integer, VertexPropertyValue> properties) {
        String target = String.valueOf(vertexId);
        Map<Integer, VertexPropertyValue> toWrite = new LinkedHashMap<>();
        for (Map.Entry<Integer, VertexPropertyValue> entry : properties.entrySet()) {
            int index = entry.getKey();
            Object storedTime = record.bins.get(propertyTimeBin(index));
            long stored = storedTime == null ? Long.MIN_VALUE : ((Number) storedTime).longValue();
            long incoming = entry.getValue().lastModifiedTime();
            if (isNewer(incoming, stored)) {
                toWrite.put(index, entry.getValue());
            } else {
                businessLog.record(BusinessLogEntry.failure("upsertVertex", target, Map.of(
                        "property", index, "stale", true,
                        "incomingLastModifiedTime", incoming, "storedLastModifiedTime", stored)));
            }
        }
        return toWrite;
    }

    private static Operation[] buildCreateOps(Map<Integer, VertexPropertyValue> properties,
                                              long createdTime, long lastUpdatedTime) {
        Operation[] ops = new Operation[properties.size() * 2 + 2];
        int i = 0;
        for (Map.Entry<Integer, VertexPropertyValue> entry : properties.entrySet()) {
            ops[i++] = Operation.put(new Bin(propertyBin(entry.getKey()), Value.get(entry.getValue().value())));
            ops[i++] = Operation.put(new Bin(propertyTimeBin(entry.getKey()), entry.getValue().lastModifiedTime()));
        }
        ops[i++] = Operation.put(new Bin(CREATED_TIME_BIN, createdTime));
        ops[i] = Operation.put(new Bin(LAST_UPDATED_TIME_BIN, lastUpdatedTime));
        return ops;
    }

    private static Operation[] buildUpdateOps(Map<Integer, VertexPropertyValue> toWrite,
                                              long lastUpdatedTime, Long createdTime) {
        int extra = createdTime == null ? 1 : 2;
        Operation[] ops = new Operation[toWrite.size() * 2 + extra];
        int i = 0;
        for (Map.Entry<Integer, VertexPropertyValue> entry : toWrite.entrySet()) {
            ops[i++] = Operation.put(new Bin(propertyBin(entry.getKey()), Value.get(entry.getValue().value())));
            ops[i++] = Operation.put(new Bin(propertyTimeBin(entry.getKey()), entry.getValue().lastModifiedTime()));
        }
        if (createdTime != null) {
            ops[i++] = Operation.put(new Bin(CREATED_TIME_BIN, createdTime));
        }
        ops[i] = Operation.put(new Bin(LAST_UPDATED_TIME_BIN, lastUpdatedTime));
        return ops;
    }

    private static long readLong(Record record, String bin) {
        Object value = record.bins.get(bin);
        return value == null ? 0L : ((Number) value).longValue();
    }

    private static Map<String, Object> upsertPayload(Map<Integer, VertexPropertyValue> properties,
                                                     long createdTime, long lastUpdatedTime) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (Map.Entry<Integer, VertexPropertyValue> entry : properties.entrySet()) {
            payload.put(propertyBin(entry.getKey()), entry.getValue().lastModifiedTime());
        }
        payload.put(CREATED_TIME_BIN, createdTime);
        payload.put(LAST_UPDATED_TIME_BIN, lastUpdatedTime);
        return payload;
    }

    private static Map<String, Object> conflictDetails(Map<String, Object> payload, String message) {
        Map<String, Object> details = new LinkedHashMap<>(payload);
        details.put("conflict", true);
        details.put("message", message);
        return details;
    }

    private void logBatchRecord(Object vertexId, WriteOutcome outcome) {
        if (outcome == WriteOutcome.SUCCESS) {
            return;
        }
        String target = String.valueOf(vertexId);
        if (outcome == WriteOutcome.CONFLICT) {
            businessLog.record(BusinessLogEntry.failure("upsertVertices", target, Map.of("conflict", true)));
        } else {
            Map<String, Object> details = Map.of("error", "batch write failed");
            errorLog.record(BusinessLogEntry.failure("upsertVertices", target, details));
            businessLog.record(BusinessLogEntry.failure("upsertVertices", target, details));
        }
    }

    /** Reads the vertex and decodes its properties and metadata (all bins). */
    public StoredVertex readVertex(Object vertexId) {
        Record record = client.get(null, key(vertexId));
        return record == null ? null : decode(vertexId, record);
    }

    /** Reads the vertex reading only the given property bins (plus meta bins). */
    public StoredVertex readVertex(Object vertexId, List<Integer> propertyIndexes) {
        Record record = client.get(null, key(vertexId), propertyBins(propertyIndexes));
        return record == null ? null : decode(vertexId, record);
    }

    /**
     * Batch-reads vertices with one native Aerospike batch get, preserving the
     * request order; an absent vertex is returned as {@code null} at its position.
     */
    public List<StoredVertex> readVertices(List<Object> vertexIds) {
        Objects.requireNonNull(vertexIds, "vertexIds");
        if (vertexIds.isEmpty()) {
            return List.of();
        }
        Key[] keys = vertexIds.stream().map(this::key).toArray(Key[]::new);
        Record[] records = client.get(new BatchPolicy(), keys);
        return decodeAll(vertexIds, records);
    }

    /** Batch-reads vertices reading only the given property bins (plus meta bins). */
    public List<StoredVertex> readVertices(List<Object> vertexIds, List<Integer> propertyIndexes) {
        Objects.requireNonNull(vertexIds, "vertexIds");
        if (vertexIds.isEmpty()) {
            return List.of();
        }
        Key[] keys = vertexIds.stream().map(this::key).toArray(Key[]::new);
        Record[] records = client.get(new BatchPolicy(), keys, propertyBins(propertyIndexes));
        return decodeAll(vertexIds, records);
    }

    static String[] propertyBins(List<Integer> propertyIndexes) {
        List<String> bins = new ArrayList<>();
        if (propertyIndexes != null) {
            for (int index : propertyIndexes) {
                bins.add(propertyBin(index));
                bins.add(propertyTimeBin(index));
            }
        }
        bins.add(CREATED_TIME_BIN);
        bins.add(LAST_UPDATED_TIME_BIN);
        return bins.toArray(new String[0]);
    }

    private List<StoredVertex> decodeAll(List<Object> vertexIds, Record[] records) {
        List<StoredVertex> vertices = new ArrayList<>(vertexIds.size());
        for (int i = 0; i < vertexIds.size(); i++) {
            vertices.add(records[i] == null ? null : decode(vertexIds.get(i), records[i]));
        }
        return vertices;
    }

    private StoredVertex decode(Object vertexId, Record record) {
        Map<Integer, VertexPropertyValue> properties = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : record.bins.entrySet()) {
            String name = entry.getKey();
            if (name.startsWith(PROPERTY_BIN_PREFIX) && !name.startsWith(PROPERTY_TIME_BIN_PREFIX)) {
                int index = Integer.parseInt(name.substring(PROPERTY_BIN_PREFIX.length()));
                if (index < 0) {
                    continue; // createdTime/lastUpdatedTime meta bins (vp_-2 / vp_-1)
                }
                long lastModifiedTime = readLong(record, propertyTimeBin(index));
                properties.put(index, new VertexPropertyValue(entry.getValue(), lastModifiedTime));
            }
        }
        return new StoredVertex(vertexId, properties,
                readLong(record, CREATED_TIME_BIN), readLong(record, LAST_UPDATED_TIME_BIN));
    }

    /** Reads a single property value by property index. */
    @Override
    public Object getVertexProperty(Object vertexId, int propertyIndex) {
        Record record = client.get(null, key(vertexId));
        return record == null ? null : record.bins.get(propertyBin(propertyIndex));
    }

    /** Reads a single property's {@code lastModifiedTime}, or {@code null} if absent. */
    public Long getVertexPropertyLastModifiedTime(Object vertexId, int propertyIndex) {
        Record record = client.get(null, key(vertexId));
        if (record == null || record.bins.get(propertyTimeBin(propertyIndex)) == null) {
            return null;
        }
        return ((Number) record.bins.get(propertyTimeBin(propertyIndex))).longValue();
    }

    /** Reads the stored {@code createdTime}, or {@code null} if absent. */
    public Long createdTime(Object vertexId) {
        Record record = client.get(null, key(vertexId));
        if (record == null || record.bins.get(CREATED_TIME_BIN) == null) {
            return null;
        }
        return ((Number) record.bins.get(CREATED_TIME_BIN)).longValue();
    }

    /** Reads the stored {@code lastUpdatedTime}, or {@code null} if absent. */
    public Long lastUpdatedTime(Object vertexId) {
        Record record = client.get(null, key(vertexId));
        if (record == null || record.bins.get(LAST_UPDATED_TIME_BIN) == null) {
            return null;
        }
        return ((Number) record.bins.get(LAST_UPDATED_TIME_BIN)).longValue();
    }

    /** Soft-deletes the vertex by marking its {@code lastUpdatedTime} as expired. */
    public WriteOutcome deleteVertex(Object vertexId) {
        String target = String.valueOf(vertexId);
        Exception last = null;
        for (int attempt = 1; attempt <= maxWriteAttempts; attempt++) {
            Record record;
            try {
                record = client.get(null, key(vertexId));
            } catch (AerospikeException e) {
                last = e;
                continue;
            }
            if (record == null) {
                businessLog.record(BusinessLogEntry.success("deleteVertex", target, Map.of()));
                return WriteOutcome.SUCCESS;
            }
            try {
                client.operate(generationPolicy(record.generation), key(vertexId),
                        Operation.put(new Bin(LAST_UPDATED_TIME_BIN, EXPIRED_LAST_UPDATED_TIME)));
                businessLog.record(BusinessLogEntry.success("deleteVertex", target,
                        Map.of("expired", EXPIRED_LAST_UPDATED_TIME)));
                return WriteOutcome.SUCCESS;
            } catch (AerospikeException e) {
                last = e;
            }
        }
        Map<String, Object> details = Map.of("error", last == null ? "unknown" : last.toString());
        errorLog.record(BusinessLogEntry.failure("deleteVertex", target, details));
        businessLog.record(BusinessLogEntry.failure("deleteVertex", target, details));
        return WriteOutcome.FAILED;
    }

    /** Returns whether the vertex has been soft-deleted (lastUpdatedTime is the epoch-0 sentinel). */
    public boolean isExpired(Object vertexId) {
        Long lastUpdated = lastUpdatedTime(vertexId);
        return lastUpdated != null && lastUpdated <= EXPIRED_LAST_UPDATED_TIME;
    }

    /** Hard-deletes a single vertex property (both its value and timestamp bins). */
    public WriteOutcome deleteVertexProperty(Object vertexId, int propertyIndex) {
        String target = String.valueOf(vertexId);
        String bin = propertyBin(propertyIndex);
        String timeBin = propertyTimeBin(propertyIndex);
        Exception last = null;
        for (int attempt = 1; attempt <= maxWriteAttempts; attempt++) {
            Record record;
            try {
                record = client.get(null, key(vertexId));
            } catch (AerospikeException e) {
                last = e;
                continue;
            }
            if (record == null || (record.bins.get(bin) == null && record.bins.get(timeBin) == null)) {
                businessLog.record(BusinessLogEntry.success("deleteVertexProperty", target, Map.of("bin", bin)));
                return WriteOutcome.SUCCESS;
            }
            try {
                client.operate(generationPolicy(record.generation), key(vertexId),
                        Operation.put(Bin.asNull(bin)),
                        Operation.put(Bin.asNull(timeBin)));
                businessLog.record(BusinessLogEntry.success("deleteVertexProperty", target, Map.of("bin", bin)));
                return WriteOutcome.SUCCESS;
            } catch (AerospikeException e) {
                last = e;
            }
        }
        Map<String, Object> details = Map.of("bin", bin, "error", last == null ? "unknown" : last.toString());
        errorLog.record(BusinessLogEntry.failure("deleteVertexProperty", target, details));
        businessLog.record(BusinessLogEntry.failure("deleteVertexProperty", target, details));
        return WriteOutcome.FAILED;
    }

    /** Batch soft-deletes the given vertices. */
    public List<WriteOutcome> deleteVertices(List<Object> vertexIds) {
        Objects.requireNonNull(vertexIds, "vertexIds");
        List<Object> ids = vertexIds.stream().distinct().toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        Key[] keys = ids.stream().map(this::key).toArray(Key[]::new);
        List<String> targets = ids.stream().map(String::valueOf).toList();
        return batchDelete("deleteVertices", keys, targets, (i, record) -> new Operation[]{
                Operation.put(new Bin(LAST_UPDATED_TIME_BIN, EXPIRED_LAST_UPDATED_TIME))
        });
    }

    /** Batch hard-deletes vertex properties, grouped by vertex. */
    public List<WriteOutcome> deleteVertexProperties(List<VertexPropertyDelete> deletes) {
        Objects.requireNonNull(deletes, "deletes");
        if (deletes.isEmpty()) {
            return List.of();
        }
        Map<Object, List<Integer>> byVertex = new LinkedHashMap<>();
        for (VertexPropertyDelete delete : deletes) {
            byVertex.computeIfAbsent(delete.vertexId(), k -> new ArrayList<>()).add(delete.propertyIndex());
        }
        List<Object> ids = new ArrayList<>(byVertex.keySet());
        Key[] keys = ids.stream().map(this::key).toArray(Key[]::new);
        List<String> targets = ids.stream().map(String::valueOf).toList();
        return batchDelete("deleteVertexProperties", keys, targets, (i, record) -> {
            List<Operation> ops = new ArrayList<>();
            for (int index : byVertex.get(ids.get(i))) {
                if (record.bins.get(propertyBin(index)) != null) {
                    ops.add(Operation.put(Bin.asNull(propertyBin(index))));
                }
                if (record.bins.get(propertyTimeBin(index)) != null) {
                    ops.add(Operation.put(Bin.asNull(propertyTimeBin(index))));
                }
            }
            return ops.toArray(new Operation[0]);
        });
    }

    private List<WriteOutcome> batchDelete(String operation, Key[] keys, List<String> targets,
                                           BiFunction<Integer, Record, Operation[]> opsBuilder) {
        List<WriteOutcome> outcomes = new ArrayList<>(Collections.nCopies(keys.length, null));
        List<BatchRecord> writes = new ArrayList<>();
        List<Integer> writeIndices = new ArrayList<>();

        WriteOutcome batchOutcome = writer.execute(operation, "batch:" + keys.length,
                Map.of("count", keys.length), () -> {
            Collections.fill(outcomes, null);
            writes.clear();
            writeIndices.clear();
            Record[] records = client.get(new BatchPolicy(), keys);
            for (int i = 0; i < keys.length; i++) {
                Record record = records[i];
                if (record == null) {
                    outcomes.set(i, WriteOutcome.SUCCESS); // nothing to delete
                    continue;
                }
                Operation[] ops = opsBuilder.apply(i, record);
                if (ops == null || ops.length == 0) {
                    outcomes.set(i, WriteOutcome.SUCCESS);
                    continue;
                }
                writes.add(new BatchWrite(generationBatchPolicy(record.generation), keys[i], ops));
                writeIndices.add(i);
            }
            if (!writes.isEmpty()) {
                client.operate(new BatchPolicy(), writes);
            }
        });

        if (batchOutcome != WriteOutcome.SUCCESS) {
            for (int i = 0; i < outcomes.size(); i++) {
                if (outcomes.get(i) == null) {
                    outcomes.set(i, WriteOutcome.FAILED);
                }
            }
            return outcomes;
        }
        for (int j = 0; j < writes.size(); j++) {
            outcomes.set(writeIndices.get(j), toOutcome(writes.get(j).resultCode));
        }
        for (int i = 0; i < outcomes.size(); i++) {
            if (outcomes.get(i) == null) {
                outcomes.set(i, WriteOutcome.FAILED);
            }
        }
        for (int i = 0; i < outcomes.size(); i++) {
            if (outcomes.get(i) == WriteOutcome.FAILED) {
                Map<String, Object> details = Map.of("error", "batch delete failed");
                errorLog.record(BusinessLogEntry.failure(operation, targets.get(i), details));
                businessLog.record(BusinessLogEntry.failure(operation, targets.get(i), details));
            }
        }
        return outcomes;
    }

    // --- VertexStore interface adapters ---

    @Override
    public void putVertex(Object vertexId, Map<Integer, Object> properties) {
        Objects.requireNonNull(properties, "properties");
        long now = System.currentTimeMillis();
        Map<Integer, VertexPropertyValue> values = new LinkedHashMap<>();
        for (Map.Entry<Integer, Object> entry : properties.entrySet()) {
            values.put(entry.getKey(), new VertexPropertyValue(entry.getValue(), now));
        }
        upsertVertex(vertexId, values, now, now);
    }

    @Override
    public Map<Integer, Object> getVertex(Object vertexId) {
        StoredVertex vertex = readVertex(vertexId);
        if (vertex == null) {
            return null;
        }
        Map<Integer, Object> properties = new LinkedHashMap<>();
        for (Map.Entry<Integer, VertexPropertyValue> entry : vertex.properties().entrySet()) {
            properties.put(entry.getKey(), entry.getValue().value());
        }
        return properties;
    }

    @Override
    public void updateVertexProperty(Object vertexId, int propertyIndex, Object value) {
        long now = System.currentTimeMillis();
        upsertVertex(vertexId, Map.of(propertyIndex, new VertexPropertyValue(value, now)), now, now);
    }

    @Override
    public void removeVertexProperty(Object vertexId, int propertyIndex) {
        deleteVertexProperty(vertexId, propertyIndex);
    }

    @Override
    public void removeVertex(Object vertexId) {
        deleteVertex(vertexId);
    }

    private Key key(Object vertexId) {
        return AerospikeKeys.of(namespace, set, vertexId);
    }
}
