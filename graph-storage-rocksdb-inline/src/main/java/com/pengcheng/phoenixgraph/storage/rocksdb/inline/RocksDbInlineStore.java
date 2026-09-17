package com.pengcheng.phoenixgraph.storage.rocksdb.inline;

import com.pengcheng.phoenixgraph.schema.EdgeDirection;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.storage.BusinessLog;
import com.pengcheng.phoenixgraph.storage.BusinessLogEntry;
import com.pengcheng.phoenixgraph.storage.WriteOutcome;
import com.pengcheng.phoenixgraph.storage.rocksdb.KeyLocks;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbEdgeProperty;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbEdgeUpsert;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbHandle;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbKeys;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbLruCache;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbStoreConfig;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbStoredEdge;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbStoredVertex;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbVertexTable;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbVertexUpsert;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The RocksDB-Inline storage scheme.
 *
 * <p><b>Vertices</b> live in one table per vertex type (see {@link RocksDbVertexTable}) and
 * <b>edges are folded into the record of the edge class</b>: every edge type gets its own
 * column family, and inside it a vertex holds one entry per Aerospike bin name
 * ({@code ve_<schemaId>_<direction>}) with all of its edges of that class and direction.
 * So a vertex's neighbourhood is one record read, and the degree limits of the schema are
 * enforceable: an update reads the bin, merges the new edges by their per-property
 * timestamps and drops the oldest edges beyond {@code maxOutDegree}/{@code maxInDegree}/
 * {@code maxDegree}.
 *
 * <p>An edge is stored twice - in the outgoing bin of its source and the incoming bin of
 * its destination - which is what makes {@code out()} and {@code in()} a single lookup
 * from either end. Nothing else is denormalised: the vertex tables do not know about edges.
 */
public final class RocksDbInlineStore implements AutoCloseable {

    /** Prefix of an edge table (column family) name. */
    public static final String FAMILY_PREFIX = "edge-inline:";

    /** How many decoded edge records are cached. */
    public static final int DEFAULT_CACHE_SIZE = 4_096;

    private final RocksDbHandle handle;
    private final GraphSchema schema;
    private final RocksDbStoreConfig config;
    private final KeyLocks locks;
    private final BusinessLog businessLog;
    private final RocksDbVertexTable vertexTable;
    private final RocksDbLruCache<List<RocksDbInlineEdgeCodec.Bin>> edgeCache;

    private RocksDbInlineStore(RocksDbHandle handle, GraphSchema schema, RocksDbStoreConfig config,
                               BusinessLog businessLog) {
        this.handle = handle;
        this.schema = schema;
        this.config = config;
        this.businessLog = businessLog == null ? entry -> { } : businessLog;
        this.locks = new KeyLocks();
        this.vertexTable = new RocksDbVertexTable(handle, schema, config, locks, businessLog);
        this.edgeCache = new RocksDbLruCache<>(DEFAULT_CACHE_SIZE);
    }

    /** Opens (creating it if needed) the RocksDB directory of a graph. */
    public static RocksDbInlineStore open(GraphSchema schema, RocksDbStoreConfig config) {
        return open(schema, config, null);
    }

