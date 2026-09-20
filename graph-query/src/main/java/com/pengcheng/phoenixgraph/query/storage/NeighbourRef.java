package com.pengcheng.phoenixgraph.query.storage;

/**
 * One neighbour of a vertex, as an edge hop that only wants to reach the other end needs it: which edge,
 * which side, and who is at the far end.
 *
 * <p>A vertex hop ({@code both('Payment')}) walks an edge to reach the vertex at its far end and never looks
 * at the edge's properties - the properties of the edges it walked are only of interest to a UI that draws
 * them. This record is what such a read returns: enough to resolve the neighbour's type and to name the
 * edge, and nothing that has to be decoded from the stored value.
 *
 * @param edgeName      the edge class
 * @param edgeId        the edge id
 * @param side          which side of the queried vertex the edge is on
 * @param neighbourId   the vertex at the other end
 * @param createdTime   creation timestamp (epoch millis)
 * @param lastUpdatedTime last-update timestamp (epoch millis)
 */
public record NeighbourRef(String edgeName, Object edgeId, EdgeSide side, Object neighbourId,
                           long createdTime, long lastUpdatedTime) {

    /** The same neighbour as an edge result without properties, for the callers that work with edges. */
    public EdgeQueryResult toEdgeResult() {
        return new EdgeQueryResult(edgeName, String.valueOf(edgeId), side, neighbourId, java.util.Map.of(),
                createdTime, lastUpdatedTime);
    }
}
