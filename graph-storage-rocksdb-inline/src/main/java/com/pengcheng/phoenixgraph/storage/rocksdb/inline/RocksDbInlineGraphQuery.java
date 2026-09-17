package com.pengcheng.phoenixgraph.storage.rocksdb.inline;

import com.pengcheng.phoenixgraph.query.storage.EdgeQueryResult;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
import com.pengcheng.phoenixgraph.query.storage.GraphQuery;
import com.pengcheng.phoenixgraph.query.storage.SchemaProjections;
import com.pengcheng.phoenixgraph.query.storage.VertexQueryResult;
import com.pengcheng.phoenixgraph.schema.EdgeDirection;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.VertexSchema;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbPropertyValue;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbStoredVertex;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbEdgeProperty;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbStoredEdge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@link GraphQuery} over the RocksDB-Inline store.
 *
 * <p>It is the read half of the scheme and nothing else: the same engine serves Gremlin and
 * Cypher, because both only know {@link GraphQuery}. Property names are resolved against the
 * graph schema into property indexes (the keys a record is stored under), an empty request
 * means every declared property, and the meta timestamps are always returned.
 *
 * <p>A directed edge is read one direction at a time - outgoing from the source's bin,
 * incoming from the destination's bin - so every result carries the side it was found on,
 * and {@code BOTH} is the two reads merged and ordered newest first. Batched reads go
 * through the store's batched calls, so a hop over many seeds is one {@code multiGet} per
 * table rather than one read per vertex.
 */
public class RocksDbInlineGraphQuery implements GraphQuery {

    private final RocksDbInlineStore store;
    private final GraphSchema schema;

    public RocksDbInlineGraphQuery(RocksDbInlineStore store, GraphSchema schema) {
        this.store = Objects.requireNonNull(store, "store");
        this.schema = Objects.requireNonNull(schema, "schema");
    }

    /** The store behind this query. */
    public RocksDbInlineStore store() {
        return store;
    }

    // --- vertices ---

    @Override
    public VertexQueryResult readVertex(Object vertexId, String vertexName, List<String> propertyNames) {
        Objects.requireNonNull(vertexId, "vertexId");
        VertexSchema vertexSchema = SchemaProjections.requireVertex(schema, vertexName);
        Map<Integer, String> requested = SchemaProjections.vertexProperties(vertexSchema, propertyNames);
        RocksDbStoredVertex vertex = store.readVertex(vertexName, vertexId, List.copyOf(requested.keySet()));
        return vertex == null ? null : toVertexResult(vertexName, vertex, requested);
    }

    @Override
    public List<VertexQueryResult> readVertices(String vertexName, List<Object> vertexIds,
                                                List<String> propertyNames) {
        Objects.requireNonNull(vertexIds, "vertexIds");
        if (vertexIds.isEmpty()) {
            return List.of();
        }
        VertexSchema vertexSchema = SchemaProjections.requireVertex(schema, vertexName);
        Map<Integer, String> requested = SchemaProjections.vertexProperties(vertexSchema, propertyNames);
        List<RocksDbStoredVertex> stored =
                store.readVertices(vertexName, vertexIds, List.copyOf(requested.keySet()));
        List<VertexQueryResult> results = new ArrayList<>(stored.size());
        for (RocksDbStoredVertex vertex : stored) {
            results.add(vertex == null ? null : toVertexResult(vertexName, vertex, requested));
        }
        return results;
    }

    private static VertexQueryResult toVertexResult(String vertexName, RocksDbStoredVertex vertex,
                                                    Map<Integer, String> requested) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> entry : requested.entrySet()) {
            RocksDbPropertyValue value = vertex.properties().get(entry.getKey());
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
            SchemaProjections.requireVertex(schema, vertexName);
        }
        EdgeSchema edgeSchema = SchemaProjections.requireEdge(schema, edgeName);
        Map<Integer, String> requested = SchemaProjections.edgeProperties(edgeSchema, propertyNames);
        List<EdgeQueryResult> results = new ArrayList<>();
        for (EdgeSide side : sides(edgeSchema, direction)) {
            List<RocksDbStoredEdge> edges = store.readEdges(edgeName, vertexId,
                    RocksDbEdgeBins.directionCode(side), List.copyOf(requested.keySet()));
            for (RocksDbStoredEdge edge : edges) {
                results.add(toEdgeResult(edgeName, edge, side, requested));
            }
        }
        sort(results);
        return results;
    }

    @Override
    public List<List<EdgeQueryResult>> readEdgesBatch(String edgeName, EdgeSide direction,
                                                      List<Object> vertexIds, List<String> propertyNames) {
        Objects.requireNonNull(vertexIds, "vertexIds");
        Objects.requireNonNull(direction, "direction");
        if (vertexIds.isEmpty()) {
            return List.of();
        }
        EdgeSchema edgeSchema = SchemaProjections.requireEdge(schema, edgeName);
        Map<Integer, String> requested = SchemaProjections.edgeProperties(edgeSchema, propertyNames);

        List<List<EdgeQueryResult>> perVertex = new ArrayList<>(vertexIds.size());
        for (int i = 0; i < vertexIds.size(); i++) {
            perVertex.add(new ArrayList<>());
        }
        for (EdgeSide side : sides(edgeSchema, direction)) {
            List<List<RocksDbStoredEdge>> batches = store.readEdgesBatch(edgeName,
                    RocksDbEdgeBins.directionCode(side), vertexIds, List.copyOf(requested.keySet()));
            for (int i = 0; i < vertexIds.size(); i++) {
                for (RocksDbStoredEdge edge : batches.get(i)) {
                    perVertex.get(i).add(toEdgeResult(edgeName, edge, side, requested));
                }
            }
        }
        for (List<EdgeQueryResult> edges : perVertex) {
            sort(edges);
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
            List<List<EdgeQueryResult>> byEdge =
                    readEdgesBatch(edgeSchema.name(), direction, vertexIds, propertyNames);
            for (int i = 0; i < vertexIds.size(); i++) {
                perVertex.get(i).addAll(byEdge.get(i));
            }
        }
        return perVertex;
    }

    /**
     * The sides a request has to read. An undirected/bidirectional edge has no in/out
     * distinction - any direction reads its one bin - while a directed edge reads the
     * outgoing bin for {@code OUT}, the incoming bin for {@code IN} and both for {@code BOTH}.
     */
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

    private static void sort(List<EdgeQueryResult> edges) {
        edges.sort(Comparator.comparingLong(EdgeQueryResult::lastUpdatedTime).reversed()
                .thenComparing(EdgeQueryResult::edgeId));
    }

    private static EdgeQueryResult toEdgeResult(String edgeName, RocksDbStoredEdge edge, EdgeSide side,
                                                Map<Integer, String> requested) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> entry : requested.entrySet()) {
            RocksDbEdgeProperty value = edge.properties().get(entry.getKey());
            if (value != null) {
                properties.put(entry.getValue(), value.value());
            }
        }
        return new EdgeQueryResult(edgeName, edge.edgeId(), side, edge.otherVertexId(), properties,
                edge.createdTime(), edge.lastUpdatedTime());
    }
}
