package com.pengcheng.phoenixgraph.query.aerospike;

import com.pengcheng.phoenixgraph.hnsw.HnswConfig;
import com.pengcheng.phoenixgraph.hnsw.HnswEntryPoint;
import com.pengcheng.phoenixgraph.hnsw.HnswMetadata;
import com.pengcheng.phoenixgraph.hnsw.HnswMetric;
import com.pengcheng.phoenixgraph.schema.EdgeDirection;
import com.pengcheng.phoenixgraph.schema.EdgeEndpointSchema;
import com.pengcheng.phoenixgraph.schema.EdgePropertySchema;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.MetaPropertySchema;
import com.pengcheng.phoenixgraph.schema.PropertyType;
import com.pengcheng.phoenixgraph.schema.VectorIndexConfig;
import com.pengcheng.phoenixgraph.schema.VectorIndexSchemas;
import com.pengcheng.phoenixgraph.schema.VertexPropertySchema;
import com.pengcheng.phoenixgraph.schema.VertexSchema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The schema-driven part of the HNSW adapter: which properties are vectors, how
 * the layer edge classes are named/created and how the index configuration is
 * derived from the schema (plus an existing build).
 */
class AerospikeHnswIndexFactoryTest {

    @Test
    void detectsTheVectorProperties() {
        GraphSchema schema = vectorSchema(VectorIndexConfig.DEFAULT, 3);
        List<VectorIndexSchemas.VectorProperty> properties = AerospikeHnswIndexFactory.vectorProperties(schema);
        assertEquals(1, properties.size());
        assertEquals("Vector", properties.get(0).vertexName());
        assertEquals("vector", properties.get(0).propertyName());
        assertEquals(3, properties.get(0).declaredLevels());
        assertEquals(2, properties.get(0).topLevel());
        assertTrue(properties.get(0).config().sameAs(VectorIndexConfig.DEFAULT));
    }

    @Test
    void ignoresVertexTypesWithoutVectorProperties() {
        GraphSchema plain = new GraphSchema(1, "payment-graph", null);
        plain.vertex(new VertexSchema(1, "Account", null,
                new VertexPropertySchema(0, "id", null, PropertyType.ScalarType.LONG),
                MetaPropertySchema.createdTime(), MetaPropertySchema.lastUpdatedTime())
                .property(new VertexPropertySchema(1, "accountStatus", null, PropertyType.ScalarType.STRING)));
        assertEquals(List.of(), AerospikeHnswIndexFactory.vectorProperties(plain));
        assertTrue(AerospikeHnswIndexFactory.vectorProperties(plain, "Account").isEmpty());
    }

    @Test
    void layerEdgeClassesAreCreatedOnDemand() {
        // a schema with the vector property but no layer edge classes at all
        GraphSchema schema = vectorSchema(VectorIndexConfig.DEFAULT, 0);
        assertEquals(0, VectorIndexSchemas.declaredLevels(schema, "Vector", "vector"));

        AerospikeHnswIndexFactory.provisioner(schema).ensureLayer("Vector", "vector", 3);

        assertEquals(4, VectorIndexSchemas.declaredLevels(schema, "Vector", "vector"));
        assertEquals(4, schema.edgeSchemas().size());
        EdgeSchema layer0 = schema.edgeSchema("vector_0");
        assertNotNull(layer0);
        assertEquals(EdgeDirection.BIDIRECTIONAL, layer0.direction());
        assertEquals("Vector", layer0.sourceVertexName());
        assertEquals("Vector", layer0.targetVertexName());
        // 2 * (2 * m) for the bottom layer, 2 * m above it (head-room over the index' cap)
        assertEquals(4 * VectorIndexConfig.DEFAULT.m(), layer0.maxDegree());
        assertEquals(2 * VectorIndexConfig.DEFAULT.m(), schema.edgeSchema("vector_1").maxDegree());

        // ids are deterministic and layers already declared keep theirs
        int firstId = layer0.id();
        AerospikeHnswIndexFactory.provisioner(schema).ensureLayer("Vector", "vector", 3);
        assertEquals(4, schema.edgeSchemas().size());
        assertEquals(firstId, schema.edgeSchema("vector_0").id());
        assertTrue(VectorIndexSchemas.levelOfLayerEdge("vector", "vector_3") == 3);
        assertTrue(VectorIndexSchemas.levelOfLayerEdge("vector", "embedding_3") == -1);
    }

