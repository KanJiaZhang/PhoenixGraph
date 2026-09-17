package com.pengcheng.phoenixgraph.storage.aerospike;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.AerospikeException;
import com.aerospike.client.BatchRecord;
import com.aerospike.client.BatchWrite;
import com.aerospike.client.Bin;
import com.aerospike.client.Key;
import com.aerospike.client.Operation;
import com.aerospike.client.Record;
import com.aerospike.client.policy.BatchPolicy;
import com.aerospike.client.policy.BatchWritePolicy;
import com.aerospike.client.policy.WritePolicy;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.storage.BusinessLog;
import com.pengcheng.phoenixgraph.storage.BusinessLogEntry;
import com.pengcheng.phoenixgraph.storage.TimeRange;
import com.pengcheng.phoenixgraph.storage.log.JulBusinessLog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The aggregated Aerospike edge tables: writing them (bulk import) and reading a time window
 * out of them.
 *
 * <p>What a query does for a hub is walk the levels from the top: read the root chunk, keep the
 * children whose {@code [min, max]} overlaps the window, read those, and so on until level 0,
 * where the edges inside the window are taken from the chunk's CDT. Because a chunk key is its
 * span, a child is read without any extra index. The hub marker says whether a vertex has
 * aggregated edges at all (and how many levels), and it is cached for a minute, so the common
 * "this vertex is inline only" case costs nothing after the first look.
 */
public final class AerospikeEdgeChunkStore {

    /** How long a hub marker stays in the cache, in milliseconds. */
    public static final long HUB_CACHE_TTL_MILLIS = 60_000L;

    /** How many records one bulk write batch carries. */
    public static final int WRITE_BATCH_SIZE = 500;

    /** The most edges one window read returns (a safety net for a very wide window). */
    public static final int MAX_WINDOW_EDGES = 500_000;

    private final AerospikeClient client;
    private final String namespace;
    private final String baseSet;
    private final BusinessLog businessLog;
    private final Map<String, CachedHub> hubCache = new java.util.concurrent.ConcurrentHashMap<>();

    public AerospikeEdgeChunkStore(AerospikeClient client, String namespace, String baseSet) {
        this(client, namespace, baseSet, new JulBusinessLog());
    }

    public AerospikeEdgeChunkStore(AerospikeClient client, String namespace, String baseSet,
                                   BusinessLog businessLog) {
        this.client = Objects.requireNonNull(client, "client");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.baseSet = Objects.requireNonNull(baseSet, "baseSet");
        this.businessLog = businessLog == null ? entry -> { } : businessLog;
    }

    /** A cached hub marker with the moment it was read (the cache is a minute long). */
    private record CachedHub(long loadedAt, HubInfo info) {
    }

    /** What a hub marker says about a vertex: how many edges it has in the tables and how deep. */
    public record HubInfo(long edgeCount, int levels, long minCreatedTime, long maxCreatedTime) {

        /** Whether the aggregated tables hold any edge for the vertex. */
        public boolean isEmpty() {
            return edgeCount <= 0;
        }
    }

    // --- reading ---

    /** The hub marker of a vertex, or {@code null} when its edges are inline only. */
    public HubInfo hub(EdgeSchema edgeSchema, int directionCode, Object vertexId) {
        String cacheKey = AerospikeEdgeChunks.hubKey(edgeSchema, directionCode, vertexId);
        long now = System.currentTimeMillis();
        CachedHub cached = hubCache.get(cacheKey);
        if (cached != null && now - cached.loadedAt() < HUB_CACHE_TTL_MILLIS) {
            return cached.info().isEmpty() ? null : cached.info();
        }
        HubInfo loaded = readHub(edgeSchema, directionCode, vertexId);
        hubCache.put(cacheKey, new CachedHub(now, loaded == null ? new HubInfo(0, 0, 0, 0) : loaded));
        return loaded;
    }

    private HubInfo readHub(EdgeSchema edgeSchema, int directionCode, Object vertexId) {
        Record record = client.get(null, new Key(namespace, AerospikeEdgeChunks.hubSet(baseSet),
                AerospikeEdgeChunks.hubKey(edgeSchema, directionCode, vertexId)));
        if (record == null || record.bins == null) {
            return null;
        }
        return new HubInfo(number(record, AerospikeEdgeChunks.COUNT_BIN),
                (int) number(record, AerospikeEdgeChunks.LEVEL_BIN),
                number(record, AerospikeEdgeChunks.MIN_BIN),
                number(record, AerospikeEdgeChunks.MAX_BIN));
    }

