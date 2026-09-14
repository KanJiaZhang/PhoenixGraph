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
 */
public record GremlinEdge(String label, Object id, EdgeSide side, Object otherVertexId,
                          Map<String, Object> properties,
                          long createdTime, long lastUpdatedTime) implements GremlinElement {
}
