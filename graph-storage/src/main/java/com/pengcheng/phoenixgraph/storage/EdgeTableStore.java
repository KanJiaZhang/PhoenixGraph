package com.pengcheng.phoenixgraph.storage;

import java.util.Collection;

/**
 * Edge storage strategy backed by a separate edge table.
 *
 * <p>The edge id is the key and edge properties are stored as columns. Source
 * and destination indexes enable traversal in both directions. This strategy is
 * simple but less efficient for traversals than {@link AdjacentEdgeStore}.
 */
public interface EdgeTableStore {

    void put(StoredEdge edge);

    StoredEdge get(Object edgeId);

    void remove(Object edgeId);

    /** Edges whose source is the given vertex. */
    Collection<StoredEdge> edgesBySource(Object source);

    /** Edges whose destination is the given vertex. */
    Collection<StoredEdge> edgesByDestination(Object destination);
}
