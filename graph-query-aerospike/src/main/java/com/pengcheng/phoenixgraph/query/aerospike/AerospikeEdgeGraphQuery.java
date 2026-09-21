package com.pengcheng.phoenixgraph.query.aerospike;

import com.pengcheng.phoenixgraph.query.QueryException;
import com.pengcheng.phoenixgraph.query.storage.EdgeQueryResult;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
import com.pengcheng.phoenixgraph.query.storage.GraphQuery;
import com.pengcheng.phoenixgraph.query.storage.VertexQueryResult;
import com.pengcheng.phoenixgraph.schema.EdgeDirection;
import com.pengcheng.phoenixgraph.schema.EdgePropertySchema;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.storage.TimeRange;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeChunkStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeNearlineStore;
import com.pengcheng.phoenixgraph.storage.aerospike.EdgePropertyValue;
import com.pengcheng.phoenixgraph.storage.aerospike.EdgeTiering;
import com.pengcheng.phoenixgraph.storage.aerospike.StoredEdgeValue;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@link GraphQuery} over an Aerospike graph whose big vertices live in the aggregated edge
 * tables ({@code Edge_Out} / {@code Edge_In}) while everything else stays in the vertex record.
 *
 * <p>A read of a vertex that has a hub marker needs a creation-time window (there may be a
 * hundred million edges behind it, so "all of them" is not a question storage can answer); a
 * read of a vertex without one is the plain inline read. Either way the answer is the <b>merge</b>
 * of the two places: an edge written by the bulk import lives in the tables, but if it was updated
 * later that update went through the ordinary write path into the vertex record, and the newer of
 * the two copies wins (compared by {@code lastUpdatedTime}, which an update always lifts - the
 * same rule the inline store applies per bin).
 */
public class AerospikeEdgeGraphQuery implements GraphQuery {

    private final AerospikeGraphQuery inline;
    private final AerospikeEdgeChunkStore chunks;
    private final GraphSchema schema;
    /** The near-line tier; {@code null} keeps the legacy merge (inline + cold, always both). */
    private final AerospikeNearlineStore nearline;
    private final Clock clock;
    private final long nearlineWindowMillis;

    /**
     * The original wiring: inline and cold are always both read and merged. Kept so the scheme as
     * it was before the tiers keeps working unchanged (and its tests stay green).
     */
    public AerospikeEdgeGraphQuery(AerospikeGraphQuery inline, AerospikeEdgeChunkStore chunks,
                                   GraphSchema schema) {
        this(inline, chunks, schema, null, Clock.systemUTC(),
                EdgeTiering.DEFAULT_NEARLINE_WINDOW_MILLIS);
    }

    /**
     * The tiered wiring: a read consults the online (inline), near-line and cold tiers according
     * to the creation-time spans its window overlaps, and merges what it finds by edge id (the
     * newest copy wins). A {@code null} near-line store degrades to the legacy merge.
     */
    public AerospikeEdgeGraphQuery(AerospikeGraphQuery inline, AerospikeEdgeChunkStore chunks,
                                   GraphSchema schema, AerospikeNearlineStore nearline,
                                   Clock clock, long nearlineWindowMillis) {
        this.inline = Objects.requireNonNull(inline, "inline");
        this.chunks = Objects.requireNonNull(chunks, "chunks");
        this.schema = Objects.requireNonNull(schema, "schema");
        this.nearline = nearline;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.nearlineWindowMillis = nearlineWindowMillis;
    }

    /** Whether the tiered read (near-line and time-routed tiers) is enabled. */
    public boolean tiered() {
        return nearline != null;
    }

    /** The near-line tier, or {@code null} when the legacy merge is in effect. */
    public AerospikeNearlineStore nearline() {
        return nearline;
    }

    /** The inline read path (vertices, and edges of vertices without an aggregated table). */
    public AerospikeGraphQuery inline() {
        return inline;
    }

    /** The aggregated edge tables. */
    public AerospikeEdgeChunkStore chunks() {
        return chunks;
    }

