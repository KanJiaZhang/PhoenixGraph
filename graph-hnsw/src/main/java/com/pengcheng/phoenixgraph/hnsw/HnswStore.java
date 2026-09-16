package com.pengcheng.phoenixgraph.hnsw;

import java.util.List;

/**
 * Port for the layered neighbour lists of an HNSW index.
 *
 * <p>Layer {@code l} is the bidirectional edge class {@code vector_<l>} between
 * two vertices of the indexed type (see {@link HnswLevels#edgeName(String, int)}), so a
 * link is visible from both endpoints and reading a vertex's neighbours on a
 * layer is a single graph read.
 *
 * <p>A read-only implementation (e.g. one backed by the query layer) may leave
 * {@link #link} / {@link #unlink} to throw; that is all a search needs.
 */
public interface HnswStore {

    /** Neighbours of {@code vertexId} on {@code level} (empty when the vertex has none). */
    List<HnswNeighbor> neighbours(Object vertexId, int level);

    /** Neighbours of several vertices on one layer, in the requested order. */
    default List<List<HnswNeighbor>> neighboursBatch(List<Object> vertexIds, int level) {
        return vertexIds.stream().map(id -> neighbours(id, level)).toList();
    }

    /** Creates the bidirectional link {@code from <-> to} on {@code level} if absent. */
    default void link(Object from, Object to, int level) {
        linkAll(from, List.of(to), level);
    }

    /** Creates several bidirectional links from one vertex on {@code level} (batched by the store). */
    void linkAll(Object from, List<Object> to, int level);

    /**
     * Creates several links, possibly on different layers, in one call. The default
     * loops; stores that can aggregate the writes (one read-modify-write per touched
     * vertex bin) should override it - an insert produces tens of links at once.
     */
    default void linkBatch(List<HnswLink> links) {
        for (HnswLink link : links) {
            link(link.from(), link.to(), link.level());
        }
    }

    /** Removes several links, possibly on different layers, in one call. */
    default void unlinkBatch(List<HnswUnlink> unlinks) {
        for (HnswUnlink unlink : unlinks) {
            unlink(unlink.edgeId(), unlink.from(), unlink.to(), unlink.level());
        }
    }

    /** Removes the link {@code from <-> to} (identified by {@code edgeId}) on {@code level}. */
    void unlink(String edgeId, Object from, Object to, int level);

    /**
     * Id used for a link. Derived from the inserting endpoint so both directions of
     * the same pair resolve to the same edge id (the edge is stored as one
     * bidirectional edge on both endpoints).
     */
    default String edgeId(Object from, Object to, int level) {
        return "v" + level + ":" + from + ":" + to;
    }
}