    /**
     * Reads the edges of one vertex inside a window by descending the levels.
     *
     * @return the edges found, in creation-time order
     */
    public List<StoredEdgeValue> readWindow(EdgeSchema edgeSchema, int directionCode, Object vertexId,
                                            TimeRange range) {
        HubInfo hub = hub(edgeSchema, directionCode, vertexId);
        if (hub == null || hub.isEmpty()) {
            return List.of();
        }
        if (range == null || range.isUnbounded()) {
            throw new IllegalArgumentException("Vertex '" + vertexId + "' has " + hub.edgeCount()
                    + " edges in the aggregated edge table of " + edgeSchema.name()
                    + "; a query for it needs a creation-time window:"
                    + " add createdBetween(from, to) before the edge step");
        }
        String set = AerospikeEdgeChunks.edgeSet(baseSet, edgeSchema, directionCode);
        long from = Math.max(range.fromTime(), hub.minCreatedTime());
        long to = Math.min(range.toTime(), hub.maxCreatedTime());
        if (from > to) {
            return List.of();
        }

        List<AerospikeEdgeChunks.ChunkRecord> chunks = new ArrayList<>();
        if (hub.levels() <= 1) {
            chunks.add(new AerospikeEdgeChunks.ChunkRecord(0, from, to, null, null));
        } else {
            descend(set, vertexId, hub.levels() - 1, hub.minCreatedTime(), hub.maxCreatedTime(),
                    from, to, chunks);
        }

        List<StoredEdgeValue> edges = new ArrayList<>();
        for (AerospikeEdgeChunks.ChunkRecord chunk : chunks) {
            if (edges.size() >= MAX_WINDOW_EDGES) {
                break;
            }
            Record record = client.get(null, chunkKey(set, vertexId, chunk));
            if (record == null || record.bins == null) {
                continue;
            }
            readEdges(record, from, to, edges);
        }
        edges.sort((a, b) -> {
            int byTime = Long.compare(a.createdTime(), b.createdTime());
            return byTime != 0 ? byTime : a.edgeId().compareTo(b.edgeId());
        });
        return List.copyOf(edges);
    }

    /** Walks one level down, collecting the level-0 chunks whose span overlaps the window. */
    private void descend(String set, Object vertexId, int level, long min, long max,
                         long from, long to, List<AerospikeEdgeChunks.ChunkRecord> chunks) {
        Record record = client.get(null, new Key(namespace, set,
                AerospikeEdgeChunks.chunkKey(vertexId, min, max, level)));
        if (record == null || record.bins == null) {
            return;
        }
        List<long[]> children = children(record);
        for (long[] span : AerospikeEdgeChunks.selectChildren(children, from, to)) {
            if (level == 1) {
                chunks.add(new AerospikeEdgeChunks.ChunkRecord(0, span[0], span[1], null, null));
            } else {
                descend(set, vertexId, level - 1, span[0], span[1], from, to, chunks);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<long[]> children(Record record) {
        Object raw = record.bins.get(AerospikeEdgeChunks.CHILDREN_BIN);
        List<long[]> spans = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof List<?> pair && pair.size() == 2) {
                    spans.add(new long[]{((Number) pair.get(0)).longValue(),
                            ((Number) pair.get(1)).longValue()});
                }
            }
        }
        return spans;
    }

    @SuppressWarnings("unchecked")
    private void readEdges(Record record, long from, long to, List<StoredEdgeValue> out) {
        Object raw = record.bins.get(AerospikeEdgeChunks.EDGES_BIN);
        if (!(raw instanceof Map<?, ?> edges)) {
            return;
        }
        for (Map.Entry<?, ?> entry : edges.entrySet()) {
            String edgeId = String.valueOf(entry.getKey());
            StoredEdgeValue edge = AerospikeEdgeStore.deserializeEdgeValue(edgeId, entry.getValue());
            if (edge.createdTime() >= from && edge.createdTime() <= to) {
                out.add(edge);
            }
        }
    }

    // --- writing (the bulk import) ---

