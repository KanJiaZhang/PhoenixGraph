package com.pengcheng.phoenixgraph.query.storage;

/**
 * Which side of the queried vertex an edge is on.
 */
public enum EdgeSide {

    /** The queried vertex is the source of a directed edge. */
    OUT,

    /** The queried vertex is the destination of a directed edge. */
    IN,

    /** Undirected/bidirectional edge: there is no in/out distinction. */
    BOTH
}
