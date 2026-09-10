package com.pengcheng.phoenixgraph.query;

import com.pengcheng.phoenixgraph.core.Graph;

/**
 * Executes queries written in a supported query language against a {@link Graph}.
 */
public interface QueryEngine {

    /** Query language dialect supported by this engine (e.g. "cypher", "gremlin"). */
    String language();

    /** Parses, plans and executes the given query against the graph. */
    QueryResult execute(Graph graph, String query);
}
