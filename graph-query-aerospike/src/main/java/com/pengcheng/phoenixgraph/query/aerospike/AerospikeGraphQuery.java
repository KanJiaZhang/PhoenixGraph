package com.pengcheng.phoenixgraph.query.aerospike;

import com.pengcheng.phoenixgraph.query.QueryException;
import com.pengcheng.phoenixgraph.query.storage.EdgePropertyFilter;
import com.pengcheng.phoenixgraph.query.storage.EdgeQueryResult;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
import com.pengcheng.phoenixgraph.query.storage.NeighbourRef;
import com.pengcheng.phoenixgraph.query.storage.GraphQuery;
import com.pengcheng.phoenixgraph.query.storage.VertexQueryResult;
import com.pengcheng.phoenixgraph.schema.EdgeDirection;
import com.pengcheng.phoenixgraph.schema.EdgePropertySchema;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.VertexPropertySchema;
import com.pengcheng.phoenixgraph.schema.VertexSchema;
import com.pengcheng.phoenixgraph.schema.geo.GeoHit;
import com.pengcheng.phoenixgraph.schema.geo.GeoSearch;
import com.pengcheng.phoenixgraph.storage.BusinessLogEntry;
import com.pengcheng.phoenixgraph.storage.EdgeHeader;
import com.pengcheng.phoenixgraph.storage.TimeRange;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeGeoIndex;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import com.pengcheng.phoenixgraph.storage.aerospike.EdgePropertyValue;
import com.pengcheng.phoenixgraph.storage.aerospike.StoredEdgeValue;
import com.pengcheng.phoenixgraph.storage.aerospike.StoredVertex;
import com.pengcheng.phoenixgraph.storage.aerospike.VertexPropertyValue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Aerospike-backed {@link GraphQuery} implementation.
 *
 * <p>Reads vertices from {@link AerospikeVertexStore} and denormalised edges
 * from {@link AerospikeEdgeStore}, projecting the requested properties by name.
 * Property names are resolved against the {@link GraphSchema}: a vertex is
 * located by {@code vertexId} (its record key) and its schema is identified by
 * {@code vertexName}; an edge is located on the vertex record by
 * {@code vertexId} and its schema is identified by {@code edgeName}.
 *
 * <p>An empty (or {@code null}) property list selects all properties declared by
 * the schema. Meta timestamps (createdTime / lastUpdatedTime) are always
 * returned. A property that is declared but not present in storage is omitted
 * from the result.
 */
public class AerospikeGraphQuery implements GraphQuery {

    private final AerospikeVertexStore vertexStore;
    private final AerospikeEdgeStore edgeStore;
    private final GraphSchema schema;
    private final com.pengcheng.phoenixgraph.storage.BusinessLog businessLog;

    public AerospikeGraphQuery(AerospikeVertexStore vertexStore, AerospikeEdgeStore edgeStore, GraphSchema schema) {
        this(vertexStore, edgeStore, schema, null);
    }

    public AerospikeGraphQuery(AerospikeVertexStore vertexStore, AerospikeEdgeStore edgeStore, GraphSchema schema,
                               com.pengcheng.phoenixgraph.storage.BusinessLog businessLog) {
        this.vertexStore = Objects.requireNonNull(vertexStore, "vertexStore");
        this.edgeStore = Objects.requireNonNull(edgeStore, "edgeStore");
        this.schema = Objects.requireNonNull(schema, "schema");
        this.businessLog = businessLog == null ? entry -> { } : businessLog;
    }

    // --- vertex ---

    @Override
    public VertexQueryResult readVertex(Object vertexId, String vertexName, List<String> propertyNames) {
        Objects.requireNonNull(vertexId, "vertexId");
        VertexSchema vertexSchema = resolveVertexSchema(vertexName);
        List<String> names = propertyNames == null ? List.of() : propertyNames;
        Map<Integer, String> requested = requestedVertexProperties(vertexSchema, names);

        StoredVertex vertex = vertexStore.readVertex(vertexId, new ArrayList<>(requested.keySet()));
        if (vertex == null) {
            return null;
        }
        return toVertexResult(vertexName, vertex, requested);
    }

