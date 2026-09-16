package com.pengcheng.phoenixgraph.query.aerospike;

import com.pengcheng.phoenixgraph.schema.EdgeDirection;
import com.pengcheng.phoenixgraph.schema.EdgeEndpointSchema;
import com.pengcheng.phoenixgraph.schema.EdgePropertySchema;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.MetaPropertySchema;
import com.pengcheng.phoenixgraph.schema.PropertyType;
import com.pengcheng.phoenixgraph.schema.VertexPropertySchema;
import com.pengcheng.phoenixgraph.schema.VertexSchema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Detection of the HNSW layout from a graph schema (which vertex types have a
 * layered index) - the hook that turns {@code nearest(k)} on for a graph without
 * any extra configuration.
 */
class AerospikeHnswIndexFactoryTest {

    @Test
    void detectsTheLayeredVertexTypes() {
        GraphSchema hnsw = hnswSchema(3);
        assertTrue(AerospikeHnswIndexFactory.isHnswVertex(hnsw, "Vector"));
        assertEquals(List.of("Vector"), AerospikeHnswIndexFactory.vectorVertexTypes(hnsw));
        assertEquals("vector", AerospikeHnswIndexFactory.vectorProperty(hnsw, "Vector"));
    }

    @Test
    void ignoresGraphsWithoutLayerEdges() {
        GraphSchema plain = new GraphSchema(1, "payment-graph", null);
        plain.vertex(new VertexSchema(1, "Account", null,
                new VertexPropertySchema(0, "id", null, PropertyType.ScalarType.LONG),
                new MetaPropertySchema(-2, "createdTime", null),
                new MetaPropertySchema(-1, "lastUpdatedTime", null)));
        assertEquals(List.of(), AerospikeHnswIndexFactory.vectorVertexTypes(plain));
        assertFalse(AerospikeHnswIndexFactory.isHnswVertex(plain, "Account"));
    }

    @Test
    void aDirectedOrForeignLayerEdgeDoesNotCount() {
        // vector_0/vector_1 exist but are directed instead of bidirectional
        GraphSchema directed = hnswSchema(2, EdgeDirection.DIRECTED);
        assertFalse(AerospikeHnswIndexFactory.isHnswVertex(directed, "Vector"));
        // only the bottom layer exists: not a layered index
        GraphSchema partial = hnswSchema(1);
        assertFalse(AerospikeHnswIndexFactory.isHnswVertex(partial, "Vector"));
    }

    @Test
    void vectorPropertyIsTheListValuedProperty() {
        GraphSchema schema = new GraphSchema(1, "g", null);
        schema.vertex(new VertexSchema(1, "Vector", null,
                new VertexPropertySchema(0, "id", null, PropertyType.ScalarType.STRING),
                new MetaPropertySchema(-2, "createdTime", null),
                new MetaPropertySchema(-1, "lastUpdatedTime", null))
                .property(new VertexPropertySchema(1, "name", null, PropertyType.ScalarType.STRING))
                .property(new VertexPropertySchema(2, "embedding", null,
                        new PropertyType.ListType(PropertyType.ScalarType.DOUBLE))));
        assertEquals("embedding", AerospikeHnswIndexFactory.vectorProperty(schema, "Vector"));
        assertThrows(IllegalArgumentException.class,
                () -> AerospikeHnswIndexFactory.vectorProperty(schema, "Missing"));
    }

    /** A schema with a Vector vertex type and the first {@code layers} bidirectional layer classes. */
    private static GraphSchema hnswSchema(int layers) {
        return hnswSchema(layers, EdgeDirection.BIDIRECTIONAL);
    }

    private static GraphSchema hnswSchema(int layers, EdgeDirection direction) {
        GraphSchema schema = new GraphSchema(3, "hnsw-sift", null);
        schema.vertex(new VertexSchema(1, "Vector", null,
                new VertexPropertySchema(0, "id", null, PropertyType.ScalarType.STRING),
                new MetaPropertySchema(-2, "createdTime", null),
                new MetaPropertySchema(-1, "lastUpdatedTime", null))
                .property(new VertexPropertySchema(1, "name", null, PropertyType.ScalarType.STRING))
                .property(new VertexPropertySchema(2, "vector", null,
                        new PropertyType.ListType(PropertyType.ScalarType.DOUBLE))));
        for (int level = 0; level < layers; level++) {
            schema.edge(new EdgeSchema(level + 1, "vector_" + level, null,
                    new EdgePropertySchema(0, "id", null, PropertyType.ScalarType.STRING),
                    new EdgeEndpointSchema(1, "source", null, PropertyType.ScalarType.STRING, "Vector"),
                    new EdgeEndpointSchema(2, "target", null, PropertyType.ScalarType.STRING, "Vector"),
                    new MetaPropertySchema(-2, "createdTime", null),
                    new MetaPropertySchema(-1, "lastUpdatedTime", null),
                    direction, 60, 60, 60));
        }
        return schema;
    }
}
