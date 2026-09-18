package com.pengcheng.phoenixgraph.schema;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two vertex types may both call their embedding {@code embedding}. Their HNSW layer edge
 * classes must not collide: the first type keeps the historical {@code embedding_<level>}
 * names, the second gets {@code <Vertex>_embedding_<level>}.
 */
class VectorLayerNamingTest {

    private static VertexSchema vectorVertex(int id, String name, String propertyName) {
        return new VertexSchema(id, name, "A vector holder")
                .property(new VertexPropertySchema(1, propertyName, "The embedding",
                        PropertyType.listOf(PropertyType.ScalarType.DOUBLE)).vector(true));
    }

    @Test
    void oneVectorPropertyKeepsThePlainLayerNames() {
        GraphSchema schema = new GraphSchema(1, "one").vertex(vectorVertex(1, "Doc", "embedding"));

        assertEquals(3, VectorIndexSchemas.ensureLayer(schema, "Doc", "embedding", 2));
        assertEquals(List.of("embedding_0", "embedding_1", "embedding_2"),
                schema.edgeSchemas().stream().map(EdgeSchema::name).toList());
        assertNotNull(schema.edgeSchema("embedding_1"));
    }

    @Test
    void aSecondVertexTypeWithTheSamePropertyNameGetsItsOwnLayerClasses() {
        GraphSchema schema = new GraphSchema(1, "two")
                .vertex(vectorVertex(1, "Account", "embedding"))
                .vertex(vectorVertex(2, "Merchant", "embedding"));

        assertEquals(3, VectorIndexSchemas.ensureLayer(schema, "Account", "embedding", 2));
        assertEquals(3, VectorIndexSchemas.ensureLayer(schema, "Merchant", "embedding", 2));

        assertEquals(List.of("embedding_0", "embedding_1", "embedding_2",
                        "Merchant_embedding_0", "Merchant_embedding_1", "Merchant_embedding_2"),
                schema.edgeSchemas().stream().map(EdgeSchema::name).toList());

        // each lookup finds its own class, and every id is used once
        assertNotNull(VectorIndexSchemas.layerSchema(schema, "Account", "embedding", 1));
        assertEquals("embedding_1", VectorIndexSchemas.layerSchema(schema, "Account", "embedding", 1).name());
        assertEquals("Merchant_embedding_1",
                VectorIndexSchemas.layerSchema(schema, "Merchant", "embedding", 1).name());
        assertEquals(6, schema.edgeSchemas().stream().map(EdgeSchema::id).distinct().count());
    }

    @Test
    void aSchemaThatDeclaresItsLayerClassesKeepsThem() {
        // hnsw-sift declares vector_0..vector_14 by hand; those names must not be renamed
        GraphSchema schema = new GraphSchema(1, "declared")
                .vertex(vectorVertex(1, "Vector", "vector"))
                .edge(new EdgeSchema(2, "vector_0", "HNSW layer 0",
                        new EdgeEndpointSchema(1, "source", "First", PropertyType.ScalarType.STRING, "Vector"),
                        new EdgeEndpointSchema(2, "target", "Second", PropertyType.ScalarType.STRING, "Vector"),
                        EdgeDirection.BIDIRECTIONAL));

        assertEquals(0, VectorIndexSchemas.ensureLayer(schema, "Vector", "vector", 0),
                "the declared class is reused, not duplicated");
        assertEquals("vector_0", VectorIndexSchemas.layerSchema(schema, "Vector", "vector", 0).name());
        assertNull(VectorIndexSchemas.layerSchema(schema, "Other", "vector", 0));
        assertTrue(schema.edgeSchemas().size() == 1);
    }
}
