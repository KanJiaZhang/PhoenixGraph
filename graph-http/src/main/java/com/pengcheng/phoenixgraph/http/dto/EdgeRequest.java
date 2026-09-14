package com.pengcheng.phoenixgraph.http.dto;

import java.util.List;

/**
 * An edge upsert request.
 *
 * @param edgeId          edge id
 * @param edgeSchemaName  name of the {@code EdgeSchema} this edge conforms to
 * @param source          source vertex id
 * @param destination     destination vertex id
 * @param properties      properties to write
 * @param createdTime     edge creation timestamp (epoch millis), optional
 * @param lastUpdatedTime edge last-update timestamp (epoch millis), optional
 */
public record EdgeRequest(Object edgeId, String edgeSchemaName, Object source, Object destination,
                          List<EdgePropertyRequest> properties, Long createdTime, Long lastUpdatedTime) {
}
