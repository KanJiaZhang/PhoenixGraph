package com.pengcheng.phoenixgraph.query.aerospike;

import com.aerospike.client.AerospikeClient;
import com.pengcheng.phoenixgraph.hnsw.GraphQueryVectorSource;
import com.pengcheng.phoenixgraph.hnsw.HnswIndex;
import com.pengcheng.phoenixgraph.hnsw.HnswLevels;
import com.pengcheng.phoenixgraph.hnsw.HnswMetadata;
import com.pengcheng.phoenixgraph.hnsw.HnswMetadataStore;
import com.pengcheng.phoenixgraph.schema.EdgeDirection;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.PropertyType;
import com.pengcheng.phoenixgraph.schema.VertexPropertySchema;
import com.pengcheng.phoenixgraph.schema.VertexSchema;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Opens the HNSW indexes of a graph schema over Aerospike.
 *
 * <p>A vertex type carries an HNSW index when its schema declares the layer edge
 * classes {@code vector_0} (bottom, every vertex) and {@code vector_1} as
 * bidirectional self-edges ({@code Vector -> Vector}) - the layout produced by
 * {@code tools/generate-hnsw-schema.js}. The index is only opened when its
 * metadata record exists, i.e. once {@code HnswIndex} has built something, so an
 * empty dataset simply has no vector search.
 */
public final class AerospikeHnswIndexFactory {

    /** Set holding one metadata record per indexed vertex type. */
    public static final String DEFAULT_METADATA_SET = "hnsw_meta";

    private AerospikeHnswIndexFactory() {
    }

    /** Vertex types of the schema that are laid out as an HNSW index. */
    public static List<String> vectorVertexTypes(GraphSchema schema) {
        Objects.requireNonNull(schema, "schema");
        List<String> types = new ArrayList<>();
        for (VertexSchema vertex : schema.vertexSchemas()) {
            if (isHnswVertex(schema, vertex.name())) {
                types.add(vertex.name());
            }
        }
        return types;
    }

    /** Whether the vertex type has the layered HNSW edge classes. */
    public static boolean isHnswVertex(GraphSchema schema, String vertexName) {
        return isLayerEdge(schema, vertexName, 0) && isLayerEdge(schema, vertexName, 1);
    }

    private static boolean isLayerEdge(GraphSchema schema, String vertexName, int level) {
        EdgeSchema edge = schema.edgeSchema(HnswLevels.edgeName(level));
        if (edge == null || edge.direction() == EdgeDirection.DIRECTED) {
            return false;
        }
        return isSelfEndpoint(edge.source(), vertexName) && isSelfEndpoint(edge.target(), vertexName);
    }

    private static boolean isSelfEndpoint(com.pengcheng.phoenixgraph.schema.EdgeEndpointSchema endpoint,
                                          String vertexName) {
        return endpoint != null && vertexName.equals(endpoint.vertexName());
    }

    /**
     * The property holding the vectors of a vertex type: the (first) list-valued
     * property, e.g. {@code list<double>} for the demo schemas.
     */
    public static String vectorProperty(GraphSchema schema, String vertexName) {
        VertexSchema vertex = schema.vertexSchema(vertexName);
        if (vertex == null) {
            throw new IllegalArgumentException("Unknown vertex schema: " + vertexName);
        }
        for (VertexPropertySchema property : vertex.properties()) {
            if (property.type() instanceof PropertyType.ListType) {
                return property.name();
            }
        }
        throw new IllegalArgumentException("Vertex type '" + vertexName
                + "' declares no list property to hold the vector");
    }

    /**
     * Opens every built HNSW index of the schema, keyed by vertex type. Indexes
     * whose metadata record is absent are skipped.
     *
     * @param query        read layer (vectors and layer edges)
     * @param edgeStore    the vertex set holding the layer edges
     * @param schema       graph schema
     * @param client       connected Aerospike client
     * @param namespace    namespace holding the graph
     * @param metadataSet  set holding the metadata records
     */
    public static Map<String, HnswIndex> open(AerospikeGraphQuery query, AerospikeEdgeStore edgeStore,
                                              GraphSchema schema, AerospikeClient client, String namespace,
                                              String metadataSet) {
        Map<String, HnswIndex> indexes = new LinkedHashMap<>();
        for (String vertexName : vectorVertexTypes(schema)) {
            HnswIndex index = open(query, edgeStore, schema, client, namespace, metadataSet, vertexName);
            if (index != null) {
                indexes.put(vertexName, index);
            }
        }
        return indexes;
    }

    /** Opens the HNSW index of one vertex type, or {@code null} when it was never built. */
    public static HnswIndex open(AerospikeGraphQuery query, AerospikeEdgeStore edgeStore, GraphSchema schema,
                                 AerospikeClient client, String namespace, String metadataSet,
                                 String vertexName) {
        HnswMetadataStore metadataStore =
                new AerospikeHnswMetadataStore(client, namespace, metadataSet, vertexName);
        HnswMetadata metadata = metadataStore.read();
        if (metadata == null) {
            return null;
        }
        // the metadata of a build records the property it used; fall back to the schema
        String vectorProperty = metadata.vectorProperty() != null && !metadata.vectorProperty().isBlank()
                ? metadata.vectorProperty()
                : vectorProperty(schema, vertexName);
        return HnswIndex.builder()
                .vertexName(vertexName)
                .vectorProperty(vectorProperty)
                .store(new AerospikeHnswStore(edgeStore, schema, vertexName))
                .vectors(new GraphQueryVectorSource(query, vertexName, vectorProperty, metadata.dimensions()))
                .metadataStore(metadataStore)
                .config(metadata.toConfig())
                .build();
    }
}
