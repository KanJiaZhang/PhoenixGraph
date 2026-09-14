package com.pengcheng.phoenixgraph.http.dto;

import java.util.List;

/**
 * A vertex upsert request.
 *
 * @param id                vertex id
 * @param vertexSchemaName  name of the {@code VertexSchema} this vertex conforms to
 * @param properties        properties to write
 * @param createdTime       vertex creation timestamp (epoch millis), optional
 * @param lastUpdatedTime   vertex last-update timestamp (epoch millis), optional
 */
public record VertexRequest(Object id, String vertexSchemaName, List<VertexPropertyRequest> properties,
                            Long createdTime, Long lastUpdatedTime) {
}
