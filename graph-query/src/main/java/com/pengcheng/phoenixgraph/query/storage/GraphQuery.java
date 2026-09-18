package com.pengcheng.phoenixgraph.query.storage;

import com.pengcheng.phoenixgraph.query.QueryException;
import com.pengcheng.phoenixgraph.storage.TimeRange;
import com.pengcheng.phoenixgraph.schema.geo.GeoHit;
import com.pengcheng.phoenixgraph.schema.geo.GeoSearch;

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

    /**
     * Reads the edges of one class attached to a vertex <b>inside a creation-time window</b>.
     *
     * <p>A backend whose edges are stored in aggregated tables (a vertex with a hundred million
     * edges cannot live in one record) can only answer a bounded read - and may refuse an
     * unbounded one with a message that says to add {@code createdBetween(...)}. Implementations
     * without such tables simply ignore the window and delegate to
     * {@link #readEdges(Object, String, String, EdgeSide, List)}.
     *
     * @param range the window a big vertex needs; {@link TimeRange#ALL} means "no bound"
     */
    default List<EdgeQueryResult> readEdgesWithin(Object vertexId, String vertexName, String edgeName,
                                                  EdgeSide direction, TimeRange range,
                                                  List<String> propertyNames) {
        return readEdges(vertexId, vertexName, edgeName, direction, propertyNames);
    }

    /**
     * Counts the edges of one class at a vertex without materialising them.
     *
     * <p>Counting is the most common aggregation a dashboard asks for, and a store that keeps the
     * edges in one record can answer it from the record's shape - so this is a separate call
     * rather than {@code readEdges(...).size()}. The default reads and counts, which every
     * implementation supports; stores that can do better override it.
     */
    default long countEdges(Object vertexId, String vertexName, String edgeName, EdgeSide direction) {
        return readEdges(vertexId, vertexName, edgeName, direction, List.of()).size();
    }

    /** Whether {@link #readEdgesWithin} narrows the read (a big vertex may need a window). */
    default boolean supportsEdgeTimeRange() {
        return false;
    }

    /**
     * Windowed read that keeps at most {@code maxResults} edges ({@code 0} = all of them).
     *
     * <p>The cap is what makes "expand this hub in the UI" cheap: the aggregated tables are
     * ordered by creation time, so a capped read stops as soon as it has that many edges
     * instead of materialising a year of a hub's history.
     */
    default List<EdgeQueryResult> readEdgesWithin(Object vertexId, String vertexName, String edgeName,
                                                  EdgeSide direction, TimeRange range,
                                                  List<String> propertyNames, int maxResults) {
        List<EdgeQueryResult> edges = readEdgesWithin(vertexId, vertexName, edgeName, direction, range,
                propertyNames);
        if (maxResults <= 0 || edges.size() <= maxResults) {
            return edges;
        }
        return List.copyOf(edges.subList(0, maxResults));
    }

    /**
     * Batched form of {@link #readEdgesWithin}: the window applies to every vertex of the batch.
     * The default reads them one by one; a backend whose tables are already ordered by time can
     * override it with something smarter.
     */
    default List<List<EdgeQueryResult>> readEdgesBatchWithin(String edgeName, EdgeSide direction,
                                                             TimeRange range, List<Object> vertexIds,
                                                             List<String> propertyNames) {
        return readEdgesBatchWithin(edgeName, direction, range, vertexIds, propertyNames, 0);
    }

    /** Batched form of {@link #readEdgesWithin(Object, String, String, EdgeSide, TimeRange, List, int)}. */
    default List<List<EdgeQueryResult>> readEdgesBatchWithin(String edgeName, EdgeSide direction,
                                                             TimeRange range, List<Object> vertexIds,
                                                             List<String> propertyNames, int maxResults) {
        List<List<EdgeQueryResult>> out = new ArrayList<>(vertexIds.size());
        for (Object vertexId : vertexIds) {
            out.add(readEdgesWithin(vertexId, null, edgeName, direction, range, propertyNames, maxResults));
        }
        return out;
    }

    /**
     * Batched form of {@link #readEdgesWithin} over every edge class of the graph. The default
     * ignores the window (a backend without aggregated tables reads the same either way); a
     * backend whose window changes the answer overrides it.
     */
    default List<List<EdgeQueryResult>> readAllEdgesBatchWithin(List<Object> vertexIds, EdgeSide direction,
                                                               TimeRange range, List<String> propertyNames) {
        return readAllEdgesBatchWithin(vertexIds, direction, range, propertyNames, 0);
    }

    /**
     * Capped form of the all-classes window read. The cap applies <b>per edge class</b> (a class
     * is what one read covers), which already bounds what a UI expansion collects.
     */
    default List<List<EdgeQueryResult>> readAllEdgesBatchWithin(List<Object> vertexIds, EdgeSide direction,
                                                               TimeRange range, List<String> propertyNames,
                                                               int maxResults) {
        return readAllEdgesBatchWithin(vertexIds, direction, range, propertyNames);
    }

    /**
     * Finds the vertices of one type whose geo property lies inside a circle, nearest first.
     *
     * <p>This is the one read that is not reached through a vertex or an edge: a radius query
     * asks the geo index of a type where its points are, which is why it is a port of its own.
     * Implementations that index geo points answer it from the index; the default refuses with
     * a clear message rather than scanning.
     *
     * @param vertexName   the vertex type holding the points
     * @param propertyName the geo property
     * @param search       the centre, the radius and the search budget
     * @return the hits inside the radius, nearest first (possibly empty)
     */
    default List<GeoHit> geoNear(String vertexName, String propertyName, GeoSearch search) {
        throw new QueryException("Geo search is not supported by " + getClass().getSimpleName());
    }

    /** Whether {@link #geoNear} is implemented (used to explain a missing index in an error). */
    default boolean supportsGeoSearch() {
        return false;
    }
}
