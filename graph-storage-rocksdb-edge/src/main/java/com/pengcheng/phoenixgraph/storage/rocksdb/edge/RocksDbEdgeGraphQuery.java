package com.pengcheng.phoenixgraph.storage.rocksdb.edge;

import com.pengcheng.phoenixgraph.query.QueryException;
import com.pengcheng.phoenixgraph.query.storage.EdgeQueryResult;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
import com.pengcheng.phoenixgraph.query.storage.NeighbourRef;
import com.pengcheng.phoenixgraph.query.storage.GraphQuery;
import com.pengcheng.phoenixgraph.storage.TimeRange;
import com.pengcheng.phoenixgraph.query.storage.SchemaProjections;
import com.pengcheng.phoenixgraph.query.storage.ScannedEdge;
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

    /**
     * The scan port: this scheme keeps one ordered table per type, so a whole class is one iterator.
     *
     * <p>That is the difference the two storage implementations stand for. Aerospike is the
     * transactional one - a question arrives with a key and is answered from one record (or a batch
     * of records), which is what a request path does. This one is the analytical one: the tables are
     * ordered ranges, so a question that names a <i>class</i> rather than an id -
     * {@code g.V('Account')}, {@code g.E('Payment').pagerank(DIST)} - is answered by walking the
     * table, and the walk can be capped or narrowed to a prefix.
     */
    /**
     * The neighbours of a vertex over one edge class, read from the header of each stored row: a vertex hop
     * walks an edge to arrive somewhere and never looks at the edge itself, so no edge is built and no
     * property is decoded. This is the read {@code supportsNeighbourRead} promises the engine.
     */
    @Override
    public List<NeighbourRef> readNeighbours(Object vertexId, String vertexName, String edgeName,
                                             EdgeSide direction) {
        Objects.requireNonNull(vertexId, "vertexId");
        Objects.requireNonNull(direction, "direction");
        if (vertexName != null) {
            SchemaProjections.requireVertex(schema, vertexName);
        }
        EdgeSchema edgeSchema = SchemaProjections.requireEdge(schema, edgeName);

        List<NeighbourRef> out = new ArrayList<>();
        for (EdgeSide side : sides(edgeSchema, direction)) {
            out.addAll(store.readNeighbours(edgeName, vertexId,
                    RocksDbEdgeStore.directionCode(side, edgeSchema.direction()), side));
        }
        out.sort(Comparator.comparingLong(NeighbourRef::lastUpdatedTime).reversed()
                .thenComparing(ref -> String.valueOf(ref.edgeId())));
        return out;
    }

    /** Batched neighbour read: one header scan per vertex and direction, no edge decoded. */
    @Override
    public List<List<NeighbourRef>> readNeighboursBatch(String edgeName, EdgeSide direction,
                                                       List<Object> vertexIds) {
        Objects.requireNonNull(vertexIds, "vertexIds");
        Objects.requireNonNull(direction, "direction");
        if (vertexIds.isEmpty()) {
            return List.of();
        }
        EdgeSchema edgeSchema = SchemaProjections.requireEdge(schema, edgeName);
        List<List<NeighbourRef>> perVertex = new ArrayList<>(vertexIds.size());
        for (int i = 0; i < vertexIds.size(); i++) {
            perVertex.add(new ArrayList<>());
        }
        for (EdgeSide side : sides(edgeSchema, direction)) {
            byte directionCode = RocksDbEdgeStore.directionCode(side, edgeSchema.direction());
            for (int i = 0; i < vertexIds.size(); i++) {
                perVertex.get(i).addAll(store.readNeighbours(edgeName, vertexIds.get(i), directionCode, side));
            }
        }
        for (List<NeighbourRef> neighbours : perVertex) {
            neighbours.sort(Comparator.comparingLong(NeighbourRef::lastUpdatedTime).reversed()
                    .thenComparing(ref -> String.valueOf(ref.edgeId())));
        }
        return perVertex;
    }

    @Override
    public boolean supportsNeighbourRead() {
        return true;
    }

    @Override
    public boolean supportsScan() {
        return true;
    }

    /** Walks a whole vertex type, projecting only what the query asked for. */
    @Override
    public List<VertexQueryResult> scanVertices(String vertexName, List<String> propertyNames,
                                                int maxResults) {
        VertexSchema vertexSchema = SchemaProjections.requireVertex(schema, vertexName);
        Map<Integer, String> requested = SchemaProjections.vertexProperties(vertexSchema, propertyNames);
        List<RocksDbStoredVertex> stored =
                store.vertices().scan(vertexName, List.copyOf(requested.keySet()), maxResults);
        List<VertexQueryResult> out = new ArrayList<>(stored.size());
        for (RocksDbStoredVertex vertex : stored) {
            out.add(toVertexResult(vertexName, vertex, requested));
        }
        return out;
    }

    /** Walks a whole edge class; every edge comes back once, with both of its endpoints. */
    @Override
    public List<ScannedEdge> scanEdges(String edgeName, List<String> propertyNames, int maxResults) {
        EdgeSchema edgeSchema = SchemaProjections.requireEdge(schema, edgeName);
        Map<Integer, String> requested = SchemaProjections.edgeProperties(edgeSchema, propertyNames);
        List<RocksDbEdgeStore.ScannedEdgeRow> rows =
                store.scanEdges(edgeName, List.copyOf(requested.keySet()), maxResults);
        List<ScannedEdge> out = new ArrayList<>(rows.size());
        for (RocksDbEdgeStore.ScannedEdgeRow row : rows) {
            RocksDbStoredEdge edge = row.edge();
            out.add(new ScannedEdge(row.ownerVertexId(), row.peerVertexId(),
                    toEdgeResult(edgeName, edge, EdgeSide.BOTH, requested)));
        }
        return out;
    }

    /** Counts a whole edge class from the table, without decoding a row. */
    @Override
    public long countScanEdges(String edgeName) {
        SchemaProjections.requireEdge(schema, edgeName);
        return store.countScannedEdges(edgeName, 0);
    }

    /** Counts a whole vertex type from the table, without decoding a record. */
    @Override
    public long countScanVertices(String vertexName) {
        SchemaProjections.requireVertex(schema, vertexName);
        return store.vertices().count(vertexName);
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
        return readEdgesWithin(vertexId, vertexName, edgeName, direction, TimeRange.ALL, propertyNames);
    }

    /**
     * Reads the edges of a class attached to a vertex inside a creation-time window. This is
     * the layer's native read: the window is the range the table scan walks.
     */
    public List<EdgeQueryResult> readEdgesWithin(Object vertexId, String vertexName, String edgeName,
                                                 EdgeSide direction, TimeRange range,
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
        return readEdgesBatchWithin(edgeName, direction, TimeRange.ALL, vertexIds, propertyNames);
    }

    /** Batched form of {@link #readEdgesWithin}. */
    @Override
    public List<List<EdgeQueryResult>> readEdgesBatchWithin(String edgeName, EdgeSide direction,
                                                            TimeRange range, List<Object> vertexIds,
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

    /** Every edge class, inside a window (the edge tables are ordered by creation time). */
    @Override
    public List<List<EdgeQueryResult>> readAllEdgesBatchWithin(List<Object> vertexIds, EdgeSide direction,
                                                              TimeRange range, List<String> propertyNames) {
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
                    readEdgesBatchWithin(edgeSchema.name(), direction, range, vertexIds, propertyNames);
            for (int i = 0; i < vertexIds.size(); i++) {
                perVertex.get(i).addAll(byEdge.get(i));
            }
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
