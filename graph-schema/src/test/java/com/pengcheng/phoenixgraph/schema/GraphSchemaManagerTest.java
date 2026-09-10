package com.pengcheng.phoenixgraph.schema;

import com.pengcheng.phoenixgraph.schema.json.SchemaJsonCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GraphSchemaManagerTest {

    @Test
    void jsonCodecRoundTripsGraphSchema() {
        SchemaJsonCodec codec = new SchemaJsonCodec();
        GraphSchema original = sampleSchema();

        GraphSchema parsed = codec.fromJson(codec.toJson(original));

        assertEquals(original.id(), parsed.id());
        assertEquals(original.name(), parsed.name());
        assertEquals(original.description(), parsed.description());
        assertEquals(original.vertexSchemas().size(), parsed.vertexSchemas().size());
        assertEquals(original.edgeSchemas().size(), parsed.edgeSchemas().size());

        VertexSchema person = parsed.vertexSchema("person");
        assertNotNull(person);
        assertEquals(PropertyType.ScalarType.STRING, person.property("name").type());
        assertEquals(PropertyType.ScalarType.INT, person.property(2).type());
        assertInstanceOf(PropertyType.ListType.class, person.property("tags").type());
        assertInstanceOf(PropertyType.EnumType.class, person.property("status").type());

        EdgeSchema worksAt = parsed.edgeSchema("works_at");
        assertNotNull(worksAt);
        assertEquals("source", worksAt.source().name());
        assertEquals("target", worksAt.target().name());
        assertEquals(PropertyType.ScalarType.INT, worksAt.property("since").type());
    }

    @Test
    void managerLoadsAndCachesSchemasByName() {
        GraphSchemaManager manager = new FileSystemGraphSchemaManager(
                name -> new SchemaJsonCodec().toJson(sampleSchema()));

        GraphSchema first = manager.load("social");
        GraphSchema second = manager.load("social");

        assertNotNull(first);
        assertSame(first, second); // cached
        assertSame(first, manager.get("social"));
        assertEquals(1, manager.all().size());
    }

    @Test
    void managerLoadsFromDirectory(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("social.json"), new SchemaJsonCodec().toJson(sampleSchema()));

        GraphSchemaManager manager = new FileSystemGraphSchemaManager(dir);
        GraphSchema schema = manager.load("social");

        assertEquals("social", schema.name());
        assertEquals("person", schema.vertexSchema("person").name());
        assertEquals("works_at", schema.edgeSchema("works_at").name());
    }

    @Test
    void managerRejectsUnknownSchema() {
        GraphSchemaManager manager = new FileSystemGraphSchemaManager(name -> null);
        assertThrows(IllegalArgumentException.class, () -> manager.load("missing"));
    }

    @Test
    void providerReturnsDefaultFileSystemManagerSingleton() {
        GraphSchemaManager first = GraphSchemaManagers.provider().provide();
        GraphSchemaManager second = GraphSchemaManagers.provider().provide();
        assertInstanceOf(FileSystemGraphSchemaManager.class, first);
        assertSame(first, second); // singleton instance
    }

    @Test
    void providerCanBeOverridden() {
        GraphSchemaManagerProvider original = GraphSchemaManagers.provider();
        try {
            GraphSchemaManagers.setProvider(() -> new FileSystemGraphSchemaManager(
                    name -> new SchemaJsonCodec().toJson(sampleSchema())));
            assertNotNull(GraphSchemaManagers.manager().load("social"));
        } finally {
            GraphSchemaManagers.setProvider(original);
        }
    }

    private static GraphSchema sampleSchema() {
        VertexSchema person = new VertexSchema(1, "person", "a person")
                .property(new VertexPropertySchema(1, "name", "person name", PropertyType.ScalarType.STRING))
                .property(new VertexPropertySchema(2, "age", "person age", PropertyType.ScalarType.INT))
                .property(new VertexPropertySchema(3, "tags", "person tags",
                        PropertyType.listOf(PropertyType.ScalarType.STRING)))
                .property(new VertexPropertySchema(4, "status", "person status",
                        PropertyType.enumOf("active", "inactive")));
        VertexSchema company = new VertexSchema(2, "company", "a company");

        EdgeSchema worksAt = new EdgeSchema(1, "works_at", "employment relation",
                new EdgePropertySchema(1, "source", "source vertex", PropertyType.ScalarType.LONG),
                new EdgePropertySchema(2, "target", "target vertex", PropertyType.ScalarType.LONG))
                .property(new EdgePropertySchema(3, "since", "start year", PropertyType.ScalarType.INT));

        return new GraphSchema(1, "social", "social graph")
                .vertex(person)
                .vertex(company)
                .edge(worksAt);
    }
}
