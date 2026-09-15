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
import com.pengcheng.phoenixgraph.schema.VertexPropertySchema;
import com.pengcheng.phoenixgraph.schema.VertexSchema;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import com.pengcheng.phoenixgraph.storage.aerospike.EdgePropertyValue;
import com.pengcheng.phoenixgraph.storage.aerospike.StoredEdgeValue;
import com.pengcheng.phoenixgraph.storage.aerospike.StoredVertex;
import com.pengcheng.phoenixgraph.storage.aerospike.VertexPropertyValue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

    public AerospikeGraphQuery(AerospikeVertexStore vertexStore, AerospikeEdgeStore edgeStore, GraphSchema schema) {
        this.vertexStore = Objects.requireNonNull(vertexStore, "vertexStore");
        this.edgeStore = Objects.requireNonNull(edgeStore, "edgeStore");
        this.schema = Objects.requireNonNull(schema, "schema");
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
    public List<EdgeQueryResult> readEdges(Object vertexId, String vertexName, String edgeName,
                                           EdgeSide direction, List<String> propertyNames) {
        Objects.requireNonNull(vertexId, "vertexId");
        Objects.requireNonNull(direction, "direction");
        if (vertexName != null) {
            resolveVertexSchema(vertexName);
        }
        EdgeSchema edgeSchema = resolveEdgeSchema(edgeName);
        Map<Integer, String> requested = requestedEdgeProperties(edgeSchema, propertyNames);
        return readEdgesForVertex(vertexId, edgeName, edgeSchema, direction, requested);
    }

    @Override
    public List<List<EdgeQueryResult>> readEdgesBatch(String edgeName, EdgeSide direction,
                                                      List<Object> vertexIds, List<String> propertyNames) {
        Objects.requireNonNull(vertexIds, "vertexIds");
        Objects.requireNonNull(direction, "direction");
        if (vertexIds.isEmpty()) {
            return List.of();
        }
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
            List<List<StoredEdgeValue>> batches = edgeStore.getEdgesBatch(vertexIds, edgeSchema.id(), dirCode);
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
        for (StoredEdgeValue edge : edgeStore.getEdges(vertexId, edgeSchema.id(), directionCode)) {
            results.add(toEdgeResult(edgeName, edge, side, requested));
        }
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
