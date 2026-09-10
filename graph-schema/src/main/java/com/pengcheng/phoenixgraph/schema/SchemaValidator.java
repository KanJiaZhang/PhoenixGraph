package com.pengcheng.phoenixgraph.schema;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates a {@link GraphSchema} against {@link SchemaConstraints}.
 */
public class SchemaValidator {

    /**
     * Validates the given graph schema and returns a list of violations
     * (empty when the schema conforms to the constraints).
     */
    public List<String> validate(GraphSchema schema, SchemaConstraints constraints) {
        List<String> errors = new ArrayList<>();
        for (VertexSchema vertex : schema.vertexSchemas()) {
            checkIdSchema(errors, "vertex", vertex.name(), vertex.idSchema().id(), constraints);
            checkMetaProperties(errors, "vertex", vertex.name(),
                    vertex.createdTime(), vertex.lastUpdatedTime(), constraints);
        }
        for (EdgeSchema edge : schema.edgeSchemas()) {
            checkIdSchema(errors, "edge", edge.name(), edge.idSchema().id(), constraints);
            checkMetaProperties(errors, "edge", edge.name(),
                    edge.createdTime(), edge.lastUpdatedTime(), constraints);
        }
        return errors;
    }

    private void checkIdSchema(List<String> errors, String kind, String name, int actual,
                               SchemaConstraints constraints) {
        if (actual != constraints.idSchemaIndex()) {
            errors.add(kind + " schema '" + name + "' idSchema index " + actual
                    + " != required " + constraints.idSchemaIndex());
        }
    }

    private void checkMetaProperties(List<String> errors, String kind, String name,
                                     MetaPropertySchema createdTime, MetaPropertySchema lastUpdatedTime,
                                     SchemaConstraints constraints) {
        if (createdTime.id() != constraints.createdTimeIndex()) {
            errors.add(kind + " schema '" + name + "' createdTime index " + createdTime.id()
                    + " != required " + constraints.createdTimeIndex());
        }
        if (lastUpdatedTime.id() != constraints.lastUpdatedTimeIndex()) {
            errors.add(kind + " schema '" + name + "' lastUpdatedTime index " + lastUpdatedTime.id()
                    + " != required " + constraints.lastUpdatedTimeIndex());
        }
        if (createdTime.type() != PropertyType.ScalarType.LONG) {
            errors.add(kind + " schema '" + name + "' createdTime must be of type long");
        }
        if (lastUpdatedTime.type() != PropertyType.ScalarType.LONG) {
            errors.add(kind + " schema '" + name + "' lastUpdatedTime must be of type long");
        }
    }
}
