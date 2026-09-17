package com.pengcheng.phoenixgraph.storage.rocksdb;

import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.VertexSchema;
import com.pengcheng.phoenixgraph.storage.BusinessLog;
import com.pengcheng.phoenixgraph.storage.BusinessLogEntry;
import com.pengcheng.phoenixgraph.storage.WriteOutcome;
import org.rocksdb.WriteBatch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The vertex tables of a RocksDB graph: one column family per vertex type, keyed by
 * {@code hash(vertexId) : vertexId}.
 *
 * <p>A record holds the vertex timestamps followed by its properties, and each property
 * carries a <b>version</b> - the property's {@code lastUpdatedTime}. A write only
 * replaces a property whose stored version is older, which is the same
 * last-update-wins rule the Aerospike store applies per bin. Only the current version is
 * kept by default ({@link RocksDbStoreConfig#maxVersions()} = 1); with a larger setting
 * the previous values are retained as history and can be read back with {@link #versions}.
 *
 * <p>RocksDB has no compare-and-set, so a write is a read-merge-write cycle guarded by a
 * {@link KeyLocks} stripe. Reads (single and batched) are plain {@code get}/{@code multiGet}
 * calls with the requested property projection.
 */
public final class RocksDbVertexTable {

    /** Prefix of a vertex table (column family) name. */
    public static final String FAMILY_PREFIX = "vertex:";

    /** How many records one batched write merges before it writes them as a batch. */
    private static final int WRITE_CHUNK = 128;

    private final RocksDbHandle handle;
    private final GraphSchema schema;
    private final RocksDbStoreConfig config;
    private final KeyLocks locks;
    private final BusinessLog businessLog;

    public RocksDbVertexTable(RocksDbHandle handle, GraphSchema schema, RocksDbStoreConfig config,
                              KeyLocks locks, BusinessLog businessLog) {
        this.handle = Objects.requireNonNull(handle, "handle");
        this.schema = Objects.requireNonNull(schema, "schema");
        this.config = Objects.requireNonNull(config, "config");
        this.locks = Objects.requireNonNull(locks, "locks");
        this.businessLog = businessLog == null ? entry -> { } : businessLog;
    }

    /** The column family (table) of a vertex type. */
    public static String familyOf(String vertexName) {
        return FAMILY_PREFIX + vertexName;
    }

    /** The column family (table) of a vertex type. */
    public static String familyOf(VertexSchema vertexSchema) {
        return familyOf(vertexSchema.name());
    }

    /** The column families every vertex type of the schema needs. */
    public Set<String> columnFamilies() {
        Set<String> families = new LinkedHashSet<>();
        for (VertexSchema vertexSchema : schema.vertexSchemas()) {
            families.add(familyOf(vertexSchema));
        }
        return families;
    }

    /** The region a vertex id belongs to. */
    public int regionOf(Object vertexId) {
        return handle.regionOf(vertexId);
    }

    // --- reads ---

    /**
     * Reads one vertex, or {@code null} when it is absent. An empty property list reads
     * every stored property.
     */
    public RocksDbStoredVertex read(String vertexName, Object vertexId, List<Integer> propertyIds) {
        Record record = decodeRecord(handle.get(familyOf(vertexName), RocksDbKeys.vertexKey(vertexId)));
        return record == null ? null : toVertex(recordId(record, vertexId), vertexName, record, propertyIds);
    }

    /**
     * Reads many vertices of one vertex type in a single {@code multiGet}; the result
     * has one entry per id ({@code null} for an absent vertex).
     */
    public List<RocksDbStoredVertex> readAll(String vertexName, List<Object> vertexIds,
                                             List<Integer> propertyIds) {
        Objects.requireNonNull(vertexIds, "vertexIds");
        if (vertexIds.isEmpty()) {
            return List.of();
        }
        List<byte[]> values = handle.multiGet(familyOf(vertexName), vertexKeys(vertexIds));
        List<RocksDbStoredVertex> out = new ArrayList<>(vertexIds.size());
        for (int i = 0; i < vertexIds.size(); i++) {
            Record record = decodeRecord(values.get(i));
            out.add(record == null ? null
                    : toVertex(recordId(record, vertexIds.get(i)), vertexName, record, propertyIds));
        }
        return out;
    }

    /** The stored versions of one property, newest first (the history when configured). */
    public List<RocksDbPropertyValue> versions(String vertexName, Object vertexId, int propertyId) {
        Record record = decodeRecord(handle.get(familyOf(vertexName), RocksDbKeys.vertexKey(vertexId)));
        if (record == null) {
            return List.of();
        }
        List<RocksDbPropertyValue> chain = record.chains().get(propertyId);
        return chain == null ? List.of() : List.copyOf(chain);
    }

    /** Scans every vertex id of a vertex type (keys carry the id, so no value is read). */
    public List<Object> ids(String vertexName) {
        List<Object> ids = new ArrayList<>();
        try (RocksDbHandle.RocksDbRange range = handle.iterator(familyOf(vertexName), null, null)) {
            while (range.isValid()) {
                ids.add(idOfKey(range.key()));
                range.next();
            }
        }
        return ids;
    }

    /** Scans a vertex type, projecting the requested properties. */
    public void forEach(String vertexName, List<Integer> propertyIds, Consumer<RocksDbStoredVertex> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        try (RocksDbHandle.RocksDbRange range = handle.iterator(familyOf(vertexName), null, null)) {
            while (range.isValid()) {
                Record record = decodeRecord(range.value());
                if (record != null) {
                    consumer.accept(toVertex(recordId(record, idOfKey(range.key())), vertexName,
                            record, propertyIds));
                }
                range.next();
            }
        }
    }

    /** The number of records of a vertex type. */
    public long count(String vertexName) {
        long count = 0;
        try (RocksDbHandle.RocksDbRange range = handle.iterator(familyOf(vertexName), null, null)) {
            while (range.isValid()) {
                count++;
                range.next();
            }
        }
        return count;
    }

    // --- writes ---

    /** Applies one vertex upsert (read-merge-write under the vertex's lock). */
    public WriteOutcome upsert(RocksDbVertexUpsert upsert) {
        Objects.requireNonNull(upsert, "upsert");
        List<WriteOutcome> outcomes = upsertAll(List.of(upsert));
        return outcomes.isEmpty() ? WriteOutcome.FAILED : outcomes.get(0);
    }

    /**
     * Applies many vertex upserts. Records are merged in chunks: the chunk's locks are
     * taken, its records read in one {@code multiGet}, merged, and written in one
     * {@link WriteBatch} - so a bulk load batches its writes while every key still obeys
     * the version rule.
     *
     * @return one outcome per upsert, in the input order
     */
    public List<WriteOutcome> upsertAll(List<RocksDbVertexUpsert> upserts) {
        Objects.requireNonNull(upserts, "upserts");
        if (upserts.isEmpty()) {
            return List.of();
        }
        WriteOutcome[] outcomes = new WriteOutcome[upserts.size()];
        for (int start = 0; start < upserts.size(); start += WRITE_CHUNK) {
            int end = Math.min(start + WRITE_CHUNK, upserts.size());
            List<RocksDbVertexUpsert> chunk = upserts.subList(start, end);
            List<Object> keys = new ArrayList<>(chunk.size());
            for (RocksDbVertexUpsert upsert : chunk) {
                keys.add(upsert.vertexId());
            }
            final int offset = start;
            locks.withLocks(keys, () -> {
                applyChunk(chunk, outcomes, offset);
                return null;
            });
        }
        return List.of(outcomes);
    }

    private void applyChunk(List<RocksDbVertexUpsert> chunk, WriteOutcome[] outcomes, int offset) {
        Map<String, List<Object>> idsByFamily = new LinkedHashMap<>();
        for (RocksDbVertexUpsert upsert : chunk) {
            idsByFamily.computeIfAbsent(upsert.vertexName(), k -> new ArrayList<>()).add(upsert.vertexId());
        }
        Map<Object, Record> stored = new LinkedHashMap<>();
        for (Map.Entry<String, List<Object>> entry : idsByFamily.entrySet()) {
            List<byte[]> values = handle.multiGet(familyOf(entry.getKey()), vertexKeys(entry.getValue()));
            for (int i = 0; i < entry.getValue().size(); i++) {
                Object vertexId = entry.getValue().get(i);
                Record record = decodeRecord(values.get(i));
                if (record != null) {
                    stored.put(vertexId, record);
                }
            }
        }

        try (WriteBatch batch = handle.batch()) {
            for (int i = 0; i < chunk.size(); i++) {
                RocksDbVertexUpsert upsert = chunk.get(i);
                Record existing = stored.get(upsert.vertexId());
                Map<Integer, List<RocksDbPropertyValue>> merged = merge(existing, upsert);
                if (merged == null) {
                    outcomes[offset + i] = WriteOutcome.CONFLICT;
                    businessLog.record(BusinessLogEntry.failure("upsertVertex",
                            String.valueOf(upsert.vertexId()),
                            Map.of("table", familyOf(upsert.vertexName()), "stale", true)));
                    continue;
                }
                long createdTime = existing == null || existing.createdTime() == 0L
                        ? upsert.createdTime() : existing.createdTime();
                long lastUpdatedTime = existing == null ? 0L : existing.lastUpdatedTime();
                lastUpdatedTime = Math.max(lastUpdatedTime, upsert.lastUpdatedTime());
                for (RocksDbPropertyValue property : upsert.properties().values()) {
                    lastUpdatedTime = Math.max(lastUpdatedTime, property.version());
                }
                handle.put(batch, familyOf(upsert.vertexName()), RocksDbKeys.vertexKey(upsert.vertexId()),
                        encode(upsert.vertexId(), merged, createdTime, lastUpdatedTime));
                stored.put(upsert.vertexId(), new Record(RocksDbIdCodec.typeOf(upsert.vertexId()),
                        merged, createdTime, lastUpdatedTime));
                outcomes[offset + i] = WriteOutcome.SUCCESS;
                businessLog.record(BusinessLogEntry.success("upsertVertex",
                        String.valueOf(upsert.vertexId()),
                        Map.of("table", familyOf(upsert.vertexName()), "properties", upsert.properties().size())));
            }
            handle.write(batch);
        }
    }

    /** Deletes a vertex record. Returns whether a record was removed. */
    public boolean delete(String vertexName, Object vertexId) {
        return locks.withLock(vertexId, () -> {
            String family = familyOf(vertexName);
            byte[] key = RocksDbKeys.vertexKey(vertexId);
            boolean present = handle.get(family, key) != null;
            if (present) {
                handle.delete(family, key);
                businessLog.record(BusinessLogEntry.success("deleteVertex", String.valueOf(vertexId),
                        Map.of("table", family)));
            }
            return present;
        });
    }

    /**
     * Merges an upsert into the stored version chains following the per-property version
     * rule. Returns {@code null} when nothing changed (every incoming property was stale).
     */
    private Map<Integer, List<RocksDbPropertyValue>> merge(Record existing, RocksDbVertexUpsert upsert) {
        Map<Integer, List<RocksDbPropertyValue>> merged = new LinkedHashMap<>();
        if (existing != null) {
            for (Map.Entry<Integer, List<RocksDbPropertyValue>> entry : existing.chains().entrySet()) {
                merged.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
        }
        boolean changed = existing == null;
        int maxVersions = config.maxVersions();
        for (Map.Entry<Integer, RocksDbPropertyValue> entry : upsert.properties().entrySet()) {
            List<RocksDbPropertyValue> chain = merged.get(entry.getKey());
            RocksDbPropertyValue incoming = entry.getValue();
            if (chain == null || chain.isEmpty()) {
                List<RocksDbPropertyValue> created = new ArrayList<>();
                created.add(incoming);
                merged.put(entry.getKey(), created);
                changed = true;
            } else if (incoming.version() > chain.get(0).version()) {
                // the previous value becomes history, trimmed to the configured depth
                List<RocksDbPropertyValue> updated = new ArrayList<>();
                updated.add(incoming);
                updated.addAll(chain);
                while (updated.size() > maxVersions) {
                    updated.remove(updated.size() - 1);
                }
                merged.put(entry.getKey(), updated);
                changed = true;
            }
        }
        return changed ? merged : null;
    }

    // --- encoding ---

    /**
     * The stored form of a vertex:
     * <pre>
     * createdTime : lastUpdatedTime : propertyCount
     * per property: propertyId : versionCount : [ version : length : value ] ...
     * </pre>
     * The first version of a property is the current one; the rest is history.
     */
    private byte[] encode(Object vertexId, Map<Integer, List<RocksDbPropertyValue>> chains,
                          long createdTime, long lastUpdatedTime) {
        RocksDbBytes.Writer writer = new RocksDbBytes.Writer();
        writer.writeByte(RocksDbIdCodec.typeOf(vertexId));
        writer.writeLong(createdTime);
        writer.writeLong(lastUpdatedTime);
        writer.writeInt(chains.size());
        for (Map.Entry<Integer, List<RocksDbPropertyValue>> entry : chains.entrySet()) {
            writer.writeInt(entry.getKey());
            List<RocksDbPropertyValue> chain = entry.getValue();
            writer.writeInt(chain.size());
            for (RocksDbPropertyValue version : chain) {
                writer.writeLong(version.version());
                writer.writeBytes(RocksDbPropertyCodec.encode(version.value()));
            }
        }
        return writer.toByteArray();
    }

    /** A decoded record: the id type, the version chain of every property and the timestamps. */
    private record Record(byte idType, Map<Integer, List<RocksDbPropertyValue>> chains,
                          long createdTime, long lastUpdatedTime) {
    }

    /** Decodes a record, or {@code null} when the value is absent. */
    private static Record decodeRecord(byte[] value) {
        if (value == null) {
            return null;
        }
        RocksDbBytes.Reader reader = new RocksDbBytes.Reader(value);
        byte idType = reader.readByte();
        long createdTime = reader.readLong();
        long lastUpdatedTime = reader.readLong();
        int propertyCount = reader.readInt();
        Map<Integer, List<RocksDbPropertyValue>> chains = new LinkedHashMap<>();
        for (int i = 0; i < propertyCount; i++) {
            int propertyId = reader.readInt();
            int versionCount = reader.readInt();
            List<RocksDbPropertyValue> chain = new ArrayList<>(Math.max(versionCount, 0));
            for (int v = 0; v < versionCount; v++) {
                long version = reader.readLong();
                chain.add(new RocksDbPropertyValue(RocksDbPropertyCodec.decode(reader.readBytes()), version));
            }
            chains.put(propertyId, chain);
        }
        return new Record(idType, chains, createdTime, lastUpdatedTime);
    }

    /** The id of a record in its original type (the key holds the text form). */
    private static Object recordId(Record record, Object fallbackId) {
        return RocksDbIdCodec.decode(record.idType(), String.valueOf(fallbackId));
    }

    /** Projects the version chains onto the current values, honouring the projection. */
    private static RocksDbStoredVertex toVertex(Object vertexId, String vertexName, Record record,
                                                List<Integer> propertyIds) {
        Set<Integer> requested = propertyIds == null || propertyIds.isEmpty()
                ? Set.of() : Set.copyOf(propertyIds);
        Map<Integer, RocksDbPropertyValue> properties = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<RocksDbPropertyValue>> entry : record.chains().entrySet()) {
            if (requested.isEmpty() || requested.contains(entry.getKey())) {
                List<RocksDbPropertyValue> chain = entry.getValue();
                if (!chain.isEmpty()) {
                    properties.put(entry.getKey(), chain.get(0));
                }
            }
        }
        return new RocksDbStoredVertex(vertexId, vertexName, properties,
                record.createdTime(), record.lastUpdatedTime());
    }

    private static List<byte[]> vertexKeys(List<Object> vertexIds) {
        List<byte[]> keys = new ArrayList<>(vertexIds.size());
        for (Object vertexId : vertexIds) {
            keys.add(RocksDbKeys.vertexKey(vertexId));
        }
        return keys;
    }

    /** The id part of a vertex key ({@code hash : id}). */
    static Object idOfKey(byte[] key) {
        int from = RocksDbHash.HASH_BYTES + 1;
        if (key.length <= from) {
            throw new RocksDbException("Malformed vertex key of " + key.length + " bytes");
        }
        return RocksDbBytes.text(Arrays.copyOfRange(key, from, key.length));
    }
}
