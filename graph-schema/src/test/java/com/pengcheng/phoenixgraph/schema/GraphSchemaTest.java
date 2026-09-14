package com.pengcheng.phoenixgraph.schema;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GraphSchemaTest {

    @Test
    void vertexSchemaHasDefaultIdSchema() {
        VertexSchema person = new VertexSchema(1, "person", "a person");
        assertEquals(1, person.id());
        assertEquals("person", person.name());
        assertEquals("a person", person.description());
        assertEquals(VertexSchema.ID_INDEX, person.idSchema().id());
        assertEquals(0, person.idSchema().id());
        assertEquals(List.of(), person.properties());
    }

    @Test
    void vertexSchemaLookupByIdAndName() {
        VertexPropertySchema name = new VertexPropertySchema(1, "name", "person name", PropertyType.ScalarType.STRING);
        VertexPropertySchema age = new VertexPropertySchema(2, "age", "person age", PropertyType.ScalarType.INT);

        VertexSchema person = new VertexSchema(1, "person", "a person").property(name).property(age);

        assertSame(person.idSchema(), person.property(VertexSchema.ID_INDEX));
        assertSame(person.idSchema(), person.property("id"));
        assertSame(name, person.property(1));
        assertSame(name, person.property("name"));
        assertSame(age, person.property(2));
        assertSame(age, person.property("age"));
        assertNull(person.property(99));
        assertNull(person.property("missing"));
    }

    @Test
    void vertexSchemaRejectsDuplicateIdOrName() {
        VertexSchema person = new VertexSchema(1, "person", null)
                .property(new VertexPropertySchema(1, "name", null, PropertyType.ScalarType.STRING));

        assertThrows(IllegalArgumentException.class,
                () -> person.property(new VertexPropertySchema(1, "other", null, PropertyType.ScalarType.STRING)));
        assertThrows(IllegalArgumentException.class,
                () -> person.property(new VertexPropertySchema(9, "name", null, PropertyType.ScalarType.STRING)));
    }

    @Test
    void edgeSchemaHasDescriptionAndLookup() {
        EdgePropertySchema source = new EdgePropertySchema(1, "source", null, PropertyType.ScalarType.LONG);
        EdgePropertySchema target = new EdgePropertySchema(2, "target", null, PropertyType.ScalarType.LONG);

        EdgeSchema worksAt = new EdgeSchema(1, "works_at", "employment relation", source, target)
                .property(new EdgePropertySchema(3, "since", null, PropertyType.ScalarType.INT));

        assertEquals("employment relation", worksAt.description());
        assertEquals("source", worksAt.property(1).name());
        assertEquals("source", worksAt.property("source").name());
        assertEquals("target", worksAt.property("target").name());
        assertSame(worksAt.idSchema(), worksAt.property(EdgeSchema.ID_INDEX));
        assertNull(worksAt.property(42));
        // plain endpoints are wrapped, so the vertex schema name is unknown
        assertNull(worksAt.sourceVertexName());
        assertNull(worksAt.targetVertexName());
    }

    @Test
    void edgeSchemaDirectionDefaultsToDirected() {
        EdgeSchema edge = new EdgeSchema(1, "e",
                new EdgePropertySchema(1, "source", null, PropertyType.ScalarType.LONG),
                new EdgePropertySchema(2, "target", null, PropertyType.ScalarType.LONG));
        assertEquals(EdgeDirection.DIRECTED, edge.direction());
    }

    @Test
    void edgeSchemaSupportsExplicitDirection() {
        EdgePropertySchema source = new EdgePropertySchema(1, "source", null, PropertyType.ScalarType.LONG);
        EdgePropertySchema target = new EdgePropertySchema(2, "target", null, PropertyType.ScalarType.LONG);

        assertEquals(EdgeDirection.UNDIRECTED,
                new EdgeSchema(1, "e", "desc", source, target, EdgeDirection.UNDIRECTED).direction());
        assertEquals(EdgeDirection.BIDIRECTIONAL,
                new EdgeSchema(1, "e", "desc", source, target, EdgeDirection.BIDIRECTIONAL).direction());
    }

    @Test
    void graphSchemaHasDescriptionAndLookup() {
        VertexSchema person = new VertexSchema(1, "person", null);
        VertexSchema company = new VertexSchema(2, "company", null);
        EdgeSchema worksAt = new EdgeSchema(1, "works_at",
                new EdgePropertySchema(1, "source", null, PropertyType.ScalarType.LONG),
                new EdgePropertySchema(2, "target", null, PropertyType.ScalarType.LONG));

        GraphSchema graph = new GraphSchema(1, "social", "social graph")
                .vertex(person)
                .vertex(company)
                .edge(worksAt);

        assertEquals("social graph", graph.description());
        assertSame(person, graph.vertexSchema(1));
        assertSame(person, graph.vertexSchema("person"));
        assertSame(company, graph.vertexSchema("company"));
        assertSame(worksAt, graph.edgeSchema(1));
        assertSame(worksAt, graph.edgeSchema("works_at"));
        assertNull(graph.vertexSchema(99));
        assertNull(graph.edgeSchema("missing"));
    }

    @Test
    void graphSchemaRejectsDuplicateVertexOrEdge() {
        GraphSchema graph = new GraphSchema(1, "g", null).vertex(new VertexSchema(1, "person", null));

        assertThrows(IllegalArgumentException.class, () -> graph.vertex(new VertexSchema(1, "other", null)));
        assertThrows(IllegalArgumentException.class, () -> graph.vertex(new VertexSchema(2, "person", null)));

        graph.edge(new EdgeSchema(1, "e",
                new EdgePropertySchema(1, "source", null, PropertyType.ScalarType.LONG),
                new EdgePropertySchema(2, "target", null, PropertyType.ScalarType.LONG)));
        assertThrows(IllegalArgumentException.class, () -> graph.edge(new EdgeSchema(1, "e2",
                new EdgePropertySchema(1, "source", null, PropertyType.ScalarType.LONG),
                new EdgePropertySchema(2, "target", null, PropertyType.ScalarType.LONG))));
        assertThrows(IllegalArgumentException.class, () -> graph.edge(new EdgeSchema(2, "e",
                new EdgePropertySchema(1, "source", null, PropertyType.ScalarType.LONG),
                new EdgePropertySchema(2, "target", null, PropertyType.ScalarType.LONG))));
    }

    @Test
    void validatesRequiredFields() {
        assertThrows(IllegalArgumentException.class, () -> new VertexSchema(1, "  ", null));
        assertThrows(IllegalArgumentException.class, () -> new GraphSchema(1, " "));
        assertThrows(NullPointerException.class, () -> new EdgeSchema(1, "e",
                new EdgePropertySchema(1, "source", null, PropertyType.ScalarType.LONG), null));
    }
}
