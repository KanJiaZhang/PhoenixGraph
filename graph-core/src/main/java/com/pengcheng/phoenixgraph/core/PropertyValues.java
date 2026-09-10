package com.pengcheng.phoenixgraph.core;

import com.pengcheng.phoenixgraph.schema.PropertyType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Creates and validates property values according to a {@link PropertyType}.
 */
public final class PropertyValues {

    private PropertyValues() {
    }

    /** Returns a default value for the given type. */
    public static Object defaultValue(PropertyType type) {
        if (type instanceof PropertyType.ScalarType scalar) {
            return switch (scalar) {
                case BOOLEAN -> Boolean.FALSE;
                case INT -> 0;
                case LONG -> 0L;
                case FLOAT -> 0.0f;
                case DOUBLE -> 0.0d;
                case STRING -> "";
                case OBJECT -> null;
            };
        }
        if (type instanceof PropertyType.ListType) {
            return new ArrayList<>();
        }
        if (type instanceof PropertyType.MapType) {
            return new LinkedHashMap<>();
        }
        if (type instanceof PropertyType.EnumType en) {
            return en.values().stream().findFirst().orElse(null);
        }
        throw new IllegalArgumentException("Unsupported property type: " + type);
    }

    /**
     * Validates a value against the type. Returns the type's default value when
     * the given value is {@code null} (i.e. instantiates a typed default).
     */
    public static Object validate(PropertyType type, Object value) {
        if (value == null) {
            return defaultValue(type);
        }
        if (type instanceof PropertyType.ScalarType scalar) {
            if (!matches(scalar, value)) {
                throw new IllegalArgumentException("Value '" + value + "' does not match scalar type " + scalar);
            }
            return value;
        }
        if (type instanceof PropertyType.ListType) {
            if (!(value instanceof List<?>)) {
                throw new IllegalArgumentException("Value '" + value + "' is not a list");
            }
            return value;
        }
        if (type instanceof PropertyType.MapType) {
            if (!(value instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("Value '" + value + "' is not a map");
            }
            return value;
        }
        if (type instanceof PropertyType.EnumType en) {
            if (!(value instanceof String s) || !en.values().contains(s)) {
                throw new IllegalArgumentException("Value '" + value + "' is not one of enum values " + en.values());
            }
            return value;
        }
        throw new IllegalArgumentException("Unsupported property type: " + type);
    }

    private static boolean matches(PropertyType.ScalarType scalar, Object value) {
        return switch (scalar) {
            case BOOLEAN -> value instanceof Boolean;
            case INT -> value instanceof Integer;
            case LONG -> value instanceof Long;
            case FLOAT -> value instanceof Float;
            case DOUBLE -> value instanceof Double;
            case STRING -> value instanceof String;
            case OBJECT -> true;
        };
    }
}