    // --- vertices: the aggregated tables do not change them ---

    @Override
    public VertexQueryResult readVertex(Object vertexId, String vertexName, List<String> propertyNames) {
        return inline.readVertex(vertexId, vertexName, propertyNames);
    }

    @Override
    public List<VertexQueryResult> readVertices(String vertexName, List<Object> vertexIds,
                                                List<String> propertyNames) {
        return inline.readVertices(vertexName, vertexIds, propertyNames);
    }

    // --- edges ---

    @Override
    public boolean supportsEdgeTimeRange() {
        return true;
    }

    @Override
    public List<EdgeQueryResult> readEdges(Object vertexId, String vertexName, String edgeName,
                                           EdgeSide direction, List<String> propertyNames) {
        EdgeSchema edgeSchema = requireEdge(edgeName);
        for (EdgeSide side : sides(edgeSchema, direction)) {
            if (isHub(vertexId, edgeSchema, side)) {
                throw new QueryException(hubHint(vertexId, edgeSchema, side));
            }
            if (tiered() && nearline.hasData(vertexId, edgeSchema.id(),
                    directionCode(edgeSchema, side), clock.millis())) {
                throw new QueryException(hubHint(vertexId, edgeSchema, side));
            }
        }
        return inline.readEdges(vertexId, vertexName, edgeName, direction, propertyNames);
    }

    @Override
    public List<EdgeQueryResult> readEdgesWithin(Object vertexId, String vertexName, String edgeName,
                                                 EdgeSide direction, TimeRange range,
                                                 List<String> propertyNames) {
        return readEdgesWithin(vertexId, vertexName, edgeName, direction, range, propertyNames, 0);
    }

    @Override
    public List<EdgeQueryResult> readEdgesWithin(Object vertexId, String vertexName, String edgeName,
                                                 EdgeSide direction, TimeRange range,
                                                 List<String> propertyNames, int maxResults) {
        Objects.requireNonNull(vertexId, "vertexId");
        Objects.requireNonNull(direction, "direction");
        EdgeSchema edgeSchema = requireEdge(edgeName);
        Map<Integer, String> requested = requestedProperties(edgeSchema, propertyNames);
        TimeRange window = range == null ? TimeRange.ALL : range;

        Map<String, EdgeQueryResult> merged = new LinkedHashMap<>();
        long now = clock.millis();
        long nearlineFrom = now - nearlineWindowMillis;
        for (EdgeSide side : sides(edgeSchema, direction)) {
            int dir = directionCode(edgeSchema, side);

            // The tiers whose time coverage the window overlaps. The online tier's lower bound is
            // the oldest edge still inline; a window entirely in the past never pays that read.
            long onlineEarliest = Long.MAX_VALUE;
            if (tiered() && window.toTime() > nearlineFrom) {
                onlineEarliest = inline.earliestCreatedTime(vertexId, edgeSchema.id(), dir);
            }
            EnumSet<EdgeTiering.Tier> tiers = tiered()
                    ? EdgeTiering.classify(window, now, nearlineWindowMillis, onlineEarliest)
                    : EnumSet.of(EdgeTiering.Tier.ONLINE, EdgeTiering.Tier.COLD);

            // 1. online: the edges (or updates) still inline in the vertex record
            if (tiers.contains(EdgeTiering.Tier.ONLINE)) {
                for (EdgeQueryResult edge : inline.readEdgesWithin(vertexId, vertexName, edgeName,
                        side, window, List.copyOf(requested.values()))) {
                    merge(merged, edge);
                }
            }
            // 2. near-line: the last 24 hours, in the TTL'd near-line KVs
            if (tiers.contains(EdgeTiering.Tier.NEARLINE)) {
                for (StoredEdgeValue edge : nearline.readWindow(vertexId, edgeSchema.id(), dir,
                        window)) {
                    merge(merged, toResult(edgeName, edge, side, requested));
                }
            }
            // 3. cold: the aggregated tables, the chunks whose span overlaps the window
            if (tiers.contains(EdgeTiering.Tier.COLD)) {
                for (StoredEdgeValue edge : chunks.readWindow(edgeSchema, dir, vertexId, window,
                        maxResults)) {
                    merge(merged, toResult(edgeName, edge, side, requested));
                }
            }
        }

        List<EdgeQueryResult> results = new ArrayList<>(merged.size());
        for (EdgeQueryResult edge : merged.values()) {
            if (window.isUnbounded() || window.contains(edge.createdTime())) {
                results.add(edge);
            }
        }
        // a window read answers in creation-time order
        results.sort(Comparator.comparingLong(EdgeQueryResult::createdTime)
                .thenComparing(EdgeQueryResult::edgeId));
        if (maxResults > 0 && results.size() > maxResults) {
            return List.copyOf(results.subList(0, maxResults));
        }
        return results;
    }

