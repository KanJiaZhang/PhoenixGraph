package com.pengcheng.phoenixgraph.schema;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates a {@link GraphSchema} against {@link SchemaConstraints} and, when
 * provided, against a {@link GraphMetaSchema} (constraint vocabulary and global
 * bounds).
 */
public class SchemaValidator {

    /**
     * Validates the given graph schema and returns a list of violations
     * (empty when the schema conforms to the constraints).
     */
    public List<String> validate(GraphSchema schema, SchemaConstraints constraints) {
        return validateResult(schema, constraints).errors();
    }

    /**
     * Validates the graph schema and returns both violations and warnings (e.g.
     * implicitly-applied defaults such as undeclared degree limits or endpoints).
     */
    public SchemaValidationResult validateResult(GraphSchema schema, SchemaConstraints constraints) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (VertexSchema vertex : schema.vertexSchemas()) {
            checkIdSchema(errors, "vertex", vertex.name(), vertex.idSchema().id(), constraints);
            checkMetaProperties(errors, "vertex", vertex.name(),
                    vertex.createdTime(), vertex.lastUpdatedTime(), constraints);
            checkDuplicateProperties(errors, "vertex", vertex.name(), vertex.properties());
            checkDisplayProperties(warnings, vertex);
            for (VertexPropertySchema property : vertex.properties()) {
                checkSupportedPropertyType(errors, "vertex", vertex.name(), property);
            }
        }
        for (EdgeSchema edge : schema.edgeSchemas()) {
            checkIdSchema(errors, "edge", edge.name(), edge.idSchema().id(), constraints);
            checkMetaProperties(errors, "edge", edge.name(),
                    edge.createdTime(), edge.lastUpdatedTime(), constraints);
            checkEndpointVertex(errors, schema, edge);
            checkEndpointVertexDeclared(warnings, edge);
            checkDegreeLimitsDeclared(warnings, edge);
            checkDuplicateProperties(errors, "edge", edge.name(), edge.properties());
            for (EdgePropertySchema property : edge.properties()) {
                checkSupportedPropertyType(errors, "edge", edge.name(), property);
            }
        }
        return new SchemaValidationResult(List.copyOf(errors), List.copyOf(warnings));
    }

    /**
     * Validates the graph schema structurally and, when {@code metaSchema} is
     * non-null, checks that the declared property constraints and edge degree
     * limits conform to the meta-schema.
     */
    public List<String> validate(GraphSchema schema, SchemaConstraints constraints, GraphMetaSchema metaSchema) {
        List<String> errors = new ArrayList<>(validate(schema, constraints));
        if (metaSchema != null) {
            validateAgainstMeta(schema, metaSchema, errors);
        }
        return errors;
    }

    private void validateAgainstMeta(GraphSchema schema, GraphMetaSchema metaSchema, List<String> errors) {        for (VertexSchema vertex : schema.vertexSchemas()) {
            checkPropertyConstraints(errors, "vertex", vertex.name(), vertex.idSchema(), metaSchema);
            for (VertexPropertySchema property : vertex.properties()) {
                checkPropertyConstraints(errors, "vertex", vertex.name(), property, metaSchema);
            }
        }
        for (EdgeSchema edge : schema.edgeSchemas()) {
            checkPropertyConstraints(errors, "edge", edge.name(), edge.idSchema(), metaSchema);
            checkPropertyConstraints(errors, "edge", edge.name(), edge.source(), metaSchema);
            checkPropertyConstraints(errors, "edge", edge.name(), edge.target(), metaSchema);
            for (EdgePropertySchema property : edge.properties()) {
                checkPropertyConstraints(errors, "edge", edge.name(), property, metaSchema);
            }
            checkDegreeLimit(errors, edge, metaSchema);
        }
    }

    private void checkPropertyConstraints(List<String> errors, String kind, String schemaName,
                                          PropertySchema property, GraphMetaSchema metaSchema) {
        PropertyConstraints constraints = property.constraints();
        if (constraints.isEmpty()) {
            return;
        }
        boolean numericType = property.type() instanceof PropertyType.ScalarType scalar
                && (scalar == PropertyType.ScalarType.INT
                || scalar == PropertyType.ScalarType.LONG
                || scalar == PropertyType.ScalarType.FLOAT
                || scalar == PropertyType.ScalarType.DOUBLE);
        boolean sizedType = (property.type() instanceof PropertyType.ScalarType scalar
                && scalar == PropertyType.ScalarType.STRING)
                || property.type() instanceof PropertyType.ListType
                || property.type() instanceof PropertyType.MapType;

        if ((constraints.min() != null || constraints.max() != null) && !numericType) {
            errors.add(kind + " schema '" + schemaName + "' property '" + property.name()
                    + "' declares min/max but its type " + property.type() + " is not numeric");
        }
        if ((constraints.minLength() != null || constraints.maxLength() != null) && !sizedType) {
            errors.add(kind + " schema '" + schemaName + "' property '" + property.name()
                    + "' declares minLength/maxLength but its type " + property.type()
                    + " has no length/size");
        }

        if (numericType && property.type() instanceof PropertyType.ScalarType scalar) {
            PropertyConstraintBounds bounds = metaSchema.boundFor(scalar);
            if (bounds != null) {
                if (!bounds.contains(constraints.min()) || !bounds.contains(constraints.max())) {
                    errors.add(kind + " schema '" + schemaName + "' property '" + property.name()
                            + "' constraint is outside meta-schema bound " + bounds);
                }
            }
        }
    }

    private void checkDegreeLimit(List<String> errors, EdgeSchema edge, GraphMetaSchema metaSchema) {
        if (metaSchema.maxOutDegree() > 0 && edge.maxOutDegree() > metaSchema.maxOutDegree()) {
            errors.add("edge schema '" + edge.name() + "' maxOutDegree " + edge.maxOutDegree()
                    + " > meta-schema cap " + metaSchema.maxOutDegree());
        }
        if (metaSchema.maxInDegree() > 0 && edge.maxInDegree() > metaSchema.maxInDegree()) {
            errors.add("edge schema '" + edge.name() + "' maxInDegree " + edge.maxInDegree()
                    + " > meta-schema cap " + metaSchema.maxInDegree());
        }
        if (metaSchema.maxDegree() > 0 && edge.maxDegree() > metaSchema.maxDegree()) {
            errors.add("edge schema '" + edge.name() + "' maxDegree " + edge.maxDegree()
                    + " > meta-schema cap " + metaSchema.maxDegree());
        }
    }

    private void checkEndpointVertex(List<String> errors, GraphSchema schema, EdgeSchema edge) {
        if (edge.sourceVertexName() != null && schema.vertexSchema(edge.sourceVertexName()) == null) {
            errors.add("edge schema '" + edge.name() + "' sourceVertex '" + edge.sourceVertexName()
                    + "' is not a declared vertex schema");
        }
        if (edge.targetVertexName() != null && schema.vertexSchema(edge.targetVertexName()) == null) {
            errors.add("edge schema '" + edge.name() + "' targetVertex '" + edge.targetVertexName()
                    + "' is not a declared vertex schema");
        }
    }

    private void checkEndpointVertexDeclared(List<String> warnings, EdgeSchema edge) {
        if (edge.sourceVertexName() == null) {
            warnings.add("edge schema '" + edge.name() + "' does not declare sourceVertex");
        }
        if (edge.targetVertexName() == null) {
            warnings.add("edge schema '" + edge.name() + "' does not declare targetVertex");
        }
    }

    private void checkDegreeLimitsDeclared(List<String> warnings, EdgeSchema edge) {
        if (!edge.maxOutgoingEdgesSet()) {
            warnings.add("edge schema '" + edge.name() + "' does not declare maxOutgoingEdges (default: unlimited)");
        }
        if (!edge.maxIncomingEdgesSet()) {
            warnings.add("edge schema '" + edge.name() + "' does not declare maxIncomingEdges (default: unlimited)");
        }
        if (!edge.maxDegreeSet()) {
            warnings.add("edge schema '" + edge.name() + "' does not declare maxDegree (default: unlimited)");
        }
    }

    private void checkDuplicateProperties(List<String> errors, String kind, String schemaName,
                                          List<? extends PropertySchema> properties) {
        Set<Integer> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (PropertySchema property : properties) {
            if (!ids.add(property.id())) {
                errors.add(kind + " schema '" + schemaName + "' has duplicate property id " + property.id()
                        + " (" + property.name() + ")");
            }
            if (!names.add(property.name())) {
                errors.add(kind + " schema '" + schemaName + "' has duplicate property name '"
                        + property.name() + "'");
            }
        }
    }

    /**
     * Warns (never fails) when a vertex schema declares no display property: the
     * UI then has to fall back to the raw vertex id to label the vertex. Also
     * warns when a display property is not a scalar (it is still rendered, as text).
     */
    private void checkDisplayProperties(List<String> warnings, VertexSchema vertex) {
        List<VertexPropertySchema> display = vertex.displayProperties();
        if (display.isEmpty()) {
            warnings.add("vertex schema '" + vertex.name()
                    + "' declares no display property; the UI will label its vertices by id");
            return;
        }
        for (VertexPropertySchema property : display) {
            if (!(property.type() instanceof PropertyType.ScalarType)) {
                warnings.add("vertex schema '" + vertex.name() + "' display property '"
                        + property.name() + "' has non-scalar type " + property.type()
                        + "; the UI renders it as text");
            }
        }
    }

    private void checkSupportedPropertyType(List<String> errors, String kind, String schemaName,
                                            PropertySchema property) {
        PropertyType type = property.type();
        if (type == null) {
            errors.add(kind + " schema '" + schemaName + "' property '" + property.name() + "' has a null type");
            return;
        }
        if (type instanceof PropertyType.EnumType en && en.values().isEmpty()) {
            errors.add(kind + " schema '" + schemaName + "' property '" + property.name()
                    + "' declares an enum type with no allowed values");
        }
        if (type instanceof PropertyType.ListType list && list.elementType() == null) {
            errors.add(kind + " schema '" + schemaName + "' property '" + property.name()
                    + "' list type has no element type");
        }
        if (type instanceof PropertyType.MapType map && map.valueType() == null) {
            errors.add(kind + " schema '" + schemaName + "' property '" + property.name()
                    + "' map type has no value type");
        }
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