    @Override
    public List<VertexQueryResult> readVertices(String vertexName, List<Object> vertexIds,
                                                List<String> propertyNames) {
        Objects.requireNonNull(vertexIds, "vertexIds");
        if (vertexIds.isEmpty()) {
            return List.of();
        }
        VertexSchema vertexSchema = resolveVertexSchema(vertexName);
        List<String> names = propertyNames == null ? List.of() : propertyNames;
        Map<Integer, String> requested = requestedVertexProperties(vertexSchema, names);

        List<StoredVertex> stored = vertexStore.readVertices(vertexIds, new ArrayList<>(requested.keySet()));
        List<VertexQueryResult> results = new ArrayList<>(vertexIds.size());
        for (StoredVertex vertex : stored) {
            results.add(vertex == null ? null : toVertexResult(vertexName, vertex, requested));
        }
        return results;
    }

    private Map<Integer, String> requestedVertexProperties(VertexSchema vertexSchema, List<String> names) {
        Map<Integer, String> requested = new LinkedHashMap<>();
        if (names.isEmpty()) {
            for (VertexPropertySchema propertySchema : vertexSchema.properties()) {
                requested.put(propertySchema.id(), propertySchema.name());
            }
        } else {
            for (String name : names) {
                VertexPropertySchema propertySchema = requireVertexProperty(vertexSchema, name);
                requested.put(propertySchema.id(), propertySchema.name());
            }
        }
        return requested;
    }

