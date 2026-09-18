package com.pengcheng.phoenixgraph.storage.rocksdb.edge;

import com.pengcheng.phoenixgraph.schema.EdgeDirection;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.VertexSchema;
import com.pengcheng.phoenixgraph.storage.BusinessLog;
import com.pengcheng.phoenixgraph.storage.BusinessLogEntry;
import com.pengcheng.phoenixgraph.storage.WriteOutcome;
import com.pengcheng.phoenixgraph.storage.rocksdb.KeyLocks;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbEdgeUpsert;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbHash;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbGeoIndex;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbHandle;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbMetricsStore;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbKeys;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbLruCache;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbEdgeProperty;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbStoreConfig;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbStoredEdge;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbStoredVertex;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbVertexTable;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbVertexUpsert;
import com.pengcheng.phoenixgraph.storage.TimeRange;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The RocksDB-Edge storage scheme.
 *
 * <p><b>Vertices</b> live in one table per vertex type, exactly as in the inline scheme.
 * <b>Edges live in a table of their own per edge class</b>, one row per endpoint and
 * direction, keyed by
 *
 * <pre>
 * hash(accountId) : direction : accountId : createdTime : edgeId
 * </pre>
 *
 * <p>with {@code direction} {@code 0} undirected, {@code 1} outgoing and {@code 2} incoming.
 * Because the account id is part of the key prefix, "the edges of this vertex" is one range
 * scan, and because the creation time follows as a fixed width, order-preserving field, a
 * time window is a {@code seek} into that range rather than a filter over it. A directed edge
 * is stored twice - once as an outgoing row of its source, once as an incoming row of its
 * destination - so {@code out} and {@code in} are both a prefix scan, and {@code both} is two
 * iterators, one per direction, merged by the caller.
 *
 * <p>This scheme has <b>no degree limits</b>: a vertex may have any number of edges, which is
 * what makes it the right choice for a hub whose degree is unbounded ({@code maxInDegree} /
 * {@code maxOutDegree} of the schema are ignored).
 */
public final class RocksDbEdgeStore implements AutoCloseable {

    /** Prefix of an edge table (column family) name. */
    public static final String FAMILY_PREFIX = "edge:";

    /** How many decoded edge lists are cached. */
    public static final int DEFAULT_CACHE_SIZE = 4_096;

    private final RocksDbHandle handle;
    private final GraphSchema schema;
    private final RocksDbStoreConfig config;
    private final KeyLocks locks;
    private final BusinessLog businessLog;
    private final RocksDbVertexTable vertexTable;
    private final RocksDbLruCache<List<RocksDbStoredEdge>> edgeCache;

    private RocksDbEdgeStore(RocksDbHandle handle, GraphSchema schema, RocksDbStoreConfig config,
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
    public static RocksDbEdgeStore open(GraphSchema schema, RocksDbStoreConfig config) {
        return open(schema, config, null);
    }

    /** Opens the store, recording key writes in {@code businessLog} when it is given. */
    public static RocksDbEdgeStore open(GraphSchema schema, RocksDbStoreConfig config, BusinessLog businessLog) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(config, "config");
        return new RocksDbEdgeStore(RocksDbHandle.open(config, columnFamiliesOf(schema)),
                schema, config, businessLog);
    }

    /** Opens the store at a path with default settings. */
    public static RocksDbEdgeStore open(GraphSchema schema, String path) {
        return open(schema, RocksDbStoreConfig.of(path), null);
    }

    /** The column families a graph of this schema needs (vertex tables plus edge tables). */
    public static Set<String> columnFamiliesOf(GraphSchema schema) {
        Set<String> families = new LinkedHashSet<>();
        for (VertexSchema vertexSchema : schema.vertexSchemas()) {
            families.add(RocksDbVertexTable.familyOf(vertexSchema));
        }
        for (EdgeSchema edgeSchema : schema.edgeSchemas()) {
            families.add(familyOf(edgeSchema));
        }
        families.addAll(RocksDbGeoIndex.columnFamilies(schema));
        families.add(RocksDbMetricsStore.FAMILY); // the graph's metrics live next to its data
        return families;
    }

    /** The geo index of this graph (one table per {@code isGeo} property). */
    public RocksDbGeoIndex geoIndex() {
        return vertexTable.geoIndex();
    }

