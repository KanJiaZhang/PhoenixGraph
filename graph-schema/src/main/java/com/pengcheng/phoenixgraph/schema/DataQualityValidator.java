package com.pengcheng.phoenixgraph.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Validates actual property values against a {@link PropertySchema}'s declared
 * constraints (declared type, enum membership, numeric range, length/size
 * bounds), enforcing data quality at write time.
 *
 * <p><b>Why the declared type is checked here and not at the storage layer.</b>
 * A store happily writes whatever it is given: an object into a property the
 * schema calls {@code STRING} is stored as a nested record, an index the schema
 * does not declare is stored as another bin, and the graph then answers
 * {@code elementMap()} with values no reader expects. The write path is the only
 * place that still knows what the schema promised, so a value that does not match
 * its declared type is refused <em>there</em>, with a message that names the
 * property, the value and the type. Types are checked structurally (by Java
 * shape) rather than by coercing: a query language that silently turns
 * {@code "5"} into {@code 5} would make a wrong write look like a good one.
 *
 * <p>Messages quote the offending value, so a value is truncated before it is
 * echoed: a rejected 1 MB string must not come back as a 1 MB error body.
 */
public class DataQualityValidator {

    /** How much of an offending value an error message quotes. */
    static final int VALUE_QUOTE_LIMIT = 80;

    /**
     * Validates a property value against the property schema and returns the
     * list of violations (empty when the value is valid).
     *
     * <p>A {@code null} value is valid: writing no value is not writing a wrong
     * one, and the schema cannot tell "absent" from "explicitly cleared" here.
     */
    public List<String> validate(PropertySchema property, Object value) {
        List<String> errors = new ArrayList<>();
        if (value == null) {
            return errors;
        }
        String declaredType = declaredTypeOf(property.type());
        if (!matches(property.type(), value)) {
            errors.add(property.name() + " value " + quote(value) + " is not a " + declaredType);
            // one violation per value is enough to act on: the value is wrong, so its constraints
            // (length, range, enum membership) cannot say anything more useful about it
            return errors;
        }
        if (property.type() instanceof PropertyType.EnumType enumType) {
            String stringValue = String.valueOf(value);
            if (!enumType.values().contains(stringValue)) {
                errors.add(property.name() + " value '" + stringValue
                        + "' is not one of the allowed enum values " + enumType.values());
            }
        }
        errors.addAll(property.constraints().validate(property.name(), value));
        return errors;
    }

    /**
     * Validates all values of a property map keyed by property index, using the
     * given lookup to resolve each property schema; returns all violations.
     */
    public List<String> validateProperties(java.util.Map<Integer, Object> values,
                                           java.util.function.Function<Integer, PropertySchema> propertyLookup) {
        List<String> errors = new ArrayList<>();
        for (java.util.Map.Entry<Integer, Object> entry : values.entrySet()) {
            PropertySchema property = propertyLookup.apply(entry.getKey());
            if (property != null) {
                errors.addAll(validate(property, entry.getValue()));
            }
        }
        return errors;
    }

    /** True when the value has the Java shape the declared property type describes. */
    static boolean matches(PropertyType type, Object value) {
        return switch (type) {
            case PropertyType.ScalarType scalar -> matchesScalar(scalar, value);
            case PropertyType.EnumType ignored -> value instanceof CharSequence;
            case PropertyType.ListType list -> value instanceof List<?> elements
                    && elements.stream().allMatch(element -> element == null
                            || matchesScalar(list.elementType(), element));
            case PropertyType.MapType map -> value instanceof Map<?, ?> entries
                    && entries.values().stream().allMatch(entry -> entry == null
                            || matchesScalar(map.valueType(), entry));
        };
    }

    /** How a declared type is named in an error message. */
    static String declaredTypeOf(PropertyType type) {
        return switch (type) {
            case PropertyType.ScalarType scalar -> scalar.name();
            case PropertyType.EnumType ignored -> "STRING (one of the declared enum values)";
            case PropertyType.ListType list -> "list<" + list.elementType().name() + ">";
            case PropertyType.MapType map -> "map<" + map.valueType().name() + ">";
        };
    }

    private static boolean matchesScalar(PropertyType.ScalarType type, Object value) {
        return switch (type) {
            case BOOLEAN -> value instanceof Boolean;
            // an integer literal that Jackson read as Long is still an INT here: refusing it would make the
            // same JSON valid or invalid depending on the client's number formatting, not on the value
            case INT -> value instanceof Integer || (value instanceof Long longValue
                    && longValue >= Integer.MIN_VALUE && longValue <= Integer.MAX_VALUE);
            case LONG -> value instanceof Integer || value instanceof Long;
            case FLOAT, DOUBLE -> value instanceof Number;
            case STRING -> value instanceof CharSequence;
            // OBJECT accepts anything, including the maps and lists a caller stores deliberately
            case OBJECT -> true;
        };
    }

    /** The value as an error message quotes it: bounded, and never a raw nested structure. */
    static String quote(Object value) {
        String text = String.valueOf(value);
        return text.length() <= VALUE_QUOTE_LIMIT
                ? "'" + text + "'"
                : "'" + text.substring(0, VALUE_QUOTE_LIMIT) + "…' (" + text.length() + " characters)";
    }
}
