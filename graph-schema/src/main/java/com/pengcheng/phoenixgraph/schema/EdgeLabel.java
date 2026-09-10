package com.pengcheng.phoenixgraph.schema;

import java.util.Set;

/**
 * Schema definition for a class of edges sharing the same properties.
 */
public record EdgeLabel(String name, Set<PropertyKey> properties, boolean directed) {

    public EdgeLabel {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Edge label name must not be blank");
        }
        properties = properties == null ? Set.of() : Set.copyOf(properties);
    }

    public EdgeLabel(String name, Set<PropertyKey> properties) {
        this(name, properties, true);
    }
}
