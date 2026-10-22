package com.pengcheng.phoenixgraph.storage.aerospike;

import com.aerospike.client.AerospikeClient;
import com.pengcheng.phoenixgraph.metrics.Metrics;
import com.pengcheng.phoenixgraph.metrics.MetricsSink;
import com.pengcheng.phoenixgraph.metrics.MutableMetricsSink;
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
import com.pengcheng.phoenixgraph.schema.EdgeDirection;
import com.pengcheng.phoenixgraph.schema.EdgePropertySchema;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.PropertyType;
import com.pengcheng.phoenixgraph.storage.AdjacentEdgeStore;
import com.pengcheng.phoenixgraph.storage.EdgeHeader;
import com.pengcheng.phoenixgraph.storage.BusinessLog;
import com.pengcheng.phoenixgraph.storage.BusinessLogEntry;
import com.pengcheng.phoenixgraph.storage.ErrorLog;
import com.pengcheng.phoenixgraph.storage.RetryingWriter;
import com.pengcheng.phoenixgraph.storage.StoredEdge;
import com.pengcheng.phoenixgraph.storage.TimeRange;
import com.pengcheng.phoenixgraph.storage.WriteOutcome;
import com.pengcheng.phoenixgraph.storage.log.JulBusinessLog;
import com.pengcheng.phoenixgraph.storage.log.JulErrorLog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Aerospike-backed edge storage, denormalised onto the vertex record.
 *
 * <p>Edges are stored inline in the vertex record using a Map (CDT) per
 * direction and edge schema. The bin name encodes the edge schema id followed
 * by the direction code:
 * <ul>
 *   <li>{@code ve_<edgeSchemaId>_0} — both (undirected/bidirectional edges)</li>
 *   <li>{@code ve_<edgeSchemaId>_1} — in (incoming directed edges)</li>
 *   <li>{@code ve_<edgeSchemaId>_2} — out (outgoing directed edges)</li>
 * </ul>
 *
 * <p>The CDT map key is the edge id. The value is a list
 * {@code [lastUpdatedTime, createdTime, properties]} where {@code lastUpdatedTime}
 * is first so edges can be ordered by it, and {@code properties} maps a property
 * index to {@code [value, createdTime, lastUpdatedTime]} (each property keeps its
 * own timestamps).
 *
 * <p>The maximum in-/out-degree retained per vertex is read from the edge
 * schema metadata ({@code maxOutgoingEdges}/{@code maxIncomingEdges} for
 * DIRECTED edges, or the single {@code maxDegree} for UNDIRECTED/BIDIRECTIONAL
 * edges). When a bin exceeds its limit, the oldest edges (by
 * {@code lastUpdatedTime}) are evicted. Edges are written with a
 * record-generation CAS (read-modify-write), with bounded retries. When a
 * vertex record does not yet exist, a minimal vertex stub (the record key is
 * the vertex id) is created to host the edge.
 */
public class AerospikeEdgeStore implements AdjacentEdgeStore {

    /** Prefix of edge bins. */
    public static final String EDGE_BIN_PREFIX = "ve_";
    /** Direction code for both (undirected/bidirectional) edges. */
    public static final int EDGE_DIR_BOTH = 0;
    /** Direction code for incoming edges. */
    public static final int EDGE_DIR_IN = 1;
    /** Direction code for outgoing edges. */
    public static final int EDGE_DIR_OUT = 2;

    private final AerospikeClient client;
    /** The same client, with every round trip counted into {@link #metrics}. */
    private final AerospikeOperations db;
    /** Where this store records its traffic; the graph's metrics are wired in later. */
    private final MutableMetricsSink metrics = new MutableMetricsSink();
    private final String namespace;
    private final String vertexSet;
    private final GraphSchema schema;
    private final int maxWriteAttempts;
    private final BusinessLog businessLog;
    private final ErrorLog errorLog;
    private final RetryingWriter writer;

    public AerospikeEdgeStore(AerospikeClient client, String namespace, String vertexSet) {
        this(client, namespace, vertexSet, null);
    }

    public AerospikeEdgeStore(AerospikeClient client, String namespace, String vertexSet, GraphSchema schema) {
        this(client, namespace, vertexSet, schema, new JulBusinessLog(), new JulErrorLog());
    }

    public AerospikeEdgeStore(AerospikeClient client, String namespace, String vertexSet, GraphSchema schema,
                              BusinessLog businessLog, ErrorLog errorLog) {
        this(client, namespace, vertexSet, schema, businessLog, errorLog, 3);
    }

    public AerospikeEdgeStore(AerospikeClient client, String namespace, String vertexSet, GraphSchema schema,
                              BusinessLog businessLog, ErrorLog errorLog, int maxWriteAttempts) {
        this.client = Objects.requireNonNull(client, "client");
        this.db = AerospikeOperations.metered(this.client, metrics);
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.vertexSet = Objects.requireNonNull(vertexSet, "vertexSet");
        this.schema = schema;
        this.businessLog = Objects.requireNonNull(businessLog, "businessLog");
        this.errorLog = Objects.requireNonNull(errorLog, "errorLog");
        this.maxWriteAttempts = maxWriteAttempts;
        this.writer = new RetryingWriter(maxWriteAttempts, businessLog, errorLog);
    }

    public AerospikeEdgeStore(String host, int port, String namespace, String vertexSet) {
        this(new AerospikeClient(host, port), namespace, vertexSet, null);
    }

    public AerospikeEdgeStore(String host, int port, String namespace, String vertexSet, GraphSchema schema) {
        this(new AerospikeClient(host, port), namespace, vertexSet, schema);
    }

    /** Returns the bin name for an edge schema id and a direction code. */
    public static String edgeBin(int edgeSchemaId, int directionCode) {
        return EDGE_BIN_PREFIX + edgeSchemaId + "_" + directionCode;
    }

    /** Direction bin for a vertex being the source of an edge. */
    static int sourceDirection(EdgeDirection direction) {
        return direction == EdgeDirection.DIRECTED ? EDGE_DIR_OUT : EDGE_DIR_BOTH;
    }

    /** Direction bin for a vertex being the destination of an edge. */
    static int destinationDirection(EdgeDirection direction) {
        return direction == EdgeDirection.DIRECTED ? EDGE_DIR_IN : EDGE_DIR_BOTH;
    }

    /** Serializes an edge value: {@code [lastUpdatedTime, createdTime, otherVertexId, properties]}. */
    static List<Object> serializeEdgeValue(long lastUpdatedTime, long createdTime, Object otherVertexId,
                                           Map<Integer, EdgePropertyValue> properties) {
        Map<Integer, List<Object>> props = new LinkedHashMap<>();
        for (Map.Entry<Integer, EdgePropertyValue> entry : properties.entrySet()) {
            EdgePropertyValue p = entry.getValue();
            props.put(entry.getKey(), Arrays.asList(p.value(), p.createdTime(), p.lastUpdatedTime()));
        }
        return Arrays.asList(lastUpdatedTime, createdTime, otherVertexId, props);
    }

    /** Decodes a raw edge value back into a {@link StoredEdgeValue}. */
    @SuppressWarnings("unchecked")
    /**
     * One edge read as the store sees it: which properties to build, which ones the conditions
     * read, the conditions themselves, the creation-time window and how many edges are wanted
     * ({@code 0} = all).
     *
     * <p>Property numbers are the schema ids of the edge class. {@code projection} and
     * {@code filterProperties} are unioned for decoding: an edge that fails a condition is
     * discarded before the projection is applied, so a condition never costs more than the
     * properties it actually reads. A {@code null} projection means "every property".
     *
     * <p>The window is part of the read for the same reason the conditions are: an edge outside it is
     * dropped from the raw entry, before anything is built - a query that asks for one hour of a hub's
     * history never pays for the rest of it.
     */
    public record EdgeRead(Set<Integer> projection, Set<Integer> filterProperties,
                           java.util.function.Predicate<Map<Integer, Object>> filter, TimeRange window,
                           int maxResults) {

        public EdgeRead {
            projection = projection == null ? null : Set.copyOf(projection);
            filterProperties = filterProperties == null ? Set.of() : Set.copyOf(filterProperties);
            window = window == null ? TimeRange.ALL : window;
        }

        /** Everything, uncapped - what a plain read asks for. */
        public static EdgeRead all() {
            return new EdgeRead(null, Set.of(), null, TimeRange.ALL, 0);
        }

        /** Only these properties (a query projection), uncapped. */
        public static EdgeRead of(Set<Integer> projection) {
            return new EdgeRead(projection, Set.of(), null, TimeRange.ALL, 0);
        }

        /** A read with conditions and/or a cap. */
        public static EdgeRead filtered(Set<Integer> projection, Set<Integer> filterProperties,
                                        java.util.function.Predicate<Map<Integer, Object>> filter,
                                        int maxResults) {
            return new EdgeRead(projection, filterProperties, filter, TimeRange.ALL, maxResults);
        }

        public boolean hasFilter() {
            return filter != null;
        }

        /** Whether the read is bounded in time. */
        public boolean hasWindow() {
            return !window.isUnbounded();
        }

        /** Whether a creation time is inside the window. */
        public boolean inWindow(long createdTime) {
            return window.contains(createdTime);
        }

        /** Whether the read is a plain one (no condition, no window, no cap, no projection). */
        public boolean isPlain() {
            return filter == null && maxResults <= 0 && projection == null && !hasWindow();
        }

        /** The property ids to decode for the condition test + the result ({@code null} = all). */
        public Set<Integer> decodedProperties() {
            if (projection == null) {
                return null;
            }
            if (filterProperties.isEmpty()) {
                return projection;
            }
            Set<Integer> ids = new LinkedHashSet<>(filterProperties);
            ids.addAll(projection);
            return Set.copyOf(ids);
        }

        /** The read that keeps only the parameters of this one but decodes nothing extra. */
        public EdgeRead withMaxResults(int max) {
            return new EdgeRead(projection, filterProperties, filter, window, max);
        }

        /** The same read, narrowed to a creation-time window. */
        public EdgeRead withWindow(TimeRange window) {
            return new EdgeRead(projection, filterProperties, filter, window, maxResults);
        }
    }

