package com.pengcheng.phoenixgraph.schema.json;

import java.util.List;

/**
 * JSON DTO for an edge schema definition.
 */
public record EdgeSchemaDto(int id, String name, String description,
                            PropertyDto idSchema,
                            PropertyDto source,
                            PropertyDto target,
                            PropertyDto createdTime,
                            PropertyDto lastUpdatedTime,
                            List<PropertyDto> properties) {
}