    /**
     * The direction byte a requested side maps to: {@code 1} outgoing, {@code 2} incoming and
     * {@code 0} for an undirected edge or a query that asked for both (each side is then read
     * on its own).
     */
    public static byte directionCode(com.pengcheng.phoenixgraph.query.storage.EdgeSide side,
                                     EdgeDirection schemaDirection) {
        if (schemaDirection != EdgeDirection.DIRECTED) {
            return RocksDbKeys.DIRECTION_UNDIRECTED;
        }
        return switch (side) {
            case OUT -> RocksDbKeys.DIRECTION_OUT;
            case IN -> RocksDbKeys.DIRECTION_IN;
            case BOTH -> RocksDbKeys.DIRECTION_UNDIRECTED;
        };
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

    /** The vertex tables (the same layout the inline scheme uses). */
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

    /** Counts this graph's key-value traffic into {@code metrics}. */
    public RocksDbEdgeStore metrics(com.pengcheng.phoenixgraph.metrics.MetricsSink metrics) {
        handle.metrics(metrics);
        return this;
    }

    /** The metrics of this graph, kept in the `metrics` column family. */
    public RocksDbMetricsStore metricsStore() {
        return new RocksDbMetricsStore(handle);
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

    /** The edge-list cache. */
    public RocksDbLruCache<List<RocksDbStoredEdge>> edgeCache() {
        return edgeCache;
    }

    // --- vertices (same table layout as the inline scheme) ---

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

    /** Deletes a vertex record (its edge rows are not touched; delete them first). */
    public boolean deleteVertex(String vertexName, Object vertexId) {
        return vertexTable.delete(vertexName, vertexId);
    }

    // --- edges ---

    /** Writes one edge (one row per endpoint). */
    public WriteOutcome upsertEdge(RocksDbEdgeUpsert edge) {
        List<WriteOutcome> outcomes = upsertEdges(List.of(edge));
        return outcomes.isEmpty() ? WriteOutcome.FAILED : outcomes.get(0);
    }

    /**
     * Writes many edges of one edge class. Each edge becomes one row per endpoint
     * ({@code 1} outgoing at the source, {@code 2} incoming at the destination, {@code 0} on
     * both for an undirected edge); an existing row is merged by property timestamps.
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

        String family = familyOf(edgeSchema);
        List<WriteOutcome> outcomes = new ArrayList<>(edges.size());
        for (RocksDbEdgeUpsert edge : edges) {
            boolean ok = true;
            for (EdgeRow row : rowsOf(edge, edgeSchema)) {
                ok &= writeRow(family, row);
            }
            outcomes.add(ok ? WriteOutcome.SUCCESS : WriteOutcome.FAILED);
        }
        return outcomes;
    }

    /** One row to write: the owning vertex, the direction, the key and the stored value. */
    private record EdgeRow(Object vertexId, byte direction, byte[] key, RocksDbStoredEdge edge) {
    }

    /**
     * The rows of one edge. An edge with a nullable target ({@code isNull} on its target
     * endpoint) has no far side: only its source row is written, so no vertex points back at it.
     */
    private List<EdgeRow> rowsOf(RocksDbEdgeUpsert edge, EdgeSchema edgeSchema) {
        List<EdgeRow> rows = new ArrayList<>(2);
        long createdTime = edge.createdTime();
        boolean farSide = edge.destination() != null && !Objects.equals(edge.source(), edge.destination());
        if (edgeSchema.direction() == EdgeDirection.DIRECTED) {
            rows.add(new EdgeRow(edge.source(), RocksDbKeys.DIRECTION_OUT,
                    RocksDbKeys.edgeKey(edge.source(), RocksDbKeys.DIRECTION_OUT, createdTime, edge.edgeId()),
                    stored(edge, edge.destination())));
            if (farSide) {
                rows.add(new EdgeRow(edge.destination(), RocksDbKeys.DIRECTION_IN,
                        RocksDbKeys.edgeKey(edge.destination(), RocksDbKeys.DIRECTION_IN, createdTime,
                                edge.edgeId()),
                        stored(edge, edge.source())));
            }
            return rows;
        }
        // UNDIRECTED / BIDIRECTIONAL: direction 0, one row on each endpoint
        rows.add(new EdgeRow(edge.source(), RocksDbKeys.DIRECTION_UNDIRECTED,
                RocksDbKeys.edgeKey(edge.source(), RocksDbKeys.DIRECTION_UNDIRECTED, createdTime, edge.edgeId()),
                stored(edge, edge.destination())));
        if (farSide) {
            rows.add(new EdgeRow(edge.destination(), RocksDbKeys.DIRECTION_UNDIRECTED,
                    RocksDbKeys.edgeKey(edge.destination(), RocksDbKeys.DIRECTION_UNDIRECTED, createdTime,
                            edge.edgeId()),
                    stored(edge, edge.source())));
        }
        return rows;
    }

    private static RocksDbStoredEdge stored(RocksDbEdgeUpsert edge, Object otherVertexId) {
        long lastUpdatedTime = edge.lastUpdatedTime();
        for (RocksDbEdgeProperty property : edge.properties().values()) {
            lastUpdatedTime = Math.max(lastUpdatedTime, property.lastUpdatedTime());
        }
        return new RocksDbStoredEdge(String.valueOf(edge.edgeId()), otherVertexId,
                edge.createdTime(), lastUpdatedTime, edge.properties());
    }

    /** Writes one row, merging it with the stored row when it already exists. */
    private boolean writeRow(String family, EdgeRow row) {
        return locks.withLock(lockKey(family, row.vertexId()), () -> {
            byte[] existing = handle.get(family, row.key());
            RocksDbStoredEdge value = row.edge();
            if (existing != null) {
                RocksDbStoredEdge stored = RocksDbEdgeRowCodec.decode(row.edge().edgeId(), existing, Set.of());
                value = RocksDbEdgeRowCodec.merge(stored, row.edge());
            }
            handle.put(family, row.key(), RocksDbEdgeRowCodec.encode(value));
            invalidate(family, row.vertexId());
            businessLog.record(BusinessLogEntry.success("upsertEdge", row.edge().edgeId(),
                    Map.of("table", family, "direction", row.direction(),
                            "vertexId", String.valueOf(row.vertexId()))));
            return true;
        });
    }

    /**
     * Reads the edges of one edge class attached to a vertex, in one direction and inside a
     * time window. {@link TimeRange#ALL} reads every edge.
     */
    public List<RocksDbStoredEdge> readEdges(String edgeName, Object vertexId, byte direction,
                                             TimeRange range, List<Integer> propertyIds) {
        EdgeSchema edgeSchema = requireEdge(edgeName);
        TimeRange window = range == null ? TimeRange.ALL : range;
        Set<Integer> requested = propertyIds == null || propertyIds.isEmpty()
                ? Set.of() : Set.copyOf(propertyIds);
        String family = familyOf(edgeSchema);
        String cacheKey = cacheKey(family, vertexId, direction, window, requested);
        if (config.cacheEdgeLists()) {
            List<RocksDbStoredEdge> cached = edgeCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
        }
        List<RocksDbStoredEdge> edges = scan(family, vertexId, direction, window, requested);
        if (config.cacheEdgeLists()) {
            edgeCache.put(cacheKey, edges);
        }
        return edges;
    }

    /** The range scan behind {@link #readEdges}: one ordered iterator over the key prefix. */
    private List<RocksDbStoredEdge> scan(String family, Object vertexId, byte direction,
                                         TimeRange window, Set<Integer> requested) {
        byte[] prefix = RocksDbKeys.edgePrefix(vertexId, direction);
        byte[] from = window.fromTime() == Long.MIN_VALUE ? prefix
                : RocksDbKeys.edgeKeyFrom(prefix, window.fromTime());
        byte[] to = window.toTime() == Long.MAX_VALUE ? RocksDbKeys.prefixEnd(prefix)
                : RocksDbKeys.edgeKeyFrom(prefix, window.toTime() + 1);
        List<RocksDbStoredEdge> edges = new ArrayList<>();
        try (RocksDbHandle.RocksDbRange iterator = handle.iterator(family, from, to)) {
            while (iterator.isValid()) {
                byte[] key = iterator.key();
                long createdTime = RocksDbKeys.createdTimeOf(key, prefix);
                if (window.contains(createdTime)) {
                    String edgeId = RocksDbKeys.edgeIdOf(key, prefix);
                    edges.add(RocksDbEdgeRowCodec.decode(edgeId, iterator.value(), requested));
                }
                iterator.next();
            }
        }
        return edges;
    }

    /**
     * Reads the edges of one edge class for many vertices, in one direction and inside a time
     * window. Each vertex is an ordered prefix scan; results are cached, so a traversal that
     * visits the same vertex twice reads the table once.
     */
    public List<List<RocksDbStoredEdge>> readEdgesBatch(String edgeName, byte direction, TimeRange range,
                                                        List<Object> vertexIds, List<Integer> propertyIds) {
        Objects.requireNonNull(vertexIds, "vertexIds");
        if (vertexIds.isEmpty()) {
            return List.of();
        }
        List<List<RocksDbStoredEdge>> out = new ArrayList<>(vertexIds.size());
        for (Object vertexId : vertexIds) {
            out.add(readEdges(edgeName, vertexId, direction, range, propertyIds));
        }
        return out;
    }

    /**
     * Deletes one edge. The creation time is part of the key, so it must be given; read the
     * edge first when only its id is known.
     */
    public boolean deleteEdge(EdgeSchema edgeSchema, Object edgeId, long createdTime, Object source, Object destination) {
        String family = familyOf(edgeSchema);
        boolean farSide = destination != null && !Objects.equals(source, destination);
        boolean removed;
        if (edgeSchema.direction() == EdgeDirection.DIRECTED) {
            removed = removeRow(family, source, RocksDbKeys.DIRECTION_OUT, createdTime, edgeId);
            if (farSide) {
                removeRow(family, destination, RocksDbKeys.DIRECTION_IN, createdTime, edgeId);
            }
        } else {
            removed = removeRow(family, source, RocksDbKeys.DIRECTION_UNDIRECTED, createdTime, edgeId);
            if (farSide) {
                removeRow(family, destination, RocksDbKeys.DIRECTION_UNDIRECTED, createdTime, edgeId);
            }
        }
        return removed;
    }

    private boolean removeRow(String family, Object vertexId, byte direction, long createdTime, Object edgeId) {
        return locks.withLock(lockKey(family, vertexId), () -> {
            byte[] key = RocksDbKeys.edgeKey(vertexId, direction, createdTime, edgeId);
            boolean present = handle.get(family, key) != null;
            if (present) {
                handle.delete(family, key);
                invalidate(family, vertexId);
                businessLog.record(BusinessLogEntry.success("deleteEdge", String.valueOf(edgeId),
                        Map.of("table", family, "direction", direction,
                                "vertexId", String.valueOf(vertexId))));
            }
            return present;
        });
    }

    /** The number of edge rows of one edge class (each edge has one row per endpoint). */
    public long edgeRowCount(String edgeName) {
        EdgeSchema edgeSchema = requireEdge(edgeName);
        long count = 0;
        try (RocksDbHandle.RocksDbRange range = handle.iterator(familyOf(edgeSchema), null, null)) {
            while (range.isValid()) {
                count++;
                range.next();
            }
        }
        return count;
    }

    /** The number of regions that have at least one edge row of a class (partition spread). */
    public int regionsUsed(String edgeName) {
        EdgeSchema edgeSchema = requireEdge(edgeName);
        Set<Integer> regions = new LinkedHashSet<>();
        try (RocksDbHandle.RocksDbRange range = handle.iterator(familyOf(edgeSchema), null, null)) {
            while (range.isValid()) {
                long hash = RocksDbHash.readLong(range.key(), 0);
                regions.add(RocksDbHash.regionOfHash(hash, config.regionCount()));
                range.next();
            }
        }
        return regions.size();
    }

    private void invalidate(String family, Object vertexId) {
        String prefix = family + '\u0000' + vertexId + '\u0000';
        edgeCache.invalidateIf(key -> key.startsWith(prefix));
    }

    /**
     * The cache key of a decoded edge list: the table, the vertex, the direction, the window
     * and the projection. The projection belongs in the key because the cached value is already
     * projected - a list read for one property must not answer a read that asks for another.
     */
    private static String cacheKey(String family, Object vertexId, byte direction, TimeRange range,
                                   Set<Integer> requested) {
        List<Integer> properties = new ArrayList<>(requested);
        Collections.sort(properties);
        return family + '\u0000' + vertexId + '\u0000' + direction + '\u0000' + range.fromTime()
                + '\u0000' + range.toTime() + '\u0000' + properties;
    }

    private static String lockKey(String family, Object vertexId) {
        return family + '\u0000' + vertexId;
    }

    private EdgeSchema requireEdge(String edgeName) {
        EdgeSchema edgeSchema = schema.edgeSchema(edgeName);
        if (edgeSchema == null) {
            throw new IllegalArgumentException("Unknown edge schema: " + edgeName);
        }
        return edgeSchema;
    }

    @Override
    public void close() {
        edgeCache.clear();
        handle.close();
    }
}

