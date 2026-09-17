package com.pengcheng.phoenixgraph.storage.rocksdb.edge;

import com.pengcheng.phoenixgraph.query.QueryException;
import com.pengcheng.phoenixgraph.query.storage.EdgeQueryResult;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
import com.pengcheng.phoenixgraph.query.storage.GraphQuery;
import com.pengcheng.phoenixgraph.query.storage.SchemaProjections;
import com.pengcheng.phoenixgraph.query.storage.VertexQueryResult;
import com.pengcheng.phoenixgraph.schema.EdgeDirection;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GeoIndexSchemas;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.VertexSchema;
import com.pengcheng.phoenixgraph.schema.geo.GeoHit;
import com.pengcheng.phoenixgraph.schema.geo.GeoSearch;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbEdgeProperty;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbPropertyValue;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbStoredEdge;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbStoredVertex;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@link GraphQuery} over the RocksDB-Edge store.
 *
 * <p>A vertex read is a point lookup in its type's table with the requested properties; an
 * edge read is a prefix scan of the edge table ordered by creation time, and a directed
 * {@code BOTH} read runs <b>two iterators</b> - one over the outgoing rows, one over the
 * incoming rows - which is the same pair of reads {@code out} and {@code in} do on their own.
 *
 * <p>{@link #readEdgesWithin} adds what the table layout makes cheap: a creation-time window,
 * so a query can ask for the payments of the last hour instead of every payment of an account.
 */
public class RocksDbEdgeGraphQuery implements GraphQuery {

    private final RocksDbEdgeStore store;
    private final GraphSchema schema;

    public RocksDbEdgeGraphQuery(RocksDbEdgeStore store, GraphSchema schema) {
        this.store = Objects.requireNonNull(store, "store");
        this.schema = Objects.requireNonNull(schema, "schema");
    }

    /** The store behind this query. */
    public RocksDbEdgeStore store() {
        return store;
    }

    /**
     * Finds the vertices of a type inside a circle, nearest first, from the S2 geo index.
     * The index belongs to the vertex tables, so the edge tables of this scheme are not
     * involved - a radius query is a few range scans of the index.
     */
    @Override
    public List<GeoHit> geoNear(String vertexName, String propertyName, GeoSearch search) {
        SchemaProjections.requireVertex(schema, vertexName);
        requireGeoProperty(vertexName, propertyName);
        return store.geoIndex().search(vertexName, propertyName, search);
    }

    @Override
    public boolean supportsGeoSearch() {
        return true;
    }

    private void requireGeoProperty(String vertexName, String propertyName) {
        if (GeoIndexSchemas.geoProperty(schema, vertexName, propertyName) == null) {
            throw new QueryException("Property '" + propertyName + "' of vertex type '" + vertexName
                    + "' is not declared with isGeo, so it has no geo index");
        }
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
        return readEdgesWithin(vertexId, vertexName, edgeName, direction, RocksDbTimeRange.ALL, propertyNames);
    }

    /**
     * Reads the edges of a class attached to a vertex inside a creation-time window. This is
     * the layer's native read: the window is the range the table scan walks.
     */
    public List<EdgeQueryResult> readEdgesWithin(Object vertexId, String vertexName, String edgeName,
                                                 EdgeSide direction, RocksDbTimeRange range,
                                                 List<String> propertyNames) {
        Objects.requireNonNull(vertexId, "vertexId");
        Objects.requireNonNull(direction, "direction");
        if (vertexName != null) {
            SchemaProjections.requireVertex(schema, vertexName);
        }
        EdgeSchema edgeSchema = SchemaProjections.requireEdge(schema, edgeName);
        Map<Integer, String> requested = SchemaProjections.edgeProperties(edgeSchema, propertyNames);

        List<EdgeQueryResult> results = new ArrayList<>();
        for (EdgeSide side : sides(edgeSchema, direction)) {
            // one iterator per direction: outgoing rows and incoming rows are separate scans
            List<RocksDbStoredEdge> edges = store.readEdges(edgeName, vertexId,
                    RocksDbEdgeStore.directionCode(side, edgeSchema.direction()),
                    range, List.copyOf(requested.keySet()));
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
        return readEdgesBatchWithin(edgeName, direction, RocksDbTimeRange.ALL, vertexIds, propertyNames);
    }

    /** Batched form of {@link #readEdgesWithin}. */
    public List<List<EdgeQueryResult>> readEdgesBatchWithin(String edgeName, EdgeSide direction,
                                                            RocksDbTimeRange range, List<Object> vertexIds,
                                                            List<String> propertyNames) {
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
            byte directionCode = RocksDbEdgeStore.directionCode(side, edgeSchema.direction());
            for (int i = 0; i < vertexIds.size(); i++) {
                List<RocksDbStoredEdge> edges = store.readEdges(edgeName, vertexIds.get(i), directionCode,
                        range, List.copyOf(requested.keySet()));
                for (RocksDbStoredEdge edge : edges) {
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
     * The sides a request reads: a directed edge reads its outgoing rows for {@code OUT}, its
     * incoming rows for {@code IN} and both for {@code BOTH}; an undirected edge has one kind
     * of row, stored under direction {@code 0}.
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
