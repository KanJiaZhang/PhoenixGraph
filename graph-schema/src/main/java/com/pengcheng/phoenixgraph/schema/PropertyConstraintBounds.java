package com.pengcheng.phoenixgraph.schema;

/**
 * A meta-schema declaration of the numeric range that a property's
 * {@code min}/{@code max} {@link PropertyConstraints} may use for a given
 * {@link PropertyType.ScalarType}.
 *
 * @param type the scalar type this bound applies to
 * @param min  inclusive lower bound for a property constraint ({@code null} = unbounded)
 * @param max  inclusive upper bound for a property constraint ({@code null} = unbounded)
 */
public record PropertyConstraintBounds(PropertyType.ScalarType type, Number min, Number max) {

    public PropertyConstraintBounds {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (min != null && max != null && Double.compare(min.doubleValue(), max.doubleValue()) > 0) {
            throw new IllegalArgumentException("min (" + min + ") must be <= max (" + max + ")");
        }
    }

    /** Whether the given numeric constraint value lies within this bound. */
    public boolean contains(Number value) {
        if (value == null) {
            return true;
        }
        if (min != null && value.doubleValue() < min.doubleValue()) {
            return false;
        }
        if (max != null && value.doubleValue() > max.doubleValue()) {
            return false;
        }
        return true;
    }
}