    /** Opens the store, recording key writes in {@code businessLog} when it is given. */
    public static RocksDbInlineStore open(GraphSchema schema, RocksDbStoreConfig config, BusinessLog businessLog) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(config, "config");
        return new RocksDbInlineStore(RocksDbHandle.open(config, columnFamiliesOf(schema)),
                schema, config, businessLog);
    }

    /** Opens the store at a path with default settings. */
    public static RocksDbInlineStore open(GraphSchema schema, String path) {
        return open(schema, RocksDbStoreConfig.of(path), null);
    }

    /** The column families a graph of this schema needs (vertex tables plus edge tables). */
    public static Set<String> columnFamiliesOf(GraphSchema schema) {
        Set<String> families = new LinkedHashSet<>();
        for (com.pengcheng.phoenixgraph.schema.VertexSchema vertexSchema : schema.vertexSchemas()) {
            families.add(RocksDbVertexTable.familyOf(vertexSchema));
        }
        for (EdgeSchema edgeSchema : schema.edgeSchemas()) {
            families.add(familyOf(edgeSchema));
        }
        return families;
    }

    /** The column family (table) of an edge class. */
    public static String familyOf(EdgeSchema edgeSchema) {
        return familyOf(edgeSchema.name());
    }

    /** The column family (table) of an edge class. */
    public static String familyOf(String edgeName) {
        return FAMILY_PREFIX + edgeName;
    }

    // --- accessors ---

    /** The vertex tables. */
    public RocksDbVertexTable vertices() {
        return vertexTable;
    }

    public GraphSchema schema() {
        return schema;
    }

    public RocksDbHandle handle() {
        return handle;
    }

    public RocksDbStoreConfig config() {
        return config;
    }

    /** The column families that exist in this store. */
    public Set<String> columnFamilies() {
        return handle.columnFamilies();
    }

    /** The region a vertex id belongs to. */
    public int regionOf(Object vertexId) {
        return handle.regionOf(vertexId);
    }

    /** Flushes memtables to disk. */
    public void flush() {
        handle.flush();
    }

    // --- vertices ---

    /** Writes one vertex (per-property version rule). */
    public WriteOutcome upsertVertex(RocksDbVertexUpsert upsert) {
        return vertexTable.upsert(upsert);
    }

    /** Writes many vertices (batched read-merge-write per chunk). */
    public List<WriteOutcome> upsertVertices(List<RocksDbVertexUpsert> upserts) {
        return vertexTable.upsertAll(upserts);
    }

    /** Reads one vertex if present. */
    public RocksDbStoredVertex readVertex(String vertexName, Object vertexId, List<Integer> propertyIds) {
        return vertexTable.read(vertexName, vertexId, propertyIds);
    }

    /** Reads many vertices of one type in a single batched call. */
    public List<RocksDbStoredVertex> readVertices(String vertexName, List<Object> vertexIds,
                                                  List<Integer> propertyIds) {
        return vertexTable.readAll(vertexName, vertexIds, propertyIds);
    }

    /** Deletes a vertex record. */
    public boolean deleteVertex(String vertexName, Object vertexId) {
        return vertexTable.delete(vertexName, vertexId);
    }

    // --- edges ---

    /** Writes one edge (both endpoints). */
    public WriteOutcome upsertEdge(RocksDbEdgeUpsert edge) {
        List<WriteOutcome> outcomes = upsertEdges(List.of(edge));
        return outcomes.isEmpty() ? WriteOutcome.FAILED : outcomes.get(0);
    }

    /**
     * Writes many edges of one edge class. Every edge is written into the outgoing bin of
     * its source and the incoming bin of its destination; the bins are then read once,
     * merged (per-property timestamps) and trimmed to the schema's degree limit.
     *
     * @return one outcome per edge, in the input order
     */
    public List<WriteOutcome> upsertEdges(List<RocksDbEdgeUpsert> edges) {
        Objects.requireNonNull(edges, "edges");
        if (edges.isEmpty()) {
            return List.of();
        }
        EdgeSchema edgeSchema = edges.get(0).edgeSchema();
        for (RocksDbEdgeUpsert edge : edges) {
            if (edge.edgeSchema().id() != edgeSchema.id()) {
                throw new IllegalArgumentException("All edges in a batch must share one edge class (got "
                        + edgeSchema.name() + " and " + edge.edgeSchema().name() + ")");
            }
        }

        // plan the writes: one entry per (vertex, bin) side of every edge
        List<EdgeSideWrite> writes = new ArrayList<>(edges.size() * 2);
        for (RocksDbEdgeUpsert edge : edges) {
            addSideWrites(writes, edge, edgeSchema);
        }
        Map<Object, Map<String, Map<String, RocksDbStoredEdge>>> byVertex = new LinkedHashMap<>();
        for (EdgeSideWrite write : writes) {
            byVertex.computeIfAbsent(write.vertexId(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(write.binName(), k -> new LinkedHashMap<>())
                    .put(write.edgeId(), write.stored());
        }

        // one locked read-merge-write per vertex record
        Map<Object, Boolean> written = new LinkedHashMap<>();
        String family = familyOf(edgeSchema);
        for (Map.Entry<Object, Map<String, Map<String, RocksDbStoredEdge>>> entry : byVertex.entrySet()) {
            Object vertexId = entry.getKey();
            boolean ok = locks.withLock(lockKey(family, vertexId), () -> {
                List<RocksDbInlineEdgeCodec.Bin> bins = readBins(family, vertexId);
                List<RocksDbInlineEdgeCodec.Bin> updated = bins;
                for (Map.Entry<String, Map<String, RocksDbStoredEdge>> binEntry : entry.getValue().entrySet()) {
                    String binName = binEntry.getKey();
                    int directionCode = directionOfBin(edgeSchema, binName);
                    int limit = RocksDbEdgeBins.degreeLimit(edgeSchema, directionCode);
                    RocksDbInlineEdgeCodec.Bin merged = RocksDbInlineEdgeCodec.merge(
                            RocksDbInlineEdgeCodec.bin(updated, binName), binEntry.getValue(), limit);
                    updated = RocksDbInlineEdgeCodec.withBin(updated, merged);
                }
                writeBins(family, vertexId, updated);
                return Boolean.TRUE;
            });
            written.put(vertexId, Boolean.TRUE.equals(ok));
        }

        List<WriteOutcome> outcomes = new ArrayList<>(edges.size());
        for (RocksDbEdgeUpsert edge : edges) {
            boolean ok = Boolean.TRUE.equals(written.get(edge.source()));
            if (ok && !Objects.equals(edge.source(), edge.destination())) {
                ok = Boolean.TRUE.equals(written.get(edge.destination()));
            }
            outcomes.add(ok ? WriteOutcome.SUCCESS : WriteOutcome.FAILED);
        }
        return outcomes;
    }

    /** Adds the two sides of one edge to the write plan. */
    private void addSideWrites(List<EdgeSideWrite> writes, RocksDbEdgeUpsert edge, EdgeSchema edgeSchema) {
        if (edgeSchema.direction() == EdgeDirection.DIRECTED) {
            writes.add(new EdgeSideWrite(edge.source(), RocksDbEdgeBins.bin(edgeSchema, RocksDbEdgeBins.DIR_OUT),
                    String.valueOf(edge.edgeId()), storedEdge(edge, edge.destination())));
            if (!Objects.equals(edge.source(), edge.destination())) {
                writes.add(new EdgeSideWrite(edge.destination(),
                        RocksDbEdgeBins.bin(edgeSchema, RocksDbEdgeBins.DIR_IN), String.valueOf(edge.edgeId()),
                        storedEdge(edge, edge.source())));
            }
            return;
        }
        // UNDIRECTED / BIDIRECTIONAL: one bin on each endpoint, no in/out distinction
        writes.add(new EdgeSideWrite(edge.source(), RocksDbEdgeBins.bin(edgeSchema, RocksDbEdgeBins.DIR_BOTH),
                String.valueOf(edge.edgeId()), storedEdge(edge, edge.destination())));
        if (!Objects.equals(edge.source(), edge.destination())) {
            writes.add(new EdgeSideWrite(edge.destination(),
                    RocksDbEdgeBins.bin(edgeSchema, RocksDbEdgeBins.DIR_BOTH), String.valueOf(edge.edgeId()),
                    storedEdge(edge, edge.source())));
        }
    }

    private static RocksDbStoredEdge storedEdge(RocksDbEdgeUpsert edge, Object otherVertexId) {
        long lastUpdatedTime = edge.lastUpdatedTime();
        for (RocksDbEdgeProperty property : edge.properties().values()) {
            lastUpdatedTime = Math.max(lastUpdatedTime, property.lastUpdatedTime());
        }
        return new RocksDbStoredEdge(String.valueOf(edge.edgeId()), otherVertexId,
                edge.createdTime(), lastUpdatedTime, edge.properties());
    }

    /** Reads the edges of one edge class attached to a vertex. */
    public List<RocksDbStoredEdge> readEdges(String edgeName, Object vertexId, int directionCode,
                                             List<Integer> propertyIds) {
        EdgeSchema edgeSchema = requireEdge(edgeName);
        Set<Integer> requested = propertyIds == null || propertyIds.isEmpty()
                ? Set.of() : Set.copyOf(propertyIds);
        return selectEdges(edgeSchema, readBins(familyOf(edgeSchema), vertexId), directionCode, requested);
    }

    /** Picks the bins a direction asks for and projects them onto the requested properties. */
    private static List<RocksDbStoredEdge> selectEdges(EdgeSchema edgeSchema,
                                                       List<RocksDbInlineEdgeCodec.Bin> bins,
                                                       int directionCode, Set<Integer> requested) {
        List<RocksDbStoredEdge> edges = new ArrayList<>();
        if (edgeSchema.direction() != EdgeDirection.DIRECTED) {
            // no in/out distinction: every direction reads the single undirected bin
            edges.addAll(RocksDbInlineEdgeCodec.project(
                    RocksDbInlineEdgeCodec.bin(bins, RocksDbEdgeBins.bin(edgeSchema, RocksDbEdgeBins.DIR_BOTH)),
                    requested));
        } else {
            if (directionCode == RocksDbEdgeBins.DIR_OUT || directionCode == RocksDbEdgeBins.DIR_BOTH) {
                edges.addAll(RocksDbInlineEdgeCodec.project(
                        RocksDbInlineEdgeCodec.bin(bins, RocksDbEdgeBins.bin(edgeSchema, RocksDbEdgeBins.DIR_OUT)),
                        requested));
            }
            if (directionCode == RocksDbEdgeBins.DIR_IN || directionCode == RocksDbEdgeBins.DIR_BOTH) {
                edges.addAll(RocksDbInlineEdgeCodec.project(
                        RocksDbInlineEdgeCodec.bin(bins, RocksDbEdgeBins.bin(edgeSchema, RocksDbEdgeBins.DIR_IN)),
                        requested));
            }
        }
        edges.sort(Comparator.comparingLong(RocksDbStoredEdge::lastUpdatedTime).reversed()
                .thenComparing(RocksDbStoredEdge::edgeId));
        return edges;
    }

    /**
     * Reads the edges of one edge class for many vertices, one batched call per column
     * family (an absent record simply has no edges).
     */
    public List<List<RocksDbStoredEdge>> readEdgesBatch(String edgeName, int directionCode,
                                                        List<Object> vertexIds, List<Integer> propertyIds) {
        Objects.requireNonNull(vertexIds, "vertexIds");
        if (vertexIds.isEmpty()) {
            return List.of();
        }
        EdgeSchema edgeSchema = requireEdge(edgeName);
        Set<Integer> requested = propertyIds == null || propertyIds.isEmpty()
                ? Set.of() : Set.copyOf(propertyIds);
        String family = familyOf(edgeSchema);

        List<byte[]> keys = new ArrayList<>(vertexIds.size());
        for (Object vertexId : vertexIds) {
            keys.add(RocksDbKeys.vertexKey(vertexId));
        }
        // the cache holds the decoded bins of every vertex, so a traversal that asks twice
        // for the same neighbourhood decodes it once
        List<byte[]> records = new ArrayList<>(vertexIds.size());
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < vertexIds.size(); i++) {
            List<RocksDbInlineEdgeCodec.Bin> cached = config.cacheEdgeLists()
                    ? edgeCache.get(lockKey(family, vertexIds.get(i))) : null;
            if (cached == null) {
                records.add(null);
                missing.add(i);
            } else {
                records.add(null);
            }
        }
        if (!missing.isEmpty()) {
            List<byte[]> loaded = handle.multiGet(family, keys);
            for (int i = 0; i < vertexIds.size(); i++) {
                byte[] value = loaded.get(i);
                List<RocksDbInlineEdgeCodec.Bin> bins = RocksDbInlineEdgeCodec.decode(value);
                records.set(i, value);
                if (config.cacheEdgeLists()) {
                    edgeCache.put(lockKey(family, vertexIds.get(i)), bins);
                }
            }
        }

        List<List<RocksDbStoredEdge>> out = new ArrayList<>(vertexIds.size());
        for (int i = 0; i < vertexIds.size(); i++) {
            List<RocksDbInlineEdgeCodec.Bin> bins;
            if (config.cacheEdgeLists()) {
                bins = edgeCache.get(lockKey(family, vertexIds.get(i)));
                if (bins == null) {
                    bins = RocksDbInlineEdgeCodec.decode(records.get(i));
                    edgeCache.put(lockKey(family, vertexIds.get(i)), bins);
                }
            } else {
                bins = RocksDbInlineEdgeCodec.decode(records.get(i));
            }
            out.add(selectEdges(edgeSchema, bins, directionCode, requested));
        }
        return out;
    }

    /** Deletes an edge from both endpoints. Returns whether it was present at the source. */
    public boolean deleteEdge(EdgeSchema edgeSchema, Object edgeId, Object source, Object destination) {
        String family = familyOf(edgeSchema);
        boolean directed = edgeSchema.direction() == EdgeDirection.DIRECTED;
        boolean removed;
        if (directed) {
            removed = removeFromBin(family, source, RocksDbEdgeBins.bin(edgeSchema, RocksDbEdgeBins.DIR_OUT),
                    String.valueOf(edgeId));
            if (!Objects.equals(source, destination)) {
                removeFromBin(family, destination, RocksDbEdgeBins.bin(edgeSchema, RocksDbEdgeBins.DIR_IN),
                        String.valueOf(edgeId));
            }
        } else {
            removed = removeFromBin(family, source, RocksDbEdgeBins.bin(edgeSchema, RocksDbEdgeBins.DIR_BOTH),
                    String.valueOf(edgeId));
            if (!Objects.equals(source, destination)) {
                removeFromBin(family, destination, RocksDbEdgeBins.bin(edgeSchema, RocksDbEdgeBins.DIR_BOTH),
                        String.valueOf(edgeId));
            }
        }
        return removed;
    }

    private boolean removeFromBin(String family, Object vertexId, String binName, String edgeId) {
        return locks.withLock(lockKey(family, vertexId), () -> {
            List<RocksDbInlineEdgeCodec.Bin> bins = readBins(family, vertexId);
            RocksDbInlineEdgeCodec.Bin bin = RocksDbInlineEdgeCodec.bin(bins, binName);
            boolean present = bin.edges().containsKey(edgeId);
            List<RocksDbInlineEdgeCodec.Bin> updated =
                    RocksDbInlineEdgeCodec.withBin(bins, RocksDbInlineEdgeCodec.withoutEdge(bins, binName, edgeId));
            writeBins(family, vertexId, updated);
            if (present) {
                businessLog.record(BusinessLogEntry.success("deleteEdge", edgeId,
                        Map.of("table", family, "bin", binName, "vertexId", String.valueOf(vertexId))));
            }
            return present;
        });
    }

    /** The bins stored for a vertex of an edge class (cached). */
    private List<RocksDbInlineEdgeCodec.Bin> readBins(String family, Object vertexId) {
        String key = lockKey(family, vertexId);
        if (!config.cacheEdgeLists()) {
            return RocksDbInlineEdgeCodec.decode(handle.get(family, RocksDbKeys.vertexKey(vertexId)));
        }
        return edgeCache.get(key, () ->
                RocksDbInlineEdgeCodec.decode(handle.get(family, RocksDbKeys.vertexKey(vertexId))));
    }

    /** Writes the bins of a vertex, dropping the record when it holds no edge at all. */
    private void writeBins(String family, Object vertexId, List<RocksDbInlineEdgeCodec.Bin> bins) {
        byte[] key = RocksDbKeys.vertexKey(vertexId);
        if (RocksDbInlineEdgeCodec.isEmpty(bins)) {
            handle.delete(family, key);
        } else {
            handle.put(family, key, RocksDbInlineEdgeCodec.encode(bins));
        }
        edgeCache.invalidate(lockKey(family, vertexId));
    }

    /** The number of edges of one class stored across all vertices (both sides counted). */
    public long edgeCount(String edgeName) {
        EdgeSchema edgeSchema = requireEdge(edgeName);
        long count = 0;
        try (RocksDbHandle.RocksDbRange range = handle.iterator(familyOf(edgeSchema), null, null)) {
            while (range.isValid()) {
                for (RocksDbInlineEdgeCodec.Bin bin : RocksDbInlineEdgeCodec.decode(range.value())) {
                    count += bin.edges().size();
                }
                range.next();
            }
        }
        return count;
    }

    /** The bins of one vertex of one edge class (diagnostics and tests). */
    public Map<String, Integer> binSizes(String edgeName, Object vertexId) {
        EdgeSchema edgeSchema = requireEdge(edgeName);
        Map<String, Integer> sizes = new LinkedHashMap<>();
        for (RocksDbInlineEdgeCodec.Bin bin : readBins(familyOf(edgeSchema), vertexId)) {
            sizes.put(bin.name(), bin.edges().size());
        }
        return sizes;
    }

    /** The cache in front of the decoded edge records. */
    public RocksDbLruCache<List<RocksDbInlineEdgeCodec.Bin>> edgeCache() {
        return edgeCache;
    }

    private int directionOfBin(EdgeSchema edgeSchema, String binName) {
        for (int direction : new int[]{RocksDbEdgeBins.DIR_OUT, RocksDbEdgeBins.DIR_IN,
                RocksDbEdgeBins.DIR_BOTH}) {
            if (RocksDbEdgeBins.bin(edgeSchema, direction).equals(binName)) {
                return direction;
            }
        }
        return RocksDbEdgeBins.DIR_BOTH;
    }

    private EdgeSchema requireEdge(String edgeName) {
        EdgeSchema edgeSchema = schema.edgeSchema(edgeName);
        if (edgeSchema == null) {
            throw new IllegalArgumentException("Unknown edge schema: " + edgeName);
        }
        return edgeSchema;
    }

    private static String lockKey(String family, Object vertexId) {
        return family + '\u0000' + vertexId;
    }

    @Override
    public void close() {
        edgeCache.clear();
        handle.close();
    }

    /** One side of an edge: the vertex whose record holds it, the bin, and the stored edge. */
    private record EdgeSideWrite(Object vertexId, String binName, String edgeId, RocksDbStoredEdge stored) {
    }
}
