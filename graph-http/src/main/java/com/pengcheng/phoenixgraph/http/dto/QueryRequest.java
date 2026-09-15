package com.pengcheng.phoenixgraph.http.dto;

import java.util.Map;

/**
 * A Gremlin traversal query request.
 *
 * @param graphName the graph schema name used to resolve vertex/edge/property names
 * @param query     the Gremlin query string; {@code ${paramName}} placeholders are
 *                  replaced with the matching value from {@code params}
 * @param params    optional substitution parameters
 */
public record QueryRequest(String graphName, String query, Map<String, String> params, boolean returnSubgraph) {
}
