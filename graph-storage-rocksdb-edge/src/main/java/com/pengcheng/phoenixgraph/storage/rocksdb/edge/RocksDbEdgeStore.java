package com.pengcheng.phoenixgraph.storage.rocksdb.edge;

import com.pengcheng.phoenixgraph.query.storage.EdgeQueryResult;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
import com.pengcheng.phoenixgraph.query.storage.NeighbourRef;
import com.pengcheng.phoenixgraph.schema.EdgeDirection;
import com.pengcheng.phoenixgraph.schema.PropertyType;
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
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbIdCodec;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbStoreConfig;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbStoredEdge;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbStoredVertex;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbVertexTable;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbVertexUpsert;
import com.pengcheng.phoenixgraph.storage.TimeRange;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.function.Consumer;
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

    /**
     * A property set no read can ask for, so the neighbours of a vertex are cached apart from its edges: a
     * neighbour list is a subset of what a full edge read returns, and handing one out for the other would
     * lose the properties.
     */
    private static final Set<Integer> NEIGHBOURS_ONLY = Set.of(-1);

    /**
     * The neighbours of one edge class of a vertex, in one direction, read from the <b>headers</b> of the
     * rows: no property is decoded and no edge is built, because a vertex hop only needs to know where each
     * edge leads ({@link RocksDbEdgeGraphQuery#readNeighbours} is what the engine asks for).
     *
     * <p>Cached like the edge lists are, under a key of its own: the whole point is that repeating a
     * traversal reads the same headers again, and a query engine's own cache only covers one execution.
     */
    public List<NeighbourRef> readNeighbours(String edgeName, Object vertexId, byte direction, EdgeSide side) {
        Objects.requireNonNull(vertexId, "vertexId");
        EdgeSchema edgeSchema = requireEdge(edgeName);
        String family = familyOf(edgeSchema);
        String cacheKey = cacheKey(family, vertexId, direction, TimeRange.ALL, NEIGHBOURS_ONLY);
        if (config.cacheEdgeLists()) {
            List<RocksDbStoredEdge> cached = edgeCache.get(cacheKey);
            if (cached != null) {
                return toNeighbours(edgeName, cached, side);
            }
        }
        List<RocksDbStoredEdge> headers = scanNeighbours(family, vertexId, direction);
        if (config.cacheEdgeLists()) {
            edgeCache.put(cacheKey, headers);
        }
        return toNeighbours(edgeName, headers, side);
    }

    /** The headers of one direction of a vertex, as edge rows without their properties. */
    private List<RocksDbStoredEdge> scanNeighbours(String family, Object vertexId, byte direction) {
        List<RocksDbStoredEdge> out = new ArrayList<>();
        try (RocksDbHandle.RocksDbRange iterator = range(family, vertexId, direction, TimeRange.ALL)) {
            while (iterator.isValid()) {
                byte[] key = iterator.key();
                String edgeId = RocksDbKeys.edgeIdOf(key, RocksDbKeys.edgePrefix(vertexId, direction));
                RocksDbEdgeRowCodec.NeighbourHeader header =
                        RocksDbEdgeRowCodec.decodeHeader(edgeId, iterator.value());
                out.add(new RocksDbStoredEdge(header.edgeId(), header.otherVertexId(), header.createdTime(),
                        header.lastUpdatedTime(), Map.of()));
                iterator.next();
            }
        }
        return out;
    }

    private static List<NeighbourRef> toNeighbours(String edgeName, List<RocksDbStoredEdge> headers,
                                                   EdgeSide side) {
        List<NeighbourRef> out = new ArrayList<>(headers.size());
        for (RocksDbStoredEdge header : headers) {
            out.add(new NeighbourRef(edgeName, header.edgeId(), side, header.otherVertexId(),
                    header.createdTime(), header.lastUpdatedTime()));
        }
        return out;
    }

    /** The range scan behind {@link #readEdges}: one ordered iterator over the key prefix. */
    private List<RocksDbStoredEdge> scan(String family, Object vertexId, byte direction,
                                         TimeRange window, Set<Integer> requested) {
        List<RocksDbStoredEdge> edges = new ArrayList<>();
        try (RocksDbHandle.RocksDbRange iterator = range(family, vertexId, direction, window)) {
            byte[] prefix = RocksDbKeys.edgePrefix(vertexId, direction);
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

    /** The rows of one direction of a vertex inside a time window, as one ordered range. */
    private RocksDbHandle.RocksDbRange range(String family, Object vertexId, byte direction, TimeRange window) {
        byte[] prefix = RocksDbKeys.edgePrefix(vertexId, direction);
        byte[] from = window.fromTime() == Long.MIN_VALUE ? prefix
                : RocksDbKeys.edgeKeyFrom(prefix, window.fromTime());
        byte[] to = window.toTime() == Long.MAX_VALUE ? RocksDbKeys.prefixEnd(prefix)
                : RocksDbKeys.edgeKeyFrom(prefix, window.toTime() + 1);
        return handle.iterator(family, from, to);
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

    /**
     * Reads a whole edge class: one iterator over the class's table, one entry per edge.
     *
     * <p>This is the read that makes the scheme analytical. An edge is stored once per endpoint, so
     * the scan keeps the <b>outgoing</b> row of a directed class (the row whose direction byte is
     * {@code 1}) and the single row of an undirected one ({@code 0}) - each edge therefore comes back
     * exactly once, with both of its endpoints: the owner comes from the key (the row's creation time
     * locates it, see {@link RocksDbKeys#edgeOwnerOf}) and the peer from the value.
     *
     * <p>Keyed reads ask for one vertex's edges, which is what a transactional query does; this asks
     * for a class, which is what an algorithm needs ({@code g.E('payment').pagerank(DIST)}). The
     * properties are decoded with the projection, and the scan stops at {@code maxResults} instead of
     * walking the rest of the table.
     */
    public List<ScannedEdgeRow> scanEdges(String edgeName, List<Integer> propertyIds, int maxResults) {
        List<ScannedEdgeRow> out = new ArrayList<>();
        forEachEdgeRange(edgeName, propertyIds, null, null, maxResults, out::add);
        return out;
    }

    /**
     * Walks the edges of one class whose key falls in {@code [fromKey, toKey)} - a {@code null} bound is
     * open - and hands each row to the consumer. The keys start with the hash of the vertex that owns the
     * row, so a hash range is a contiguous key range: one partition of a distributed edge scan is one
     * iterator, without materialising the class.
     */
    public void forEachEdgeRange(String edgeName, List<Integer> propertyIds, byte[] fromKey, byte[] toKey,
                                 int maxResults, Consumer<ScannedEdgeRow> consumer) {
        Objects.requireNonNull(edgeName, "edgeName");
        Objects.requireNonNull(consumer, "consumer");
        EdgeSchema edgeSchema = requireEdge(edgeName);
        Set<Integer> requested = propertyIds == null || propertyIds.isEmpty()
                ? Set.of() : Set.copyOf(propertyIds);
        boolean directed = edgeSchema.direction() == EdgeDirection.DIRECTED;
        // a directed class stores each edge twice (outgoing at its source, incoming at its
        // destination) and an undirected one has a row on each endpoint under the same direction, so
        // a scan has to pick one row per edge - otherwise every edge would be read twice
        byte scannedDirection = directed ? RocksDbKeys.DIRECTION_OUT : RocksDbKeys.DIRECTION_UNDIRECTED;
        int written = 0;
        try (RocksDbHandle.RocksDbRange range = handle.iterator(familyOf(edgeSchema), fromKey, toKey)) {
            while (range.isValid()) {
                byte[] key = range.key();
                if (RocksDbKeys.directionOfKey(key) == scannedDirection) {
                    RocksDbStoredEdge row = RocksDbEdgeRowCodec.decode("", range.value(), requested);
                    RocksDbKeys.EdgeKeyParts parts = RocksDbKeys.edgeKeyParts(key, row.createdTime());
                    if (directed || isTheNearEndpoint(parts.ownerVertexId(), row.otherVertexId())) {
                        RocksDbStoredEdge edge = new RocksDbStoredEdge(parts.edgeId(), row.otherVertexId(),
                                row.createdTime(), row.lastUpdatedTime(), row.properties());
                        consumer.accept(new ScannedEdgeRow(
                                ownerId(edgeSchema, parts.ownerVertexId(), row.otherVertexId()), edge));
                        written++;
                    }
                }
                if (maxResults > 0 && written >= maxResults) {
                    break;
                }
                range.next();
            }
        }
    }

    /**
     * Whether an undirected row belongs to the endpoint a scan keeps: the two rows of an undirected
     * edge are mirror images of each other and say nothing about which is the source, so the lowest
     * id keeps the edge (and a self-loop, stored once, is always kept).
     */
    private static boolean isTheNearEndpoint(String ownerText, Object peer) {
        return ownerText.compareTo(String.valueOf(peer)) <= 0;
    }

    /**
     * The owner of a scanned row, read back in the type the endpoint declares: a key holds the id as
     * text, while every other read of this store returns the id the caller used, so a scan has to put
     * the type back (a payment from {@code 100} to {@code 200} is about the accounts 100 and 200, not
     * about the strings "100" and "200").
     */
    private static Object ownerId(EdgeSchema edgeSchema, String ownerText, Object peer) {
        PropertyType type = ownerType(edgeSchema, peer);
        Object id = RocksDbIdCodec.decode(RocksDbIdCodec.typeOf(exampleOf(type)), ownerText);
        return id == null ? ownerText : id;
    }

    /** The declared type of the endpoint a scanned row's owner is, given what the row says about the peer. */
    private static PropertyType ownerType(EdgeSchema edgeSchema, Object peer) {
        if (edgeSchema.direction() == EdgeDirection.DIRECTED) {
            return edgeSchema.source().type(); // the outgoing row belongs to the source
        }
        PropertyType source = edgeSchema.source().type();
        PropertyType target = edgeSchema.target().type();
        if (source.equals(target) || peer == null) {
            return source;
        }
        // undirected: the owner is the endpoint the peer is not, which the peer's own type tells
        return matches(peer, source) ? target : source;
    }

    /** A value whose type tag matches a declared scalar type (the tag is what {@code decode} needs). */
    private static Object exampleOf(PropertyType type) {
        if (type instanceof PropertyType.ScalarType scalar) {
            return switch (scalar) {
                case LONG -> Long.valueOf(0);
                case INT -> Integer.valueOf(0);
                default -> "";
            };
        }
        return "";
    }

    /** Whether a stored peer id has the Java type a declared scalar type stands for. */
    private static boolean matches(Object peer, PropertyType type) {
        if (!(type instanceof PropertyType.ScalarType scalar)) {
            return peer instanceof String;
        }
        return switch (scalar) {
            case LONG -> peer instanceof Long;
            case INT -> peer instanceof Integer;
            default -> peer instanceof String;
        };
    }

    /** One row of an edge-class scan: the vertex the row belongs to, and the stored edge. */
    public record ScannedEdgeRow(Object ownerVertexId, RocksDbStoredEdge edge) {

        /** The far endpoint of the edge (the owner is the near one). */
        public Object peerVertexId() {
            return edge.otherVertexId();
        }
    }

    /**
     * Every edge of a class, counted without decoding a row.
     *
     * <p>A directed class is counted by its outgoing rows, which is one row per edge - the count never
     * touches a value. An undirected class stores a row on each endpoint, so it has to read the peer
     * to tell which of the two rows is the one a scan keeps.
     */
    public long countScannedEdges(String edgeName, long maxResults) {
        EdgeSchema edgeSchema = requireEdge(edgeName);
        boolean directed = edgeSchema.direction() == EdgeDirection.DIRECTED;
        byte scannedDirection = directed ? RocksDbKeys.DIRECTION_OUT : RocksDbKeys.DIRECTION_UNDIRECTED;
        long count = 0;
        try (RocksDbHandle.RocksDbRange range = handle.iterator(familyOf(edgeSchema), null, null)) {
            while (range.isValid()) {
                if (RocksDbKeys.directionOfKey(range.key()) == scannedDirection) {
                    boolean counted = true;
                    if (!directed) {
                        RocksDbStoredEdge row = RocksDbEdgeRowCodec.decode("", range.value(), Set.of(-1));
                        RocksDbKeys.EdgeKeyParts parts =
                                RocksDbKeys.edgeKeyParts(range.key(), row.createdTime());
                        counted = isTheNearEndpoint(parts.ownerVertexId(), row.otherVertexId());
                    }
                    if (counted) {
                        count++;
                        if (maxResults > 0 && count >= maxResults) {
                            break;
                        }
                    }
                }
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

