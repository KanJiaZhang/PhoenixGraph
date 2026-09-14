package com.pengcheng.phoenixgraph.schema;

import com.pengcheng.phoenixgraph.schema.json.GraphMetaSchemaJsonCodec;
import com.pengcheng.phoenixgraph.schema.json.SchemaJsonCodec;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphMetaSchemaTest {

    private final SchemaJsonCodec schemaCodec = new SchemaJsonCodec();
    private final GraphMetaSchemaJsonCodec metaCodec = new GraphMetaSchemaJsonCodec();

    @Test
    void metaSchemaJsonRoundTrips() {
        GraphMetaSchema meta = new GraphMetaSchema(1, "payment-meta", "bounds",
                1000, 1000, 500,
                List.of(new PropertyConstraintBounds(PropertyType.ScalarType.INT, -100, 100)));

        GraphMetaSchema parsed = metaCodec.fromJson(metaCodec.toJson(meta));

        assertEquals(meta.id(), parsed.id());
        assertEquals(meta.name(), parsed.name());
        assertEquals(1000, parsed.maxInDegree());
        assertEquals(1000, parsed.maxOutDegree());
        assertEquals(500, parsed.maxDegree());
        assertEquals(1, parsed.propertyBounds().size());
        assertEquals(PropertyType.ScalarType.INT, parsed.boundFor(PropertyType.ScalarType.INT).type());
        assertEquals(-100, parsed.boundFor(PropertyType.ScalarType.INT).min().intValue());
        assertEquals(100, parsed.boundFor(PropertyType.ScalarType.INT).max().intValue());
    }

    @Test
    void loadsMetaSchemaResource() throws IOException {
        GraphMetaSchema meta = metaCodec.fromJson(readResource("/GraphMetaSchema.json"));
        assertEquals("payment-meta-schema", meta.name());
        assertEquals(1000, meta.maxInDegree());
        assertNotNull(meta.boundFor(PropertyType.ScalarType.INT));
        assertNotNull(meta.boundFor(PropertyType.ScalarType.DOUBLE));
    }

    @Test
    void schemaJsonRoundTripsPropertyConstraints() {
        VertexSchema person = new VertexSchema(1, "person", null)
                .property(new VertexPropertySchema(1, "age", "Age", PropertyType.ScalarType.INT,
                        PropertyConstraints.range(0, 150)))
                .property(new VertexPropertySchema(2, "name", "Name", PropertyType.ScalarType.STRING,
                        PropertyConstraints.length(1, 50)));
        GraphSchema graph = new GraphSchema(1, "g", null).vertex(person);

        GraphSchema parsed = schemaCodec.fromJson(schemaCodec.toJson(graph));

        assertEquals(PropertyConstraints.range(0, 150),
                parsed.vertexSchema("person").property("age").constraints());
        assertEquals(PropertyConstraints.length(1, 50),
                parsed.vertexSchema("person").property("name").constraints());
    }

    @Test
    void loadsSchemaJsonConstraintsFromResource() throws IOException {
        GraphSchema graph = schemaCodec.fromJson(readResource("/GraphSchema.json"));

        assertEquals(0.0, graph.edgeSchema("Payment").property("amount").constraints().min().doubleValue());
        assertEquals(3, graph.vertexSchema("Phone").property("phoneNumber").constraints().minLength().intValue());
        assertEquals(20, graph.vertexSchema("Phone").property("phoneNumber").constraints().maxLength().intValue());
    }

    @Test
    void validatorRejectsConstraintOutsideMetaBound() {
        GraphMetaSchema meta = new GraphMetaSchema(1, "m", null, 0, 0, 0,
                List.of(new PropertyConstraintBounds(PropertyType.ScalarType.INT, 0, 100)));
        VertexSchema person = new VertexSchema(1, "person", null)
                .property(new VertexPropertySchema(1, "age", null, PropertyType.ScalarType.INT,
                        PropertyConstraints.range(-5, 150)));
        GraphSchema graph = new GraphSchema(1, "g", null).vertex(person);

        List<String> errors = new SchemaValidator().validate(graph, SchemaConstraints.DEFAULTS, meta);

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("outside meta-schema bound"));
    }

    @Test
    void validatorRejectsNonNumericMinMax() {
        GraphMetaSchema meta = new GraphMetaSchema(1, "m", null);
        VertexSchema person = new VertexSchema(1, "person", null)
                .property(new VertexPropertySchema(1, "name", null, PropertyType.ScalarType.STRING,
                        PropertyConstraints.range(0, 10)));
        GraphSchema graph = new GraphSchema(1, "g", null).vertex(person);

        List<String> errors = new SchemaValidator().validate(graph, SchemaConstraints.DEFAULTS, meta);

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("not numeric"));
    }

    @Test
    void validatorRejectsDegreeAboveMetaCap() {
        GraphMetaSchema meta = new GraphMetaSchema(1, "m", null, 10, 20, 0);
        EdgeSchema edge = new EdgeSchema(1, "e", null,
                new EdgePropertySchema(1, "source", null, PropertyType.ScalarType.LONG),
                new EdgePropertySchema(2, "target", null, PropertyType.ScalarType.LONG))
                .maxIncomingEdges(15); // > meta.maxInDegree (10)
        GraphSchema graph = new GraphSchema(1, "g", null).edge(edge);

        List<String> errors = new SchemaValidator().validate(graph, SchemaConstraints.DEFAULTS, meta);

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("maxInDegree"));
    }

    @Test
    void dataQualityValidatorEnforcesEnumRangeAndLength() {
        DataQualityValidator validator = new DataQualityValidator();

        VertexPropertySchema age = new VertexPropertySchema(1, "age", null, PropertyType.ScalarType.INT,
                PropertyConstraints.range(0, 150));
        assertTrue(validator.validate(age, 200).size() == 1);
        assertTrue(validator.validate(age, 30).isEmpty());

        VertexPropertySchema status = new VertexPropertySchema(1, "status", null,
                PropertyType.enumOf("ACTIVE", "FROZEN", "CLOSED"));
        assertTrue(validator.validate(status, "BANNED").size() == 1);
        assertTrue(validator.validate(status, "ACTIVE").isEmpty());

        VertexPropertySchema name = new VertexPropertySchema(1, "name", null, PropertyType.ScalarType.STRING,
                PropertyConstraints.length(2, 10));
        assertTrue(validator.validate(name, "a").size() == 1);
        assertTrue(validator.validate(name, "alice").isEmpty());
    }

    private static String readResource(String path) throws IOException {
        try (InputStream in = GraphMetaSchemaTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "Missing resource: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
