package com.pengcheng.phoenixgraph.schema;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SchemaTest {

    @Test
    void propertyTypesAreConstructible() {
        assertEquals(PropertyType.ScalarType.INT, PropertyType.ScalarType.INT);

        PropertyType list = PropertyType.listOf(PropertyType.ScalarType.STRING);
        assertInstanceOf(PropertyType.ListType.class, list);
        assertEquals(PropertyType.ScalarType.STRING, ((PropertyType.ListType) list).elementType());

        PropertyType map = PropertyType.mapOf(PropertyType.ScalarType.STRING);
        assertInstanceOf(PropertyType.MapType.class, map);
        assertEquals(PropertyType.ScalarType.STRING, ((PropertyType.MapType) map).valueType());

        PropertyType enumType = PropertyType.enumOf("A", "B", "C");
        assertInstanceOf(PropertyType.EnumType.class, enumType);
        assertEquals(Set.of("A", "B", "C"), ((PropertyType.EnumType) enumType).values());
    }

    @Test
    void metaPropertySchemaDefinesCreatedAndLastUpdatedTime() {
        MetaPropertySchema created = MetaPropertySchema.createdTime();
        assertEquals(MetaPropertySchema.CREATED_TIME_ID, created.id());
        assertEquals(-2, created.id());
        assertEquals("createdTime", created.name());
        assertEquals(PropertyType.ScalarType.LONG, created.type());

        MetaPropertySchema updated = MetaPropertySchema.lastUpdatedTime();
        assertEquals(MetaPropertySchema.LAST_UPDATED_TIME_ID, updated.id());
        assertEquals(-1, updated.id());
        assertEquals("lastUpdatedTime", updated.name());
        assertEquals(PropertyType.ScalarType.LONG, updated.type());
    }

    @Test
    void metaPropertySchemaAlwaysHasLongType() {
        MetaPropertySchema any = new MetaPropertySchema(10, "custom", "custom meta");
        assertEquals(PropertyType.ScalarType.LONG, any.type());
    }

    @Test
    void vertexPropertySchemaCarriesIdNameDescriptionAndType() {
        VertexPropertySchema age = new VertexPropertySchema(3, "age", "person age", PropertyType.ScalarType.INT);
        assertEquals(3, age.id());
        assertEquals("age", age.name());
        assertEquals("person age", age.description());
        assertEquals(PropertyType.ScalarType.INT, age.type());
    }

    @Test
    void rejectsBlankName() {
        assertThrows(IllegalArgumentException.class,
                () -> new VertexPropertySchema(1, "  ", null, PropertyType.ScalarType.STRING));
    }
}