    /** Keeps the newer of two copies of the same edge (an update always lifts lastUpdatedTime). */
    private static void merge(Map<String, EdgeQueryResult> merged, EdgeQueryResult edge) {
        EdgeQueryResult existing = merged.get(edge.edgeId());
        if (existing == null || edge.lastUpdatedTime() >= existing.lastUpdatedTime()) {
            merged.put(edge.edgeId(), edge);
        }
    }

    @Override
    public List<List<EdgeQueryResult>> readEdgesBatch(String edgeName, EdgeSide direction,
                                                      List<Object> vertexIds, List<String> propertyNames) {
        Objects.requireNonNull(vertexIds, "vertexIds");
        if (vertexIds.isEmpty()) {
            return List.of();
        }
        List<List<EdgeQueryResult>> out = new ArrayList<>(vertexIds.size());
        for (Object vertexId : vertexIds) {
            out.add(readEdgesWithin(vertexId, null, edgeName, direction, TimeRange.ALL, propertyNames));
        }
        return out;
    }

    /** Batched form of {@link #readEdgesWithin}. */
    @Override
    public List<List<EdgeQueryResult>> readEdgesBatchWithin(String edgeName, EdgeSide direction,
                                                            TimeRange range, List<Object> vertexIds,
                                                            List<String> propertyNames, int maxResults) {
        Objects.requireNonNull(vertexIds, "vertexIds");
        if (vertexIds.isEmpty()) {
            return List.of();
        }
        List<List<EdgeQueryResult>> out = new ArrayList<>(vertexIds.size());
        for (Object vertexId : vertexIds) {
            out.add(readEdgesWithin(vertexId, null, edgeName, direction, range, propertyNames, maxResults));
        }
        return out;
    }

    @Override
    public List<List<EdgeQueryResult>> readAllEdgesBatch(List<Object> vertexIds, EdgeSide direction,
                                                         List<String> propertyNames) {
        return readAllEdgesBatchWithin(vertexIds, direction, TimeRange.ALL, propertyNames);
    }

    /** Batched form of {@link #readEdgesWithin} over every edge class. */
    @Override
    public List<List<EdgeQueryResult>> readAllEdgesBatchWithin(List<Object> vertexIds, EdgeSide direction,
                                                               TimeRange range, List<String> propertyNames) {
        return readAllEdgesBatchWithin(vertexIds, direction, range, propertyNames, 0);
    }

    /**
     * Every edge class inside a window, with the per-class cap a {@code limit} of the query
     * became: each class stops reading once it has that many edges.
     */
    @Override
    public List<List<EdgeQueryResult>> readAllEdgesBatchWithin(List<Object> vertexIds, EdgeSide direction,
                                                               TimeRange range, List<String> propertyNames,
                                                               int maxResults) {
        Objects.requireNonNull(vertexIds, "vertexIds");
        if (vertexIds.isEmpty()) {
            return List.of();
        }
        List<List<EdgeQueryResult>> perVertex = new ArrayList<>(vertexIds.size());
        for (int i = 0; i < vertexIds.size(); i++) {
            perVertex.add(new ArrayList<>());
        }
        for (EdgeSchema edgeSchema : schema.edgeSchemas()) {
            List<List<EdgeQueryResult>> byEdge =
                    readEdgesBatchWithin(edgeSchema.name(), direction, range, vertexIds, propertyNames,
                            maxResults);
            for (int i = 0; i < vertexIds.size(); i++) {
                perVertex.get(i).addAll(byEdge.get(i));
            }
        }
        return perVertex;
    }

