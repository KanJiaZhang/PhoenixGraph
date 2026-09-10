package com.pengcheng.phoenixgraph.schema;

import java.util.Objects;

/**
 * Base schema definition for a property that can be attached to a vertex or an edge.
 *
 * <p>Every property schema has an {@code int} id which is its unique index within
 * its parent schema (a {@link VertexSchema} or an {@link EdgeSchema}), plus a
 * {@code name}, a human-readable {@code description} and a {@link PropertyType}.
 *
 * <p>Concrete subclasses are {@link VertexPropertySchema}, {@link EdgePropertySchema}
 * and {@link MetaPropertySchema}.
 */
public abstract class PropertySchema {

    private final int id;
    private final String name;
    private final String description;
    private final PropertyType type;

    protected PropertySchema(int id, String name, String description, PropertyType type) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Property schema name must not be blank");
        }
        this.id = id;
        this.name = name;
        this.description = description;
        this.type = Objects.requireNonNull(type, "type");
    }

    /** Unique index of this property within its parent schema. */
    public int id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public PropertyType type() {
        return type;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName()
                + "{id=" + id
                + ", name='" + name + '\''
                + ", type=" + type
                + '}';
    }
}