    static StoredEdgeValue deserializeEdgeValue(String edgeId, Object raw) {
        return deserializeEdgeValue(edgeId, raw, null);
    }

    /**
     * Same, keeping only the requested properties ({@code null} keeps all of them).
     *
     * <p>A query that asks for {@code status} alone does not need the other properties of an edge
     * to be built: the values are in the bin either way, but the objects are not.
     */
    static StoredEdgeValue deserializeEdgeValue(String edgeId, Object raw, java.util.Set<Integer> only) {
        if (!(raw instanceof List<?> list) || list.size() < 4) {
            throw new IllegalArgumentException("Malformed edge value for " + edgeId);
        }
        long lastUpdatedTime = ((Number) list.get(0)).longValue();
        long createdTime = ((Number) list.get(1)).longValue();
        Object otherVertexId = list.get(2);
        Map<Integer, EdgePropertyValue> properties = new LinkedHashMap<>();
        if (list.get(3) instanceof Map<?, ?> rawProps) {
            for (Map.Entry<?, ?> entry : rawProps.entrySet()) {
                int index = toIndex(entry.getKey());
                if (only != null && !only.contains(index)) {
                    continue;
                }
                List<?> pv = (List<?>) entry.getValue();
                properties.put(index, new EdgePropertyValue(
                        pv.get(0),
                        ((Number) pv.get(1)).longValue(),
                        ((Number) pv.get(2)).longValue()));
            }
        }
        return new StoredEdgeValue(edgeId, otherVertexId, lastUpdatedTime, createdTime, properties);
    }

    /**
     * Merges an incoming edge value into an existing one with a per-property
     * {@code lastUpdatedTime} CAS: newer properties replace the stored ones,
     * older properties are ignored. Returns {@code null} when every incoming
     * property is stale (nothing to write).
     */
    static List<Object> mergeEdgeValue(String edgeId, Object incoming, Object existing) {
        StoredEdgeValue incomingEdge = deserializeEdgeValue(edgeId, incoming);
        StoredEdgeValue existingEdge = deserializeEdgeValue(edgeId, existing);

        Map<Integer, EdgePropertyValue> merged = new LinkedHashMap<>(existingEdge.properties());
        boolean anyNewer = false;
        for (Map.Entry<Integer, EdgePropertyValue> entry : incomingEdge.properties().entrySet()) {
            EdgePropertyValue old = merged.get(entry.getKey());
            if (old == null || entry.getValue().lastUpdatedTime() > old.lastUpdatedTime()) {
                merged.put(entry.getKey(), entry.getValue());
                anyNewer = true;
            }
        }
        if (!anyNewer) {
            return null;
        }
        long lastUpdatedTime = Math.max(existingEdge.lastUpdatedTime(),
                edgeLastUpdatedTime(incomingEdge.lastUpdatedTime(), merged));
        return serializeEdgeValue(lastUpdatedTime, existingEdge.createdTime(),
                existingEdge.otherVertexId(), merged);
    }

    /** Maximum property lastUpdatedTime, or {@code fallback} when there are no properties. */
    static long edgeLastUpdatedTime(long fallback, Map<Integer, EdgePropertyValue> properties) {
        long max = Long.MIN_VALUE;
        for (EdgePropertyValue property : properties.values()) {
            max = Math.max(max, property.lastUpdatedTime());
        }
        return max == Long.MIN_VALUE ? fallback : max;
    }

    /** Removes the oldest edges (by lastUpdatedTime) beyond {@code limit}. */
    static void evictOldest(Map<String, Object> edges, int limit) {
        evictOldest(edges, limit, null);
    }

    /** Same, counting the edges that fall off the degree limit. */
    static int evictOldest(Map<String, Object> edges, int limit, com.pengcheng.phoenixgraph.metrics.MetricsSink metrics) {
        if (limit <= 0 || edges.size() <= limit) {
            return 0;
        }
        List<String> toEvict = edges.entrySet().stream()
                .sorted(Comparator.comparingLong(e -> lastUpdatedTimeOf(e.getValue())))
                .limit(edges.size() - limit)
                .map(Map.Entry::getKey)
                .toList();
        toEvict.forEach(edges::remove);
        if (metrics != null && !toEvict.isEmpty()) {
            metrics.increment(com.pengcheng.phoenixgraph.metrics.Metrics.EDGE_EVICTED, toEvict.size());
        }
        return toEvict.size();
    }

    /** Extracts the lastUpdatedTime (first element) from a serialized edge value. */
    static long lastUpdatedTimeOf(Object value) {
        if (value instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Number number) {
            return number.longValue();
        }
        return Long.MAX_VALUE;
    }

    /** Combined limit for both-direction bins (the tighter positive limit). */
    static int bothLimit(int maxOutgoing, int maxIncoming) {
        if (maxOutgoing <= 0) {
            return maxIncoming;
        }
        if (maxIncoming <= 0) {
            return maxOutgoing;
        }
        return Math.min(maxOutgoing, maxIncoming);
    }

    private static int toIndex(Object key) {
        if (key instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(key));
    }

    private static WritePolicy generationPolicy(int generation) {
        WritePolicy policy = new WritePolicy();
        policy.generationPolicy = GenerationPolicy.EXPECT_GEN_EQUAL;
        policy.generation = generation;
        return policy;
    }

    private static WritePolicy createOnlyPolicy() {
        WritePolicy policy = new WritePolicy();
        policy.recordExistsAction = RecordExistsAction.CREATE_ONLY;
        return policy;
    }

    private static BatchWritePolicy generationBatchPolicy(int generation) {
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
        if (resultCode == ResultCode.FILTERED_OUT
                || resultCode == ResultCode.KEY_EXISTS_ERROR
                || resultCode == ResultCode.GENERATION_ERROR) {
            return WriteOutcome.CONFLICT;
        }
        return WriteOutcome.FAILED;
    }

    /**
     * Upserts an edge onto its source and destination vertices.
     *
     * <p>For a {@link EdgeDirection#DIRECTED} edge the out-edge is stored on the
     * source ({@code ve_<schema>_2}) and the in-edge on the destination
     * ({@code ve_<schema>_1}); for undirected/bidirectional edges the edge is
     * stored as both ({@code ve_<schema>_0}) on both endpoints.
     */
    public WriteOutcome upsertEdge(EdgeUpsert edge) {
        Objects.requireNonNull(edge, "edge");
        EdgeSchema edgeSchema = edge.edgeSchema();
        EdgeDirection direction = edgeSchema.direction();
        String edgeId = String.valueOf(edge.edgeId());
        long effectiveUpdated = edgeLastUpdatedTime(edge.lastUpdatedTime(), edge.properties());
        List<Object> onSource = serializeEdgeValue(effectiveUpdated, edge.createdTime(),
                edge.destination(), edge.properties());
        List<Object> onDestination = serializeEdgeValue(effectiveUpdated, edge.createdTime(),
                edge.source(), edge.properties());

        int sourceDir = sourceDirection(direction);
        int destinationDir = destinationDirection(direction);

        WriteOutcome sourceOutcome = upsertInto(edge.source(), edgeSchema, sourceDir, edgeId, onSource);
        if (sourceOutcome == WriteOutcome.FAILED) {
            return WriteOutcome.FAILED;
        }
        WriteOutcome destinationOutcome = upsertInto(edge.destination(), edgeSchema, destinationDir, edgeId, onDestination);
        if (destinationOutcome == WriteOutcome.FAILED) {
            return WriteOutcome.FAILED;
        }
        return WriteOutcome.SUCCESS;
    }