    /** Whether a vertex has edges of this class and direction in the aggregated tables. */
    public boolean isHub(Object vertexId, EdgeSchema edgeSchema, EdgeSide side) {
        AerospikeEdgeChunkStore.HubInfo hub =
                chunks.hub(edgeSchema, directionCode(edgeSchema, side), vertexId);
        return hub != null && !hub.isEmpty();
    }

    private String hubHint(Object vertexId, EdgeSchema edgeSchema, EdgeSide side) {
        AerospikeEdgeChunkStore.HubInfo hub =
                chunks.hub(edgeSchema, directionCode(edgeSchema, side), vertexId);
        long count = hub == null ? 0 : hub.edgeCount();
        return "Vertex '" + vertexId + "' has " + count + " edges of class " + edgeSchema.name()
                + " in the aggregated " + (side == EdgeSide.IN ? "Edge_In" : "Edge_Out")
                + " table; reading them needs a creation-time window, for example"
                + " createdBetween('2025-01-01', '2025-02-01') before the edge step";
    }

    private EdgeSchema requireEdge(String edgeName) {
        EdgeSchema edgeSchema = schema.edgeSchema(edgeName);
        if (edgeSchema == null) {
            throw new QueryException("Unknown edge schema: " + edgeName);
        }
        return edgeSchema;
    }

    /** The sides a request reads: a directed edge reads one table per side. */
    static List<EdgeSide> sides(EdgeSchema edgeSchema, EdgeSide requested) {
        if (edgeSchema.direction() != EdgeDirection.DIRECTED) {
            return List.of(EdgeSide.BOTH);
        }
        return switch (requested) {
            case OUT -> List.of(EdgeSide.OUT);
            case IN -> List.of(EdgeSide.IN);
            case BOTH -> List.of(EdgeSide.OUT, EdgeSide.IN);
        };
    }

    private static int directionCode(EdgeSchema edgeSchema, EdgeSide side) {
        if (edgeSchema.direction() != EdgeDirection.DIRECTED) {
            return AerospikeEdgeStore.EDGE_DIR_BOTH;
        }
        return side == EdgeSide.IN ? AerospikeEdgeStore.EDGE_DIR_IN : AerospikeEdgeStore.EDGE_DIR_OUT;
    }

    private static Map<Integer, String> requestedProperties(EdgeSchema edgeSchema, List<String> propertyNames) {
        List<String> names = propertyNames == null ? List.of() : propertyNames;
        Map<Integer, String> requested = new LinkedHashMap<>();
        if (names.isEmpty()) {
            for (EdgePropertySchema property : edgeSchema.properties()) {
                requested.put(property.id(), property.name());
            }
            return requested;
        }
        for (String name : names) {
            EdgePropertySchema property = edgeSchema.property(name);
            if (property == null) {
                throw new QueryException("Unknown property '" + name + "' in edge schema '"
                        + edgeSchema.name() + "'");
            }
            requested.put(property.id(), property.name());
        }
        return requested;
    }

    private static EdgeQueryResult toResult(String edgeName, StoredEdgeValue edge, EdgeSide side,
                                            Map<Integer, String> requested) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> entry : requested.entrySet()) {
            EdgePropertyValue value = edge.properties().get(entry.getKey());
            if (value != null) {
                properties.put(entry.getValue(), value.value());
            }
        }
        return new EdgeQueryResult(edgeName, edge.edgeId(), side, edge.otherVertexId(), properties,
                edge.createdTime(), edge.lastUpdatedTime());
    }
}
