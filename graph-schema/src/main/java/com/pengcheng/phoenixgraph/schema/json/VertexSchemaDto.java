package com.pengcheng.phoenixgraph.schema.json;

import java.util.List;

/**
 * JSON DTO for a vertex schema definition.
 */
public record VertexSchemaDto(int id, String name, String description,
                              PropertyDto idSchema,
                              PropertyDto createdTime,
                              PropertyDto lastUpdatedTime,
                              List<PropertyDto> properties) {
}