    /**
     * Upserts several edges (of possibly different edge classes), aggregating by
     * vertex so each vertex's shared bin (out/in for DIRECTED, both for
     * UNDIRECTED/BIDIRECTIONAL) is read and written once, evicting against the
     * schema's degree limit a single time. Returns one outcome per edge in the
     * input order.
     */
    public List<WriteOutcome> upsertEdges(List<EdgeUpsert> edges) {
        Objects.requireNonNull(edges, "edges");
        if (edges.isEmpty()) {
            return List.of();
        }

        Map<Integer, EdgeSchema> schemas = new LinkedHashMap<>();
        Map<Integer, Map<Object, Map<Integer, Map<String, Object>>>> bySchemaVertex = new LinkedHashMap<>();
        record EdgeTargets(int schemaId, Object v1, int dir1, Object v2, int dir2, String edgeId) {}
        List<EdgeTargets> targets = new ArrayList<>(edges.size());

        for (EdgeUpsert edge : edges) {
            EdgeSchema schema = edge.edgeSchema();
            int schemaId = schema.id();
            schemas.putIfAbsent(schemaId, schema);
            EdgeDirection direction = schema.direction();
            String edgeId = String.valueOf(edge.edgeId());
            long updated = edgeLastUpdatedTime(edge.lastUpdatedTime(), edge.properties());
            Object source = edge.source();
            Object destination = edge.destination();
            int sourceDir = sourceDirection(direction);
            int destinationDir = destinationDirection(direction);

            Map<Object, Map<Integer, Map<String, Object>>> byVertex =
                    bySchemaVertex.computeIfAbsent(schemaId, k -> new LinkedHashMap<>());
            addEdgeValue(byVertex, source, sourceDir, edgeId,
                    serializeEdgeValue(updated, edge.createdTime(), destination, edge.properties()));
            addEdgeValue(byVertex, destination, destinationDir, edgeId,
                    serializeEdgeValue(updated, edge.createdTime(), source, edge.properties()));

            targets.add(new EdgeTargets(schemaId, source, sourceDir, destination, destinationDir, edgeId));
        }

        Map<Object, List<BinUpdate>> updatesByVertex = new LinkedHashMap<>();
        for (Map.Entry<Integer, Map<Object, Map<Integer, Map<String, Object>>>> schemaEntry
                : bySchemaVertex.entrySet()) {
            EdgeSchema schema = schemas.get(schemaEntry.getKey());
            for (Map.Entry<Object, Map<Integer, Map<String, Object>>> vertexEntry
                    : schemaEntry.getValue().entrySet()) {
                List<BinUpdate> updates = updatesByVertex.computeIfAbsent(vertexEntry.getKey(), key -> new ArrayList<>());
                for (Map.Entry<Integer, Map<String, Object>> binEntry : vertexEntry.getValue().entrySet()) {
                    updates.add(new BinUpdate(schema, binEntry.getKey(), binEntry.getValue()));
                }
            }
        }
        Map<BinKey, BatchWriteResult> binOutcomes = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.stream.Stream<Map.Entry<Object, List<BinUpdate>>> groups = updatesByVertex.entrySet().stream();
        if (updatesByVertex.size() > 1) {
            groups = groups.parallel();
        }
        groups.forEach(entry -> {
            for (BinWriteResult result : upsertBinsForVertex(entry.getKey(), entry.getValue())) {
                binOutcomes.put(new BinKey(result.schemaId(), entry.getKey(), result.dir()), result.result());
            }
        });

        List<WriteOutcome> outcomes = new ArrayList<>(edges.size());
        for (EdgeTargets target : targets) {
            outcomes.add(combine(
                    edgeOutcome(binOutcomes.get(new BinKey(target.schemaId(), target.v1(), target.dir1())),
                            target.edgeId()),
                    edgeOutcome(binOutcomes.get(new BinKey(target.schemaId(), target.v2(), target.dir2())),
                            target.edgeId())));
        }
        return outcomes;
    }

    private static void addEdgeValue(Map<Object, Map<Integer, Map<String, Object>>> byVertex, Object vertex,
                                     int dir, String edgeId, List<Object> value) {
        byVertex.computeIfAbsent(vertex, k -> new LinkedHashMap<>())
                .computeIfAbsent(dir, k -> new LinkedHashMap<>())
                .put(edgeId, value);
    }

    /**
     * Batch-upserts multiple directed edges as <em>incoming</em> edges of the
     * given vertex. Every edge must be DIRECTED and have
     * {@code destination() == vertexId}. Delegates to
     * {@link #upsertEdgesOnVertex(Object, List)}.
     */
    public List<WriteOutcome> upsertIncomingEdges(Object vertexId, List<EdgeUpsert> edges) {
        requireDirection(vertexId, edges, false);
        return upsertEdgesOnVertex(vertexId, edges);
    }

    /**
     * Batch-upserts multiple directed edges as <em>outgoing</em> edges of the
     * given vertex. Every edge must be DIRECTED and have
     * {@code source() == vertexId}. Delegates to
     * {@link #upsertEdgesOnVertex(Object, List)}.
     */
    public List<WriteOutcome> upsertOutgoingEdges(Object vertexId, List<EdgeUpsert> edges) {
        requireDirection(vertexId, edges, true);
        return upsertEdgesOnVertex(vertexId, edges);
    }

    /**
     * Batch-upserts multiple edges of the same edge schema that all attach to
     * the given vertex, for any edge direction.
     *
     * <p>For {@link EdgeDirection#DIRECTED} edges each edge may be an incoming
     * ({@code destination == vertexId}) or outgoing ({@code source == vertexId})
     * edge; the edges are grouped by their shared direction bin and each bin is
     * read and written <b>once</b>, evicting the oldest edges a single time
     * against the schema's {@code maxInDegree} / {@code maxOutDegree}. For
     * {@link EdgeDirection#UNDIRECTED} / {@link EdgeDirection#BIDIRECTIONAL}
     * edges there is no in/out distinction: both endpoints share the single
     * {@code maxDegree} limit and the {@code ve_<schema>_0} bin is written once.
     *
     * <p>Each edge's other side is written individually. Returns one
     * {@link WriteOutcome} per edge.
     */
    public List<WriteOutcome> upsertEdgesOnVertex(Object vertexId, List<EdgeUpsert> edges) {
        Objects.requireNonNull(edges, "edges");
        if (edges.isEmpty()) {
            return List.of();
        }
        Objects.requireNonNull(vertexId, "vertexId");

        EdgeSchema edgeSchema = edges.get(0).edgeSchema();
        int edgeSchemaId = edgeSchema.id();

        List<EdgeWritePlan> plans = new ArrayList<>(edges.size());
        Map<Integer, Map<String, Object>> bySharedDir = new LinkedHashMap<>();
        for (EdgeUpsert edge : edges) {
            if (edge.edgeSchema().id() != edgeSchemaId) {
                throw new IllegalArgumentException(
                        "All edges in a batch must share the same edge schema (got " + edgeSchemaId
                                + " and " + edge.edgeSchema().id() + ")");
            }
            EdgeWritePlan plan = planFor(vertexId, edgeSchema, edge);
            bySharedDir.computeIfAbsent(plan.sharedDir(), k -> new LinkedHashMap<>())
                    .put(plan.edgeId(), serializeEdgeValue(
                            edgeLastUpdatedTime(edge.lastUpdatedTime(), edge.properties()),
                            edge.createdTime(), plan.otherEndpoint(), edge.properties()));
            plans.add(plan);
        }

        // One read-modify-write per shared direction bin on the vertex.
        Map<Integer, BatchWriteResult> sharedOutcomes = new LinkedHashMap<>();
        for (Map.Entry<Integer, Map<String, Object>> entry : bySharedDir.entrySet()) {
            sharedOutcomes.put(entry.getKey(),
                    upsertBatchInto(vertexId, edgeSchema, entry.getKey(), entry.getValue()));
        }

        // Write each edge's other side individually. An edge with a nullable target
        // (isNull on its target endpoint) has no far side: only the source bin is written.
        List<WriteOutcome> outcomes = new ArrayList<>(plans.size());
        for (EdgeWritePlan plan : plans) {
            if (plan.otherEndpoint() == null) {
                outcomes.add(edgeOutcome(sharedOutcomes.get(plan.sharedDir()), plan.edgeId()));
                continue;
            }
            List<Object> otherSideValue = serializeEdgeValue(
                    edgeLastUpdatedTime(plan.edge().lastUpdatedTime(), plan.edge().properties()),
                    plan.edge().createdTime(), vertexId, plan.edge().properties());
            WriteOutcome otherOutcome = upsertInto(plan.otherEndpoint(), edgeSchema, plan.otherDir(),
                    plan.edgeId(), otherSideValue);
            outcomes.add(combine(edgeOutcome(sharedOutcomes.get(plan.sharedDir()), plan.edgeId()),
                    otherOutcome));
        }
        return outcomes;
    }

