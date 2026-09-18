package com.pengcheng.phoenixgraph.http.dto;

import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.GraphStorageType;
import com.pengcheng.phoenixgraph.schema.PropertyType;
import com.pengcheng.phoenixgraph.schema.SchemaValidator;
import com.pengcheng.phoenixgraph.schema.VertexSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The editor's model has to build the same schema the engine uses: property indexes in
 * declaration order, endpoint properties that know their vertex type, the storage the
 * builder picked, and the flags (display, vector, geo) preserved in both directions.
 */
class SchemaDraftTest {

    private static SchemaDraft draft() {
        return new SchemaDraft("built-graph", "built in a test", "string",
                new SchemaDraft.StorageDraft("Aerospike", null, null, null, null),
                List.of(
                        new SchemaDraft.VertexDraft("Person", "A person", "name", List.of(
                                new SchemaDraft.PropertyDraft("name", "string", "Name", true, null, null, null, null,
                                        null, null, null, null),
                                new SchemaDraft.PropertyDraft("age", "int", "Age", null, null, null, null, null,
                                        0, 150, null, null),
                                new SchemaDraft.PropertyDraft("embedding", "list<double>", "Embedding", null,
                                        true, null, null, null, null, null, null, null),
                                new SchemaDraft.PropertyDraft("where", "string", "Where", null, null, true, null,
                                        null, null, null, null, null))),
                        new SchemaDraft.VertexDraft("Merchant", "A shop", null, List.of(
                                new SchemaDraft.PropertyDraft("title", "string", "Title", null, null, null, null,
                                        null, null, null, null, null)))),
                List.of(new SchemaDraft.EdgeDraft("Visits", "Visits", "DIRECTED", "Person", "Merchant", false,
                        List.of(new SchemaDraft.PropertyDraft("times", "long", "Times", null, null, null, null,
                                null, null, null, null, null)))));
    }

    @Test
    void aDraftBuildsASchemaWithIndexesInDeclarationOrder() {
        GraphSchema schema = draft().toSchema(7);

        assertEquals(7, schema.id());
        assertEquals("built-graph", schema.name());
        VertexSchema person = schema.vertexSchema("Person");
        assertEquals(List.of("name", "age", "embedding", "where"),
                person.properties().stream().map(property -> property.name()).toList());
        assertEquals(List.of(1, 2, 3, 4), person.properties().stream().map(property -> property.id()).toList());
        assertEquals("STRING", person.idSchema().type().toString());
    }

    @Test
    void theFlagsSurviveTheConversion() {
        VertexSchema person = draft().toSchema(7).vertexSchema("Person");

        assertTrue(person.properties().get(0).display(), "the display property is flagged");
        assertTrue(person.properties().get(2).isVector(), "the embedding is a vector property");
        assertTrue(person.properties().get(3).isGeo(), "the location is a geo property");
        assertEquals(0d, person.properties().get(1).constraints().min().doubleValue(), 1e-9);
        assertEquals(150d, person.properties().get(1).constraints().max().doubleValue(), 1e-9);
    }

    @Test
    void anEdgeKnowsItsEndpoints() {
        EdgeSchema visits = draft().toSchema(7).edgeSchema("Visits");

        assertEquals("Person", visits.source().vertexName());
        assertEquals("Merchant", visits.target().vertexName());
        assertFalse(visits.target().isNull());
        assertEquals("times", visits.properties().get(0).name());
    }

    @Test
    void aNullableTargetIsAllowed() {
        SchemaDraft withNullTarget = new SchemaDraft("g", null, "string", null,
                List.of(new SchemaDraft.VertexDraft("Merchant", null, null, List.of())),
                List.of(new SchemaDraft.EdgeDraft("Opens", null, "DIRECTED", "Merchant", "Merchant", true,
                        List.of())));

        assertTrue(withNullTarget.toSchema(1).edgeSchema("Opens").target().isNull());
    }

    @Test
    void theStorageTheBuilderPickedIsDeclared() {
        SchemaDraft rocks = new SchemaDraft("g", null, "string",
                new SchemaDraft.StorageDraft("RocksDB-Inline", ".rocksdb/g", 128, 2, false),
                List.of(new SchemaDraft.VertexDraft("V", null, null, List.of())), List.of());

        assertEquals(GraphStorageType.ROCKSDB_INLINE, rocks.toSchema(1).storage().type());
        assertEquals(".rocksdb/g", rocks.toSchema(1).storage().path());
        assertEquals(128, rocks.toSchema(1).storage().regionCount());
    }

    @Test
    void anExistingGraphComesBackAsADraftThatRebuildsIt() {
        GraphSchema first = draft().toSchema(7);
        SchemaDraft again = SchemaDraft.toDraft(first);
        GraphSchema second = again.toSchema(8);

        assertEquals(first.name(), second.name());
        assertEquals(first.vertexSchemas().size(), second.vertexSchemas().size());
        assertEquals("Person", second.edgeSchema("Visits").source().vertexName());
        assertEquals(List.of("name", "age", "embedding", "where"),
                second.vertexSchema("Person").properties().stream().map(property -> property.name()).toList());
        assertTrue(second.vertexSchema("Person").properties().get(2).isVector(), "flags survive the round trip");
        assertEquals("name", again.vertices().get(0).display());
    }

    @Test
    void propertyTypesAreSpelledTheWayPeopleWriteThem() {
        assertInstanceOf(PropertyType.ScalarType.class, SchemaDraft.parseType("string"));
        assertEquals("list<double>", SchemaDraft.describeType(SchemaDraft.parseType("list<double>")));
        assertEquals("map<long>", SchemaDraft.describeType(SchemaDraft.parseType("map<long>")));
        assertEquals(Set.of("ACTIVE", "FROZEN"),
                ((PropertyType.EnumType) SchemaDraft.parseType("enum:ACTIVE|FROZEN")).values());
        assertEquals(PropertyType.ScalarType.INT, SchemaDraft.parseType("integer"));
        assertThrows(IllegalArgumentException.class, () -> SchemaDraft.parseType("nope"));
        assertThrows(IllegalArgumentException.class, () -> SchemaDraft.parseType("enum:"));
    }

    @Test
    void aDraftWithNothingToNameIsRejectedWithAReason() {
        IllegalArgumentException nameless = assertThrows(IllegalArgumentException.class,
                () -> new SchemaDraft("", null, "string", null, List.of(), List.of()).toSchema(1));
        assertTrue(nameless.getMessage().contains("name"));

        SchemaDraft blankProperty = new SchemaDraft("g", null, "string", null,
                List.of(new SchemaDraft.VertexDraft("V", null, null,
                        List.of(new SchemaDraft.PropertyDraft("", "string", null, null, null, null, null, null,
                                null, null, null, null)))), List.of());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> blankProperty.toSchema(1))
                .getMessage().contains("property"));
    }

    @Test
    void theGeneratedSchemaPassesTheValidator() {
        GraphSchema schema = draft().toSchema(7);

        assertTrue(new SchemaValidator().validateResult(schema, schema.constraints()).isValid(),
                "what the builder saves must be a schema the service accepts");
    }
}
