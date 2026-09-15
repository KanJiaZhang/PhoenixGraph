package com.pengcheng.phoenixgraph.query.gremlin;

import com.pengcheng.phoenixgraph.query.storage.EdgeQueryResult;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
import com.pengcheng.phoenixgraph.query.storage.GraphQuery;
import com.pengcheng.phoenixgraph.query.storage.VertexQueryResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Execution context handed to every {@link GremlinStep}: the storage query layer,
 * the neighbour-vertex schema resolver, the fan-out batch size, the property
 * materialization flag and per-query vertex/edge caches.
 *
 * <p>The caches are scoped to a single query execution: the first read of a
 * vertex or of a vertex's edges (per edge class + direction) is stored, and
 * later reads of the same key reuse it. Duplicate ids within one batch are also
 * collapsed so only one storage query is issued per unique id.
 */
public final class GremlinContext {

    /** Default fan-out batch size (parallel lookups are on by default). */
    public static final int DEFAULT_BATCH_SIZE = 10;

    /** Upper bound for the adaptive batch size. */
    public static final int DEFAULT_MAX_BATCH_SIZE = 100;

    /** Fan-out divisor: the adaptive batch size is {@code fanOut / DIVISOR}, clamped. */
    static final int ADAPTIVE_DIVISOR = 5;

    private final GraphQuery graphQuery;
    private final NeighborVertexResolver vertexResolver;
    private final Map<Object, VertexQueryResult> vertexCache = new LinkedHashMap<>();
    private final Map<EdgeCacheKey, List<EdgeQueryResult>> edgeCache = new LinkedHashMap<>();
    private int batchSize = DEFAULT_BATCH_SIZE;
    private int maxBatchSize = DEFAULT_MAX_BATCH_SIZE;
    private boolean adaptiveBatchEnabled = true;
    private boolean explicitBatchSize = false;
    private List<String> readProperties = List.of(); // empty = all, null = skip read
    private boolean cacheEnabled = true;
    private boolean collectSubgraph = false;
    private final Map<String, VertexRef> subgraphVertices = new LinkedHashMap<>();
    private final Map<String, Subgraph.Edge> subgraphEdges = new LinkedHashMap<>();

    private record EdgeCacheKey(Object vertexId, String edgeName, EdgeSide direction) {
    }

    private record VertexRef(String label, Object id) {
    }

    public GremlinContext(GraphQuery graphQuery, NeighborVertexResolver vertexResolver) {
        this.graphQuery = Objects.requireNonNull(graphQuery, "graphQuery");
        this.vertexResolver = vertexResolver == null ? NeighborVertexResolver.none() : vertexResolver;
    }

    public GraphQuery graphQuery() {
        return graphQuery;
    }

    public NeighborVertexResolver vertexResolver() {
        return vertexResolver;
    }

    public int batchSize() {
        return batchSize;
    }

