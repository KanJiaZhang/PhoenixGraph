package com.pengcheng.phoenixgraph.schema.json;

import com.pengcheng.phoenixgraph.schema.PropertyType;

/**
 * JSON DTO for a property definition.
 */
public record PropertyDto(int id, String name, String description, PropertyType type) {
}
