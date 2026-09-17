package com.pengcheng.phoenixgraph.schema;

import com.pengcheng.phoenixgraph.schema.json.SchemaJsonCodec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code isVector} schema flag: JSON round trip, defaults/validation of the
 * index configuration, the layer edge classes a vector property gets, and the
 * warnings/errors the validator raises.
 */
class VectorPropertyTest {

    @Test
    void isVectorSurvivesTheJsonRoundTrip() {
        GraphSchema schema = new GraphSchema(3, "embedding-graph", "vector demo");
        schema.vertex(new VertexSchema(1, "Doc", "A document",
                new VertexPropertySchema(0, "id", "Doc id", PropertyType.ScalarType.STRING),
                MetaPropertySchema.createdTime(), MetaPropertySchema.lastUpdatedTime())
                .property(new VertexPropertySchema(1, "title", "Title", PropertyType.ScalarType.STRING)
                        .display(true))
                .property(new VertexPropertySchema(2, "embedding", "The embedding",
                        new PropertyType.ListType(PropertyType.ScalarType.DOUBLE))
                        .vector(new VectorIndexConfig("COSINE", 8, 64, 24))));

        SchemaJsonCodec codec = new SchemaJsonCodec();
        String json = codec.toJson(schema);
        assertTrue(json.contains("\"isVector\" : true"), json);
        assertTrue(json.contains("\"efSearch\" : 24"), json);
        assertTrue(json.contains("\"metric\" : \"COSINE\""), json);

        GraphSchema parsed = codec.fromJson(json);
        VertexPropertySchema title = parsed.vertexSchema("Doc").property("title");
        VertexPropertySchema embedding = parsed.vertexSchema("Doc").property("embedding");
        assertFalse(title.isVector(), "a plain property is not a vector property");
        assertNull(title.vectorConfig());
        assertEquals(VectorIndexConfig.DEFAULT, title.vectorConfigOrDefault());
        assertTrue(embedding.isVector());
        assertTrue(new VectorIndexConfig("COSINE", 8, 64, 24).sameAs(embedding.vectorConfig()));
        assertTrue(embedding.toString().contains("isVector=true"));
    }