    private VertexQueryResult toVertexResult(String vertexName, StoredVertex vertex,
                                             Map<Integer, String> requested) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> entry : requested.entrySet()) {
            VertexPropertyValue value = vertex.properties().get(entry.getKey());
            if (value != null) {
                properties.put(entry.getValue(), value.value());
            }
        }
        return new VertexQueryResult(vertexName, vertex.vertexId(), properties,
                vertex.createdTime(), vertex.lastUpdatedTime());
    }

    // --- edges ---

    @Override
    public boolean supportsEdgeReadPushdown() {
        // conditions and caps are evaluated inside the decode loop of the edge bins
        return true;
    }

    @Override
    public long countEdges(Object vertexId, String vertexName, String edgeName, EdgeSide direction) {
        EdgeSchema edgeSchema = schema.edgeSchema(edgeName);
        if (edgeSchema == null) {
            return 0;
        }
        boolean directed = edgeSchema.direction() == com.pengcheng.phoenixgraph.schema.EdgeDirection.DIRECTED;
        if (!directed) {
            // undirected and bidirectional edges are stored in the 'both' bin
            return edgeStore.countEdges(vertexId, edgeSchema.id(), AerospikeEdgeStore.EDGE_DIR_BOTH);
        }
        if (direction == EdgeSide.OUT) {
            return edgeStore.countEdges(vertexId, edgeSchema.id(), AerospikeEdgeStore.EDGE_DIR_OUT);
        }
        if (direction == EdgeSide.IN) {
            return edgeStore.countEdges(vertexId, edgeSchema.id(), AerospikeEdgeStore.EDGE_DIR_IN);
        }
        return edgeStore.countEdges(vertexId, edgeSchema.id(), AerospikeEdgeStore.EDGE_DIR_OUT)
                + edgeStore.countEdges(vertexId, edgeSchema.id(), AerospikeEdgeStore.EDGE_DIR_IN);
    }

    /**
     * Counting with conditions: an edge that fails them is skipped while the bin is walked, so
     * {@code outE('Payment').has('status','SUCCESS').count()} never builds a payment.
     */
    @Override
    public long countEdges(Object vertexId, String vertexName, String edgeName, EdgeSide direction,
                           List<EdgePropertyFilter> filters) {
        Objects.requireNonNull(vertexId, "vertexId");
        if (filters == null || filters.isEmpty()) {
            return countEdges(vertexId, vertexName, edgeName, direction);
        }
        EdgeSchema edgeSchema = resolveEdgeSchema(edgeName);
        AerospikeEdgeStore.EdgeRead read = edgeRead(edgeSchema, List.of(), filters, 0);
        if (edgeSchema.direction() != EdgeDirection.DIRECTED) {
            return edgeStore.countEdges(vertexId, edgeSchema.id(), AerospikeEdgeStore.EDGE_DIR_BOTH, read);
        }
        long total = 0;
        if (direction == EdgeSide.OUT || direction == EdgeSide.BOTH) {
            total += edgeStore.countEdges(vertexId, edgeSchema.id(), AerospikeEdgeStore.EDGE_DIR_OUT, read);
        }
        if (direction == EdgeSide.IN || direction == EdgeSide.BOTH) {
            total += edgeStore.countEdges(vertexId, edgeSchema.id(), AerospikeEdgeStore.EDGE_DIR_IN, read);
        }
        return total;
    }

    @Override
    public List<EdgeQueryResult> readEdges(Object vertexId, String vertexName, String edgeName,
                                           EdgeSide direction, List<String> propertyNames) {
        return readEdges(vertexId, vertexName, edgeName, direction, propertyNames, List.of(), 0);
    }

    /**
     * Reads a filtered and/or capped list of edges, decoding only what the conditions and the
     * projection need: {@code has(...)} discards an edge before it is built, and a cap picks the
     * newest edges instead of building every one of them and cutting the list afterwards.
     */
    @Override
    public List<EdgeQueryResult> readEdges(Object vertexId, String vertexName, String edgeName,
                                           EdgeSide direction, List<String> propertyNames,
                                           List<EdgePropertyFilter> filters, int maxResults) {
        Objects.requireNonNull(vertexId, "vertexId");
        Objects.requireNonNull(direction, "direction");
        if (vertexName != null) {
            resolveVertexSchema(vertexName);
        }
        EdgeSchema edgeSchema = resolveEdgeSchema(edgeName);
        Map<Integer, String> requested = requestedEdgeProperties(edgeSchema, propertyNames);
        if ((filters == null || filters.isEmpty()) && maxResults <= 0) {
            return readEdgesForVertex(vertexId, edgeName, edgeSchema, direction, requested);
        }
        AerospikeEdgeStore.EdgeRead read = edgeRead(edgeSchema, propertyNames, filters, maxResults);
        List<EdgeQueryResult> results = new ArrayList<>();
        if (edgeSchema.direction() == EdgeDirection.DIRECTED) {
            if (direction == EdgeSide.OUT || direction == EdgeSide.BOTH) {
                readInto(results, vertexId, edgeName, edgeSchema, AerospikeEdgeStore.EDGE_DIR_OUT,
                        EdgeSide.OUT, requested, read);
            }
            if (direction == EdgeSide.IN || direction == EdgeSide.BOTH) {
                readInto(results, vertexId, edgeName, edgeSchema, AerospikeEdgeStore.EDGE_DIR_IN,
                        EdgeSide.IN, requested, read);
            }
        } else {
            readInto(results, vertexId, edgeName, edgeSchema, AerospikeEdgeStore.EDGE_DIR_BOTH,
                    EdgeSide.BOTH, requested, read);
        }
        results.sort(Comparator.comparingLong(EdgeQueryResult::lastUpdatedTime).reversed()
                .thenComparing(EdgeQueryResult::edgeId));
        if (maxResults > 0 && results.size() > maxResults) {
            return List.copyOf(results.subList(0, maxResults));
        }
        return results;
    }

    @Override
    public List<List<EdgeQueryResult>> readEdgesBatch(String edgeName, EdgeSide direction,
                                                      List<Object> vertexIds, List<String> propertyNames) {
        return readEdgesBatch(edgeName, direction, vertexIds, propertyNames, List.of(), 0);
    }

    /** This scheme keeps each edge as a value whose head is its peer and timestamps, so it can answer a
     * neighbour read without decoding anything. */
    @Override
    public boolean supportsNeighbourRead() {
        return true;
    }

    /**
     * The neighbours of a vertex over one edge class, read from the header of each stored edge: a vertex
     * hop walks an edge to arrive somewhere and never looks at the edge itself, so no edge is built.
     */
    @Override
    public List<NeighbourRef> readNeighbours(Object vertexId, String vertexName, String edgeName,
                                             EdgeSide direction) {
        Objects.requireNonNull(vertexId, "vertexId");
        Objects.requireNonNull(direction, "direction");
        if (vertexName != null) {
            resolveVertexSchema(vertexName);
        }
        EdgeSchema edgeSchema = resolveEdgeSchema(edgeName);
        List<NeighbourRef> out = new ArrayList<>();
        for (int directionCode : directionCodes(edgeSchema, direction)) {
            EdgeSide side = sideOf(directionCode);
            for (EdgeHeader header : edgeStore.getEdgeHeaders(vertexId, edgeSchema.id(), directionCode)) {
                out.add(toNeighbour(edgeName, header, side));
            }
        }
        out.sort(Comparator.comparingLong(NeighbourRef::lastUpdatedTime).reversed()
                .thenComparing(ref -> String.valueOf(ref.edgeId())));
        return out;
    }

    /** Batched neighbour read: one native batch get per direction, no edge decoded. */
    @Override
    public List<List<NeighbourRef>> readNeighboursBatch(String edgeName, EdgeSide direction,
                                                        List<Object> vertexIds) {
        Objects.requireNonNull(vertexIds, "vertexIds");
        if (vertexIds.isEmpty()) {
            return List.of();
        }
        Objects.requireNonNull(direction, "direction");
        EdgeSchema edgeSchema = resolveEdgeSchema(edgeName);
        List<List<NeighbourRef>> perVertex = new ArrayList<>(vertexIds.size());
        for (int i = 0; i < vertexIds.size(); i++) {
            perVertex.add(new ArrayList<>());
        }
        for (int directionCode : directionCodes(edgeSchema, direction)) {
            EdgeSide side = sideOf(directionCode);
            List<List<EdgeHeader>> batches =
                    edgeStore.getEdgeHeadersBatch(vertexIds, edgeSchema.id(), directionCode);
            for (int i = 0; i < vertexIds.size(); i++) {
                for (EdgeHeader header : batches.get(i)) {
                    perVertex.get(i).add(toNeighbour(edgeName, header, side));
                }
            }
        }
        for (List<NeighbourRef> list : perVertex) {
            list.sort(Comparator.comparingLong(NeighbourRef::lastUpdatedTime).reversed()
                    .thenComparing(ref -> String.valueOf(ref.edgeId())));
        }
        return perVertex;
    }

    private static NeighbourRef toNeighbour(String edgeName, EdgeHeader header, EdgeSide side) {
        return new NeighbourRef(edgeName, header.edgeId(), side, header.peerVertexId(),
                header.createdTime(), header.lastUpdatedTime());
    }

    /**
     * Windowed read: the window travels to the store, which drops an edge outside it while it walks
     * the bin - so a query for an hour of a hub's history does not build the rest of it.
     */
    @Override
    public List<EdgeQueryResult> readEdgesWithin(Object vertexId, String vertexName, String edgeName,
                                                 EdgeSide direction, TimeRange range,
                                                 List<String> propertyNames) {
        if (range == null || range.isUnbounded()) {
            return readEdges(vertexId, vertexName, edgeName, direction, propertyNames);
        }
        Objects.requireNonNull(vertexId, "vertexId");
        Objects.requireNonNull(direction, "direction");
        if (vertexName != null) {
            resolveVertexSchema(vertexName);
        }
        EdgeSchema edgeSchema = resolveEdgeSchema(edgeName);
        Map<Integer, String> requested = requestedEdgeProperties(edgeSchema, propertyNames);
        AerospikeEdgeStore.EdgeRead read = edgeRead(edgeSchema, propertyNames, List.of(), 0).withWindow(range);
        List<EdgeQueryResult> results = new ArrayList<>();
        if (edgeSchema.direction() == EdgeDirection.DIRECTED) {
            if (direction == EdgeSide.OUT || direction == EdgeSide.BOTH) {
                readInto(results, vertexId, edgeName, edgeSchema, AerospikeEdgeStore.EDGE_DIR_OUT,
                        EdgeSide.OUT, requested, read);
            }
            if (direction == EdgeSide.IN || direction == EdgeSide.BOTH) {
                readInto(results, vertexId, edgeName, edgeSchema, AerospikeEdgeStore.EDGE_DIR_IN,
                        EdgeSide.IN, requested, read);
            }
        } else {
            readInto(results, vertexId, edgeName, edgeSchema, AerospikeEdgeStore.EDGE_DIR_BOTH,
                    EdgeSide.BOTH, requested, read);
        }
        results.sort(Comparator.comparingLong(EdgeQueryResult::lastUpdatedTime).reversed()
                .thenComparing(EdgeQueryResult::edgeId));
        return results;
    }

    /** Batched windowed read: one native batch get, the window applied while each bin is decoded. */
    @Override
    public List<List<EdgeQueryResult>> readEdgesBatchWithin(String edgeName, EdgeSide direction,
                                                            TimeRange range, List<Object> vertexIds,
                                                            List<String> propertyNames,
                                                            int maxResults) {
        Objects.requireNonNull(vertexIds, "vertexIds");
        if (vertexIds.isEmpty()) {
            return List.of();
        }
        if (range == null || range.isUnbounded()) {
            return readEdgesBatch(edgeName, direction, vertexIds, propertyNames, List.of(), maxResults);
        }
        Objects.requireNonNull(direction, "direction");
        EdgeSchema edgeSchema = resolveEdgeSchema(edgeName);
        Map<Integer, String> requested = requestedEdgeProperties(edgeSchema, propertyNames);
        AerospikeEdgeStore.EdgeRead read = edgeRead(edgeSchema, propertyNames, List.of(), maxResults)
                .withWindow(range);
        List<List<EdgeQueryResult>> perVertex = new ArrayList<>(vertexIds.size());
        for (int i = 0; i < vertexIds.size(); i++) {
            perVertex.add(new ArrayList<>());
        }
        for (int directionCode : directionCodes(edgeSchema, direction)) {
            EdgeSide side = sideOf(directionCode);
            List<List<StoredEdgeValue>> batches = edgeStore.getEdgesBatchFiltered(vertexIds, edgeSchema.id(),
                    directionCode, read);
            for (int i = 0; i < vertexIds.size(); i++) {
                for (StoredEdgeValue edge : batches.get(i)) {
                    perVertex.get(i).add(toEdgeResult(edgeName, edge, side, requested));
                }
            }
        }
        for (List<EdgeQueryResult> list : perVertex) {
            list.sort(Comparator.comparingLong(EdgeQueryResult::lastUpdatedTime).reversed()
                    .thenComparing(EdgeQueryResult::edgeId));
        }
        return perVertex;
    }

    /** The direction codes a read walks (one for an undirected class, one or two for a directed one). */
    private static List<Integer> directionCodes(EdgeSchema edgeSchema, EdgeSide direction) {
        if (edgeSchema.direction() != EdgeDirection.DIRECTED) {
            return List.of(AerospikeEdgeStore.EDGE_DIR_BOTH);
        }
        List<Integer> codes = new ArrayList<>(2);
        if (direction == EdgeSide.OUT || direction == EdgeSide.BOTH) {
            codes.add(AerospikeEdgeStore.EDGE_DIR_OUT);
        }
        if (direction == EdgeSide.IN || direction == EdgeSide.BOTH) {
            codes.add(AerospikeEdgeStore.EDGE_DIR_IN);
        }
        return codes;
    }

    /** Batched filtered read: one native batch get, with the conditions applied per vertex. */
    @Override
    public List<List<EdgeQueryResult>> readEdgesBatch(String edgeName, EdgeSide direction,
                                                      List<Object> vertexIds, List<String> propertyNames,
                                                      List<EdgePropertyFilter> filters, int maxResults) {
        Objects.requireNonNull(vertexIds, "vertexIds");
        if (vertexIds.isEmpty()) {
            return List.of();
        }
        if ((filters == null || filters.isEmpty()) && maxResults <= 0) {
            return readEdgesBatchNative(edgeName, direction, vertexIds, propertyNames);
        }
        List<List<EdgeQueryResult>> out = new ArrayList<>(vertexIds.size());
        for (Object vertexId : vertexIds) {
            out.add(readEdges(vertexId, null, edgeName, direction, propertyNames, filters, maxResults));
        }
        return out;
    }

    private List<List<EdgeQueryResult>> readEdgesBatchNative(String edgeName, EdgeSide direction,
                                                             List<Object> vertexIds, List<String> propertyNames) {
        EdgeSchema edgeSchema = resolveEdgeSchema(edgeName);
        Map<Integer, String> requested = requestedEdgeProperties(edgeSchema, propertyNames);

        List<Integer> dirCodes = new ArrayList<>();
        if (edgeSchema.direction() == EdgeDirection.DIRECTED) {
            if (direction == EdgeSide.OUT || direction == EdgeSide.BOTH) {
                dirCodes.add(AerospikeEdgeStore.EDGE_DIR_OUT);
            }
            if (direction == EdgeSide.IN || direction == EdgeSide.BOTH) {
                dirCodes.add(AerospikeEdgeStore.EDGE_DIR_IN);
            }
        } else {
            dirCodes.add(AerospikeEdgeStore.EDGE_DIR_BOTH);
        }

        List<List<EdgeQueryResult>> perVertex = new ArrayList<>(vertexIds.size());
        for (int i = 0; i < vertexIds.size(); i++) {
            perVertex.add(new ArrayList<>());
        }
        for (int dirCode : dirCodes) {
            EdgeSide side = sideOf(dirCode);
            List<List<StoredEdgeValue>> batches = edgeStore.getEdgesBatch(vertexIds, edgeSchema.id(), dirCode,
                    requested.isEmpty() ? null : new java.util.LinkedHashSet<>(requested.keySet()));
            for (int i = 0; i < vertexIds.size(); i++) {
                for (StoredEdgeValue edge : batches.get(i)) {
                    perVertex.get(i).add(toEdgeResult(edgeName, edge, side, requested));
                }
            }
        }
        for (List<EdgeQueryResult> list : perVertex) {
            list.sort(Comparator.comparingLong(EdgeQueryResult::lastUpdatedTime).reversed()
                    .thenComparing(EdgeQueryResult::edgeId));
        }
        return perVertex;
    }

    @Override
    public List<List<EdgeQueryResult>> readAllEdgesBatch(List<Object> vertexIds, EdgeSide direction,
                                                         List<String> propertyNames) {
        Objects.requireNonNull(vertexIds, "vertexIds");
        Objects.requireNonNull(direction, "direction");
        if (vertexIds.isEmpty()) {
            return List.of();
        }
        List<List<EdgeQueryResult>> perVertex = new ArrayList<>(vertexIds.size());
        for (int i = 0; i < vertexIds.size(); i++) {
            perVertex.add(new ArrayList<>());
        }
        for (EdgeSchema edgeSchema : schema.edgeSchemas()) {
            List<List<EdgeQueryResult>> byEdge = readEdgesBatch(edgeSchema.name(), direction, vertexIds, propertyNames);
            for (int i = 0; i < vertexIds.size(); i++) {
                perVertex.get(i).addAll(byEdge.get(i));
            }
        }
        return perVertex;
    }

    private List<EdgeQueryResult> readEdgesForVertex(Object vertexId, String edgeName, EdgeSchema edgeSchema,
                                                     EdgeSide direction, Map<Integer, String> requested) {
        List<EdgeQueryResult> results = new ArrayList<>();
        if (edgeSchema.direction() == EdgeDirection.DIRECTED) {
            if (direction == EdgeSide.OUT || direction == EdgeSide.BOTH) {
                readInto(results, vertexId, edgeName, edgeSchema, AerospikeEdgeStore.EDGE_DIR_OUT,
                        EdgeSide.OUT, requested);
            }
            if (direction == EdgeSide.IN || direction == EdgeSide.BOTH) {
                readInto(results, vertexId, edgeName, edgeSchema, AerospikeEdgeStore.EDGE_DIR_IN,
                        EdgeSide.IN, requested);
            }
        } else {
            // UNDIRECTED / BIDIRECTIONAL: no in/out distinction — any direction reads the single both-bin.
            readInto(results, vertexId, edgeName, edgeSchema, AerospikeEdgeStore.EDGE_DIR_BOTH,
                    EdgeSide.BOTH, requested);
        }
        results.sort(Comparator.comparingLong(EdgeQueryResult::lastUpdatedTime).reversed()
                .thenComparing(EdgeQueryResult::edgeId));
        return results;
    }

    private void readInto(List<EdgeQueryResult> results, Object vertexId, String edgeName,
                          EdgeSchema edgeSchema, int directionCode, EdgeSide side,
                          Map<Integer, String> requested) {
        Set<Integer> only = requested.isEmpty() ? null : new LinkedHashSet<>(requested.keySet());
        for (StoredEdgeValue edge : edgeStore.getEdges(vertexId, edgeSchema.id(), directionCode, only)) {
            results.add(toEdgeResult(edgeName, edge, side, requested));
        }
    }

    private void readInto(List<EdgeQueryResult> results, Object vertexId, String edgeName,
                          EdgeSchema edgeSchema, int directionCode, EdgeSide side,
                          Map<Integer, String> requested, AerospikeEdgeStore.EdgeRead read) {
        for (StoredEdgeValue edge : edgeStore.getEdgesFiltered(vertexId, edgeSchema.id(), directionCode, read)) {
            results.add(toEdgeResult(edgeName, edge, side, requested));
        }
    }

    /**
     * Translates a query's conditions into a store read: the property names become schema ids and
     * the predicates are evaluated on id-keyed values, which is what the bin holds.
     */
    private AerospikeEdgeStore.EdgeRead edgeRead(EdgeSchema edgeSchema, List<String> propertyNames,
                                                 List<EdgePropertyFilter> filters, int maxResults) {
        List<String> names = propertyNames == null ? List.of() : propertyNames;
        Set<Integer> projection = null;
        if (!names.isEmpty()) {
            projection = new LinkedHashSet<>();
            for (String name : names) {
                projection.add(requireEdgeProperty(edgeSchema, name).id());
            }
        }
        if (filters == null || filters.isEmpty()) {
            return AerospikeEdgeStore.EdgeRead.filtered(projection, Set.of(), null, maxResults);
        }
        Set<Integer> filterIds = new LinkedHashSet<>();
        List<java.util.function.Predicate<Map<Integer, Object>>> predicates = new ArrayList<>();
        for (EdgePropertyFilter filter : filters) {
            Map<Integer, String> byId = new LinkedHashMap<>();
            for (String name : filter.propertyNames()) {
                int id = requireEdgeProperty(edgeSchema, name).id();
                filterIds.add(id);
                byId.put(id, name);
            }
            predicates.add(valuesById -> {
                Map<String, Object> byName = new LinkedHashMap<>();
                for (Map.Entry<Integer, String> entry : byId.entrySet()) {
                    Object value = valuesById.get(entry.getKey());
                    if (value != null) {
                        byName.put(entry.getValue(), value);
                    }
                }
                return filter.test(byName);
            });
        }
        java.util.function.Predicate<Map<Integer, Object>> combined = values -> {
            for (java.util.function.Predicate<Map<Integer, Object>> predicate : predicates) {
                if (!predicate.test(values)) {
                    return false;
                }
            }
            return true;
        };
        return AerospikeEdgeStore.EdgeRead.filtered(projection, filterIds, combined, maxResults);
    }

    private Map<Integer, String> requestedEdgeProperties(EdgeSchema edgeSchema, List<String> propertyNames) {
        List<String> names = propertyNames == null ? List.of() : propertyNames;
        Map<Integer, String> requested = new LinkedHashMap<>();
        if (names.isEmpty()) {
            for (EdgePropertySchema propertySchema : edgeSchema.properties()) {
                requested.put(propertySchema.id(), propertySchema.name());
            }
        } else {
            for (String name : names) {
                EdgePropertySchema propertySchema = requireEdgeProperty(edgeSchema, name);
                requested.put(propertySchema.id(), propertySchema.name());
            }
        }
        return requested;
    }

    private EdgeQueryResult toEdgeResult(String edgeName, StoredEdgeValue edge, EdgeSide side,
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

    private EdgeSide sideOf(int directionCode) {
        if (directionCode == AerospikeEdgeStore.EDGE_DIR_OUT) {
            return EdgeSide.OUT;
        }
        if (directionCode == AerospikeEdgeStore.EDGE_DIR_IN) {
            return EdgeSide.IN;
        }
        return EdgeSide.BOTH;
    }

    // --- geo ---

    /**
     * Finds the vertices of a type inside a circle, nearest first.
     *
     * <p>Aerospike answers this from the {@code geo2dsphere} secondary index of the geo bin when
     * the server supports it (an enterprise feature, and the stored value has to be a GeoJSON
     * point for it to match). When the index is missing - a community server, or a point stored
     * as a map - the query falls back to scanning the set and computing every distance, so the
     * same Gremlin query works either way.
     */
    @Override
    public List<GeoHit> geoNear(String vertexName, String propertyName, GeoSearch search) {
        VertexSchema vertexSchema = resolveVertexSchema(vertexName);
        VertexPropertySchema propertySchema = requireVertexProperty(vertexSchema, propertyName);
        if (!propertySchema.isGeo()) {
            throw new QueryException("Property '" + propertyName + "' of vertex type '" + vertexName
                    + "' is not declared with isGeo, so it has no geo index");
        }
        int propertyId = propertySchema.id();
        String bin = AerospikeVertexStore.propertyBin(propertyId);
        if (vertexStore.hasGeoIndex(bin)) {
            try {
                return AerospikeGeoIndex.searchByIndex(vertexStore.client(), vertexStore.namespace(),
                        vertexStore.set(), vertexName, propertyName, propertyId, search);
            } catch (RuntimeException e) {
                // the index exists but cannot serve this query: scan instead of failing
                businessLog.record(BusinessLogEntry.failure("geoNear", vertexName + "." + propertyName,
                        Map.of("detail", "geo index query failed, falling back to a scan: " + e.getMessage())));
            }
        }
        List<GeoHit> hits = AerospikeGeoIndex.searchByScan(vertexStore, vertexName, propertyName, propertyId,
                search, businessLog);
        if (search.maxResults() > 0 && hits.size() > search.maxResults()) {
            return List.copyOf(hits.subList(0, search.maxResults()));
        }
        return hits;
    }

    @Override
    public boolean supportsGeoSearch() {
        return true;
    }

    // --- schema resolution ---

    private VertexSchema resolveVertexSchema(String vertexName) {
        if (vertexName == null || vertexName.isBlank()) {
            throw new QueryException("vertexName must not be blank");
        }
        VertexSchema vertexSchema = schema.vertexSchema(vertexName);
        if (vertexSchema == null) {
            throw new QueryException("Unknown vertex schema: " + vertexName);
        }
        return vertexSchema;
    }

    private EdgeSchema resolveEdgeSchema(String edgeName) {
        if (edgeName == null || edgeName.isBlank()) {
            throw new QueryException("edgeName must not be blank");
        }
        EdgeSchema edgeSchema = schema.edgeSchema(edgeName);
        if (edgeSchema == null) {
            throw new QueryException("Unknown edge schema: " + edgeName);
        }
        return edgeSchema;
    }

    private static VertexPropertySchema requireVertexProperty(VertexSchema vertexSchema, String name) {
        for (VertexPropertySchema propertySchema : vertexSchema.properties()) {
            if (propertySchema.name().equals(name)) {
                return propertySchema;
            }
        }
        throw new QueryException("Unknown property '" + name + "' in vertex schema '" + vertexSchema.name() + "'");
    }

    private static EdgePropertySchema requireEdgeProperty(EdgeSchema edgeSchema, String name) {
        for (EdgePropertySchema propertySchema : edgeSchema.properties()) {
            if (propertySchema.name().equals(name)) {
                return propertySchema;
            }
        }
        throw new QueryException("Unknown property '" + name + "' in edge schema '" + edgeSchema.name() + "'");
    }
}
