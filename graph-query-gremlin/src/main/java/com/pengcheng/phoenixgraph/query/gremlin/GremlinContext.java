package com.pengcheng.phoenixgraph.query.gremlin;

import com.pengcheng.phoenixgraph.query.result.Subgraph;
import com.pengcheng.phoenixgraph.query.traversal.NeighborVertexResolver;
import com.pengcheng.phoenixgraph.query.storage.EdgePropertyFilter;
import com.pengcheng.phoenixgraph.query.storage.EdgeQueryResult;
import com.pengcheng.phoenixgraph.storage.TimeRange;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
import com.pengcheng.phoenixgraph.query.storage.GraphQuery;
import com.pengcheng.phoenixgraph.query.storage.NeighbourRef;
import com.pengcheng.phoenixgraph.query.storage.VertexQueryResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    public static final int DEFAULT_BATCH_SIZE = 50;

    /** Upper bound for the adaptive batch size. */
    public static final int DEFAULT_MAX_BATCH_SIZE = 100;

    /** Default number of storage reads issued concurrently. */
    public static final int DEFAULT_PARALLELISM = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));

    /**
     * Fan-out divisor: the adaptive batch size is {@code fanOut / DIVISOR}, clamped. Aerospike's
     * batch reads are cheap at these sizes, so the divisor favours fewer, larger round trips over
     * many small ones (a hundred sources become two requests of fifty rather than five of twenty).
     */
    static final int ADAPTIVE_DIVISOR = 2;

    private final GraphQuery graphQuery;
    private final NeighborVertexResolver vertexResolver;
    private final Map<Object, VertexQueryResult> vertexCache = new LinkedHashMap<>();
    private final Map<EdgeCacheKey, List<EdgeQueryResult>> edgeCache = new LinkedHashMap<>();

    /**
     * The side effects of this run: {@code aggregate(name)}/{@code store(name)} collect into a name and
     * {@code cap(name)} reads it back. They live on the context (not on a step) so that a child traversal
     * - a {@code sideEffect(...)}, a branch - collects into the same container as its parent.
     */
    private final Map<String, List<Object>> sideEffects = new LinkedHashMap<>();

    /** @see #sideEffects */
    public Map<String, List<Object>> sideEffects() {
        return sideEffects;
    }

    /**
     * The named subgraphs collected by {@code subgraph(name)} (element key to element), read back by
     * {@code cap(name)}. Kept as the elements the step saw, assembled into a {@link Subgraph} only when
     * read, so a later step that drops a traverser does not silently change what was collected before.
     */
    private final Map<String, Map<String, Object>> namedSubgraphs = new LinkedHashMap<>();

    /** @see #namedSubgraphs */
    public Map<String, Map<String, Object>> namedSubgraphs() {
        return namedSubgraphs;
    }

    /** The named path trees collected by {@code tree(name)}, read back by {@code cap(name)}. */
    private final Map<String, Map<Object, Object>> namedTrees = new LinkedHashMap<>();

    /** @see #namedTrees */
    public Map<String, Map<Object, Object>> namedTrees() {
        return namedTrees;
    }

    /**
     * Assembles the elements a {@code subgraph(name)} collected into the {@link Subgraph} the result
     * carries: vertices and edges deduplicated by label and id, edges with their resolved endpoints.
     */
    public static Subgraph subgraphOf(java.util.Collection<Object> elements) {
        Map<String, Subgraph.Vertex> vertices = new LinkedHashMap<>();
        Map<String, Subgraph.Edge> edges = new LinkedHashMap<>();
        for (Object element : elements) {
            if (element instanceof GremlinVertex vertex) {
                vertices.putIfAbsent(vertex.label() + "#" + vertex.id(), new Subgraph.Vertex(vertex.label(),
                        vertex.id(), vertex.properties(), vertex.createdTime(), vertex.lastUpdatedTime()));
            } else if (element instanceof GremlinEdge edge) {
                edges.putIfAbsent(edge.label() + "#" + edge.id(), new Subgraph.Edge(edge.label(), edge.id(),
                        edge.sourceEndpoint(), edge.targetEndpoint(), edge.properties(), edge.createdTime(),
                        edge.lastUpdatedTime()));
            }
        }
        return new Subgraph(List.copyOf(vertices.values()), List.copyOf(edges.values()));
    }
    /** Neighbours read for a vertex hop: identity only, so it is cached apart from the edges. */
    private final Map<NeighbourCacheKey, List<NeighbourRef>> neighbourCache = new LinkedHashMap<>();

    /** The cache key of a neighbour list (the same dimensions the edge cache uses). */
    private record NeighbourCacheKey(Object vertexId, String edgeName, EdgeSide direction) {
    }
    private int batchSize = DEFAULT_BATCH_SIZE;
    private int maxBatchSize = DEFAULT_MAX_BATCH_SIZE;
    private int parallelism = DEFAULT_PARALLELISM;
    private boolean adaptiveBatchEnabled = true;
    private boolean explicitBatchSize = false;
    private List<String> readProperties = List.of(); // empty = all, null = skip read
    private boolean cacheEnabled = true;
    private int scanLimit = 0;
    private boolean collectSubgraph = false;
    /**
     * Whether the algorithm steps of this run go to a Gemini cluster. A distributed start
     * ({@code g.V(DIST)...}) sets it, so the marker at the start says where the whole query runs - the
     * scan, the expansions and the algorithm - instead of having to repeat {@code (DIST)} on the step.
     */
    private boolean distributedAlgorithms = false;
    /**
     * Edge classes an unnamed hop ({@code out()}, {@code bothE()}) must not read: the HNSW layer edges of a
     * vector index are the index' own machinery, not a relationship of the data, so a normal traversal over
     * "every edge" would otherwise walk them and a UI would draw them. A hop that names one explicitly still
     * reads it, and {@code nearest} records the layer-0 links it searched on purpose.
     */
    private Set<String> hiddenEdgeClasses = Set.of();
    private final Map<String, VertexRef> subgraphVertices = new LinkedHashMap<>();
    private final Map<String, VertexRef> edgeEndpointVertices = new LinkedHashMap<>();
    private final Map<String, Subgraph.Edge> subgraphEdges = new LinkedHashMap<>();

    /** For every edge a vertex hop read, the ids of the vertices it reached (see {@link #recordHopEdge}). */
    private final Map<String, Set<String>> hopEdgeVertices = new LinkedHashMap<>();

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

    /**
     * How many elements a starting scan reads, or {@code 0} for all of them.
     *
     * <p>A traversal that starts from a whole type ({@code g.V('Account')}, {@code g.E('Payment')})
     * reads as much as the store holds, which is what an analytical query wants and what a
     * mis-typed query must not do by accident. The cap is here rather than in the step so an
     * embedding (the HTTP service) can set one policy for every scan.
     */
    public int scanLimit() {
        return scanLimit;
    }

    /** Sets the cap a starting scan reads ({@code 0} = no cap). */
    public GremlinContext scanLimit(int scanLimit) {
        if (scanLimit < 0) {
            throw new IllegalArgumentException("scan limit must be >= 0");
        }
        this.scanLimit = scanLimit;
        return this;
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
     *
     * <p>The seed read happens when the source is expanded, before any step runs, so a {@code batch(n)} written
     * after {@code g.V(...)} in the same traversal does not reach it - the seed split is sized by
     * {@code parallelism} and capped by the batch size in force at that moment ({@link #DEFAULT_BATCH_SIZE} unless
     * a caller set one). {@code batch(n)} governs the reads after the step. {@code parallelism} is the knob for
     * the seed fan-out, and it is the one to pin in a test.
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

    /**
     * Asks this run's algorithm steps to use the distributed engine (a Gemini cluster). Set by the
     * distributed runner, so a query that starts with {@code g.V(DIST)} runs its algorithm steps on the
     * cluster too - the explicit {@code cc(DIST)} form is for a query that runs in this process.
     */
    public boolean distributedAlgorithms() {
        return distributedAlgorithms;
    }

    /** @see #distributedAlgorithms() */
    public void distributedAlgorithms(boolean distributedAlgorithms) {
        this.distributedAlgorithms = distributedAlgorithms;
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
     * Drops an element from the collected subgraph because a step discarded it, so
     * {@code bothE().filter(...)} renders the filtered edges instead of every edge it had to read to
     * evaluate the filter, and {@code both('X').limit(3)} renders the three edges of the answer
     * instead of every edge the hub has.
     *
     * <p>A dropped vertex also leaves the far-endpoint registry: it is not part of the traversal any
     * more, so it must not come back as the endpoint of an edge that is still recorded - it was
     * dropped together with the edges that were read because of it.
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
            edgeEndpointVertices.remove(key);
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

    /**
     * Records that a vertex hop reached {@code vertexId} through the edge {@code edgeId}.
     *
     * <p>A vertex hop's edges are only the way to the vertex at their far end, so such an edge belongs
     * to the answer while that vertex is still part of the traversal. Without this, an edge the
     * traversal walked past stays in the drawn graph whenever a later step dropped the vertex it led
     * to but some other path happened to keep that vertex alive: filtering
     * {@code out('PhoneToAccount')} down to account 1002 still drew the phone -> 1003 edge, because
     * 1003 was also the target of a payment of 1002.
     */
    public void recordHopEdge(String edgeLabel, Object edgeId, Object vertexId) {
        if (!collectSubgraph) {
            return;
        }
        hopEdgeVertices.computeIfAbsent(key(edgeLabel, String.valueOf(edgeId)), k -> new LinkedHashSet<>())
                .add(String.valueOf(vertexId));
    }

    /** Reads full vertex properties for every recorded vertex and returns the induced subgraph. */
    public Subgraph buildSubgraph() {
        if (!collectSubgraph) {
            return Subgraph.EMPTY;
        }
        // An edge is part of the subgraph while both of its endpoints are part of the traversal: a
        // vertex the traversal kept, or the far endpoint a kept edge reaches. A step that dropped a
        // vertex removed it from both sets, so the edges that were read because of it go with it -
        // which is what keeps the drawn graph the answer (a capped or filtered hop draws what it
        // kept, not everything it walked past).
        Set<String> liveIds = endpointIds(subgraphVertices.values());
        Set<String> pendingIds = endpointIds(edgeEndpointVertices.values());
        List<Subgraph.Edge> edges = new ArrayList<>(subgraphEdges.size());
        for (Subgraph.Edge edge : subgraphEdges.values()) {
            // an edge of a vertex hop is kept while a vertex it reached is still in the traversal; an
            // edge traverser (or an edge of a class nothing else claims) keeps the endpoint rule
            Set<String> reached = hopEdgeVertices.get(key(edge.label(), edge.id()));
            boolean keep = reached == null
                    ? belongsToTraversal(edge, liveIds, pendingIds)
                    : reached.stream().anyMatch(liveIds::contains);
            if (keep) {
                edges.add(edge);
            }
        }
        // vertices reached only as an edge's far endpoint stay in the subgraph while a surviving edge
        // still references them (a dropped edge must not leave a dangling node behind)
        Map<Object, List<VertexRef>> endpointsById = new LinkedHashMap<>();
        for (VertexRef ref : edgeEndpointVertices.values()) {
            endpointsById.computeIfAbsent(text(ref.id()), k -> new ArrayList<>()).add(ref);
        }
        Map<String, VertexRef> verticesToRead = new LinkedHashMap<>(subgraphVertices);
        for (Subgraph.Edge edge : edges) {
            // an edge with a nullable target has no far endpoint: only the endpoint it has is
            // resolved (List.of would reject the null it carries)
            for (Object endpoint : endpointsOf(edge)) {
                if (endpoint == null) {
                    continue;
                }
                for (VertexRef ref : endpointsById.getOrDefault(text(endpoint), List.of())) {
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
        return new Subgraph(List.copyOf(vertices), List.copyOf(edges));
    }

    /** Whether both endpoints of an edge are still part of the traversal (live or reached by it). */
    private static boolean belongsToTraversal(Subgraph.Edge edge, Set<String> liveIds,
                                              Set<String> pendingIds) {
        for (Object endpoint : endpointsOf(edge)) {
            if (endpoint == null) {
                continue; // a nullable target has no far side
            }
            String id = text(endpoint);
            if (!liveIds.contains(id) && !pendingIds.contains(id)) {
                return false;
            }
        }
        return true;
    }

    private static Set<String> endpointIds(Iterable<VertexRef> refs) {
        Set<String> ids = new LinkedHashSet<>();
        for (VertexRef ref : refs) {
            if (ref != null && ref.id() != null) {
                ids.add(text(ref.id()));
            }
        }
        return ids;
    }

    /** Ids are compared as text: a query names them as text, a scan reports them typed. */
    private static String text(Object id) {
        return String.valueOf(id);
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
     * The neighbours of many vertices, in the request order, through the port's neighbour read.
     *
     * <p>Used by a vertex hop when nothing will draw the edges it walks: the read returns identity only,
     * so a store can answer it from the header of each stored edge instead of decoding properties the
     * traversal throws away. Ids are deduplicated for the read (a repeated vertex is read once) and the
     * result keeps the request order, duplicates included.
     */
    public List<List<NeighbourRef>> readNeighboursBatch(String edgeName, EdgeSide direction,
                                                        List<Object> vertexIds) {
        if (vertexIds.isEmpty()) {
            return List.of();
        }
        if (cacheEnabled) {
            List<Object> unique = new ArrayList<>();
            Set<Object> seen = new HashSet<>();
            for (Object id : vertexIds) {
                if (seen.add(id)) {
                    unique.add(id);
                }
            }
            Map<Object, List<NeighbourRef>> byId = new LinkedHashMap<>();
            List<Object> missing = new ArrayList<>();
            for (Object id : unique) {
                NeighbourCacheKey key = new NeighbourCacheKey(id, edgeName, direction);
                List<NeighbourRef> cached = neighbourCache.get(key);
                if (cached != null) {
                    byId.put(id, cached);
                } else {
                    missing.add(id);
                }
            }
            if (!missing.isEmpty()) {
                List<List<NeighbourRef>> fetched =
                        graphQuery.readNeighboursBatch(edgeName, direction, missing);
                for (int i = 0; i < missing.size(); i++) {
                    Object id = missing.get(i);
                    List<NeighbourRef> neighbours = fetched.get(i);
                    neighbourCache.put(new NeighbourCacheKey(id, edgeName, direction), neighbours);
                    byId.put(id, neighbours);
                }
            }
            List<List<NeighbourRef>> out = new ArrayList<>(vertexIds.size());
            for (Object id : vertexIds) {
                List<NeighbourRef> neighbours = byId.get(id);
                out.add(neighbours == null ? List.of() : neighbours);
            }
            return out;
        }
        return graphQuery.readNeighboursBatch(edgeName, direction, vertexIds);
    }

    /** Whether the caller asked for the subgraph a UI draws (it needs the edges and their properties). */
    public boolean collectsSubgraph() {
        return collectSubgraph;
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
     *
     * <p>Edge classes in {@link #hiddenEdgeClasses()} are dropped from the result: an unnamed hop walks the
     * graph's relationships, and the layer edges of a vector index are not one of them.
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
            for (List<EdgeQueryResult> edges : part) {
                all.add(hiddenEdgeClasses.isEmpty() ? edges : visible(edges));
            }
        }
        return all;
    }

    /** The edges that are not an index' own layer links. */
    private List<EdgeQueryResult> visible(List<EdgeQueryResult> edges) {
        List<EdgeQueryResult> out = new ArrayList<>(edges.size());
        for (EdgeQueryResult edge : edges) {
            if (!hiddenEdgeClasses.contains(edge.edgeName())) {
                out.add(edge);
            }
        }
        return out;
    }

    /** The edge classes an unnamed hop skips (the vector index layer edges of the schema). */
    public Set<String> hiddenEdgeClasses() {
        return hiddenEdgeClasses;
    }

    /** @see #hiddenEdgeClasses() */
    public GremlinContext hiddenEdgeClasses(Set<String> names) {
        this.hiddenEdgeClasses = names == null ? Set.of() : Set.copyOf(names);
        return this;
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
     * With conditions the store skips the edges that fail them while it walks the bin.
     */
    public long countEdges(List<Object> vertexIds, String edgeName, EdgeSide direction) {
        return countEdges(vertexIds, edgeName, direction, List.of());
    }

    /** @see #countEdges(List, String, EdgeSide) */
    public long countEdges(List<Object> vertexIds, String edgeName, EdgeSide direction,
                           List<EdgePropertyFilter> filters) {
        List<EdgePropertyFilter> conditions = filters == null ? List.of() : filters;
        if (vertexIds.isEmpty()) {
            return 0;
        }
        if (!hasEdgeTimeRange()) {
            // one batch get per read batch instead of one round trip per source: a count over many
            // vertices has far more sources than edges per source, so the trips are the cost
            List<Long> perBatch = readBatches(batches(vertexIds),
                    batch -> graphQuery.countEdgesBatch(edgeName, direction, batch, conditions));
            long total = 0;
            for (Long count : perBatch) {
                total += count == null ? 0 : count;
            }
            return total;
        }
        long total = 0;
        for (Object vertexId : vertexIds) {
            if (conditions.isEmpty()) {
                total += graphQuery.countEdges(vertexId, null, edgeName, direction);
            } else {
                // the window already reduces the read: the conditions are applied here
                List<EdgeQueryResult> edges = graphQuery.readEdgesWithin(vertexId, null, edgeName, direction,
                        edgeTimeRange, List.of(), edgeReadLimit);
                total += GraphQuery.applyFilters(edges, conditions, 0).size();
            }
        }
        return total;
    }

    /**
     * Reads the edges of one class for the given vertices with the conditions and the cap the
     * compiler pushed into the hop.
     *
     * <p>The conditions travel to the store, which skips the edges it would only build to discard;
     * the cap travels with them, so a bounded read stops early instead of building a hub's whole
     * bin. A read inside a time window is reduced by the window already (its cap included), so the
     * conditions are applied to what comes back.
     */
    public List<EdgeQueryResult> readEdgesFiltered(List<GremlinVertex> vertices, String edgeName,
                                                   EdgeSide direction, List<String> propertyNames,
                                                   List<EdgePropertyFilter> filters, int maxResults) {
        List<EdgeQueryResult> out = new ArrayList<>();
        for (List<EdgeQueryResult> edges : readEdgesFilteredByVertex(vertices, edgeName, direction,
                propertyNames, filters, maxResults)) {
            out.addAll(edges);
        }
        return out;
    }

    /**
     * The filtered read, one list per input vertex (same order, empty lists included).
     *
     * <p>A caller that builds edge traversers wants this form: an edge hop knows which vertex each of
     * its edges was read for, and keeping that is what lets a later step say which vertices the
     * traversal went through ({@code g.V('Account').filter(...).bothE('payment').cc(DIST)} is about the
     * accounts as well as their payments).
     */
    public List<List<EdgeQueryResult>> readEdgesFilteredByVertex(List<GremlinVertex> vertices,
                                                                 String edgeName, EdgeSide direction,
                                                                 List<String> propertyNames,
                                                                 List<EdgePropertyFilter> filters,
                                                                 int maxResults) {
        List<List<EdgeQueryResult>> perVertex = new ArrayList<>(vertices.size());
        int budget = Math.max(maxResults, 0);
        for (GremlinVertex vertex : vertices) {
            if (maxResults > 0 && budget <= 0) {
                // the cap is spent: the rest of the traversal order cannot matter, but the result still
                // has one entry per input vertex (callers build one traverser per read)
                perVertex.add(List.of());
                continue;
            }
            List<EdgeQueryResult> edges;
            if (hasEdgeTimeRange()) {
                edges = graphQuery.readEdgesWithin(vertex.id(), vertex.label(), edgeName, direction,
                        edgeTimeRange, propertyNames, edgeReadLimit);
                edges = GraphQuery.applyFilters(edges, filters, budget);
            } else {
                edges = graphQuery.readEdges(vertex.id(), vertex.label(), edgeName, direction,
                        propertyNames, filters, budget);
            }
            recordEdges(vertex.id(), edges);
            perVertex.add(edges);
            if (budget > 0) {
                budget -= edges.size();
            }
        }
        return perVertex;
    }

    /** Reads the edges of one vertex for an edge class + direction, with caching. */
    public List<EdgeQueryResult> readEdgesCached(Object vertexId, String vertexName, String edgeName,
                                                 EdgeSide direction, List<String> propertyNames) {
        EdgeCacheKey key = new EdgeCacheKey(vertexId, edgeName, direction);
        if (cacheEnabled) {
            List<EdgeQueryResult> cached = edgeCache.get(key);
            if (cached == null) {
                cached = fromTheOtherDirection(vertexId, edgeName, direction);
                if (cached != null) {
                    edgeCache.put(key, cached);
                }
            }
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
    public List<List<EdgeQueryResult>> readAllEdgesBatchCached(String edgeName, EdgeSide direction,
                                                               List<Object> vertexIds,
                                                               List<String> propertyNames) {
        if (!cacheEnabled) {
            List<List<EdgeQueryResult>> fetched = readEdgesBatched(edgeName, direction, vertexIds, propertyNames);
            for (int i = 0; i < vertexIds.size(); i++) {
                recordEdges(vertexIds.get(i), fetched.get(i));
            }
            return fetched;
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
            if (cached == null) {
                cached = fromTheOtherDirection(id, edgeName, direction);
                if (cached != null) {
                    edgeCache.put(key, cached);
                }
            }
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

        List<List<EdgeQueryResult>> out = new ArrayList<>(vertexIds.size());
        for (Object id : vertexIds) {
            EdgeCacheKey key = new EdgeCacheKey(id, edgeName, direction);
            List<EdgeQueryResult> edges = byKey.get(key);
            out.add(edges == null ? List.of() : edges);
        }
        return out;
    }

    /**
     * The edges of this vertex for a direction that is <b>already known</b> in another one.
     *
     * <p>A {@code both()} read holds the out half and the in half of every directed edge, each tagged
     * with {@link EdgeQueryResult#side() the side it was read from} - that is what makes a both() hop a
     * superset of an out() and an in() hop. The cache used to be keyed by direction, so a script that
     * walked {@code both('E').bothE('E')} and then {@code out('E').outE('E')} read the same edges from
     * the store twice; now the second direction is answered from the first.
     *
     * <p>An undirected (or bidirectional) schema answers every direction with the same list, tagged
     * {@code both}, so its out and in halves are not two disjoint sets and must not be concatenated.
     *
     * <p>Only the plain read is reused: with a time range or a read limit the store decides how much of
     * each bin to return, and that decision belongs to the direction that was asked for.
     */
    private List<EdgeQueryResult> fromTheOtherDirection(Object vertexId, String edgeName, EdgeSide direction) {
        if (hasEdgeTimeRange() || edgeReadLimit > 0) {
            return null;
        }
        EdgeCacheKey outKey = new EdgeCacheKey(vertexId, edgeName, EdgeSide.OUT);
        EdgeCacheKey inKey = new EdgeCacheKey(vertexId, edgeName, EdgeSide.IN);
        EdgeCacheKey bothKey = new EdgeCacheKey(vertexId, edgeName, EdgeSide.BOTH);
        if (direction == EdgeSide.BOTH) {
            List<EdgeQueryResult> out = edgeCache.get(outKey);
            List<EdgeQueryResult> in = edgeCache.get(inKey);
            if (out == null || in == null) {
                return null;
            }
            if (isUndirected(out)) {
                return out;
            }
            List<EdgeQueryResult> both = new ArrayList<>(out.size() + in.size());
            both.addAll(out);
            both.addAll(in);
            return both;
        }
        List<EdgeQueryResult> both = edgeCache.get(bothKey);
        if (both == null || isUndirected(both)) {
            return both;
        }
        List<EdgeQueryResult> one = new ArrayList<>(both.size());
        for (EdgeQueryResult edge : both) {
            if (edge.side() == direction) {
                one.add(edge);
            }
        }
        return one;
    }

    /** Whether these edges come from an undirected schema, which answers every direction alike. */
    private static boolean isUndirected(List<EdgeQueryResult> edges) {
        for (EdgeQueryResult edge : edges) {
            if (edge.side() != EdgeSide.BOTH) {
                return false;
            }
        }
        return true;
    }
}
