package com.pengcheng.phoenixgraph.schema;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Optional value-level constraints attached to a {@link PropertySchema}.
 *
 * <p>Declares the allowed value domain of a property so that data quality can be
 * enforced at write time:
 * <ul>
 *   <li>{@code min} / {@code max} — inclusive numeric range, for
 *       {@code INT}/{@code LONG}/{@code FLOAT}/{@code DOUBLE} scalar properties;</li>
 *   <li>{@code minLength} / {@code maxLength} — inclusive length/size bounds, for
 *       {@code STRING} properties (character count) and list/map properties
 *       (element/entry count).</li>
 * </ul>
 *
 * <p>Enum properties are constrained by their {@link PropertyType.EnumType}
 * instead.
 */
public record PropertyConstraints(Number min, Number max, Integer minLength, Integer maxLength) {

    /** The empty constraint set (no restriction). */
    public static final PropertyConstraints NONE = new PropertyConstraints(null, null, null, null);

    public PropertyConstraints {
        if (min != null && max != null && compare(min, max) > 0) {
            throw new IllegalArgumentException("min (" + min + ") must be <= max (" + max + ")");
        }
        if (minLength != null && minLength < 0) {
            throw new IllegalArgumentException("minLength must be >= 0");
        }
        if (maxLength != null && maxLength < 0) {
            throw new IllegalArgumentException("maxLength must be >= 0");
        }
        if (minLength != null && maxLength != null && minLength > maxLength) {
            throw new IllegalArgumentException("minLength must be <= maxLength");
        }
    }

    public static PropertyConstraints none() {
        return NONE;
    }

    /** Numeric range constraint {@code [min, max]}. */
    public static PropertyConstraints range(Number min, Number max) {
        return new PropertyConstraints(min, max, null, null);
    }

    /** Length/size constraint {@code [minLength, maxLength]}. */
    public static PropertyConstraints length(Integer minLength, Integer maxLength) {
        return new PropertyConstraints(null, null, minLength, maxLength);
    }

    /** Whether no constraint is declared. */
    public boolean isEmpty() {
        return min == null && max == null && minLength == null && maxLength == null;
    }

    /**
     * Validates a property value against these constraints and returns the list
     * of violations (empty when the value is valid).
     */
    public List<String> validate(String propertyName, Object value) {
        List<String> errors = new ArrayList<>();
        if (value == null) {
            return errors;
        }
        if (value instanceof Number number) {
            if (min != null && number.doubleValue() < min.doubleValue()) {
                errors.add(propertyName + " value " + value + " is below min " + min);
            }
            if (max != null && number.doubleValue() > max.doubleValue()) {
                errors.add(propertyName + " value " + value + " is above max " + max);
            }
        }
        int length = lengthOf(value);
        if (length >= 0) {
            if (minLength != null && length < minLength) {
                errors.add(propertyName + " length " + length + " is below minLength " + minLength);
            }
            if (maxLength != null && length > maxLength) {
                errors.add(propertyName + " length " + length + " is above maxLength " + maxLength);
            }
        }
        return errors;
    }

    private static int lengthOf(Object value) {
        if (value instanceof CharSequence sequence) {
            return sequence.length();
        }
        if (value instanceof Collection<?> collection) {
            return collection.size();
        }
        if (value instanceof Map<?, ?> map) {
            return map.size();
        }
        return -1;
    }

    private static int compare(Number a, Number b) {
        return Double.compare(a.doubleValue(), b.doubleValue());
    }
}
