package com.pengcheng.phoenixgraph.storage;

import java.util.List;

/**
 * Edge storage strategy that denormalizes edges onto their source and
 * destination vertices.
 *
 * <p>Each edge is stored inline in the vertex's key/value record, keyed by the
 * edge's {@link StoredEdge#edgeSchemaId() edge schema id} and direction, and a
 * reverse copy is stored on the destination so traversal works from either
 * side. Edges are returned ordered by {@link StoredEdge#lastUpdatedTime()} (most
 * recent first).
 *
 * <p>The maximum number of edges retained per vertex is declared by the edge
 * schema metadata ({@code maxOutgoingEdges} / {@code maxIncomingEdges} for
 * DIRECTED edges, or the single {@code maxDegree} for UNDIRECTED/BIDIRECTIONAL
 * edges), not hard-coded in the store.
 */
public interface AdjacentEdgeStore {

    /** Column holding outgoing edges (on the source vertex). */
    String OUT_EDGES_COLUMN = "__out_edges";

    /** Column holding incoming edges (on the destination vertex). */
    String IN_EDGES_COLUMN = "__in_edges";

    /** Stores the edge on its source (outgoing) and destination (incoming), trimming if needed. */
    void addEdge(StoredEdge edge);

    /** Returns outgoing edges of the given edge schema of the vertex, sorted by lastUpdatedTime descending. */
    List<StoredEdge> outgoingEdges(Object vertexId, int edgeSchemaId);

    /** Returns incoming edges of the given edge schema of the vertex, sorted by lastUpdatedTime descending. */
    List<StoredEdge> incomingEdges(Object vertexId, int edgeSchemaId);

    /** Removes the edge from both the source and destination vertices. */
    void removeEdge(Object source, Object destination, int edgeSchemaId, Object edgeId);
}