    /**
     * Writes the levels of one vertex in bulk: the caller has already turned the edges into
     * chunks, this only generates the records and writes them in batches.
     *
     * @return how many records were written
     */
    public int writeChunks(EdgeSchema edgeSchema, int directionCode, Object vertexId, long edgeCount,
                           List<AerospikeEdgeChunks.ChunkRecord> records) {
        if (records.isEmpty()) {
            return 0;
        }
        String set = AerospikeEdgeChunks.edgeSet(baseSet, edgeSchema, directionCode);
        List<BatchRecord> writes = new ArrayList<>();
        int written = 0;
        for (AerospikeEdgeChunks.ChunkRecord record : records) {
            List<Operation> ops = new ArrayList<>();
            ops.add(Operation.put(new Bin(AerospikeEdgeChunks.VERTEX_BIN, String.valueOf(vertexId))));
            ops.add(Operation.put(new Bin(AerospikeEdgeChunks.LEVEL_BIN, record.level())));
            ops.add(Operation.put(new Bin(AerospikeEdgeChunks.MIN_BIN, record.min())));
            ops.add(Operation.put(new Bin(AerospikeEdgeChunks.MAX_BIN, record.max())));
            if (record.level() == 0) {
                Map<String, Object> edges = new LinkedHashMap<>();
                for (AerospikeEdgeChunks.EdgeEntry edge : record.edges()) {
                    edges.put(edge.edgeId(), edge.value());
                }
                ops.add(Operation.put(new Bin(AerospikeEdgeChunks.COUNT_BIN, edges.size())));
                ops.add(Operation.put(new Bin(AerospikeEdgeChunks.EDGES_BIN, edges)));
            } else {
                List<List<Object>> spans = new ArrayList<>();
                for (long[] span : record.children()) {
                    spans.add(List.of(span[0], span[1]));
                }
                ops.add(Operation.put(new Bin(AerospikeEdgeChunks.COUNT_BIN, spans.size())));
                ops.add(Operation.put(new Bin(AerospikeEdgeChunks.CHILDREN_BIN, spans)));
            }
            writes.add(new BatchWrite(new BatchWritePolicy(),
                    new Key(namespace, set, record.key(vertexId)),
                    ops.toArray(new Operation[0])));
            written++;
            if (writes.size() >= WRITE_BATCH_SIZE) {
                flush(writes);
            }
        }
        flush(writes);

        // the marker the query looks at first: how many levels there are and what they cover
        AerospikeEdgeChunks.ChunkRecord root = records.get(records.size() - 1);
        client.put(new WritePolicy(), new Key(namespace, AerospikeEdgeChunks.hubSet(baseSet),
                        AerospikeEdgeChunks.hubKey(edgeSchema, directionCode, vertexId)),
                new Bin(AerospikeEdgeChunks.VERTEX_BIN, String.valueOf(vertexId)),
                new Bin(AerospikeEdgeChunks.LEVEL_BIN, root.level() + 1),
                new Bin(AerospikeEdgeChunks.MIN_BIN, root.min()),
                new Bin(AerospikeEdgeChunks.MAX_BIN, root.max()),
                new Bin(AerospikeEdgeChunks.COUNT_BIN, edgeCount));
        hubCache.remove(AerospikeEdgeChunks.hubKey(edgeSchema, directionCode, vertexId));
        businessLog.record(BusinessLogEntry.success("writeEdgeChunks", String.valueOf(vertexId),
                Map.of("edgeClass", edgeSchema.name(), "direction", directionCode,
                        "edges", edgeCount, "records", written, "levels", root.level() + 1)));
        return written;
    }

    private void flush(List<BatchRecord> writes) {
        if (writes.isEmpty()) {
            return;
        }
        try {
            client.operate(new BatchPolicy(), writes);
        } catch (AerospikeException e) {
            throw new IllegalStateException("Bulk edge write failed: " + e.getMessage(), e);
        }
        writes.clear();
    }

    /** Drops the cached hub markers (a bulk import in this process invalidates them). */
    public void invalidateHubCache() {
        hubCache.clear();
    }

    private Key chunkKey(String set, Object vertexId, AerospikeEdgeChunks.ChunkRecord chunk) {
        return new Key(namespace, set, chunk.key(vertexId));
    }

    private static long number(Record record, String bin) {
        Object value = record.bins.get(bin);
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
