package com.pengcheng.phoenixgraph.schema.json;

import com.pengcheng.phoenixgraph.schema.PropertyConstraintBounds;

import java.util.List;

/**
 * JSON DTO for a {@code GraphMetaSchema} definition.
 */
public record GraphMetaSchemaDto(int id, String name, String description,
                                 int maxInDegree, int maxOutDegree, int maxDegree,
                                 List<PropertyConstraintBounds> propertyBounds) {
}
