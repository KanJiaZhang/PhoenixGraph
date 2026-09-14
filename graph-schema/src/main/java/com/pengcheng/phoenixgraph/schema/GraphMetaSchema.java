package com.pengcheng.phoenixgraph.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Meta-data definition for a graph: the constraint vocabulary and global
 * defaults that a {@link GraphSchema} conforms to.
 *
 * <p>A {@code GraphMetaSchema} (serialized as {@code GraphMetaSchema.json})
 * declares:
 * <ul>
 *   <li>{@code maxInDegree} / {@code maxOutDegree} / {@code maxDegree} — global
 *       upper bounds for edge degrees (used to validate the edge schema degree
 *       metadata; {@code <= 0} means unlimited);</li>
 *   <li>{@code propertyBounds} — per scalar type, the numeric range within which
 *       a property's {@code min}/{@code max} constraint must lie.</li>
 * </ul>
 *
 * <p>The actual constraints are declared on the properties of a
 * {@link GraphSchema} via {@link PropertyConstraints} (and, for enums, via
 * {@link PropertyType.EnumType}); this meta-schema only bounds what those
 * constraints may declare, ensuring data-quality rules stay within a controlled
 * domain.
 */
public class GraphMetaSchema {

    /** Sentinel for "unlimited" degree: {@code <= 0}. */
    public static final int UNLIMITED = 0;

    private final int id;
    private final String name;
    private final String description;
    private final int maxInDegree;
    private final int maxOutDegree;
    private final int maxDegree;
    private final List<PropertyConstraintBounds> propertyBounds = new ArrayList<>();

    public GraphMetaSchema(int id, String name, String description) {
        this(id, name, description, UNLIMITED, UNLIMITED, UNLIMITED, List.of());
    }

    public GraphMetaSchema(int id, String name, String description,
                           int maxInDegree, int maxOutDegree, int maxDegree) {
        this(id, name, description, maxInDegree, maxOutDegree, maxDegree, List.of());
    }

    public GraphMetaSchema(int id, String name, String description,
                           int maxInDegree, int maxOutDegree, int maxDegree,
                           List<PropertyConstraintBounds> propertyBounds) {
        this.id = id;
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Meta-schema name must not be blank");
        }
        this.name = name;
        this.description = description;
        this.maxInDegree = maxInDegree;
        this.maxOutDegree = maxOutDegree;
        this.maxDegree = maxDegree;
        if (propertyBounds != null) {
            this.propertyBounds.addAll(propertyBounds);
        }
    }

    public int id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    /** Global upper bound for incoming edges per vertex ({@code <= 0} = unlimited). */
    public int maxInDegree() {
        return maxInDegree;
    }

    /** Global upper bound for outgoing edges per vertex ({@code <= 0} = unlimited). */
    public int maxOutDegree() {
        return maxOutDegree;
    }

    /** Global upper bound for undirected/bidirectional edges per vertex ({@code <= 0} = unlimited). */
    public int maxDegree() {
        return maxDegree;
    }

    /** Immutable view of the declared per-type property constraint bounds. */
    public List<PropertyConstraintBounds> propertyBounds() {
        return List.copyOf(propertyBounds);
    }

    /** Looks up the constraint bound declared for a scalar type, or {@code null} if none. */
    public PropertyConstraintBounds boundFor(PropertyType.ScalarType type) {
        Objects.requireNonNull(type, "type");
        for (PropertyConstraintBounds bound : propertyBounds) {
            if (bound.type() == type) {
                return bound;
            }
        }
        return null;
    }
}
