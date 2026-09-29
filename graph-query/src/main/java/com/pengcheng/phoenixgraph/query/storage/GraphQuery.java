package com.pengcheng.phoenixgraph.query.storage;

import com.pengcheng.phoenixgraph.query.QueryException;
import com.pengcheng.phoenixgraph.storage.TimeRange;
import com.pengcheng.phoenixgraph.schema.geo.GeoHit;
import com.pengcheng.phoenixgraph.schema.geo.GeoSearch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
     * Reads the edges of a class at a vertex, keeping only what the conditions accept, and at
     * most {@code maxResults} of them ({@code 0} = no cap).
     *
     * <p>Both arguments are what a traversal knows before the read and the store can use while it
     * decodes: {@code outE('Payment').has('status','SUCCESS')} never needs the properties of a
     * payment that fails the condition, and {@code outE('Payment').limit(10)} never needs more
     * than the ten newest payments. The cap applies <b>after</b> the conditions, and the edges
     * come back in the same order the uncapped read returns them, so the answer is the one the
     * steps would have produced.
     *
     * <p>The default applies both to what {@link #readEdges(Object, String, String, EdgeSide, List)}
     * returns, which keeps every backend correct; a store that can do better overrides it.
     */
    default List<EdgeQueryResult> readEdges(Object vertexId, String vertexName, String edgeName,
                                            EdgeSide direction, List<String> propertyNames,
                                            List<EdgePropertyFilter> filters, int maxResults) {
        List<EdgeQueryResult> edges = readEdges(vertexId, vertexName, edgeName, direction, propertyNames);
        return applyFilters(edges, filters, maxResults);
    }

    /**
     * Batched form of the filtered read: conditions and cap apply to the edges of every vertex,
     * each vertex on its own (this is a per-vertex read, not a shared budget).
     */
    default List<List<EdgeQueryResult>> readEdgesBatch(String edgeName, EdgeSide direction,
                                                       List<Object> vertexIds, List<String> propertyNames,
                                                       List<EdgePropertyFilter> filters, int maxResults) {
        List<List<EdgeQueryResult>> out = new ArrayList<>(vertexIds.size());
        for (Object vertexId : vertexIds) {
            out.add(readEdges(vertexId, null, edgeName, direction, propertyNames, filters, maxResults));
        }
        return out;
    }

    /**
     * Counts the edges of a class at a vertex that satisfy the conditions, without materialising
     * them. The default counts what the filtered read returns; a store that keeps its edges in one
     * record can answer from the record's shape and never build an edge.
     */
    default long countEdges(Object vertexId, String vertexName, String edgeName, EdgeSide direction,
                            List<EdgePropertyFilter> filters) {
        if (filters == null || filters.isEmpty()) {
            return countEdges(vertexId, vertexName, edgeName, direction);
        }
        return readEdges(vertexId, vertexName, edgeName, direction, List.of(), filters, 0).size();
    }

    /** Applies conditions and a cap to already read edges (the shared body of the defaults). */
    static List<EdgeQueryResult> applyFilters(List<EdgeQueryResult> edges, List<EdgePropertyFilter> filters,
                                              int maxResults) {
        List<EdgeQueryResult> kept = edges;
        if (filters != null && !filters.isEmpty()) {
            kept = new ArrayList<>(edges.size());
            for (EdgeQueryResult edge : edges) {
                boolean matches = true;
                for (EdgePropertyFilter filter : filters) {
                    if (!filter.test(edge.properties())) {
                        matches = false;
                        break;
                    }
                }
                if (matches) {
                    kept.add(edge);
                }
            }
        }
        if (maxResults > 0 && kept.size() > maxResults) {
            return List.copyOf(kept.subList(0, maxResults));
        }
        return kept == edges ? edges : List.copyOf(kept);
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

    /**
     * Counts the edges of a class at many vertices, without materialising them.
     *
     * <p>A traversal that counts over a set of vertices reaches far more sources than edges per source -
     * a hub's hundred accounts, each with a handful of links - so counting them one by one costs one round
     * trip per vertex however cheap each count is. A store that can read many records at once (a native
     * batch get) answers the whole set with one trip per batch, counting from the shape of each bin.
     *
     * <p>The default adds up {@link #countEdges(Object, String, String, EdgeSide, List)}, which every
     * backend supports.
     */
    default long countEdgesBatch(String edgeName, EdgeSide direction, List<Object> vertexIds,
                                 List<EdgePropertyFilter> filters) {
        long total = 0;
        for (Object vertexId : vertexIds) {
            total += filters == null || filters.isEmpty()
                    ? countEdges(vertexId, null, edgeName, direction)
                    : countEdges(vertexId, null, edgeName, direction, filters);
        }
        return total;
    }

    /**
     * Whether the store evaluates the conditions and the cap of an edge read while it decodes.
     *
     * <p>{@code true} lets a traversal hand
     * {@link #readEdges(Object, String, String, EdgeSide, List, List, int)} (and the count with
     * conditions) to the store, which is where the saving is: an edge that fails a condition is
     * never built. A store that answers {@code false} gets the plain reads and the traversal
     * applies the conditions itself, exactly as it did before the capability existed - so this is
     * an optimisation a backend opts into, not a requirement.
     */
    default boolean supportsEdgeReadPushdown() {
        return false;
    }

    /**
     * Reads only the neighbours of a vertex: the edges of a class that reach another vertex, without the
     * edges themselves being built.
     *
     * <p>A vertex hop ({@code both('Payment')}) needs the id at the far end of each edge and nothing else -
     * the properties of the edges it walked matter to a UI that draws them, not to the traversal. A store
     * whose edges are fields inside a record can therefore answer this from the edge's header (id, peer,
     * timestamps) instead of decoding a value it will throw away, which is what
     * {@code storage.edge.decoded} counts.
     *
     * <p>The default reads the edges and keeps their identity, which every backend supports, so a store
     * that can do better overrides it.
     */
    default List<NeighbourRef> readNeighbours(Object vertexId, String vertexName, String edgeName,
                                              EdgeSide direction) {
        List<EdgeQueryResult> edges = readEdges(vertexId, vertexName, edgeName, direction, List.of());
        List<NeighbourRef> neighbours = new ArrayList<>(edges.size());
        for (EdgeQueryResult edge : edges) {
            neighbours.add(new NeighbourRef(edge.edgeName(), edge.edgeId(), edge.side(),
                    edge.otherVertexId(), edge.createdTime(), edge.lastUpdatedTime()));
        }
        return neighbours;
    }

    /**
     * Whether {@link #readNeighbours} is cheaper than reading the edges ({@code true} where the store can
     * answer it from the header of each stored edge instead of decoding a value).
     *
     * <p>Like {@link #supportsEdgeReadPushdown()}, this is opt-in: a traversal asks before it takes that
     * path, so a store (or a test double) that does not declare it keeps the plain edge read.
     */
    default boolean supportsNeighbourRead() {
        return false;
    }

    /**
     * Batched form of {@link #readNeighbours}: one neighbour list per vertex id, in the request order.
     * The default reads them one by one.
     */
    default List<List<NeighbourRef>> readNeighboursBatch(String edgeName, EdgeSide direction,
                                                         List<Object> vertexIds) {
        List<List<NeighbourRef>> out = new ArrayList<>(vertexIds.size());
        for (Object vertexId : vertexIds) {
            out.add(readNeighbours(vertexId, null, edgeName, direction));
        }
        return out;
    }

    /**
     * Whether {@link #readEdgesWithin} narrows the read (a big vertex may need a window).
     */
    default boolean supportsEdgeTimeRange() {
        return false;
    }

    /**
     * Whether the store can walk a whole <b>type</b> instead of one record at a time: every vertex
     * of a vertex type ({@link #scanVertices}) and every edge of an edge class ({@link #scanEdges}).
     *
     * <p>A question that starts from a class rather than from an id - {@code g.V('Account')},
     * {@code g.E('payment').pagerank(DIST)} - has no key to ask a keyed store for. This is therefore
     * the port an analytical engine wants and a record store does not have: Aerospike answers
     * "the record with this key", RocksDB-Edge holds one ordered table per type and answers a class
     * with one iterator per table.
     *
     * <p>Like {@link #supportsEdgeReadPushdown()}, asking is the caller's job: a traversal checks
     * this and reports what is missing, rather than getting an exception from the middle of a scan.
     */
    default boolean supportsScan() {
        return false;
    }

    /**
     * Reads every vertex of one type, in storage order, at most {@code maxResults} of them
     * ({@code 0} = all).
     *
     * <p>An empty property list means every declared property, exactly as in a single read - a scan
     * has no id to fall back on, so it always touches the records it walks past.
     *
     * @param vertexName    the vertex type to walk
     * @param propertyNames the properties to project; empty means all
     * @param maxResults    how many vertices to stop after ({@code 0} = no cap)
     */
    default List<VertexQueryResult> scanVertices(String vertexName, List<String> propertyNames,
                                                 int maxResults) {
        throw new QueryException("Scanning a vertex type is not supported by "
                + getClass().getSimpleName());
    }

    /** Reads every vertex of one type. */
    default List<VertexQueryResult> scanVertices(String vertexName, List<String> propertyNames) {
        return scanVertices(vertexName, propertyNames, 0);
    }

    /**
     * The edges of one class with the given ids, each with both endpoints, in the order asked for.
     *
     * <p>An edge is identified by its class, its two endpoints and its creation time, and a caller only
     * knows its id: a store can answer this from an id index, and one that keeps none can only walk the
     * class. The default walks it <b>once for the whole request</b> (not once per id) and keeps the rows
     * whose id was asked for, which is what {@code g.E(id, 'Class')} means on a store without an index; a
     * store with one overrides this and answers directly.
     *
     * <p>The answer is in the order of {@code edgeIds}, and an id that is not there is simply absent (an
     * id that names no edge of this class is an empty answer, not a failure).
     *
     * @param edgeName      the edge class to look in
     * @param edgeIds      the edge ids to find
     * @param propertyNames the properties to project; empty means all
     */
    default List<ScannedEdge> readEdgesByIds(String edgeName, List<Object> edgeIds,
                                             List<String> propertyNames) {
        if (edgeIds == null || edgeIds.isEmpty()) {
            return List.of();
        }
        if (!supportsScan()) {
            throw new QueryException("g.E(id, '" + edgeName + "') needs an edge id index, which "
                    + getClass().getSimpleName() + " does not keep: it can read the edges of a vertex, not"
                    + " find one by its id. Use a store whose tables are ordered ranges (the RocksDB-Edge"
                    + " scheme walks a class), or walk the edges from a vertex instead.");
        }
        Set<String> wanted = new LinkedHashSet<>();
        for (Object id : edgeIds) {
            wanted.add(String.valueOf(id));
        }
        Map<String, ScannedEdge> found = new LinkedHashMap<>();
        for (ScannedEdge edge : scanEdges(edgeName, propertyNames, 0)) {
            String id = String.valueOf(edge.edge().edgeId());
            if (wanted.contains(id)) {
                found.putIfAbsent(id, edge);
            }
        }
        List<ScannedEdge> out = new ArrayList<>(edgeIds.size());
        for (Object id : edgeIds) {
            ScannedEdge edge = found.get(String.valueOf(id));
            if (edge != null) {
                out.add(edge);
            }
        }
        return out;
    }

    /**
     * Reads every edge of one class, in storage order, at most {@code maxResults} of them
     * ({@code 0} = all).
     *
     * <p>Each edge is read once and reported with both endpoints ({@link ScannedEdge}), because a
     * scan has no vertex to name the far side against; an edge class stored on both endpoints is
     * read on one of them (the outgoing row of a directed class) so a scan does not see it twice.
     *
     * @param edgeName      the edge class to walk
     * @param propertyNames the properties to project; empty means all
     * @param maxResults    how many edges to stop after ({@code 0} = no cap)
     */
    default List<ScannedEdge> scanEdges(String edgeName, List<String> propertyNames, int maxResults) {
        throw new QueryException("Scanning an edge class is not supported by "
                + getClass().getSimpleName());
    }

    /** Reads every edge of one class. */
    default List<ScannedEdge> scanEdges(String edgeName, List<String> propertyNames) {
        return scanEdges(edgeName, propertyNames, 0);
    }

    /**
     * Counts every edge of one class without materialising it.
     *
     * <p>The default counts what {@link #scanEdges} returns; a store whose edge table is the edge
     * list itself can count the rows instead of decoding them.
     */
    default long countScanEdges(String edgeName) {
        return scanEdges(edgeName, List.of()).size();
    }

    /** Counts every vertex of one type without materialising it. */
    default long countScanVertices(String vertexName) {
        return scanVertices(vertexName, List.of()).size();
    }

    /**
     * Whether {@link #scanVerticesPartition} / {@link #scanEdgesPartition} can split a scan: a store
     * whose rows live in ordered ranges can hand each range to a mapper of its own, which is what turns
     * {@code g.V(DIST).has('Account','accountStatus','FROZEN')} into one scan per worker instead of one
     * scan for the whole table.
     *
     * <p>Like {@link #supportsScan()}, asking is the caller's job; a traversal reports that the flag
     * needs a partitioned store instead of failing inside a mapper.
     */
    default boolean supportsPartitionedScan() {
        return false;
    }

    /**
     * How many partitions a distributed scan uses. One mapper per partition, so this is the width the
     * scan can be spread over - a store that keeps its rows in {@code regionCount} physically separate
     * key ranges answers with that count.
     */
    default int scanPartitionCount() {
        return 0;
    }

    /**
     * Walks one partition of a vertex type: the same rows {@link #scanVertices} would return, cut into
     * {@code partitions} disjoint slices, of which this call hands over slice {@code partition}.
     *
     * <p>The slices must cover every row exactly once - the caller concatenates them in partition order
     * and treats the result as the scan - so a store reports how it splits (a hash range, a region, a
     * table's pages) and keeps the union stable across calls.
     *
     * @param vertexName    the vertex type to walk
     * @param propertyNames the properties to project; empty means all
     * @param partition     the slice to hand over, in {@code [0, partitions)}
     * @param partitions    how many slices the scan is cut into
     * @param consumer      receives the vertices of the slice, in storage order
     */
    default void scanVerticesPartition(String vertexName, List<String> propertyNames, int partition,
                                       int partitions, java.util.function.Consumer<VertexQueryResult> consumer) {
        throw new QueryException("Scanning a vertex type in partitions is not supported by "
                + getClass().getSimpleName());
    }

    /**
     * Walks one partition of an edge class, the edge-side twin of
     * {@link #scanVerticesPartition(String, List, int, int, java.util.function.Consumer)}.
     *
     * @param maxResults how many edges of the slice to stop after ({@code 0} = no cap)
     */
    default void scanEdgesPartition(String edgeName, List<String> propertyNames, int partition,
                                    int partitions, int maxResults,
                                    java.util.function.Consumer<ScannedEdge> consumer) {
        throw new QueryException("Scanning an edge class in partitions is not supported by "
                + getClass().getSimpleName());
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

    /**
     * Finds the vertices of one type whose full-text property contains the term, from the
     * inverted index declared with {@code isFullText} on the property.
     *
     * <p>This is the full-text twin of {@link #geoNear}: it is not reached through a vertex or
     * an edge, it asks the inverted index of a type where the matching elements are. The answer
     * is a <em>candidate</em> set — the ids whose index tokens cover the term's tokens — so the
     * caller must still verify each candidate against the stored text (character intersection
     * alone does not guarantee the characters are contiguous).
     *
     * @param vertexName   the vertex type holding the text
     * @param propertyName the full-text indexed property
     * @param term         the substring to find ({@code containing(term)})
     * @return the candidate vertex ids, in storage order (possibly empty)
     */
    default List<Object> fullTextSearch(String vertexName, String propertyName, String term) {
        throw new QueryException("Full-text search is not supported by " + getClass().getSimpleName());
    }

    /** Whether {@link #fullTextSearch} is implemented (used to decide whether a predicate can use it). */
    default boolean supportsFullTextSearch() {
        return false;
    }
}
