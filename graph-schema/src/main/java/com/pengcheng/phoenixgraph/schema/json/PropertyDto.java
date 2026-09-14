package com.pengcheng.phoenixgraph.schema.json;

import com.pengcheng.phoenixgraph.schema.PropertyType;

/**
 * JSON DTO for a property definition.
 *
 * <p>{@code min}/{@code max} and {@code minLength}/{@code maxLength} carry the
 * optional value-level constraints ({@code null} = unconstrained). {@code vertex}
 * carries the vertex schema name for an edge endpoint property ({@code null} for
 * regular properties).
 */
public record PropertyDto(int id, String name, String description, PropertyType type,
                          Number min, Number max, Integer minLength, Integer maxLength,
                          String vertex) {

    public PropertyDto(int id, String name, String description, PropertyType type) {
        this(id, name, description, type, null, null, null, null, null);
    }
}
