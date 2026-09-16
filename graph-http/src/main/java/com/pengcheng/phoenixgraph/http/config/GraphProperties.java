package com.pengcheng.phoenixgraph.http.config;

/**
 * One graph served by the HTTP service: the schema name and the Aerospike vertex
 * set holding its data.
 *
 * <p>Configured under {@code phoenixgraph.storage.graphs}; the primary graph is
 * configured by {@code phoenixgraph.storage.schema} / {@code .vertex-set}.
 */
public record GraphProperties(String schema, String vertexSet) {
}
