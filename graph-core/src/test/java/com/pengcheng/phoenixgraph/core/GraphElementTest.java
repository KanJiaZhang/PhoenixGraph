package com.pengcheng.phoenixgraph.core;

import com.pengcheng.phoenixgraph.schema.EdgePropertySchema;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.PropertyType;
import com.pengcheng.phoenixgraph.schema.VertexPropertySchema;
import com.pengcheng.phoenixgraph.schema.VertexSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphElementTest {

    @Test
    void vertexInstantiatesTypedPropertiesFromSchema() {
        Vertex person = new SimpleVertex(personSchema(), 100L,
                Map.of("name", "alice", "age", 30));

        assertEquals(100L, person.id());
        assertEquals("person", person.schema().name());

        assertEquals("alice", person.property("name").value());
        assertEquals(PropertyType.ScalarType.STRING, person.property("name").schema().type());
        assertEquals(30, person.property("age").value());
        assertEquals(PropertyType.ScalarType.INT, person.property(2).schema().type());

        // missing "tags" -> typed default (empty list)
        assertEquals(List.of(), person.property("tags").value());
        assertInstanceOfList(person.property("tags").value());

        assertNotNull(person.meta());
        assertTrue(person.meta().createdTime() > 0);
        assertEquals(person.meta().createdTime(), person.meta().lastUpdatedTime());
    }

    @Test
    void vertexRejectsWrongTypedValue() {
        assertThrows(IllegalArgumentException.class,
                () -> new SimpleVertex(personSchema(), 100L, Map.of("age", "not-an-int")));
    }

    @Test
    void edgeInstantiatesPropertiesAndEndpoints() {
        Edge edge = new SimpleEdge(paymentSchema(), 7L, 1L, 2L,
                Map.of("amount", 12.5, "currency", "USD"));

        assertEquals(7L, edge.edgeId());
        assertEquals(1L, edge.source());
        assertEquals(2L, edge.destination());
        assertEquals("Payment", edge.schema().name());

        assertEquals(12.5, edge.property("amount").value());
        assertEquals(PropertyType.ScalarType.DOUBLE, edge.property("amount").schema().type());
        assertEquals("USD", edge.property("currency").value());
        assertNotNull(edge.meta());
    }

    @Test
    void edgeRejectsWrongEndpointType() {
        assertThrows(IllegalArgumentException.class,
                () -> new SimpleEdge(paymentSchema(), 7L, "wrong-source", 2L, Map.of()));
    }

    @Test
    void vertexSupportsObjectListProperty() {
        VertexSchema schema = new VertexSchema(1, "thing", null)
                .property(new VertexPropertySchema(1, "mixed", null, PropertyType.objectList()));

        Vertex vertex = new SimpleVertex(schema, 1L, Map.of("mixed", List.of(1, 2L, 3.5, "four")));

        assertEquals(List.of(1, 2L, 3.5, "four"), vertex.property("mixed").value());
        PropertyType type = vertex.property("mixed").schema().type();
        assertEquals(PropertyType.ScalarType.OBJECT, ((PropertyType.ListType) type).elementType());
    }

    @Test
    void vertexSupportsMapPropertyWithPerEntryTimestamps() {
        VertexSchema schema = new VertexSchema(1, "profile", null)
                .property(new VertexPropertySchema(1, "attributes", null, PropertyType.mapOf(PropertyType.ScalarType.STRING)));

        Vertex vertex = new SimpleVertex(schema, 1L, Map.of("attributes", Map.of("nickname", "alice")));

        Property property = vertex.property("attributes");
        assertInstanceOf(MapProperty.class, property);
        MapProperty map = (MapProperty) property;

        MapEntryValue nickname = map.get("nickname");
        assertEquals("alice", nickname.value());
        assertTrue(nickname.createdTime() > 0);

        // add/remove entries individually (each carries its own timestamps)
        MapEntryValue added = map.put("city", "Shanghai");
        assertEquals("Shanghai", added.value());
        assertTrue(added.createdTime() > 0);
        assertTrue(map.containsKey("city"));

        MapEntryValue removed = map.remove("nickname");
        assertEquals("alice", removed.value());
        assertFalse(map.containsKey("nickname"));
    }

    @Test
    void vertexPropertyUpdatePropagatesLastUpdatedTime() {
        Vertex person = new SimpleVertex(personSchema(), 100L,
                Map.of("name", "alice", "age", 30), new MetaProperty(100L, 200L));

        person.updateProperty("age", 31, 300L);
        assertEquals(300L, person.meta().lastUpdatedTime());
        assertEquals(100L, person.meta().createdTime()); // createdTime unchanged
        assertEquals(31, person.property("age").value());
        assertEquals(300L, person.property("age").meta().lastUpdatedTime());

        // older timestamp -> vertex lastUpdatedTime stays
        person.updateProperty("name", "bob", 150L);
        assertEquals(300L, person.meta().lastUpdatedTime());
        assertEquals("bob", person.property("name").value());
        assertEquals(150L, person.property("name").meta().lastUpdatedTime());
    }

    @Test
    void edgePropertyUpdatePropagatesLastUpdatedTime() {
        Edge edge = new SimpleEdge(paymentSchema(), 7L, 1L, 2L,
                Map.of("amount", 12.5), new MetaProperty(100L, 200L));

        edge.updateProperty("amount", 99.9, 250L);
        assertEquals(250L, edge.meta().lastUpdatedTime());
        assertEquals(99.9, edge.property("amount").value());

        edge.updateProperty("currency", "CNY", 100L);
        assertEquals(250L, edge.meta().lastUpdatedTime()); // unchanged
        assertEquals("CNY", edge.property("currency").value());
    }

    private static VertexSchema personSchema() {
        return new VertexSchema(1, "person", "a person")
                .property(new VertexPropertySchema(1, "name", "name", PropertyType.ScalarType.STRING))
                .property(new VertexPropertySchema(2, "age", "age", PropertyType.ScalarType.INT))
                .property(new VertexPropertySchema(3, "tags", "tags", PropertyType.listOf(PropertyType.ScalarType.STRING)));
    }

    private static EdgeSchema paymentSchema() {
        return new EdgeSchema(1, "Payment", "a payment",
                new EdgePropertySchema(1, "source", "sender", PropertyType.ScalarType.LONG),
                new EdgePropertySchema(2, "target", "receiver", PropertyType.ScalarType.LONG))
                .property(new EdgePropertySchema(3, "amount", "amount", PropertyType.ScalarType.DOUBLE))
                .property(new EdgePropertySchema(4, "currency", "currency", PropertyType.ScalarType.STRING));
    }

    private static void assertInstanceOfList(Object value) {
        if (!(value instanceof List<?>)) {
            throw new AssertionError("Expected a List but got: " + value);
        }
    }
}