    @Test
    void indexConfigurationDefaultsAndValidation() {
        VectorIndexConfig defaults = VectorIndexConfig.of(null, null, null, null);
        assertEquals("L2", defaults.metric());
        assertEquals(15, defaults.m());
        assertEquals(100, defaults.efConstruction());
        assertEquals(50, defaults.efSearch(), "the documented default candidate list size");

        // metric names are normalised, partial configurations filled in
        assertEquals("COSINE", VectorIndexConfig.of("cosine", null, null, null).metric());
        assertEquals("INNER_PRODUCT", VectorIndexConfig.of("inner-product", null, null, null).metric());
        assertEquals(8, VectorIndexConfig.of("L2", 8, null, null).m());
        assertThrows(IllegalArgumentException.class, () -> VectorIndexConfig.of("jaccard", null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new VectorIndexConfig("L2", 0, 100, 30));
        assertThrows(IllegalArgumentException.class, () -> new VectorIndexConfig("L2", 15, 0, 30));
        assertThrows(IllegalArgumentException.class, () -> new VectorIndexConfig("L2", 15, 100, 0));
    }

    @Test
    void layerEdgeClassesAreNamedAfterTheProperty() {
        assertEquals("vector_0", VectorIndexSchemas.layerEdgeName("vector", 0));
        assertEquals("embedding_12", VectorIndexSchemas.layerEdgeName("embedding", 12));
        assertEquals(12, VectorIndexSchemas.levelOfLayerEdge("embedding", "embedding_12"));
        assertEquals(-1, VectorIndexSchemas.levelOfLayerEdge("embedding", "vector_12"));
        assertEquals(-1, VectorIndexSchemas.levelOfLayerEdge("embedding", "embedding_x"));
        assertEquals(-1, VectorIndexSchemas.levelOfLayerEdge("embedding", null));
        assertThrows(IllegalArgumentException.class, () -> VectorIndexSchemas.layerEdgeName("vector", -1));
    }

    @Test
    void ensureLayerCreatesTheMissingLayersWithStableIds() {
        GraphSchema schema = vectorSchema(new VectorIndexConfig("L2", 4, 100, 30));
        assertEquals(0, VectorIndexSchemas.declaredLevels(schema, "Doc", "embedding"));

        assertEquals(3, VectorIndexSchemas.ensureLayer(schema, "Doc", "embedding", 2));
        assertEquals(3, VectorIndexSchemas.declaredLevels(schema, "Doc", "embedding"));
        assertEquals(2, VectorIndexSchemas.vectorProperty(schema, "Doc", "embedding").topLevel());

        EdgeSchema layer0 = VectorIndexSchemas.layerSchema(schema, "Doc", "embedding", 0);
        assertEquals(EdgeDirection.BIDIRECTIONAL, layer0.direction());
        assertEquals("Doc", layer0.sourceVertexName());
        assertEquals("Doc", layer0.targetVertexName());
        assertEquals(2 * (2 * 4), layer0.maxDegree(), "layer 0 keeps 2m links, the store guards 2x that");
        assertEquals(2 * 4, VectorIndexSchemas.layerSchema(schema, "Doc", "embedding", 1).maxDegree());

        // idempotent, and the layers keep the ids they got
        int id0 = layer0.id();
        assertEquals(0, VectorIndexSchemas.ensureLayer(schema, "Doc", "embedding", 2));
        assertEquals(id0, schema.edgeSchema("embedding_0").id());

        // growing the index adds the next layers only
        assertEquals(2, VectorIndexSchemas.ensureLayer(schema, "Doc", "embedding", 4));
        assertEquals(5, VectorIndexSchemas.declaredLevels(schema, "Doc", "embedding"));

        assertThrows(IllegalArgumentException.class,
                () -> VectorIndexSchemas.ensureLayer(schema, "Doc", "title", 0));
        assertThrows(IllegalArgumentException.class,
                () -> VectorIndexSchemas.ensureLayer(schema, "Missing", "embedding", 0));
    }

    @Test
    void vectorPropertiesAreListedInDeclarationOrder() {
        GraphSchema schema = vectorSchema(VectorIndexConfig.DEFAULT);
        schema.vertex(new VertexSchema(2, "Image", "An image",
                new VertexPropertySchema(0, "id", "Image id", PropertyType.ScalarType.STRING),
                MetaPropertySchema.createdTime(), MetaPropertySchema.lastUpdatedTime())
                .property(new VertexPropertySchema(1, "pixels", "Pixels",
                        new PropertyType.ListType(PropertyType.ScalarType.FLOAT))
                        .vector(new VectorIndexConfig("COSINE", 4, 32, 8))));

        List<VectorIndexSchemas.VectorProperty> properties = VectorIndexSchemas.vectorProperties(schema);
        assertEquals(2, properties.size());
        assertEquals("Doc", properties.get(0).vertexName());
        assertEquals("embedding", properties.get(0).propertyName());
        assertEquals("Image", properties.get(1).vertexName());
        assertEquals("pixels", properties.get(1).propertyName());
        assertEquals("COSINE", properties.get(1).config().metric());
        assertEquals(1, VectorIndexSchemas.vectorProperties(schema, "Image").size());
        assertNull(VectorIndexSchemas.vectorProperty(schema, "Image", "embedding"));
    }

    @Test
    void validatorChecksTheVectorDeclaration() {
        // a string property cannot be an embedding
        GraphSchema wrongType = new GraphSchema(1, "g", null);
        wrongType.vertex(new VertexSchema(1, "Doc", null,
                new VertexPropertySchema(0, "id", null, PropertyType.ScalarType.STRING),
                MetaPropertySchema.createdTime(), MetaPropertySchema.lastUpdatedTime())
                .property(new VertexPropertySchema(1, "embedding", null, PropertyType.ScalarType.STRING)
                        .vector(true)));
        List<String> errors = new SchemaValidator().validate(wrongType, SchemaConstraints.defaults());
        assertTrue(errors.stream().anyMatch(e -> e.contains("must be a list of numbers")), errors.toString());

        // list<string> is not numeric either
        GraphSchema stringList = new GraphSchema(1, "g", null);
        stringList.vertex(new VertexSchema(1, "Doc", null,
                new VertexPropertySchema(0, "id", null, PropertyType.ScalarType.STRING),
                MetaPropertySchema.createdTime(), MetaPropertySchema.lastUpdatedTime())
                .property(new VertexPropertySchema(1, "embedding", null,
                        new PropertyType.ListType(PropertyType.ScalarType.STRING)).vector(true)));
        assertFalse(new SchemaValidator().validate(stringList, SchemaConstraints.defaults()).isEmpty());

        // list<object> works but is warned about
        GraphSchema objects = new GraphSchema(1, "g", null);
        objects.vertex(new VertexSchema(1, "Doc", null,
                new VertexPropertySchema(0, "id", null, PropertyType.ScalarType.STRING),
                MetaPropertySchema.createdTime(), MetaPropertySchema.lastUpdatedTime())
                .property(new VertexPropertySchema(1, "embedding", null,
                        new PropertyType.ListType(PropertyType.ScalarType.OBJECT)).vector(true)));
        SchemaValidationResult result = new SchemaValidator().validateResult(objects, SchemaConstraints.defaults());
        assertEquals(List.of(), result.errors());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("list<object>")), result.warnings().toString());

        // at most one vector property per vertex type
        GraphSchema twoVectors = new GraphSchema(1, "g", null);
        twoVectors.vertex(new VertexSchema(1, "Doc", null,
                new VertexPropertySchema(0, "id", null, PropertyType.ScalarType.STRING),
                MetaPropertySchema.createdTime(), MetaPropertySchema.lastUpdatedTime())
                .property(new VertexPropertySchema(1, "a", null,
                        new PropertyType.ListType(PropertyType.ScalarType.DOUBLE)).vector(true))
                .property(new VertexPropertySchema(2, "b", null,
                        new PropertyType.ListType(PropertyType.ScalarType.DOUBLE)).vector(true)));
        List<String> twoVectorErrors =
                new SchemaValidator().validate(twoVectors, SchemaConstraints.defaults());
        assertTrue(twoVectorErrors.stream().anyMatch(e -> e.contains("at most one")), twoVectorErrors.toString());
    }

    /** A schema with one {@code Doc} vertex type and an {@code embedding} vector property. */
    private static GraphSchema vectorSchema(VectorIndexConfig config) {
        GraphSchema schema = new GraphSchema(1, "g", null);
        schema.vertex(new VertexSchema(1, "Doc", null,
                new VertexPropertySchema(0, "id", null, PropertyType.ScalarType.STRING),
                MetaPropertySchema.createdTime(), MetaPropertySchema.lastUpdatedTime())
                .property(new VertexPropertySchema(1, "title", null, PropertyType.ScalarType.STRING))
                .property(new VertexPropertySchema(2, "embedding", null,
                        new PropertyType.ListType(PropertyType.ScalarType.DOUBLE)).vector(config)));
        return schema;
    }
}
