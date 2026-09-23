package com.pengcheng.phoenixgraph.storage.rocksdb.edge;

import com.pengcheng.phoenixgraph.hnsw.HnswEntryPoint;
import com.pengcheng.phoenixgraph.hnsw.HnswMetadata;
import com.pengcheng.phoenixgraph.hnsw.HnswMetadataStore;
import com.pengcheng.phoenixgraph.hnsw.HnswMetric;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbHandle;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The HNSW metadata record of a RocksDB graph: one key-value row inside the graph's own directory.
 *
 * <p>It holds what a search needs before it reads a single layer: the entry points, the size, the layer
 * count and the build configuration. Keeping it in the same directory as the graph is what makes a
 * RocksDB graph a self-contained dataset - one directory to copy, and no second service to run. The
 * counterpart for Aerospike is {@code AerospikeHnswMetadataStore}, which keeps the same fields in a
 * record; the row is written through {@link RocksDbHandle}, so the column family appears on demand and
 * no schema declares it.
 *
 * <p>The value is a line of {@code key=value} pairs. A record is read and written whole and only by this
 * class, so a compact text form is easier to inspect (in a RocksDB dump or a test) than a binary one.
 */
public final class RocksDbHnswMetadataStore implements HnswMetadataStore {

    /** Column family holding the metadata rows of a graph directory. */
    public static final String FAMILY = "hnsw:meta";

    private static final String VERSION = "version";
    private static final String VERTEX = "vertex";
    private static final String VECTOR_PROPERTY = "vectorProperty";
    private static final String METRIC = "metric";
    private static final String M = "m";
    private static final String EF_CONSTRUCTION = "efConstruction";
    private static final String EF_SEARCH = "efSearch";
    private static final String DIMENSIONS = "dimensions";
    private static final String SIZE = "size";
    private static final String TOP_LEVEL = "topLevel";
    private static final String ENTRY_IDS = "entryIds";
    private static final String ENTRY_LEVELS = "entryLevels";
    private static final String UPDATED_AT = "updatedAt";

    private final RocksDbHandle handle;
    private final String recordKey;

    /**
     * @param handle    an open RocksDB directory (the graph's own)
     * @param recordKey row key: {@code hnsw:<vertex>} for the first vector property of a vertex type,
     *                  {@code hnsw:<vertex>:<property>} when a type carries more than one
     */
    public RocksDbHnswMetadataStore(RocksDbHandle handle, String recordKey) {
        this.handle = Objects.requireNonNull(handle, "handle");
        this.recordKey = Objects.requireNonNull(recordKey, "recordKey");
    }

    @Override
    public HnswMetadata read() {
        byte[] value = handle.get(FAMILY, key());
        if (value == null || value.length == 0) {
            return null;
        }
        Map<String, String> fields = decode(new String(value, StandardCharsets.UTF_8));
        List<HnswEntryPoint> entryPoints = new ArrayList<>();
        List<String> ids = split(fields.get(ENTRY_IDS));
        List<String> levels = split(fields.get(ENTRY_LEVELS));
        for (int i = 0; i < ids.size() && i < levels.size(); i++) {
            entryPoints.add(new HnswEntryPoint(ids.get(i), number(levels.get(i), 0)));
        }
        return new HnswMetadata(number(fields.get(VERSION), HnswMetadata.CURRENT_VERSION),
                fields.get(VERTEX), fields.get(VECTOR_PROPERTY),
                HnswMetric.of(fields.get(METRIC)),
                number(fields.get(M), 15), number(fields.get(EF_CONSTRUCTION), 100),
                number(fields.get(EF_SEARCH), 50), number(fields.get(DIMENSIONS), 0),
                (long) number(fields.get(SIZE), 0), number(fields.get(TOP_LEVEL), 0),
                entryPoints, (long) number(fields.get(UPDATED_AT), 0));
    }

    @Override
    public void write(HnswMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        List<String> ids = new ArrayList<>();
        List<String> levels = new ArrayList<>();
        for (HnswEntryPoint point : metadata.entryPoints()) {
            ids.add(String.valueOf(point.vertexId()));
            levels.add(String.valueOf(point.level()));
        }
        StringBuilder out = new StringBuilder();
        // the two entry-point lists are the only values that may contain a separator, so they come last
        put(out, VERSION, metadata.version());
        put(out, VERTEX, metadata.vertexName());
        put(out, VECTOR_PROPERTY, metadata.vectorProperty());
        put(out, METRIC, metadata.metric().name());
        put(out, M, metadata.m());
        put(out, EF_CONSTRUCTION, metadata.efConstruction());
        put(out, EF_SEARCH, metadata.efSearch());
        put(out, DIMENSIONS, metadata.dimensions());
        put(out, SIZE, metadata.size());
        put(out, TOP_LEVEL, metadata.topLevel());
        put(out, UPDATED_AT, metadata.updatedAt());
        put(out, ENTRY_IDS, String.join(",", ids));
        put(out, ENTRY_LEVELS, String.join(",", levels));
        handle.put(FAMILY, key(), out.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Deletes the metadata row (a rebuild with {@code overwrite} starts from scratch). */
    public void delete() {
        handle.delete(FAMILY, key());
    }

    /** The row key, for diagnostics. */
    public String describeKey() {
        return handle.path() + "/" + FAMILY + "/" + recordKey;
    }

    private byte[] key() {
        return recordKey.getBytes(StandardCharsets.UTF_8);
    }

    private static void put(StringBuilder out, String name, Object value) {
        if (out.length() > 0) {
            out.append('\n');
        }
        out.append(name).append('=').append(value);
    }

    private static Map<String, String> decode(String text) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            int separator = line.indexOf('=');
            if (separator > 0) {
                fields.put(line.substring(0, separator), line.substring(separator + 1));
            }
        }
        return fields;
    }

    private static List<String> split(String value) {
        if (value == null || value.isEmpty()) {
            return List.of();
        }
        return List.of(value.split(","));
    }

    private static int number(String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
