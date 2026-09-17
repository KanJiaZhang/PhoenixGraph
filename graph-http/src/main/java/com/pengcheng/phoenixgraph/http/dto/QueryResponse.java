package com.pengcheng.phoenixgraph.http.dto;

import com.pengcheng.phoenixgraph.query.result.Subgraph;

/**
 * A query answer together with the subgraph the query visited, returned when a
 * request asks for both ({@code returnSubgraph} and {@code includeResult}).
 *
 * <p>The UI uses it to draw the graph <em>and</em> to show the computed value - a
 * {@code count()} has no visible graph of its own, a two-hop walk has no single
 * value, and most queries are useful with both.
 *
 * @param result   the final traversers of a Gremlin query, or the
 *                 {@code columns}/{@code rows} of a Cypher one
 * @param subgraph the vertices and edges the query touched
 */
public record QueryResponse(Object result, Subgraph subgraph) {
}