    private void requireDirection(Object vertexId, List<EdgeUpsert> edges, boolean outgoing) {
        Objects.requireNonNull(edges, "edges");
        for (EdgeUpsert edge : edges) {
            EdgeDirection direction = edge.edgeSchema().direction();
            if (direction != EdgeDirection.DIRECTED) {
                throw new IllegalArgumentException("Edge '" + edge.edgeId() + "' schema direction is " + direction
                        + "; use upsertEdgesOnVertex for undirected/bidirectional edges");
            }
            Object endpoint = outgoing ? edge.source() : edge.destination();
            if (!Objects.equals(endpoint, vertexId)) {
                throw new IllegalArgumentException("Edge '" + edge.edgeId() + "' is not "
                        + (outgoing ? "an outgoing" : "an incoming") + " edge of vertex " + vertexId);
            }
        }
    }

    /** A per-edge write plan: shared bin on the vertex, the other endpoint + its bin, and the edge. */
    record EdgeWritePlan(int sharedDir, Object otherEndpoint, int otherDir,
                         String edgeId, EdgeUpsert edge) {
    }

    static EdgeWritePlan planFor(Object vertexId, EdgeSchema edgeSchema, EdgeUpsert edge) {
        String edgeId = String.valueOf(edge.edgeId());
        EdgeDirection direction = edgeSchema.direction();

        if (direction == EdgeDirection.DIRECTED) {
            if (Objects.equals(edge.source(), vertexId)) {
                return new EdgeWritePlan(EDGE_DIR_OUT, edge.destination(), EDGE_DIR_IN, edgeId, edge);
            }
            if (Objects.equals(edge.destination(), vertexId)) {
                return new EdgeWritePlan(EDGE_DIR_IN, edge.source(), EDGE_DIR_OUT, edgeId, edge);
            }
            throw new IllegalArgumentException("Edge '" + edgeId + "' does not touch vertex " + vertexId);
        }

        // UNDIRECTED / BIDIRECTIONAL: no in/out distinction, both endpoints use the BOTH bin.
        boolean sourceIsVertex = Objects.equals(edge.source(), vertexId);
        boolean destinationIsVertex = Objects.equals(edge.destination(), vertexId);
        if (!sourceIsVertex && !destinationIsVertex) {
            throw new IllegalArgumentException("Edge '" + edgeId + "' does not touch vertex " + vertexId);
        }
        Object other = sourceIsVertex ? edge.destination() : edge.source();
        return new EdgeWritePlan(EDGE_DIR_BOTH, other, EDGE_DIR_BOTH, edgeId, edge);
    }
    /**
     * Writes every bin of one vertex with a single record read and a single operate. The bins are merged
     * in memory first, so a vertex with both incoming and outgoing edges no longer pays two reads and two
     * generation-CAS writes for the same record.
     */
    private List<BinWriteResult> upsertBinsForVertex(Object vertexId, List<BinUpdate> updates) {
        if (updates.isEmpty()) {
            return List.of();
        }
        Exception last = null;
        for (int attempt = 1; attempt <= maxWriteAttempts; attempt++) {
            Record record;
            try {
                record = db.get(null, key(vertexId));
            } catch (AerospikeException e) {
                last = e;
                continue;
            }
            boolean create = record == null;
            Map<String, Map<String, Object>> mergedByBin = new LinkedHashMap<>();
            Map<String, Set<String>> staleByBin = new LinkedHashMap<>();
            for (BinUpdate update : updates) {
                String bin = edgeBin(update.schema().id(), update.dir());
                Map<String, Object> existing = create ? new LinkedHashMap<>() : readEdgeMap(record, bin);
                Set<String> stale = new LinkedHashSet<>();
                if (!create) {
                    for (Map.Entry<String, Object> entry : update.values().entrySet()) {
                        Object existingValue = existing.get(entry.getKey());
                        if (existingValue != null
                                && mergeEdgeValue(entry.getKey(), entry.getValue(), existingValue) == null) {
                            stale.add(entry.getKey());
                        }
                    }
                }
                mergedByBin.put(bin, mergeAndEvict(existing, update.values(),
                        limitFor(update.schema(), update.dir()), metrics));
                staleByBin.put(bin, stale);
            }
            Operation[] operations = mergedByBin.entrySet().stream()
                    .map(entry -> Operation.put(new Bin(entry.getKey(), entry.getValue())))
                    .toArray(Operation[]::new);
            try {
                db.operate(create ? createOnlyPolicy() : generationPolicy(record.generation), key(vertexId), operations);
            } catch (AerospikeException e) {
                last = e; // create race or generation conflict: re-read the whole vertex and retry
                continue;
            }

            List<BinWriteResult> results = new ArrayList<>(updates.size());
            for (BinUpdate update : updates) {
                String bin = edgeBin(update.schema().id(), update.dir());
                String target = vertexId + "@" + bin;
                Set<String> stale = staleByBin.getOrDefault(bin, Set.of());
                for (String edgeId : stale) {
                    businessLog.record(BusinessLogEntry.failure("upsertEdgesOnVertex", target,
                            Map.of("edgeId", edgeId, "bin", bin, "stale", true)));
                }
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("bin", bin);
                details.put("count", update.values().size());
                if (create) {
                    details.put("createdVertex", true);
                }
                businessLog.record(BusinessLogEntry.success("upsertEdgesOnVertex", target, details));
                WriteOutcome outcome = stale.size() == update.values().size()
                        ? WriteOutcome.SUCCESS_STALE : WriteOutcome.SUCCESS;
                results.add(new BinWriteResult(update.schema().id(), update.dir(),
                        new BatchWriteResult(outcome, stale)));
            }
            return results;
        }

        String error = last == null ? "unknown" : last.toString();
        List<BinWriteResult> results = new ArrayList<>(updates.size());
        for (BinUpdate update : updates) {
            String bin = edgeBin(update.schema().id(), update.dir());
            String target = vertexId + "@" + bin;
            Map<String, Object> details = Map.of("bin", bin, "count", update.values().size(), "error", error);
            errorLog.record(BusinessLogEntry.failure("upsertEdgesOnVertex", target, details));
            businessLog.record(BusinessLogEntry.failure("upsertEdgesOnVertex", target, details));
            results.add(new BinWriteResult(update.schema().id(), update.dir(),
                    new BatchWriteResult(WriteOutcome.FAILED, Set.of())));
        }
        return results;
    }


    private BatchWriteResult upsertBatchInto(Object vertexId, EdgeSchema edgeSchema, int dir,
                                             Map<String, Object> edgeValues) {
        int edgeSchemaId = edgeSchema.id();
        String bin = edgeBin(edgeSchemaId, dir);
        String target = vertexId + "@" + bin;
        int limit = limitFor(edgeSchema, dir);
        Exception last = null;

        for (int attempt = 1; attempt <= maxWriteAttempts; attempt++) {
            Record record;
            try {
                record = db.get(null, key(vertexId));
            } catch (AerospikeException e) {
                last = e;
                continue;
            }

            if (record == null) {
                try {
                    Map<String, Object> edges = mergeAndEvict(new LinkedHashMap<>(), edgeValues, limit, metrics);
                    db.operate(createOnlyPolicy(), key(vertexId),
                            Operation.put(new Bin(bin, edges)));
                    businessLog.record(BusinessLogEntry.success("upsertEdgesOnVertex", target,
                            Map.of("bin", bin, "count", edgeValues.size(), "createdVertex", true)));
                    return new BatchWriteResult(WriteOutcome.SUCCESS, Set.of());
                } catch (AerospikeException e) {
                    last = e; // create race -> re-read and retry (update path)
                    continue;
                }
            }

            try {
                Map<String, Object> existing = readEdgeMap(record, bin);
                Set<String> stale = new LinkedHashSet<>();
                for (Map.Entry<String, Object> entry : edgeValues.entrySet()) {
                    Object existingValue = existing.get(entry.getKey());
                    if (existingValue != null
                            && mergeEdgeValue(entry.getKey(), entry.getValue(), existingValue) == null) {
                        businessLog.record(BusinessLogEntry.failure("upsertEdgesOnVertex", target,
                                Map.of("edgeId", entry.getKey(), "bin", bin, "stale", true)));
                        stale.add(entry.getKey());
                    }
                }
                Map<String, Object> edges = mergeAndEvict(existing, edgeValues, limit, metrics);
                db.operate(generationPolicy(record.generation), key(vertexId),
                        Operation.put(new Bin(bin, edges)));
                businessLog.record(BusinessLogEntry.success("upsertEdgesOnVertex", target,
                        Map.of("bin", bin, "count", edgeValues.size())));
                WriteOutcome outcome = stale.size() == edgeValues.size()
                        ? WriteOutcome.SUCCESS_STALE : WriteOutcome.SUCCESS;
                return new BatchWriteResult(outcome, stale);
            } catch (AerospikeException e) {
                last = e; // generation race or transient -> re-read and retry
            }
        }

        Map<String, Object> details = Map.of(
                "bin", bin,
                "count", edgeValues.size(),
                "error", last == null ? "unknown" : last.toString());
        errorLog.record(BusinessLogEntry.failure("upsertEdgesOnVertex", target, details));
        businessLog.record(BusinessLogEntry.failure("upsertEdgesOnVertex", target, details));
        return new BatchWriteResult(WriteOutcome.FAILED, Set.of());
    }

