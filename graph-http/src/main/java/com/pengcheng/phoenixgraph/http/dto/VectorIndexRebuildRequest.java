package com.pengcheng.phoenixgraph.http.dto;

/**
 * A vector index rebuild request: which graph, vertex type and vector property to
 * rebuild. Absent values fall back to the first (and, by validation, only) vector
 * property of the vertex type.
 */
public record VectorIndexRebuildRequest(String graph, String vertexSchemaName, String vectorProperty) {
}
