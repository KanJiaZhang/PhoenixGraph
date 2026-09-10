package com.pengcheng.phoenixgraph.schema;

import java.util.Objects;
import java.util.Set;

/**
 * The type of a property value.
 *
 * <p>Supported shapes:
 * <ul>
 *   <li>scalars — {@code boolean, int, long, float, double, string, object}</li>
 *   <li>{@code list<elementType>} — a list whose elements share one scalar type,
 *       e.g. {@code list<string>} or {@code list<object>}</li>
 *   <li>{@code map<valueType>} — a map keyed by string whose values share one
 *       scalar type, e.g. {@code map<string>}</li>
 *   <li>{@code enum} — one of a fixed set of string values</li>
 * </ul>
 */
public sealed interface PropertyType
        permits PropertyType.ScalarType, PropertyType.ListType, PropertyType.MapType, PropertyType.EnumType {

    /**
     * Scalar (single-value) types. {@link #OBJECT} accepts any Java object value.
     */
    enum ScalarType implements PropertyType {
        BOOLEAN, INT, LONG, FLOAT, DOUBLE, STRING, OBJECT
    }

    /**
     * A list whose elements share one scalar type, e.g. {@code list<string>}.
     * {@code list<object>} holds heterogeneous elements (Integer, Long, Double,
     * String, ...).
     */
    record ListType(ScalarType elementType) implements PropertyType {
        public ListType {
            Objects.requireNonNull(elementType, "elementType");
        }
    }

    /**
     * A map keyed by string whose values share one scalar type, e.g.
     * {@code map<string>}. Each entry carries its own created/last-updated
     * timestamps and can be updated individually.
     */
    record MapType(ScalarType valueType) implements PropertyType {
        public MapType {
            Objects.requireNonNull(valueType, "valueType");
        }
    }

    /** An enumeration constrained to a fixed set of allowed string values. */
    record EnumType(Set<String> values) implements PropertyType {
        public EnumType {
            values = values == null ? Set.of() : Set.copyOf(values);
        }
    }

    /** Convenience factory for {@code list<elementType>}. */
    static PropertyType listOf(ScalarType elementType) {
        return new ListType(elementType);
    }

    /** Convenience factory for {@code list<object>}, i.e. {@code List<Object>}. */
    static PropertyType objectList() {
        return new ListType(ScalarType.OBJECT);
    }

    /** Convenience factory for {@code map<valueType>}. */
    static PropertyType mapOf(ScalarType valueType) {
        return new MapType(valueType);
    }

    /** Convenience factory for an enum with the given allowed values. */
    static PropertyType enumOf(String... values) {
        Objects.requireNonNull(values, "values");
        return new EnumType(Set.of(values));
    }
}