    /**
     * Merges new edges into an existing edge map (with per-edge
     * {@code lastUpdatedTime} CAS for edges that already exist) and evicts the
     * oldest beyond {@code limit} (single pass). Stale edges keep their stored value.
     */
    static Map<String, Object> mergeAndEvict(Map<String, Object> existing, Map<String, Object> added, int limit) {
        return mergeAndEvict(existing, added, limit, null);
    }

    /** Same, counting the edges evicted by the degree limit. */
    static Map<String, Object> mergeAndEvict(Map<String, Object> existing, Map<String, Object> added, int limit,
                                             com.pengcheng.phoenixgraph.metrics.MetricsSink metrics) {
        Map<String, Object> merged = new LinkedHashMap<>(existing);
        for (Map.Entry<String, Object> entry : added.entrySet()) {
            Object existingValue = merged.get(entry.getKey());
            if (existingValue == null) {
                merged.put(entry.getKey(), entry.getValue());
            } else {
                List<Object> mergedValue = mergeEdgeValue(entry.getKey(), entry.getValue(), existingValue);
                if (mergedValue != null) {
                    merged.put(entry.getKey(), mergedValue);
                }
                // stale: keep the stored value
            }
        }
        evictOldest(merged, limit, metrics);
        return merged;
    }

    private static WriteOutcome combine(WriteOutcome shared, WriteOutcome other) {
        if (shared == WriteOutcome.FAILED || other == WriteOutcome.FAILED) {
            return WriteOutcome.FAILED;
        }
        if (shared == WriteOutcome.SUCCESS_STALE && other == WriteOutcome.SUCCESS_STALE) {
            return WriteOutcome.SUCCESS_STALE;
        }
        return WriteOutcome.SUCCESS;
    }

    /** What one batch write into a shared bin did: the outcome and which edge ids were stale. */
    private record BatchWriteResult(WriteOutcome outcome, java.util.Set<String> staleEdgeIds) {
    }

    private record BinKey(int schemaId, Object vertexId, int dir) {
    }

    private record BinUpdate(EdgeSchema schema, int dir, Map<String, Object> values) {
    }

    private record BinWriteResult(int schemaId, int dir, BatchWriteResult result) {
    }

    /** An edge's outcome inside a shared bin: stale only when that bin kept its stored copy. */
    private static WriteOutcome edgeOutcome(BatchWriteResult result, String edgeId) {
        if (result == null || result.outcome() == WriteOutcome.FAILED) {
            return WriteOutcome.FAILED;
        }
        return result.staleEdgeIds().contains(edgeId) ? WriteOutcome.SUCCESS_STALE : WriteOutcome.SUCCESS;
    }

    private WriteOutcome upsertInto(Object vertexId, EdgeSchema edgeSchema, int dir, String edgeId, List<Object> value) {
        int edgeSchemaId = edgeSchema.id();
        String bin = edgeBin(edgeSchemaId, dir);
        String target = vertexId + "@" + bin;
        int limit = limitFor(edgeSchema, dir);
        Exception last = null;

        for (int attempt = 1; attempt <= maxWriteAttempts; attempt++) {
            Record record;
            try {
                record = db.get(null, key(vertexId));
            } catch (AerospikeException e) {
                last = e;
                continue;
            }

            if (record == null) {
                // Build a minimal vertex stub (the record key carries the vertex
                // id) and attach the edge to it.
                try {
                    Map<String, Object> edges = new LinkedHashMap<>();
                    edges.put(edgeId, value);
                    db.operate(createOnlyPolicy(), key(vertexId),
                            Operation.put(new Bin(bin, edges)));
                    businessLog.record(BusinessLogEntry.success("upsertEdge", target,
                            Map.of("edgeId", edgeId, "bin", bin, "createdVertex", true)));
                    return WriteOutcome.SUCCESS;
                } catch (AerospikeException e) {
                    last = e; // create race -> re-read and retry (update path)
                    continue;
                }
            }

            try {
                Map<String, Object> edges = readEdgeMap(record, bin);
                Object existing = edges.get(edgeId);
                if (existing == null) {
                    edges.put(edgeId, value);
                } else {
                    List<Object> merged = mergeEdgeValue(edgeId, value, existing);
                    if (merged == null) {
                        businessLog.record(BusinessLogEntry.failure("upsertEdge", target,
                                Map.of("edgeId", edgeId, "bin", bin, "stale", true)));
                        return WriteOutcome.SUCCESS_STALE;
                    }
                    edges.put(edgeId, merged);
                }
                evictOldest(edges, limit, metrics);
                db.operate(generationPolicy(record.generation), key(vertexId),
                        Operation.put(new Bin(bin, edges)));
                businessLog.record(BusinessLogEntry.success("upsertEdge", target,
                        Map.of("edgeId", edgeId, "bin", bin)));
                return WriteOutcome.SUCCESS;
            } catch (AerospikeException e) {
                last = e; // generation race or transient -> re-read and retry
            }
        }

        Map<String, Object> details = Map.of(
                "edgeId", edgeId,
                "bin", bin,
                "error", last == null ? "unknown" : last.toString());
        errorLog.record(BusinessLogEntry.failure("upsertEdge", target, details));
        businessLog.record(BusinessLogEntry.failure("upsertEdge", target, details));
        return WriteOutcome.FAILED;
    }

    private int limitFor(EdgeSchema edgeSchema, int dir) {
        if (edgeSchema == null) {
            return 0;
        }
        return switch (dir) {
            case EDGE_DIR_OUT -> edgeSchema.maxOutDegree();
            case EDGE_DIR_IN -> edgeSchema.maxInDegree();
            default -> edgeSchema.maxDegree();
        };
    }

    /** A batched read returns a non-null record with {@code null} bins for an absent key. */
    private static boolean isMissing(Record record) {
        return record == null || record.bins == null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readEdgeMap(Record record, String bin) {
        if (record.bins == null) {
            return new LinkedHashMap<>();
        }
        Object existing = record.bins.get(bin);
        if (existing == null) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>((Map<String, Object>) existing);
    }

    /** Reads and decodes the edges of a vertex for an edge schema and direction, newest first. */
    public List<StoredEdgeValue> getEdges(Object vertexId, int edgeSchemaId, int directionCode) {
        return getEdgesFiltered(vertexId, edgeSchemaId, directionCode, EdgeRead.all());
    }

    /** Reads the edges of one vertex, decoding only the requested properties ({@code null} = all). */
    public List<StoredEdgeValue> getEdges(Object vertexId, int edgeSchemaId, int directionCode,
                                          java.util.Set<Integer> onlyProperties) {
        return getEdgesFiltered(vertexId, edgeSchemaId, directionCode, EdgeRead.of(onlyProperties));
    }

    /**
     * Reads the edges of one vertex with conditions and a cap, decoding no more than the read
     * needs: a payment that fails {@code has('status','SUCCESS')} never has its other properties
     * built, and a read that wants the ten newest edges never builds the other forty.
     */
    public List<StoredEdgeValue> getEdgesFiltered(Object vertexId, int edgeSchemaId, int directionCode,
                                                 EdgeRead read) {
        Objects.requireNonNull(read, "read");
        // project the bin: the caller wants one edge class, and reading one bin keeps the
        // metrics honest (an edge read) as well as the traffic small
        Record record = db.get(null, key(vertexId), edgeBin(edgeSchemaId, directionCode));
        return isMissing(record) ? List.of() : decodeEdges(record, edgeSchemaId, directionCode, read);
    }

    /**
     * Counts the edges of one class without decoding them: the bin is a CDT map keyed by edge id,
     * so its size is the answer and the 50 values of a hub's bin are never built.
     */
    public long countEdges(Object vertexId, int edgeSchemaId, int directionCode) {
        return countEdges(vertexId, edgeSchemaId, directionCode, EdgeRead.all());
    }

    /**
     * The headers of a vertex over one edge class: each edge's id, its far endpoint and its timestamps,
     * and none of its properties.
     *
     * <p>A vertex hop walks an edge to reach the vertex at its far end and never looks at the edge itself, so
     * a read that decodes the properties is work thrown away. This is the read for that: the stored value
     * starts with the timestamps and the peer, so the header is all it takes - and no edge is built, which
     * is what {@code storage.edge.decoded} counts.
     */
    public List<EdgeHeader> getEdgeHeaders(Object vertexId, int edgeSchemaId, int directionCode) {
        Record record = db.get(null, key(vertexId), edgeBin(edgeSchemaId, directionCode));
        if (isMissing(record)) {
            return List.of();
        }
        Map<String, Object> raw = readEdgeMap(record, edgeBin(edgeSchemaId, directionCode));
        if (raw.isEmpty()) {
            return List.of();
        }
        List<EdgeHeader> headers = new ArrayList<>(raw.size());
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            headers.add(headerOf(entry.getKey(), entry.getValue()));
        }
        headers.sort(NEWEST_FIRST_HEADERS);
        return headers;
    }

