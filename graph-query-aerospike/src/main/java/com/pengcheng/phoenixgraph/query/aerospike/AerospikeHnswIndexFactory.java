package com.pengcheng.phoenixgraph.query.aerospike;

import com.aerospike.client.AerospikeClient;
import com.pengcheng.phoenixgraph.hnsw.CachedHnswMetadataStore;
import com.pengcheng.phoenixgraph.hnsw.GraphQueryVectorSource;
import com.pengcheng.phoenixgraph.hnsw.HnswConfig;
import com.pengcheng.phoenixgraph.hnsw.HnswIndex;
import com.pengcheng.phoenixgraph.hnsw.HnswLayerProvisioner;
import com.pengcheng.phoenixgraph.hnsw.HnswMetadata;
import com.pengcheng.phoenixgraph.hnsw.HnswMetadataStore;
import com.pengcheng.phoenixgraph.hnsw.HnswMetric;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.VectorIndexConfig;
import com.pengcheng.phoenixgraph.schema.VectorIndexSchemas;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Opens the HNSW indexes of a graph schema over Aerospike.
 *
 * <p>A vertex property declared {@code "isVector": true} (see
 * {@link VectorIndexSchemas}) gets an index: the vectors live in that property,
 * the layers are the bidirectional edge classes {@code <property>_<level>}, and
 * the tunables ({@code metric}, {@code m}, {@code efConstruction},
 * {@code efSearch}) come from the property, with {@code metric}/{@code m}/
 * {@code efConstruction} overridden by the metadata record once an index exists
 * (they describe how the graph was built, while {@code efSearch} is a query-time
 * knob).
 *
 * <p>The index is ready to be written to (that is what the write path uses it for)
 * and publishes every insert, so a reader on another instance sees the new entry
 * points within the metadata TTL.
 *
 * <p>Layer edge classes are created on demand while the index grows
 * ({@link HnswLayerProvisioner} + {@code dynamicSize}), so a schema only has to
 * declare the vector property. The metadata record (entry points, size, layer
 * count) is read through {@link CachedHnswMetadataStore}, i.e. at most once per
 * minute.
 */
public final class AerospikeHnswIndexFactory {

    /** Set holding one metadata record per indexed vector property. */
    public static final String DEFAULT_METADATA_SET = "hnsw_meta";

    private AerospikeHnswIndexFactory() {
    }

    /** Every vector property of the schema (declaration order). */
    public static List<VectorIndexSchemas.VectorProperty> vectorProperties(GraphSchema schema) {
        return VectorIndexSchemas.vectorProperties(schema);
    }

    /** The vector properties of one vertex type (declaration order). */
    public static List<VectorIndexSchemas.VectorProperty> vectorProperties(GraphSchema schema, String vertexName) {
        return VectorIndexSchemas.vectorProperties(schema, vertexName);
    }

    /** Creates the layer edge classes of the property when the index needs them. */
    public static HnswLayerProvisioner provisioner(GraphSchema schema) {
        Objects.requireNonNull(schema, "schema");
        return (vertexName, vectorProperty, level) ->
                VectorIndexSchemas.ensureLayer(schema, vertexName, vectorProperty, level);
    }

    /**
     * Record key of a vector property's metadata. The graph name is part of the key: two graphs in one
     * namespace can both have a {@code Vector}/{@code embedding} index, and a shared key made one graph's
     * entry points point at vertices the other graph did not have.
     *
     * <p>{@code hnsw:<graph>:<vertex>} or {@code hnsw:<graph>:<vertex>:<property>}.
     */
    public static String metadataRecordKey(GraphSchema schema, String vertexName, String propertyName) {
        List<VectorIndexSchemas.VectorProperty> properties = vectorProperties(schema, vertexName);
        boolean first = properties.isEmpty() || properties.get(0).propertyName().equals(propertyName);
        String graph = schema == null || schema.name() == null || schema.name().isBlank() ? "" : schema.name();
        String prefix = graph.isEmpty() ? "hnsw" : "hnsw:" + graph;
        return first ? prefix + ":" + vertexName : prefix + ":" + vertexName + ":" + propertyName;
    }

    /** The index configuration of a vector property, taking an existing build into account. */
    public static HnswConfig config(VectorIndexConfig declared, HnswMetadata metadata) {
        HnswMetric metric = metadata != null ? metadata.metric() : HnswMetric.of(declared.metric());
        int m = metadata != null ? metadata.m() : declared.m();
        int efConstruction = metadata != null ? metadata.efConstruction() : declared.efConstruction();
        return new HnswConfig(metric, m, efConstruction, declared.efSearch(), m, 2 * m);
    }

    /**
     * Opens the index of one vector property, creating it when it does not exist
     * yet (the returned index accepts inserts and provisions its layers as it
     * grows).
     *
     * @param query        read layer (vectors and layer edges)
     * @param edgeStore    the vertex set holding the layer edges
     * @param schema       graph schema (extended with layer edge classes on demand)
     * @param client       connected Aerospike client
     * @param namespace    namespace holding the graph
     * @param metadataSet  set holding the metadata records
     * @param property     the vector property to index
     * @param metadataTtl  how long the metadata record may be reused before re-reading
     */
    public static HnswIndex open(AerospikeGraphQuery query, AerospikeEdgeStore edgeStore, GraphSchema schema,
                                 AerospikeClient client, String namespace, String metadataSet,
                                 VectorIndexSchemas.VectorProperty property, Duration metadataTtl) {
        Objects.requireNonNull(property, "property");
        HnswMetadataStore metadataStore = new CachedHnswMetadataStore(
                new AerospikeHnswMetadataStore(client, namespace, metadataSet,
                        metadataRecordKey(schema, property.vertexName(), property.propertyName())),
                metadataTtl);
        HnswMetadata metadata = metadataStore.read();
        return HnswIndex.builder()
                .vertexName(property.vertexName())
                .vectorProperty(property.propertyName())
                .store(new AerospikeHnswStore(edgeStore, schema, property.vertexName(), property.propertyName()))
                .vectors(new GraphQueryVectorSource(query, property.vertexName(), property.propertyName(),
                        metadata == null ? 0 : metadata.dimensions()))
                .metadataStore(metadataStore)
                .config(config(property.config(), metadata))
                .dynamicSize(true)
                .provisioner(provisioner(schema))
                // an index fed by writes publishes every insert (size and entry points),
                // so another instance picks the new vertex up within the metadata TTL
                .metadataWriteInterval(1)
                .build();
    }

    /**
     * Opens every vector index of the schema, keyed by vertex type (the index a
     * query step picks for a traverser). A vertex type with several vector
     * properties keeps its first one here; use
     * {@link #open(AerospikeGraphQuery, AerospikeEdgeStore, GraphSchema, AerospikeClient, String, String, VectorIndexSchemas.VectorProperty, Duration)}
     * to address the others.
     */
    public static Map<String, HnswIndex> open(AerospikeGraphQuery query, AerospikeEdgeStore edgeStore,
                                              GraphSchema schema, AerospikeClient client, String namespace,
                                              String metadataSet, Duration metadataTtl) {
        Map<String, HnswIndex> indexes = new LinkedHashMap<>();
        for (VectorIndexSchemas.VectorProperty property : vectorProperties(schema)) {
            indexes.putIfAbsent(property.vertexName(),
                    open(query, edgeStore, schema, client, namespace, metadataSet, property, metadataTtl));
        }
        return indexes;
    }
}
