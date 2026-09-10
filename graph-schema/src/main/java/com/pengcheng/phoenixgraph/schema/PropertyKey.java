package com.pengcheng.phoenixgraph.schema;

import java.util.Objects;

/**
 * A named, typed property definition shared across vertex and edge labels.
 */
public record PropertyKey(String name, DataType dataType, Cardinality cardinality) {

    public PropertyKey {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Property key name must not be blank");
        }
        Objects.requireNonNull(dataType, "dataType");
        Objects.requireNonNull(cardinality, "cardinality");
    }

    /** Convenience constructor defaulting to {@link Cardinality#SINGLE}. */
    public PropertyKey(String name, DataType dataType) {
        this(name, dataType, Cardinality.SINGLE);
    }
}
