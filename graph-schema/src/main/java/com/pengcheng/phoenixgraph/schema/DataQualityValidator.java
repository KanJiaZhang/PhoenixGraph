package com.pengcheng.phoenixgraph.schema;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates actual property values against a {@link PropertySchema}'s declared
 * constraints (enum membership, numeric range, length/size bounds), enforcing
 * data quality at write time.
 */
public class DataQualityValidator {

    /**
     * Validates a property value against the property schema and returns the
     * list of violations (empty when the value is valid).
     */
    public List<String> validate(PropertySchema property, Object value) {
        List<String> errors = new ArrayList<>();
        if (value == null) {
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
}
