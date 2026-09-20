package com.pengcheng.phoenixgraph.query.storage;

import java.util.Map;

/**
 * An edge as a whole-class scan reads it: <b>both</b> of its endpoints, plus the stored record.
 *
 * <p>A per-vertex read knows one endpoint - the vertex it was asked about - and reports the other one
 * as {@link EdgeQueryResult#otherVertexId()}. A scan has no such vertex, so it reports the pair.
 * Which of the two is the source follows the stored direction of the edge class: for a directed
 * class a scan returns the outgoing reading of each edge ({@code source} is the vertex the edge
 * leaves), for an undirected one it is whichever endpoint the row was found at.
 *
 * @param source the first endpoint (the source of a directed edge)
 * @param target the second endpoint (the destination of a directed edge)
 * @param edge   the stored edge, as one of its rows holds it
 */
public record ScannedEdge(Object source, Object target, EdgeQueryResult edge) {

    public ScannedEdge {
        if (source == null || target == null || edge == null) {
            throw new IllegalArgumentException("A scanned edge needs both endpoints and its record");
        }
    }

    /** The edge class name. */
    public String edgeName() {
        return edge.edgeName();
    }

    /** The edge id. */
    public String edgeId() {
        return edge.edgeId();
    }

    /** The projected properties of the edge. */
    public Map<String, Object> properties() {
        return edge.properties();
    }

    public long createdTime() {
        return edge.createdTime();
    }

    public long lastUpdatedTime() {
        return edge.lastUpdatedTime();
    }

    /** The endpoint that is not {@code vertexId}, or {@code null} when it is neither. */
    public Object other(Object vertexId) {
        if (source.equals(vertexId)) {
            return target;
        }
        return target.equals(vertexId) ? source : null;
    }
}
