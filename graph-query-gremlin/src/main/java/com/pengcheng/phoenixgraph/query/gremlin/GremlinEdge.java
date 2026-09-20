package com.pengcheng.phoenixgraph.query.gremlin;

import com.pengcheng.phoenixgraph.query.storage.EdgeSide;

import java.util.Map;

/**
 * An edge traverser.
 *
 * @param label           edge schema name
 * @param id              edge id
 * @param side            which side of the queried vertex the edge is on
 * @param otherVertexId   the endpoint on the other side (the reached neighbour)
 * @param properties      projected property values keyed by name
 * @param createdTime     creation timestamp (epoch millis)
 * @param lastUpdatedTime last-update timestamp (epoch millis)
 * @param sourceVertexId  the endpoint the edge was found at when it came from a scan of the whole
 *                        class ({@code g.E('Payment')}); {@code null} when the edge was reached from
 *                        a vertex, where the traverser already knows where it came from and only the
 *                        far side is carried
 */
public record GremlinEdge(String label, Object id, EdgeSide side, Object otherVertexId,
                          Map<String, Object> properties,
                          long createdTime, long lastUpdatedTime, Object sourceVertexId)
        implements GremlinElement {

    /** An edge reached from a vertex (only the far endpoint is known here). */
    public GremlinEdge(String label, Object id, EdgeSide side, Object otherVertexId,
                       Map<String, Object> properties, long createdTime, long lastUpdatedTime) {
        this(label, id, side, otherVertexId, properties, createdTime, lastUpdatedTime, null);
    }

    /**
     * Both endpoints of the edge, in the order the traversal knows them: the one it was found at
     * first, then the far one. Either may be missing for an edge built out of a condition, where only
     * the properties matter.
     */
    public Object[] endpoints() {
        if (sourceVertexId == null) {
            return new Object[] { otherVertexId };
        }
        return new Object[] { sourceVertexId, otherVertexId };
    }
}