    /** Batched form of {@link #getEdgeHeaders}: one native batch get, one list per vertex. */
    public List<List<EdgeHeader>> getEdgeHeadersBatch(List<Object> vertexIds, int edgeSchemaId,
                                                           int directionCode) {
        Objects.requireNonNull(vertexIds, "vertexIds");
        if (vertexIds.isEmpty()) {
            return List.of();
        }
        Key[] keys = vertexIds.stream().map(this::key).toArray(Key[]::new);
        String bin = edgeBin(edgeSchemaId, directionCode);
        Record[] records = db.get(new BatchPolicy(), keys, bin);
        List<List<EdgeHeader>> out = new ArrayList<>(vertexIds.size());
        for (Record record : records) {
            if (isMissing(record)) {
                out.add(List.of());
                continue;
            }
            Map<String, Object> raw = readEdgeMap(record, bin);
            List<EdgeHeader> headers = new ArrayList<>(raw.size());
            for (Map.Entry<String, Object> entry : raw.entrySet()) {
                headers.add(headerOf(entry.getKey(), entry.getValue()));
            }
            headers.sort(NEWEST_FIRST_HEADERS);
            out.add(headers);
        }
        return out;
    }

    /** One neighbour from a raw bin entry ({@code [lastUpdatedTime, createdTime, peer, properties]}). */
    private static EdgeHeader headerOf(String edgeId, Object raw) {
        if (!(raw instanceof List<?> value) || value.size() < 3) {
            throw new IllegalArgumentException("Malformed edge value for " + edgeId);
        }
        // the stored value is [lastUpdatedTime, createdTime, peer, properties]: the header is the head of
        // it, so nothing has to be deserialised to reach the vertex at the far end
        return new EdgeHeader(edgeId, value.get(2), ((Number) value.get(1)).longValue(),
                ((Number) value.get(0)).longValue());
    }

    /** Neighbours come back newest first, with the edge id as a tie break so the order is stable. */
    private static final Comparator<EdgeHeader> NEWEST_FIRST_HEADERS =
            Comparator.comparingLong(EdgeHeader::lastUpdatedTime).reversed()
                    .thenComparing(EdgeHeader::edgeId);

    /**
     * Counts the edges of one class that satisfy the read's conditions. Without a condition the
     * shape of the bin is the answer; with one, only the properties the condition reads are
     * pulled out of each entry - the edges themselves are never built.
     */
    public long countEdges(Object vertexId, int edgeSchemaId, int directionCode, EdgeRead read) {
        Objects.requireNonNull(read, "read");
        String bin = edgeBin(edgeSchemaId, directionCode);
        if (!read.hasFilter() && !read.hasWindow()) {
            // no condition and no window: the bin's size is the answer, and the server computes it
            // from the map without ever sending the map
            return db.mapSize(key(vertexId), bin);
        }
        Record record = db.get(null, key(vertexId), bin);
        return isMissing(record) ? 0 : countInRecord(record, edgeSchemaId, directionCode, read);
    }

    /**
     * Counts the edges of one class over many vertices with one native batch get.
     *
     * <p>A traversal that counts over a set of vertices has many sources and few edges per source, so
     * counting them one by one is one round trip per vertex - the dominant cost of a two-hop count.
     * The batch reads every record at once and counts each bin from its shape, so the trips become one per
     * batch and the edges are never built either way.
     */
    public long countEdgesBatch(List<Object> vertexIds, int edgeSchemaId, int directionCode, EdgeRead read) {
        Objects.requireNonNull(vertexIds, "vertexIds");
        Objects.requireNonNull(read, "read");
        if (vertexIds.isEmpty()) {
            return 0;
        }
        String bin = edgeBin(edgeSchemaId, directionCode);
        if (!read.hasFilter() && !read.hasWindow()) {
            // one batched server-side count: one round trip, and the bins themselves stay put
            Key[] keys = vertexIds.stream().map(this::key).toArray(Key[]::new);
            return java.util.Arrays.stream(db.mapSizes(keys, bin)).sum();
        }
        Key[] keys = vertexIds.stream().map(this::key).toArray(Key[]::new);
        Record[] records = db.get(new BatchPolicy(), keys, bin);
        long total = 0;
        for (Record record : records) {
            if (!isMissing(record)) {
                total += countInRecord(record, edgeSchemaId, directionCode, read);
            }
        }
        return total;
    }

