package com.pengcheng.phoenixgraph.schema;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaValidationTest {

    private final SchemaValidator validator = new SchemaValidator();

    @Test
    void managerRejectsDuplicateGraphIdOrName() {
        FileSystemGraphSchemaManager manager = new FileSystemGraphSchemaManager(name -> null);
        manager.register(new GraphSchema(1, "g1", null));

        assertThrows(IllegalArgumentException.class,
                () -> manager.register(new GraphSchema(1, "g3", null))); // duplicate id
        assertThrows(IllegalArgumentException.class,
                () -> manager.register(new GraphSchema(3, "g1", null))); // duplicate name
    }

    @Test
    void validatorWarnsForUndeclaredDegreeLimitsAndEndpoints() {
        VertexSchema account = new VertexSchema(1, "Account", null);
        EdgeSchema payment = new EdgeSchema(1, "Payment", null,
                new EdgePropertySchema(1, "source", null, PropertyType.ScalarType.LONG),
                new EdgePropertySchema(2, "target", null, PropertyType.ScalarType.LONG));
        GraphSchema schema = new GraphSchema(1, "g", null).vertex(account).edge(payment);

        SchemaValidationResult result = validator.validateResult(schema, SchemaConstraints.DEFAULTS);

        assertTrue(result.isValid());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("maxOutgoingEdges")));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("maxIncomingEdges")));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("maxDegree")));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("sourceVertex")));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("targetVertex")));
    }

    @Test
    void validatorErrorsForUnknownEndpointVertex() {
        VertexSchema account = new VertexSchema(1, "Account", null);
        EdgeSchema payment = new EdgeSchema(1, "Payment", null,
                new EdgeEndpointSchema(1, "source", null, PropertyType.ScalarType.LONG, "Missing"),
                new EdgeEndpointSchema(2, "target", null, PropertyType.ScalarType.LONG, "Missing"));
        GraphSchema schema = new GraphSchema(1, "g", null).vertex(account).edge(payment);

        SchemaValidationResult result = validator.validateResult(schema, SchemaConstraints.DEFAULTS);

        assertFalse(result.isValid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("sourceVertex")));
    }

    @Test
    void validatorErrorsForEmptyEnumType() {
        VertexSchema vertex = new VertexSchema(1, "v", null)
                .property(new VertexPropertySchema(1, "p", null, new PropertyType.EnumType(Set.of())));
        GraphSchema schema = new GraphSchema(1, "g", null).vertex(vertex);

        SchemaValidationResult result = validator.validateResult(schema, SchemaConstraints.DEFAULTS);

        assertFalse(result.isValid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("enum")));
    }

    @Test
    void validatorChecksMetaIndexesAndDuplicatePropertyAreEnforcedBySchema() {
        VertexSchema vertex = new VertexSchema(1, "v", null)
                .property(new VertexPropertySchema(1, "p", null, PropertyType.ScalarType.STRING));
        GraphSchema schema = new GraphSchema(1, "g", null).vertex(vertex);

        SchemaValidationResult result = validator.validateResult(schema, SchemaConstraints.DEFAULTS);
        assertTrue(result.isValid());

        // duplicate property id/name is rejected at construction time
        assertThrows(IllegalArgumentException.class,
                () -> vertex.property(new VertexPropertySchema(1, "other", null, PropertyType.ScalarType.STRING)));
        assertThrows(IllegalArgumentException.class,
                () -> vertex.property(new VertexPropertySchema(9, "p", null, PropertyType.ScalarType.STRING)));
    }

    @Test
    void edgeDuplicatePropertyIsRejectedAtConstruction() {
        EdgeSchema edge = new EdgeSchema(1, "e", null,
                new EdgePropertySchema(1, "source", null, PropertyType.ScalarType.LONG),
                new EdgePropertySchema(2, "target", null, PropertyType.ScalarType.LONG))
                .property(new EdgePropertySchema(3, "p", null, PropertyType.ScalarType.STRING));

        assertThrows(IllegalArgumentException.class,
                () -> edge.property(new EdgePropertySchema(3, "other", null, PropertyType.ScalarType.STRING)));
        assertThrows(IllegalArgumentException.class,
                () -> edge.property(new EdgePropertySchema(9, "p", null, PropertyType.ScalarType.STRING)));
    }
}
