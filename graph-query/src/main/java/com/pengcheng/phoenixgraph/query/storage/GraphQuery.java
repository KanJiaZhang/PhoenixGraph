package com.pengcheng.phoenixgraph.query.storage;

import java.util.ArrayList;
import java.util.List;

/**
 * Storage-agnostic vertex/edge query contract.
 *
 * <p>Implementations read vertices and edges from a concrete storage backend
 * (Aerospike, RocksDB, HBase, ...). Property names are resolved against the
 * backend's graph schema; an empty (or {@code null}) property list selects all
 * declared properties. Meta timestamps (createdTime / lastUpdatedTime) are
 * always returned.
 *
 * <p>For directed edges the {@link EdgeSide} selects whether to read outgoing,
 * incoming or both directions. For undirected/bidirectional edges the three
 * directions are equivalent (there is no in/out distinction).
 */
public interface GraphQuery {

    /**
     * Reads the vertex identified by {@code vertexId}/{@code vertexName}.
     *
     * @param vertexId      vertex id (record key)
     * @param vertexName    vertex schema name, used to resolve property names
     * @param propertyNames property names to project; empty means all
     * @return the projected vertex, or {@code null} when the vertex is absent
     */
    VertexQueryResult readVertex(Object vertexId, String vertexName, List<String> propertyNames);

    /** Reads the vertex identified by {@code vertexId}/{@code vertexName}, projecting all properties. */
    default VertexQueryResult readVertex(Object vertexId, String vertexName, String... propertyNames) {
        return readVertex(vertexId, vertexName,
                propertyNames == null ? List.of() : List.of(propertyNames));
    }

    /**
     * Reads multiple vertices that share one vertex schema. Implementations may
     * batch the storage reads into a single call; the default reads sequentially.
     *
     * @param vertexName    vertex schema name
     * @param vertexIds     vertex ids to read (result order matches this list)
     * @param propertyNames property names to project; empty means all
     * @return one {@link VertexQueryResult} per id ({@code null} for absent vertices)
     */
    default List<VertexQueryResult> readVertices(String vertexName, List<Object> vertexIds,
                                                 List<String> propertyNames) {
        List<VertexQueryResult> out = new ArrayList<>(vertexIds.size());
        for (Object vertexId : vertexIds) {
            out.add(readVertex(vertexId, vertexName, propertyNames));
        }
        return out;
    }

    /**
     * Reads the edges of the {@code edgeName} class attached to the vertex
     * identified by {@code vertexId}/{@code vertexName}, restricted to the given
     * direction.
     *
     * @param vertexId      vertex id (record key)
     * @param vertexName    vertex schema name (validated against the schema when
     *                      non-null; may be {@code null} for traversal where the
     *                      intermediate vertex schema is unknown)
     * @param edgeName      edge schema name
     * @param direction     which edges to read: {@link EdgeSide#OUT},
     *                      {@link EdgeSide#IN} or {@link EdgeSide#BOTH}
     * @param propertyNames property names to project; empty means all
     */
    List<EdgeQueryResult> readEdges(Object vertexId, String vertexName, String edgeName,
                                    EdgeSide direction, List<String> propertyNames);

    /** Reads the edges of the given edge class in the given direction, projecting all properties. */
    default List<EdgeQueryResult> readEdges(Object vertexId, String vertexName, String edgeName,
                                            EdgeSide direction, String... propertyNames) {
        return readEdges(vertexId, vertexName, edgeName, direction,
                propertyNames == null ? List.of() : List.of(propertyNames));
    }

    /** Reads the edges of the given edge class in both directions. */
    default List<EdgeQueryResult> readEdges(Object vertexId, String vertexName, String edgeName,
                                            List<String> propertyNames) {
        return readEdges(vertexId, vertexName, edgeName, EdgeSide.BOTH, propertyNames);
    }

    /**
     * Reads the edges of the given edge class for many vertices. Implementations
     * may batch the storage reads into a single call; the default reads
     * sequentially. The returned list has one edge list per vertex id (same order).
     *
     * @param edgeName      edge schema name
     * @param direction     which edges to read: {@link EdgeSide#OUT}, {@link EdgeSide#IN} or {@link EdgeSide#BOTH}
     * @param vertexIds     the vertices whose attached edges are read
     * @param propertyNames property names to project; empty means all
     */
    default List<List<EdgeQueryResult>> readEdgesBatch(String edgeName, EdgeSide direction,
                                                       List<Object> vertexIds, List<String> propertyNames) {
        List<List<EdgeQueryResult>> out = new ArrayList<>(vertexIds.size());
        for (Object vertexId : vertexIds) {
            out.add(readEdges(vertexId, null, edgeName, direction, propertyNames));
        }
        return out;
    }

    /**
     * Reads the edges of <em>all</em> edge classes for many vertices (used by
     * in-memory graph algorithms that build the induced subgraph over the
     * traversed vertices). The default returns one empty list per vertex.
     */
    default List<List<EdgeQueryResult>> readAllEdgesBatch(List<Object> vertexIds, EdgeSide direction,
                                                          List<String> propertyNames) {
        List<List<EdgeQueryResult>> out = new ArrayList<>(vertexIds.size());
        for (int i = 0; i < vertexIds.size(); i++) {
            out.add(List.of());
        }
        return out;
    }

    /** Reads the edges of the given edge class in both directions, projecting all properties. */
    default List<EdgeQueryResult> readEdges(Object vertexId, String vertexName, String edgeName,
                                            String... propertyNames) {
        return readEdges(vertexId, vertexName, edgeName, EdgeSide.BOTH,
                propertyNames == null ? List.of() : List.of(propertyNames));
    }
}