    /** The count of one record's bin: its shape, or only the entries a condition or window keeps. */
    private long countInRecord(Record record, int edgeSchemaId, int directionCode, EdgeRead read) {
        Object bin = record.bins.get(edgeBin(edgeSchemaId, directionCode));
        if (!(bin instanceof java.util.Map<?, ?> map)) {
            return bin == null ? 0 : decodeEdges(record, edgeSchemaId, directionCode, read).size();
        }
        if (!read.hasFilter() && !read.hasWindow()) {
            return map.size();
        }
        long matches = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (read.hasWindow() && !read.inWindow(rawCreatedTime(entry.getValue()))) {
                continue;
            }
            if (read.hasFilter()
                    && !read.filter().test(rawPropertyValues(entry.getValue(), read.filterProperties()))) {
                continue;
            }
            matches++;
        }
        // a count with a condition or a window builds no edge at all: the bin is only walked
        metrics.increment(Metrics.EDGE_FILTERED, map.size() - matches);
        return matches;
    }

    /**
     * The creation time of the oldest edge still inline in a bin, or {@link Long#MAX_VALUE} when
     * the bin is empty. This is the lower bound of the online tier's time coverage: the inline bin
     * holds the newest edges, so a read older than this does not need to look at it.
     */
    public long earliestCreatedTime(Object vertexId, int edgeSchemaId, int directionCode) {
        Record record = db.get(null, key(vertexId), edgeBin(edgeSchemaId, directionCode));
        if (isMissing(record)) {
            return Long.MAX_VALUE;
        }
        Map<String, Object> raw = readEdgeMap(record, edgeBin(edgeSchemaId, directionCode));
        long earliest = Long.MAX_VALUE;
        for (Object value : raw.values()) {
            earliest = Math.min(earliest, rawCreatedTime(value));
        }
        return earliest;
    }

    /** The creation time of a raw bin entry (the second field of the stored form). */
    private static long rawCreatedTime(Object raw) {
        if (raw instanceof List<?> list && list.size() >= 2 && list.get(1) instanceof Number created) {
            return created.longValue();
        }
        throw new IllegalArgumentException("Malformed edge value: " + raw);
    }

    /**
     * Batch-reads the edges of many vertices with one native Aerospike batch get,
     * preserving the request order; each absent vertex yields an empty list.
     */
    public List<List<StoredEdgeValue>> getEdgesBatch(List<Object> vertexIds, int edgeSchemaId, int directionCode) {
        return getEdgesBatchFiltered(vertexIds, edgeSchemaId, directionCode, EdgeRead.all());
    }

    /** Batch form of {@link #getEdges(Object, int, int, java.util.Set)}. */
    public List<List<StoredEdgeValue>> getEdgesBatch(List<Object> vertexIds, int edgeSchemaId, int directionCode,
                                                     java.util.Set<Integer> onlyProperties) {
        return getEdgesBatchFiltered(vertexIds, edgeSchemaId, directionCode, EdgeRead.of(onlyProperties));
    }

    /** Batch form of {@link #getEdgesFiltered} (conditions and cap per vertex). */
    public List<List<StoredEdgeValue>> getEdgesBatchFiltered(List<Object> vertexIds, int edgeSchemaId,
                                                            int directionCode, EdgeRead read) {
        Objects.requireNonNull(vertexIds, "vertexIds");
        Objects.requireNonNull(read, "read");
        if (vertexIds.isEmpty()) {
            return List.of();
        }
        Key[] keys = vertexIds.stream().map(this::key).toArray(Key[]::new);
        Record[] records = db.get(new BatchPolicy(), keys, edgeBin(edgeSchemaId, directionCode));
        List<List<StoredEdgeValue>> result = new ArrayList<>(vertexIds.size());
        for (int i = 0; i < keys.length; i++) {
            result.add(isMissing(records[i]) ? List.of()
                    : decodeEdges(records[i], edgeSchemaId, directionCode, read));
        }
        return result;
    }

    /**
     * Turns the raw entries of a bin into edges: conditions are tested on the raw properties
     * first (so a discarded edge is never built), then the wanted edges are decoded and sorted
     * newest first. A cap keeps the newest {@code maxResults} entries, which is the same list the
     * uncapped read would have been cut down to.
     */
    private List<StoredEdgeValue> decodeEdges(Record record, int edgeSchemaId, int directionCode,
                                              EdgeRead read) {
        Map<String, Object> raw = readEdgeMap(record, edgeBin(edgeSchemaId, directionCode));
        if (raw.isEmpty()) {
            return List.of();
        }
        List<Map.Entry<String, Object>> kept = new ArrayList<>(raw.entrySet());
        if (read.hasWindow()) {
            // the window is checked on the raw entry (the creation time is its second field), so an
            // edge outside it is dropped before any condition runs and before anything is built
            List<Map.Entry<String, Object>> inWindow = new ArrayList<>(kept.size());
            for (Map.Entry<String, Object> entry : kept) {
                if (read.inWindow(rawCreatedTime(entry.getValue()))) {
                    inWindow.add(entry);
                }
            }
            kept = inWindow;
        }
        if (read.hasFilter()) {
            List<Map.Entry<String, Object>> matched = new ArrayList<>(kept.size());
            for (Map.Entry<String, Object> entry : kept) {
                if (read.filter().test(rawPropertyValues(entry.getValue(), read.filterProperties()))) {
                    matched.add(entry);
                }
            }
            kept = matched;
        }
        if (read.maxResults() > 0 && kept.size() > read.maxResults()) {
            kept = newest(kept, read.maxResults());
        }
        Set<Integer> only = read.decodedProperties();
        List<StoredEdgeValue> edges = kept.stream()
                .map(e -> deserializeEdgeValue(e.getKey(), e.getValue(), only))
                .sorted(NEWEST_FIRST)
                .toList();
        // what the read cost: the edges it built (a count builds none), next to what the bin holds
        metrics.increment(Metrics.EDGE_DECODED, edges.size());
        metrics.increment(Metrics.EDGE_FILTERED, raw.size() - edges.size());
        return edges;
    }

    /**
     * The newest {@code limit} entries of a bin, by the lastUpdatedTime at the head of each value.
     *
     * <p>A bounded selection, not a sort of the whole bin: a hub's bin can hold many thousands of
     * edges while a UI expansion asks for a couple hundred, and ordering all of them to keep a
     * handful is the one part of a capped read that would still grow with the bin.
     */
    private static List<Map.Entry<String, Object>> newest(List<Map.Entry<String, Object>> entries, int limit) {
        // the queue holds the newest `limit` entries seen so far, its head being the least new
        // of them, so one comparison per entry is enough to keep the answer
        java.util.PriorityQueue<Map.Entry<String, Object>> kept =
                new java.util.PriorityQueue<>(Math.max(limit, 1), (a, b) -> -compareNewestFirst(a, b));
        for (Map.Entry<String, Object> entry : entries) {
            kept.add(entry);
            if (kept.size() > limit) {
                kept.poll();
            }
        }
        List<Map.Entry<String, Object>> out = new ArrayList<>(kept);
        out.sort(AerospikeEdgeStore::compareNewestFirst);
        return out;
    }

    /** Newest first, with the edge id as a tie break so the order is stable. */
    private static int compareNewestFirst(Map.Entry<String, Object> a, Map.Entry<String, Object> b) {
        int byTime = Long.compare(lastUpdatedTimeOf(b.getValue()), lastUpdatedTimeOf(a.getValue()));
        return byTime != 0 ? byTime : a.getKey().compareTo(b.getKey());
    }

    /**
     * The values of the given properties inside one raw edge value, without building an edge.
     * Used by the condition test: {@code [lastUpdatedTime, createdTime, otherVertexId, props]}.
     */
    static Map<Integer, Object> rawPropertyValues(Object raw, Set<Integer> propertyIds) {
        Map<Integer, Object> values = new LinkedHashMap<>();
        if (!(raw instanceof List<?> list) || list.size() < 4 || !(list.get(3) instanceof Map<?, ?> props)) {
            return values;
        }
        for (Map.Entry<?, ?> entry : props.entrySet()) {
            int index = toIndex(entry.getKey());
            if (propertyIds != null && !propertyIds.isEmpty() && !propertyIds.contains(index)) {
                continue;
            }
            Object value = entry.getValue();
            values.put(index, value instanceof List<?> property && !property.isEmpty()
                    ? property.get(0) : value);
        }
        return values;
    }

    /** Edges come back newest first, with the edge id as a tie break so the order is stable. */
    private static final Comparator<StoredEdgeValue> NEWEST_FIRST =
            Comparator.comparingLong(StoredEdgeValue::lastUpdatedTime).reversed()
                    .thenComparing(StoredEdgeValue::edgeId);

    /** Reads the raw edge map of a vertex for an edge schema and direction. */
    public Map<String, Object> getEdgeMap(Object vertexId, int edgeSchemaId, int directionCode) {
        Record record = db.get(null, key(vertexId));
        if (record == null) {
            return Map.of();
        }
        return readEdgeMap(record, edgeBin(edgeSchemaId, directionCode));
    }

    /**
     * Removes the edge bins of the given edge classes from every listed vertex
     * (all three directions), i.e. drops the attached edges without touching the
     * vertices or any other edge class.
     *
     * <p>Intended for maintenance: rebuilding an index starts by clearing its layer
     * bins so no stale link survives. One {@code operate} call per vertex removes
     * every bin at once; a bin that is not present is a no-op.
     *
     * @param edgeSchemas edge classes to clear
     * @param vertexIds   vertices whose bins are cleared
     * @return the number of vertices the operation was issued for
     */
    public int clearEdgeClasses(List<EdgeSchema> edgeSchemas, List<Object> vertexIds) {
        Objects.requireNonNull(edgeSchemas, "edgeSchemas");
        Objects.requireNonNull(vertexIds, "vertexIds");
        if (edgeSchemas.isEmpty() || vertexIds.isEmpty()) {
            return 0;
        }
        // an empty CDT map is exactly what a vertex without edges on that layer looks
        // like, and writing it needs no read-modify-write
        List<Bin> bins = new ArrayList<>(edgeSchemas.size() * 3);
        for (EdgeSchema edgeSchema : edgeSchemas) {
            for (int dir : new int[] {EDGE_DIR_BOTH, EDGE_DIR_IN, EDGE_DIR_OUT}) {
                bins.add(new Bin(edgeBin(edgeSchema.id(), dir), new LinkedHashMap<String, Object>()));
            }
        }
        Bin[] emptyBins = bins.toArray(new Bin[0]);
        WritePolicy policy = new WritePolicy();
        policy.recordExistsAction = RecordExistsAction.UPDATE_ONLY;
        int cleared = 0;
        for (Object vertexId : vertexIds.stream().distinct().toList()) {
            try {
                db.put(policy, key(vertexId), emptyBins);
                cleared++;
            } catch (AerospikeException e) {
                errorLog.record(BusinessLogEntry.failure("clearEdgeClasses", String.valueOf(vertexId),
                        Map.of("edgeClasses", edgeSchemas.stream().map(EdgeSchema::name).toList(),
                                "error", e.toString())));
            }
        }
        businessLog.record(BusinessLogEntry.success("clearEdgeClasses", "batch:" + vertexIds.size(),
                Map.of("vertices", cleared, "edgeClasses", edgeSchemas.size(), "bins", edgeSchemas.size() * 3)));
        return cleared;
    }

    /** Hard-deletes an edge: removes the in-edge and out-edge from both endpoints. */
    public WriteOutcome deleteEdge(Object edgeId, EdgeSchema edgeSchema, Object source, Object destination) {
        String id = String.valueOf(edgeId);
        EdgeDirection direction = edgeSchema.direction();
        int edgeSchemaId = edgeSchema.id();
        int sourceDir = sourceDirection(direction);
        int destinationDir = destinationDirection(direction);
        WriteOutcome a = removeFrom(source, edgeSchemaId, sourceDir, id);
        WriteOutcome b = removeFrom(destination, edgeSchemaId, destinationDir, id);
        return (a == WriteOutcome.FAILED || b == WriteOutcome.FAILED)
                ? WriteOutcome.FAILED : WriteOutcome.SUCCESS;
    }

    /** Batch hard-deletes edges, grouped by vertex and edge bin. */
    public List<WriteOutcome> deleteEdges(List<EdgeDelete> edges) {
        Objects.requireNonNull(edges, "edges");
        if (edges.isEmpty()) {
            return List.of();
        }
        Map<Object, Map<String, List<String>>> byVertex = new LinkedHashMap<>();
        for (EdgeDelete edge : edges) {
            String id = String.valueOf(edge.edgeId());
            EdgeSchema edgeSchema = edge.edgeSchema();
            addRemoval(byVertex, edge.source(),
                    edgeBin(edgeSchema.id(), sourceDirection(edgeSchema.direction())), id);
            addRemoval(byVertex, edge.destination(),
                    edgeBin(edgeSchema.id(), destinationDirection(edgeSchema.direction())), id);
        }
        List<Object> ids = new ArrayList<>(byVertex.keySet());
        Key[] keys = ids.stream().map(this::key).toArray(Key[]::new);
        List<String> targets = ids.stream().map(String::valueOf).toList();
        return batchDelete("deleteEdges", keys, targets, (i, record) -> {
            List<Operation> ops = new ArrayList<>();
            for (Map.Entry<String, List<String>> entry : byVertex.get(ids.get(i)).entrySet()) {
                String bin = entry.getKey();
                Map<String, Object> map = readEdgeMap(record, bin);
                entry.getValue().forEach(map::remove);
                if (map.isEmpty()) {
                    ops.add(Operation.put(Bin.asNull(bin)));
                } else {
                    ops.add(Operation.put(new Bin(bin, map)));
                }
            }
            return ops.toArray(new Operation[0]);
        });
    }

    private static void addRemoval(Map<Object, Map<String, List<String>>> byVertex,
                                   Object vertexId, String bin, String edgeId) {
        byVertex.computeIfAbsent(vertexId, k -> new LinkedHashMap<>())
                .computeIfAbsent(bin, k -> new ArrayList<>())
                .add(edgeId);
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
            Record[] records = db.get(new BatchPolicy(), keys);
            for (int i = 0; i < keys.length; i++) {
                Record record = records[i];
                if (isMissing(record)) {
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
                db.operate(new BatchPolicy(), writes);
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

    private WriteOutcome removeFrom(Object vertexId, int edgeSchemaId, int dir, String edgeId) {
        String bin = edgeBin(edgeSchemaId, dir);
        String target = vertexId + "@" + bin;
        Exception last = null;
        for (int attempt = 1; attempt <= maxWriteAttempts; attempt++) {
            Record record;
            try {
                record = db.get(null, key(vertexId));
            } catch (AerospikeException e) {
                last = e;
                continue;
            }
            if (record == null) {
                return WriteOutcome.SUCCESS; // nothing to remove
            }
            try {
                Map<String, Object> edges = readEdgeMap(record, bin);
                if (edges.remove(edgeId) != null) {
                    db.operate(generationPolicy(record.generation), key(vertexId),
                            Operation.put(new Bin(bin, edges)));
                }
                businessLog.record(BusinessLogEntry.success("removeEdge", target, Map.of("edgeId", edgeId)));
                return WriteOutcome.SUCCESS;
            } catch (AerospikeException e) {
                last = e;
            }
        }
        Map<String, Object> details = Map.of("edgeId", edgeId, "error", last == null ? "unknown" : last.toString());
        errorLog.record(BusinessLogEntry.failure("removeEdge", target, details));
        businessLog.record(BusinessLogEntry.failure("removeEdge", target, details));
        return WriteOutcome.FAILED;
    }

    // --- AdjacentEdgeStore ---

    @Override
    public void addEdge(StoredEdge edge) {
        Objects.requireNonNull(edge, "edge");
        EdgeSchema edgeSchema = resolveEdgeSchema(edge.edgeSchemaId(), edge.direction());
        Map<Integer, EdgePropertyValue> properties = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : edge.columns().entrySet()) {
            int index = resolveIndex(edgeSchema, entry.getKey());
            if (index >= 0) {
                properties.put(index, new EdgePropertyValue(entry.getValue(),
                        edge.lastUpdatedTime(), edge.lastUpdatedTime()));
            }
        }
        upsertEdge(new EdgeUpsert(edge.edgeId(), edgeSchema, edge.source(), edge.destination(),
                properties, edge.lastUpdatedTime(), edge.lastUpdatedTime()));
    }

    @Override
    public List<StoredEdge> outgoingEdges(Object vertexId, int edgeSchemaId) {
        int dir = directionBin(edgeSchemaId, true);
        return getEdges(vertexId, edgeSchemaId, dir).stream()
                .map(v -> toStoredEdge(v, edgeSchemaId, vertexId, true))
                .toList();
    }

    @Override
    public List<StoredEdge> incomingEdges(Object vertexId, int edgeSchemaId) {
        int dir = directionBin(edgeSchemaId, false);
        return getEdges(vertexId, edgeSchemaId, dir).stream()
                .map(v -> toStoredEdge(v, edgeSchemaId, vertexId, false))
                .toList();
    }

    private int directionBin(int edgeSchemaId, boolean outgoing) {
        EdgeSchema edgeSchema = schema == null ? null : schema.edgeSchema(edgeSchemaId);
        EdgeDirection direction = edgeSchema == null ? EdgeDirection.DIRECTED : edgeSchema.direction();
        return outgoing ? sourceDirection(direction) : destinationDirection(direction);
    }

    @Override
    public void removeEdge(Object source, Object destination, int edgeSchemaId, Object edgeId) {
        EdgeSchema edgeSchema = resolveEdgeSchema(edgeSchemaId, EdgeDirection.DIRECTED);
        deleteEdge(edgeId, edgeSchema, source, destination);
    }

    private EdgeSchema resolveEdgeSchema(int edgeSchemaId, EdgeDirection direction) {
        if (schema != null) {
            EdgeSchema edgeSchema = schema.edgeSchema(edgeSchemaId);
            if (edgeSchema != null) {
                return edgeSchema;
            }
        }
        return new EdgeSchema(edgeSchemaId, "edge-" + edgeSchemaId, null,
                new EdgePropertySchema(1, "source", null, PropertyType.ScalarType.LONG),
                new EdgePropertySchema(2, "target", null, PropertyType.ScalarType.LONG),
                direction);
    }

    private int resolveIndex(EdgeSchema edgeSchema, String key) {
        try {
            return Integer.parseInt(key);
        } catch (NumberFormatException ignored) {
            // fall through to schema lookup
        }
        if (edgeSchema != null) {
            EdgePropertySchema property = edgeSchema.property(key);
            if (property != null) {
                return property.id();
            }
        }
        return -1;
    }

    private StoredEdge toStoredEdge(StoredEdgeValue value, int edgeSchemaId, Object vertexId, boolean outgoing) {
        EdgeSchema edgeSchema = schema == null ? null : schema.edgeSchema(edgeSchemaId);
        EdgeDirection direction = edgeSchema == null ? EdgeDirection.DIRECTED : edgeSchema.direction();
        Map<String, Object> columns = new LinkedHashMap<>();
        for (Map.Entry<Integer, EdgePropertyValue> entry : value.properties().entrySet()) {
            columns.put(resolveName(edgeSchema, entry.getKey()), entry.getValue().value());
        }
        Object source = outgoing ? vertexId : value.otherVertexId();
        Object destination = outgoing ? value.otherVertexId() : vertexId;
        return new StoredEdge(value.edgeId(), edgeSchemaId, source, destination,
                direction, columns, value.lastUpdatedTime());
    }

    private String resolveName(EdgeSchema edgeSchema, int index) {
        if (edgeSchema != null) {
            EdgePropertySchema property = edgeSchema.property(index);
            if (property != null) {
                return property.name();
            }
        }
        return String.valueOf(index);
    }

    private Key key(Object vertexId) {
        return AerospikeKeys.of(namespace, vertexSet, vertexId);
    }
    /**
     * Points this store's metrics at a graph's counters. Called while wiring the
     * application (and by tests); until then the store records nothing.
     */
    public AerospikeEdgeStore metrics(MetricsSink sink) {
        metrics.target(sink);
        return this;
    }

}
