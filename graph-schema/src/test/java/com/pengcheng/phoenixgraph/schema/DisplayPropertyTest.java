package com.pengcheng.phoenixgraph.schema;

import com.pengcheng.phoenixgraph.schema.json.SchemaJsonCodec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the {@code display} flag on property schemas: the properties a UI
 * renders (joined by a space) to label a vertex.
 */
class DisplayPropertyTest {

    @Test
    void displayDefaultsToFalseAndIsFluent() {
        VertexPropertySchema property =
                new VertexPropertySchema(1, "firstName", "first name", PropertyType.ScalarType.STRING);
        assertFalse(property.display());

        assertTrue(property.display(true).display());
        assertFalse(property.display(false).display());
    }

    @Test
    void vertexSchemaExposesDisplayPropertiesInDeclarationOrder() {
        VertexSchema account = new VertexSchema(1, "Account", "An account")
                .property(new VertexPropertySchema(1, "accountStatus", "status", PropertyType.ScalarType.STRING))
                .property(new VertexPropertySchema(2, "lastName", "last name", PropertyType.ScalarType.STRING)
                        .display(true))
                .property(new VertexPropertySchema(3, "firstName", "first name", PropertyType.ScalarType.STRING)
                        .display(true));

        assertEquals(List.of("lastName", "firstName"), account.displayPropertyNames());
        assertEquals(2, account.displayProperties().size());
    }

    @Test
    void vertexSchemaWithoutDisplayPropertiesHasEmptyList() {
        VertexSchema account = new VertexSchema(1, "Account", "An account")
                .property(new VertexPropertySchema(1, "accountStatus", "status", PropertyType.ScalarType.STRING));

        assertEquals(List.of(), account.displayPropertyNames());
    }

    @Test
    void idSchemaIsNeverADisplayProperty() {
        VertexSchema account = new VertexSchema(1, "Account", "An account")
                .property(new VertexPropertySchema(1, "firstName", "first name", PropertyType.ScalarType.STRING)
                        .display(true));

        // the id property is always rendered by the UI, so it is not part of the display list
        assertEquals(List.of("firstName"), account.displayPropertyNames());
    }

    @Test
    void jsonRoundTripKeepsTheDisplayFlag() {
        SchemaJsonCodec codec = new SchemaJsonCodec();
        GraphSchema original = new GraphSchema(1, "payment-graph", "demo")
                .vertex(new VertexSchema(1, "Account", "An account")
                        .property(new VertexPropertySchema(1, "accountType", "type",
                                PropertyType.ScalarType.STRING))
                        .property(new VertexPropertySchema(2, "firstName", "first name",
                                PropertyType.ScalarType.STRING).display(true))
                        .property(new VertexPropertySchema(3, "lastName", "last name",
                                PropertyType.ScalarType.STRING).display(true)))
                .edge(new EdgeSchema(1, "Payment", "A payment",
                        new EdgePropertySchema(1, "source", "source vertex", PropertyType.ScalarType.LONG),
                        new EdgePropertySchema(2, "target", "target vertex", PropertyType.ScalarType.LONG)));

        String json = codec.toJson(original);
        String compact = json.replaceAll("\\s+", ""); // Jackson's pretty printer pads around ':'
        // only flagged properties carry the key, so schema files stay terse
        assertEquals(2, compact.split("\"display\":true", -1).length - 1);
        assertFalse(compact.contains("\"display\":false"));
        assertFalse(compact.contains("\"min\":null"));

        GraphSchema parsed = codec.fromJson(json);
        assertEquals(List.of("firstName", "lastName"),
                parsed.vertexSchema("Account").displayPropertyNames());
        assertFalse(parsed.vertexSchema("Account").property("accountType").display());
    }
}
