package com.pengcheng.phoenixgraph.query.gremlin;

import com.pengcheng.phoenixgraph.query.result.Subgraph;
import com.pengcheng.phoenixgraph.query.traversal.NeighborVertexResolver;
import com.pengcheng.phoenixgraph.query.storage.EdgeQueryResult;
import com.pengcheng.phoenixgraph.storage.TimeRange;
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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.function.Function;

/**
 * Execution context handed to every {@link GremlinStep}: the storage query layer,
 * the neighbour-vertex schema resolver, the fan-out batch size, the property
 * materialization flag and per-query vertex/edge caches.
 *
 * <p>The caches are scoped to a single query execution: the first read of a
 * vertex or of a vertex's edges (per edge class + direction) is stored, and
 * later reads of the same key reuse it. Duplicate ids within one batch are also
 * collapsed so only one storage query is issued per unique id.
 *
 * <p>Every multi-id read is split into batches of at most {@link #batchSize()}
 * and the batches are issued in parallel (up to {@link #parallelism()} at a
 * time), so a query seeded with several vertices - e.g.
 * {@code g.V(id1, id2, id3, 'Entity').batch(20).bothE(...)} - fans out
 * concurrently instead of one batch after another. A single batch is read inline,
 * so small queries pay nothing for the parallel path.
 */
public final class GremlinContext {

    /** Default fan-out batch size ({@code batch(n)} overrides it; parallel lookups are on by default). */
    public static final int DEFAULT_BATCH_SIZE = 20;

    /** Upper bound for the adaptive batch size. */
    public static final int DEFAULT_MAX_BATCH_SIZE = 100;

