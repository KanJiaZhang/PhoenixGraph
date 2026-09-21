package com.pengcheng.phoenixgraph.schema.json;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * JSON DTO for a vertex schema definition.
 *
 * <p>{@code displayName} is {@code "$id"} when the vertex is labelled by its id instead of by its
 * display properties ({@code null} / absent = use the display properties).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VertexSchemaDto(int id, String name, String description,
                              PropertyDto idSchema,
                              PropertyDto createdTime,
                              PropertyDto lastUpdatedTime,
                              List<PropertyDto> properties,
                              String displayName) {
}