    /** Sets the fan-out batch size; {@code 1} means serial, larger values run in parallel. */
    public void batchSize(int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batch size must be >= 1");
        }
        this.batchSize = batchSize;
        // An explicit batch(n)/barrier(n) step is authoritative: adaptive tuning does not override it.
        this.explicitBatchSize = true;
    }

    /** Whether the adaptive batch-size tuning is enabled (on by default). */
    public boolean adaptiveBatchEnabled() {
        return adaptiveBatchEnabled;
    }

    public void adaptiveBatchEnabled(boolean adaptiveBatchEnabled) {
        this.adaptiveBatchEnabled = adaptiveBatchEnabled;
    }

    /** Upper bound for the adaptive batch size; must be {@code >= 1}. */
    public void maxBatchSize(int maxBatchSize) {
        if (maxBatchSize < 1) {
            throw new IllegalArgumentException("max batch size must be >= 1");
        }
        this.maxBatchSize = maxBatchSize;
    }

    /**
     * Adapts the batch size for the next fan-out hop based on the previous hop's
     * output size: {@code batchSize = clamp(fanOut / ADAPTIVE_DIVISOR, current, max)}.
     * No-op when adaptive tuning is disabled, when the batch size was set explicitly
     * via {@code batch(n)}/{@code barrier(n)}, or when a filter ({@code has}) already
     * reduced the traverser cardinality (the default batch size is kept then).
     */
    public void adaptBatchSize(int fanOut, boolean filtered) {
        if (!adaptiveBatchEnabled || explicitBatchSize || filtered) {
            return;
        }
        int scaled = fanOut / ADAPTIVE_DIVISOR;
        int target = Math.min(maxBatchSize, Math.max(batchSize, scaled));
        batchSize = target;
    }

    /**
     * The property names to read for vertices that a hop materializes:
     * {@code null} means skip the read (only id/label), an empty list means read
     * all properties, a non-empty list reads only those property bins.
     */
    public List<String> readProperties() {
        return readProperties;
    }

    public void readProperties(List<String> readProperties) {
        this.readProperties = readProperties;
    }

    /** Whether the per-query vertex cache is enabled (on by default). */
    public boolean cacheEnabled() {
        return cacheEnabled;
    }

    public void cacheEnabled(boolean cacheEnabled) {
        this.cacheEnabled = cacheEnabled;
    }

    /** Enables subgraph collection for the current execution. */
    public void collectSubgraph(boolean collectSubgraph) {
        this.collectSubgraph = collectSubgraph;
    }

    /** Records a vertex touched by the traversal (deduplicated by label + id). */
    public void recordVertex(String label, Object id) {
        if (!collectSubgraph) {
            return;
        }
        subgraphVertices.putIfAbsent(key(label, id), new VertexRef(label, id));
    }

    /** Records an edge touched by the traversal (deduplicated by label + id). */
    public void recordEdge(String label, Object id, Object source, Object target,
                           Map<String, Object> properties, long createdTime, long lastUpdatedTime) {
        if (!collectSubgraph) {
            return;
        }
        subgraphEdges.putIfAbsent(key(label, id),
                new Subgraph.Edge(label, id, source, target, properties, createdTime, lastUpdatedTime));
    }

    /** Reads full vertex properties for every recorded vertex and returns the induced subgraph. */
    public Subgraph buildSubgraph() {
        if (!collectSubgraph) {
            return Subgraph.EMPTY;
        }
        Map<String, List<Object>> idsByLabel = new LinkedHashMap<>();
        for (VertexRef ref : subgraphVertices.values()) {
            idsByLabel.computeIfAbsent(ref.label(), k -> new ArrayList<>()).add(ref.id());
        }
        List<Subgraph.Vertex> vertices = new ArrayList<>();
        for (Map.Entry<String, List<Object>> entry : idsByLabel.entrySet()) {
            String label = entry.getKey();
            if (label == null) {
                for (Object id : entry.getValue()) {
                    vertices.add(new Subgraph.Vertex(null, id, Map.of(), 0L, 0L));
                }
            } else {
                List<VertexQueryResult> read = graphQuery.readVertices(label, entry.getValue(), List.of());
                for (int i = 0; i < read.size(); i++) {
                    VertexQueryResult v = read.get(i);
                    vertices.add(v == null
                            ? new Subgraph.Vertex(label, entry.getValue().get(i), Map.of(), 0L, 0L)
                            : new Subgraph.Vertex(v.vertexName(), v.vertexId(), v.properties(),
                                    v.createdTime(), v.lastUpdatedTime()));
                }
            }
        }
        return new Subgraph(List.copyOf(vertices), List.copyOf(subgraphEdges.values()));
    }

    private static String key(String label, Object id) {
        return label + "::" + id;
    }

    void recordEdges(Object vertexId, List<EdgeQueryResult> edges) {
        if (!collectSubgraph) {
            return;
        }
        for (EdgeQueryResult edge : edges) {
            Object source;
            Object target;
            switch (edge.side()) {
                case OUT -> {
                    source = vertexId;
                    target = edge.otherVertexId();
                }
                case IN -> {
                    source = edge.otherVertexId();
                    target = vertexId;
                }
                default -> {
                    source = vertexId;
                    target = edge.otherVertexId();
                }
            }
            recordEdge(edge.edgeName(), edge.edgeId(), source, target, edge.properties(),
                    edge.createdTime(), edge.lastUpdatedTime());
        }
    }

    /**
     * Reads vertices, de-duplicating ids and reusing previously read ids from the
     * cache. Only unique ids not yet cached are queried against the storage; the
     * result preserves the request order (duplicates included).
     */
    public List<VertexQueryResult> readVerticesCached(String vertexName, List<Object> vertexIds,
                                                      List<String> propertyNames) {
        if (!cacheEnabled) {
            return graphQuery.readVertices(vertexName, vertexIds, propertyNames);
        }
        if (vertexIds.isEmpty()) {
            return List.of();
        }

        // De-duplicate, preserving first-occurrence order.
        List<Object> uniqueIds = new ArrayList<>();
        Set<Object> seen = new HashSet<>();
        for (Object id : vertexIds) {
            if (seen.add(id)) {
                uniqueIds.add(id);
            }
        }

        Map<Object, VertexQueryResult> byId = new LinkedHashMap<>();
        List<Object> toFetch = new ArrayList<>();
        for (Object id : uniqueIds) {
            if (vertexCache.containsKey(id)) {
                byId.put(id, vertexCache.get(id));
            } else {
                toFetch.add(id);
            }
        }

        if (!toFetch.isEmpty()) {
            List<VertexQueryResult> fetched = graphQuery.readVertices(vertexName, toFetch, propertyNames);
            for (int i = 0; i < toFetch.size(); i++) {
                VertexQueryResult vertex = fetched.get(i);
                vertexCache.put(toFetch.get(i), vertex);
                byId.put(toFetch.get(i), vertex);
            }
        }

        List<VertexQueryResult> out = new ArrayList<>(vertexIds.size());
        for (Object id : vertexIds) {
            out.add(byId.get(id));
        }
        return out;
    }

    /** Reads the edges of one vertex for an edge class + direction, with caching. */
    public List<EdgeQueryResult> readEdgesCached(Object vertexId, String vertexName, String edgeName,
                                                 EdgeSide direction, List<String> propertyNames) {
        EdgeCacheKey key = new EdgeCacheKey(vertexId, edgeName, direction);
        if (cacheEnabled) {
            List<EdgeQueryResult> cached = edgeCache.get(key);
            if (cached != null) {
                return cached;
            }
        }
        List<EdgeQueryResult> result = graphQuery.readEdges(vertexId, vertexName, edgeName, direction, propertyNames);
        if (cacheEnabled) {
            edgeCache.put(key, result);
        }
        recordEdges(vertexId, result);
        return result;
    }

    /**
     * Reads the edges of many vertices for an edge class + direction, de-duplicating
     * ids and caching per vertex. Returns the edges flattened in the request order.
     */
    public List<EdgeQueryResult> readAllEdgesBatchCached(String edgeName, EdgeSide direction,
                                                         List<Object> vertexIds, List<String> propertyNames) {
        if (!cacheEnabled) {
            List<EdgeQueryResult> all = new ArrayList<>();
            for (int start = 0; start < vertexIds.size(); start += batchSize) {
                int end = Math.min(start + batchSize, vertexIds.size());
                List<Object> chunk = vertexIds.subList(start, end);
                List<List<EdgeQueryResult>> fetched = graphQuery.readEdgesBatch(
                        edgeName, direction, chunk, propertyNames);
                for (int i = 0; i < chunk.size(); i++) {
                    recordEdges(chunk.get(i), fetched.get(i));
                    all.addAll(fetched.get(i));
                }
            }
            return all;
        }

        List<Object> uniqueIds = new ArrayList<>();
        Set<Object> seen = new HashSet<>();
        for (Object id : vertexIds) {
            if (seen.add(id)) {
                uniqueIds.add(id);
            }
        }

        Map<EdgeCacheKey, List<EdgeQueryResult>> byKey = new LinkedHashMap<>();
        List<Object> missing = new ArrayList<>();
        for (Object id : uniqueIds) {
            EdgeCacheKey key = new EdgeCacheKey(id, edgeName, direction);
            List<EdgeQueryResult> cached = edgeCache.get(key);
            if (cached != null) {
                byKey.put(key, cached);
            } else {
                missing.add(id);
            }
        }

        for (int start = 0; start < missing.size(); start += batchSize) {
            int end = Math.min(start + batchSize, missing.size());
            List<List<EdgeQueryResult>> fetched = graphQuery.readEdgesBatch(
                    edgeName, direction, missing.subList(start, end), propertyNames);
            for (int i = start; i < end; i++) {
                Object id = missing.get(i);
                EdgeCacheKey key = new EdgeCacheKey(id, edgeName, direction);
                List<EdgeQueryResult> edges = fetched.get(i - start);
                edgeCache.put(key, edges);
                byKey.put(key, edges);
                recordEdges(id, edges);
            }
        }

        List<EdgeQueryResult> all = new ArrayList<>();
        for (Object id : vertexIds) {
            EdgeCacheKey key = new EdgeCacheKey(id, edgeName, direction);
            List<EdgeQueryResult> edges = byKey.get(key);
            if (edges != null) {
                all.addAll(edges);
            }
        }
        return all;
    }
}