    @Test
    void existingLayerEdgeClassesAreKept() {
        // the demo schema declares vector_0..vector_2 with ids 1..3
        GraphSchema schema = vectorSchema(VectorIndexConfig.DEFAULT, 3);
        AerospikeHnswIndexFactory.provisioner(schema).ensureLayer("Vector", "vector", 4);
        assertEquals(5, schema.edgeSchemas().size());
        assertEquals(1, schema.edgeSchema("vector_0").id());
        assertEquals(3, schema.edgeSchema("vector_2").id());
        assertEquals(4, schema.edgeSchema("vector_3").id());
        assertEquals(5, schema.edgeSchema("vector_4").id());
    }

    @Test
    void configurationComesFromTheSchemaAndTheMetadata() {
        VectorIndexConfig declared = new VectorIndexConfig("COSINE", 8, 64, 24);

        // no index built yet: the schema decides everything
        HnswConfig fresh = AerospikeHnswIndexFactory.config(declared, null);
        assertEquals(HnswMetric.COSINE, fresh.metric());
        assertEquals(8, fresh.m());
        assertEquals(64, fresh.efConstruction());
        assertEquals(24, fresh.efSearch());
        assertEquals(16, fresh.maxLinksLevel0());

        // an existing build wins for metric/m/efConstruction, the schema for efSearch
        HnswMetadata metadata = new HnswMetadata(1, "Vector", "vector", HnswMetric.L2, 15, 100, 50,
                128, 10_000, 14, List.of(new HnswEntryPoint("7434", 14)), 1L);
        HnswConfig existing = AerospikeHnswIndexFactory.config(declared, metadata);
        assertEquals(HnswMetric.L2, existing.metric());
        assertEquals(15, existing.m());
        assertEquals(100, existing.efConstruction());
        assertEquals(24, existing.efSearch());
    }

    @Test
    void metadataRecordsAreKeyedPerVectorProperty() {
        GraphSchema one = vectorSchema(VectorIndexConfig.DEFAULT, 1);
        assertEquals("hnsw:Vector", AerospikeHnswIndexFactory.metadataRecordKey(one, "Vector", "vector"));

        GraphSchema two = vectorSchema(VectorIndexConfig.DEFAULT, 1);
        two.vertexSchema("Vector").property(new VertexPropertySchema(3, "embedding", null,
                new PropertyType.ListType(PropertyType.ScalarType.DOUBLE)).vector(true));
        assertEquals("hnsw:Vector", AerospikeHnswIndexFactory.metadataRecordKey(two, "Vector", "vector"));
        assertEquals("hnsw:Vector:embedding",
                AerospikeHnswIndexFactory.metadataRecordKey(two, "Vector", "embedding"));
        assertEquals(2, AerospikeHnswIndexFactory.vectorProperties(two, "Vector").size());
    }

    /** A schema with one {@code Vector} vertex type declaring the given vector config. */
    private static GraphSchema vectorSchema(VectorIndexConfig config, int declaredLayers) {
        GraphSchema schema = new GraphSchema(3, "hnsw-sift", null);
        schema.vertex(new VertexSchema(1, "Vector", null,
                new VertexPropertySchema(0, "id", null, PropertyType.ScalarType.STRING),
                MetaPropertySchema.createdTime(), MetaPropertySchema.lastUpdatedTime())
                .property(new VertexPropertySchema(1, "name", null, PropertyType.ScalarType.STRING))
                .property(new VertexPropertySchema(2, "vector", null,
                        new PropertyType.ListType(PropertyType.ScalarType.DOUBLE)).vector(config)));
        for (int level = 0; level < declaredLayers; level++) {
            int maxLinks = level == 0 ? 2 * config.m() : config.m();
            schema.edge(new EdgeSchema(level + 1, "vector_" + level, null,
                    new EdgePropertySchema(0, "id", null, PropertyType.ScalarType.STRING),
                    new EdgeEndpointSchema(1, "source", null, PropertyType.ScalarType.STRING, "Vector"),
                    new EdgeEndpointSchema(2, "target", null, PropertyType.ScalarType.STRING, "Vector"),
                    MetaPropertySchema.createdTime(), MetaPropertySchema.lastUpdatedTime(),
                    EdgeDirection.BIDIRECTIONAL, 2 * maxLinks, 2 * maxLinks, 2 * maxLinks));
        }
        return schema;
    }
}