    /** Default number of storage reads issued concurrently. */
    public static final int DEFAULT_PARALLELISM = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));

    /** Fan-out divisor: the adaptive batch size is {@code fanOut / DIVISOR}, clamped. */
    static final int ADAPTIVE_DIVISOR = 5;

    private final GraphQuery graphQuery;
    private final NeighborVertexResolver vertexResolver;
    private final Map<Object, VertexQueryResult> vertexCache = new LinkedHashMap<>();
    private final Map<EdgeCacheKey, List<EdgeQueryResult>> edgeCache = new LinkedHashMap<>();
    private int batchSize = DEFAULT_BATCH_SIZE;
    private int maxBatchSize = DEFAULT_MAX_BATCH_SIZE;
    private int parallelism = DEFAULT_PARALLELISM;
    private boolean adaptiveBatchEnabled = true;
    private boolean explicitBatchSize = false;
    private List<String> readProperties = List.of(); // empty = all, null = skip read
    private boolean cacheEnabled = true;
    private boolean collectSubgraph = false;
    private final Map<String, VertexRef> subgraphVertices = new LinkedHashMap<>();
    private final Map<String, VertexRef> edgeEndpointVertices = new LinkedHashMap<>();
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

    /** How many storage read batches may run concurrently ({@code 1} = sequential). */
    public int parallelism() {
        return parallelism;
    }

    public void parallelism(int parallelism) {
        if (parallelism < 1) {
            throw new IllegalArgumentException("parallelism must be >= 1");
        }
        this.parallelism = parallelism;
    }

    /**
     * Splits the ids into batches of exactly {@link #batchSize()} ids: {@code batch(n)}
     * is the storage request size, so it is honoured as given. The batches are
     * issued in parallel by {@link #readBatches}.
     */
    public List<List<Object>> batches(List<Object> ids) {
        return split(ids, Math.max(1, batchSize));
    }

    /**
     * Splits the <em>seed</em> ids to fill up to {@link #parallelism()} concurrent
     * reads: a batch is never larger than {@link #batchSize()}, but with few ids it
     * is smaller, so {@code g.V(id1, id2, id3)} reads each seed in its own request
     * (in parallel) instead of one batch after another.
     */
    public List<List<Object>> seedBatches(List<Object> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        int target = (ids.size() + parallelism - 1) / parallelism;
        return split(ids, Math.max(1, Math.min(batchSize, target)));
    }

    private static List<List<Object>> split(List<Object> ids, int size) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<List<Object>> batches = new ArrayList<>((ids.size() + size - 1) / size);
        for (int start = 0; start < ids.size(); start += size) {
            batches.add(List.copyOf(ids.subList(start, Math.min(start + size, ids.size()))));
        }
        return batches;
    }

    /**
     * Reads every batch with {@code reader}, up to {@link #parallelism()} at a time,
     * and returns the results in batch order. A single batch (or
     * {@code parallelism(1)}) runs inline, and a failure is rethrown as it was
     * thrown by the reader.
     */
    <R> List<R> readBatches(List<List<Object>> batches, Function<List<Object>, R> reader) {
        if (batches.isEmpty()) {
            return List.of();
        }
        if (batches.size() == 1 || parallelism <= 1) {
            List<R> sequential = new ArrayList<>(batches.size());
            for (List<Object> batch : batches) {
                sequential.add(reader.apply(batch));
            }
            return sequential;
        }
        List<Callable<R>> tasks = new ArrayList<>(batches.size());
        for (List<Object> batch : batches) {
            tasks.add(() -> reader.apply(batch));
        }
        List<Future<R>> futures = ForkJoinPool.commonPool().invokeAll(tasks);
        List<R> results = new ArrayList<>(futures.size());
        for (Future<R> future : futures) {
            try {
                results.add(future.get());
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw new IllegalStateException("Batched read failed", cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Batched read interrupted", e);
            }
        }
        return results;
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

    /** Whether the execution is collecting an induced subgraph (see {@link #executeSubgraph}). */
    public boolean collectSubgraph() {
        return collectSubgraph;
    }

    /** Records a vertex touched by the traversal (deduplicated by label + id). */
    /** The creation-time window the following edge reads use ({@link TimeRange#ALL} = none). */
    private TimeRange edgeTimeRange = TimeRange.ALL;

    /** The most edges one class of one vertex may contribute ({@code 0} = no cap). */
    private int edgeReadLimit;

    /** Sets the window a {@code createdBetween} step declared. */
    public void edgeTimeRange(TimeRange range) {
        edgeTimeRange(range, 0);
    }

    /**
     * Sets the window and the per-class cap the following edge reads use. The cap is what a
     * {@code limit} after the edge step becomes for a hub: the aggregated tables are ordered by
     * creation time, so a capped read stops early instead of materialising the whole window.
     */
    public void edgeTimeRange(TimeRange range, int maxResults) {
        this.edgeTimeRange = range == null ? TimeRange.ALL : range;
        this.edgeReadLimit = Math.max(maxResults, 0);
        // a different window reads different edges: the per-query caches must not answer for it
        edgeCache.clear();
    }

    /** The per-class cap of a windowed edge read ({@code 0} = no cap). */
    public int edgeReadLimit() {
        return edgeReadLimit;
    }

    /** The window the following edge reads use. */
    public TimeRange edgeTimeRange() {
        return edgeTimeRange;
    }

    /** Whether a window is in force (a backend with aggregated tables may need one). */
    public boolean hasEdgeTimeRange() {
        return !edgeTimeRange.isUnbounded();
    }

    public void recordVertex(String label, Object id) {
        if (!collectSubgraph) {
            return;
        }
        Object text = id == null ? null : String.valueOf(id);
        subgraphVertices.putIfAbsent(key(label, text), new VertexRef(label, text));
    }

    /**
     * Drops an element from the collected subgraph because a filter step discarded
     * it, so {@code bothE().filter(...)} renders the filtered edges instead of every
     * edge it had to read to evaluate the filter.
     */
    public void dropElement(GremlinElement element) {
        if (!collectSubgraph || element == null) {
            return;
        }
        String key = key(element.label(), element.id());
        if (element instanceof GremlinEdge) {
            subgraphEdges.remove(key);
        } else {
            subgraphVertices.remove(key);
        }
    }

    /** Records an edge touched by the traversal (deduplicated by label + id). */
    public void recordEdge(String label, Object id, Object source, Object target,
                           Map<String, Object> properties, long createdTime, long lastUpdatedTime) {
        if (!collectSubgraph) {
            return;
        }
        Object text = id == null ? null : String.valueOf(id);
        subgraphEdges.putIfAbsent(key(label, text),
                new Subgraph.Edge(label, text, source, target, properties, createdTime, lastUpdatedTime));
    }

    /** Reads full vertex properties for every recorded vertex and returns the induced subgraph. */
    public Subgraph buildSubgraph() {
        if (!collectSubgraph) {
            return Subgraph.EMPTY;
        }
        // vertices reached only as an edge's far endpoint stay in the subgraph while
        // a surviving edge still references them (a dropped edge must not leave a
        // dangling node behind)
        Map<Object, List<VertexRef>> endpointsById = new LinkedHashMap<>();
        for (VertexRef ref : edgeEndpointVertices.values()) {
            endpointsById.computeIfAbsent(ref.id(), k -> new ArrayList<>()).add(ref);
        }
        Map<String, VertexRef> verticesToRead = new LinkedHashMap<>(subgraphVertices);
        for (Subgraph.Edge edge : subgraphEdges.values()) {
            // an edge with a nullable target has no far endpoint: only the endpoint it has is
            // resolved (List.of would reject the null it carries)
            for (Object endpoint : endpointsOf(edge)) {
                if (endpoint == null) {
                    continue;
                }
                for (VertexRef ref : endpointsById.getOrDefault(endpoint, List.of())) {
                    verticesToRead.putIfAbsent(key(ref.label(), ref.id()), ref);
                }
            }
        }
        Map<String, List<Object>> idsByLabel = new LinkedHashMap<>();
        for (VertexRef ref : verticesToRead.values()) {
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
                for (VertexQueryResult v : read) {
                    if (v == null) {
                        continue; // referenced id that does not exist — never emit a phantom vertex
                    }
                    vertices.add(new Subgraph.Vertex(v.vertexName(), v.vertexId(), v.properties(),
                            v.createdTime(), v.lastUpdatedTime()));
                }
            }
        }
        return new Subgraph(List.copyOf(vertices), List.copyOf(subgraphEdges.values()));
    }

    /** The endpoints of an edge, tolerating a null one (a nullable target). */
    private static List<Object> endpointsOf(Subgraph.Edge edge) {
        List<Object> endpoints = new ArrayList<>(2);
        endpoints.add(edge.source());
        endpoints.add(edge.target());
        return endpoints;
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
            // An edge hop only materialises edges; remember the far endpoint so a
            // surviving edge can still bring its other vertex into the subgraph.
            String endpointLabel = vertexResolver.neighborVertexName(edge.edgeName(), edge.side());
            Object endpointId = edge.otherVertexId() == null ? null : String.valueOf(edge.otherVertexId());
            edgeEndpointVertices.putIfAbsent(key(endpointLabel, endpointId),
                    new VertexRef(endpointLabel, endpointId));
        }
    }

    /**
     * Reads vertices, de-duplicating ids and reusing previously read ids from the
     * cache. Only unique ids not yet cached are queried against the storage; the
     * result preserves the request order (duplicates included).
     */
    public List<VertexQueryResult> readVerticesCached(String vertexName, List<Object> vertexIds,
                                                      List<String> propertyNames) {
        return readVertices(vertexName, vertexIds, propertyNames, false);
    }

    /**
     * Reads the seed vertices of {@code g.V(...)}: same as {@link #readVerticesCached}
     * but the ids are split to use the available parallelism (see {@link #seedBatches}).
     */
    public List<VertexQueryResult> readSeedVertices(String vertexName, List<Object> vertexIds,
                                                    List<String> propertyNames) {
        return readVertices(vertexName, vertexIds, propertyNames, true);
    }

    private List<VertexQueryResult> readVertices(String vertexName, List<Object> vertexIds,
                                                 List<String> propertyNames, boolean seed) {
        if (!cacheEnabled) {
            return readVerticesBatched(vertexName, vertexIds, propertyNames, seed);
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
            List<VertexQueryResult> fetched = readVerticesBatched(vertexName, toFetch, propertyNames, seed);
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

    /** Reads the vertices of many ids in parallel batches, preserving the request order. */
    public List<VertexQueryResult> readVerticesBatched(String vertexName, List<Object> vertexIds,
                                                       List<String> propertyNames) {
        return readVerticesBatched(vertexName, vertexIds, propertyNames, false);
    }

    private List<VertexQueryResult> readVerticesBatched(String vertexName, List<Object> vertexIds,
                                                        List<String> propertyNames, boolean seed) {
        if (vertexIds.isEmpty()) {
            return List.of();
        }
        List<List<Object>> batches = seed ? seedBatches(vertexIds) : batches(vertexIds);
        List<List<VertexQueryResult>> results = readBatches(batches,
                batch -> graphQuery.readVertices(vertexName, batch, propertyNames));
        List<VertexQueryResult> all = new ArrayList<>(vertexIds.size());
        for (List<VertexQueryResult> part : results) {
            all.addAll(part);
        }
        return all;
    }

    /**
     * Reads the edges of every edge class for the given vertices, in parallel
     * batches; the result has one entry per input vertex, in request order.
     */
    public List<List<EdgeQueryResult>> readAllEdgeClassesBatched(List<Object> vertexIds, EdgeSide direction,
                                                                 List<String> propertyNames) {
        if (vertexIds.isEmpty()) {
            return List.of();
        }
        List<List<List<EdgeQueryResult>>> results = readBatches(batches(vertexIds),
                batch -> hasEdgeTimeRange()
                        ? graphQuery.readAllEdgesBatchWithin(batch, direction, edgeTimeRange, propertyNames,
                                edgeReadLimit)
                        : graphQuery.readAllEdgesBatch(batch, direction, propertyNames));
        List<List<EdgeQueryResult>> all = new ArrayList<>(vertexIds.size());
        for (List<List<EdgeQueryResult>> part : results) {
            all.addAll(part);
        }
        return all;
    }

    /** Reads the edges of one edge class for many vertices in parallel batches (request order). */
    private List<List<EdgeQueryResult>> readEdgesBatched(String edgeName, EdgeSide direction,
                                                         List<Object> vertexIds, List<String> propertyNames) {
        List<List<List<EdgeQueryResult>>> results = readBatches(batches(vertexIds),
                batch -> hasEdgeTimeRange()
                        ? graphQuery.readEdgesBatchWithin(edgeName, direction, edgeTimeRange, batch,
                                propertyNames, edgeReadLimit)
                        : graphQuery.readEdgesBatch(edgeName, direction, batch, propertyNames));
        List<List<EdgeQueryResult>> all = new ArrayList<>(vertexIds.size());
        for (List<List<EdgeQueryResult>> part : results) {
            all.addAll(part);
        }
        return all;
    }

    /**
     * Counts the edges of one class for every given vertex, without materialising them.
     *
     * <p>Used by the {@code count()} rewrite: a store that keeps its edges in one record answers
     * from the record's shape, so {@code outE('Payment').count()} stops decoding a hub's payments.
     */
    public long countEdges(List<Object> vertexIds, String edgeName, EdgeSide direction) {
        long total = 0;
        for (Object vertexId : vertexIds) {
            total += graphQuery.countEdges(vertexId, null, edgeName, direction);
        }
        return total;
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
        List<EdgeQueryResult> result = hasEdgeTimeRange()
                ? graphQuery.readEdgesWithin(vertexId, vertexName, edgeName, direction, edgeTimeRange,
                        propertyNames, edgeReadLimit)
                : graphQuery.readEdges(vertexId, vertexName, edgeName, direction, propertyNames);
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
            List<List<EdgeQueryResult>> fetched = readEdgesBatched(edgeName, direction, vertexIds, propertyNames);
            for (int i = 0; i < vertexIds.size(); i++) {
                recordEdges(vertexIds.get(i), fetched.get(i));
                all.addAll(fetched.get(i));
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

        List<List<EdgeQueryResult>> fetched = readEdgesBatched(edgeName, direction, missing, propertyNames);
        for (int i = 0; i < missing.size(); i++) {
            Object id = missing.get(i);
            EdgeCacheKey key = new EdgeCacheKey(id, edgeName, direction);
            List<EdgeQueryResult> edges = fetched.get(i);
            edgeCache.put(key, edges);
            byKey.put(key, edges);
            recordEdges(id, edges);
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
