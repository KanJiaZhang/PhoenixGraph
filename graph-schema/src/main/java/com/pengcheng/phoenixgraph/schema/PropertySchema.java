package com.pengcheng.phoenixgraph.schema;

import java.util.Objects;

/**
 * Base schema definition for a property that can be attached to a vertex or an edge.
 *
 * <p>Every property schema has an {@code int} id which is its unique index within
 * its parent schema (a {@link VertexSchema} or an {@link EdgeSchema}), plus a
 * {@code name}, a human-readable {@code description}, a {@link PropertyType}
 * and optional value-level {@link PropertyConstraints}.
 *
 * <p>A property may additionally be flagged as a <em>display</em> property:
 * display properties carry the value(s) a UI should show to identify the
 * element (e.g. an {@code Account}'s {@code firstName}/{@code lastName}, a
 * {@code Phone}'s {@code cnCode}/{@code phoneNumber}). See
 * {@link VertexSchema#displayProperties()}.
 *
 * <p>Concrete subclasses are {@link VertexPropertySchema}, {@link EdgePropertySchema}
 * and {@link MetaPropertySchema}.
 */
public abstract class PropertySchema {

    private final int id;
    private final String name;
    private final String description;
    private final PropertyType type;
    private final PropertyConstraints constraints;
    private boolean display;

    protected PropertySchema(int id, String name, String description, PropertyType type) {
        this(id, name, description, type, PropertyConstraints.NONE);
    }

    protected PropertySchema(int id, String name, String description, PropertyType type,
                             PropertyConstraints constraints) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Property schema name must not be blank");
        }
        this.id = id;
        this.name = name;
        this.description = description;
        this.type = Objects.requireNonNull(type, "type");
        this.constraints = constraints == null ? PropertyConstraints.NONE : constraints;
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

    /** The optional value-level constraints declared for this property (never {@code null}). */
    public PropertyConstraints constraints() {
        return constraints;
    }

    /**
     * Whether this property is a display property, i.e. one whose value(s) a UI
     * should render to identify the owning element. Defaults to {@code false}.
     */
    public boolean display() {
        return display;
    }

    /** Marks (or unmarks) this property as a display property (fluent). */
    public PropertySchema display(boolean display) {
        this.display = display;
        return this;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName()
                + "{id=" + id
                + ", name='" + name + '\''
                + ", type=" + type
                + (display ? ", display=true" : "")
                + '}';
    }
}
