package com.pengcheng.phoenixgraph.storage;

import java.util.List;

/**
 * Edge storage strategy that denormalizes edges onto their source and
 * destination vertices.
 *
 * <p>Each edge is stored inline in the vertex's key/value record as a map-like
 * column (keyed by edge id), and a reverse copy is stored on the destination so
 * traversal works from either side. Edges are returned ordered by
 * {@link StoredEdge#lastModified()} (most recent first).
 *
 * <p>The store retains at most {@link #maxOutgoingEdges()} outgoing edges per
 * vertex and {@link #maxIncomingEdges()} incoming edges per vertex. When adding
 * an edge exceeds a limit, the oldest edges (by lastModified) are evicted from
 * both the forward and reverse columns.
 */
public interface AdjacentEdgeStore {

    /** Column holding outgoing edges (on the source vertex). */
    String OUT_EDGES_COLUMN = "__out_edges";

    /** Column holding incoming edges (on the destination vertex). */
    String IN_EDGES_COLUMN = "__in_edges";

    /** Maximum outgoing edges retained per vertex; {@code <= 0} means unlimited. */
    int maxOutgoingEdges();

    /** Maximum incoming edges retained per vertex; {@code <= 0} means unlimited. */
    int maxIncomingEdges();

    /** Stores the edge on its source (outgoing) and destination (incoming), trimming if needed. */
    void addEdge(StoredEdge edge);

    /** Returns outgoing edges of the vertex, sorted by lastModified descending. */
    List<StoredEdge> outgoingEdges(Object vertexId);

    /** Returns incoming edges of the vertex, sorted by lastModified descending. */
    List<StoredEdge> incomingEdges(Object vertexId);

    /** Removes the edge from both the source and destination vertices. */
    void removeEdge(Object source, Object destination, Object edgeId);
}
