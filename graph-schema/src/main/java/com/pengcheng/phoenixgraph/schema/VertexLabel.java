package com.pengcheng.phoenixgraph.schema;

import java.util.Set;

/**
 * Schema definition for a class of vertices sharing the same properties.
 */
public record VertexLabel(String name, Set<PropertyKey> properties) {

    public VertexLabel {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Vertex label name must not be blank");
        }
        properties = properties == null ? Set.of() : Set.copyOf(properties);
    }
}
