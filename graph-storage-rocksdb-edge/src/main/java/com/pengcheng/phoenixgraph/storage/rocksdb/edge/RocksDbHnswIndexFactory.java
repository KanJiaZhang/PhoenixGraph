package com.pengcheng.phoenixgraph.storage.rocksdb.edge;

import com.pengcheng.phoenixgraph.hnsw.CachedHnswMetadataStore;
import com.pengcheng.phoenixgraph.hnsw.GraphQueryHnswStore;
import com.pengcheng.phoenixgraph.hnsw.GraphQueryVectorSource;
import com.pengcheng.phoenixgraph.hnsw.HnswConfig;
import com.pengcheng.phoenixgraph.hnsw.HnswIndex;
import com.pengcheng.phoenixgraph.hnsw.HnswLayerProvisioner;
import com.pengcheng.phoenixgraph.hnsw.HnswMetadata;
import com.pengcheng.phoenixgraph.hnsw.HnswMetadataStore;
import com.pengcheng.phoenixgraph.hnsw.HnswMetric;
import com.pengcheng.phoenixgraph.query.storage.GraphQuery;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.VectorIndexConfig;
import com.pengcheng.phoenixgraph.schema.VectorIndexSchemas;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Opens (and prepares to build) the HNSW indexes of a graph served by RocksDB-Edge.
 *
 * <p>The counterpart of {@code AerospikeHnswIndexFactory}, with the same shape: a vector property
 * declared {@code "isVector": true} gets an index whose layers are the bidirectional edge classes
 * {@code <property>_<level>}, whose vectors are read from the property through the storage-agnostic
 * {@link GraphQuery}, and whose metadata (entry points, size, layer count) is one row in the graph's own
 * directory. Nothing about the index is Aerospike-specific any more than the layer classes are: they are
 * ordinary edges of the graph, so they live in their own column family and a query reads them the same
 * way it reads a payment.
 *
 * <p>Two instances exist for two jobs, which is the one place the storage shows through:
 * <ul>
 *   <li><b>search</b> ({@link #open}) reads layers through {@link GraphQueryHnswStore}, so it never
 *       writes and works for any backend;</li>
 *   <li><b>build</b> ({@link #builder}) writes them through {@link RocksDbHnswStore}, which needs the
 *       RocksDB store: an insert has to create links, and a search does not.</li>
 * </ul>
 */
public final class RocksDbHnswIndexFactory {

    private RocksDbHnswIndexFactory() {
    }

    /**
     * Declares the layer edge classes a vector index needs, before a store is opened over the schema.
     *
     * <p>RocksDB has to open <b>every</b> column family a directory contains, and a layer is an ordinary
     * edge class in a family of its own: a directory whose index was built once keeps those families, so a
     * schema loaded from its JSON file (which declares the vector property but not the layers) would refuse
     * to open it. Calling this first - with as many layers as the property declares - is therefore what
     * makes a vector-indexed RocksDB graph reopenable at all, not just a convenience.
     *
     * @param schema the schema to extend (in place)
     * @return how many layer classes were created
     */
    public static int declareLayers(GraphSchema schema) {
        Objects.requireNonNull(schema, "schema");
        int levels = 0;
        for (VectorIndexSchemas.VectorProperty property : vectorProperties(schema)) {
            levels = Math.max(levels, property.declaredLevels());
        }
        if (levels == 0) {
            return 0;
        }
        return VectorIndexSchemas.ensureLayers(schema, levels - 1);
    }

    /** Every vector property of the schema (declaration order). */
    public static List<VectorIndexSchemas.VectorProperty> vectorProperties(GraphSchema schema) {
        return VectorIndexSchemas.vectorProperties(schema);
    }

    /** The vector properties of one vertex type (declaration order). */
    public static List<VectorIndexSchemas.VectorProperty> vectorProperties(GraphSchema schema,
                                                                          String vertexName) {
        return VectorIndexSchemas.vectorProperties(schema, vertexName);
    }

    /** Creates the layer edge classes of the property while the index grows. */
    public static HnswLayerProvisioner provisioner(GraphSchema schema) {
        Objects.requireNonNull(schema, "schema");
        return (vertexName, vectorProperty, level) ->
                VectorIndexSchemas.ensureLayer(schema, vertexName, vectorProperty, level);
    }

    /** Row key of a vector property's metadata: {@code hnsw:<vertex>} or {@code hnsw:<vertex>:<property>}. */
    public static String metadataRecordKey(GraphSchema schema, String vertexName, String propertyName) {
        List<VectorIndexSchemas.VectorProperty> properties = vectorProperties(schema, vertexName);
        boolean first = properties.isEmpty() || properties.get(0).propertyName().equals(propertyName);
        return first ? "hnsw:" + vertexName : "hnsw:" + vertexName + ":" + propertyName;
    }

    /** The index configuration of a vector property, taking an existing build into account. */
    public static HnswConfig config(VectorIndexConfig declared, HnswMetadata metadata) {
        HnswMetric metric = metadata != null ? metadata.metric() : HnswMetric.of(declared.metric());
        int m = metadata != null ? metadata.m() : declared.m();
        int efConstruction = metadata != null ? metadata.efConstruction() : declared.efConstruction();
        return new HnswConfig(metric, m, efConstruction, declared.efSearch(), m, 2 * m);
    }

    /**
     * Opens the index of one vector property for <b>searching</b>: layers and vectors are read through the
     * graph query, so this needs no write access and no RocksDB store.
     *
     * @param query       the graph's read path (layer edges and vectors)
     * @param handle      the graph's open RocksDB directory (metadata row)
     * @param schema      the graph schema
     * @param property    the vector property to index
     * @param metadataTtl how long the metadata row may be reused before re-reading
     */
    public static HnswIndex open(GraphQuery query, com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbHandle handle,
                                 GraphSchema schema, VectorIndexSchemas.VectorProperty property,
                                 Duration metadataTtl) {
        Objects.requireNonNull(property, "property");
        HnswMetadataStore metadataStore = new CachedHnswMetadataStore(
                new RocksDbHnswMetadataStore(handle,
                        metadataRecordKey(schema, property.vertexName(), property.propertyName())),
                metadataTtl);
        HnswMetadata metadata = metadataStore.read();
        return HnswIndex.builder()
                .vertexName(property.vertexName())
                .vectorProperty(property.propertyName())
                .store(new GraphQueryHnswStore(query, property.vertexName(), property.propertyName()))
                .vectors(new GraphQueryVectorSource(query, property.vertexName(), property.propertyName(),
                        metadata == null ? 0 : metadata.dimensions()))
                .metadataStore(metadataStore)
                .config(config(property.config(), metadata))
                .dynamicSize(true)
                .provisioner(provisioner(schema))
                .build();
    }

    /** Opens every vector index of the schema for searching, keyed by vertex type. */
    public static Map<String, HnswIndex> open(GraphQuery query,
                                              com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbHandle handle,
                                              GraphSchema schema, Duration metadataTtl) {
        Map<String, HnswIndex> indexes = new LinkedHashMap<>();
        for (VectorIndexSchemas.VectorProperty property : vectorProperties(schema)) {
            indexes.putIfAbsent(property.vertexName(), open(query, handle, schema, property, metadataTtl));
        }
        return indexes;
    }

    /**
     * Opens the index of one vector property for <b>building</b>: inserts go through
     * {@link RocksDbHnswStore}, which writes the layer classes of the graph.
     *
     * @param store       the RocksDB edge store holding the layer edges
     * @param query       the graph's read path (vectors; layer reads are bridged by the store)
     * @param schema      graph schema (extended with layer edge classes on demand)
     * @param property    the vector property to index
     * @param overwrite   whether to start a new index instead of continuing the recorded one
     */
    public static HnswIndex builder(RocksDbEdgeStore store, GraphQuery query, GraphSchema schema,
                                    VectorIndexSchemas.VectorProperty property, boolean overwrite) {
        Objects.requireNonNull(property, "property");
        HnswMetadataStore metadataStore = new RocksDbHnswMetadataStore(store.handle(),
                metadataRecordKey(schema, property.vertexName(), property.propertyName()));
        HnswMetadata metadata = overwrite ? null : metadataStore.read();
        return HnswIndex.builder()
                .vertexName(property.vertexName())
                .vectorProperty(property.propertyName())
                .store(new RocksDbHnswStore(store, schema, property.vertexName(), property.propertyName()))
                .vectors(new GraphQueryVectorSource(query, property.vertexName(), property.propertyName(),
                        metadata == null ? 0 : metadata.dimensions()))
                .metadataStore(metadataStore)
                .config(config(property.config(), metadata))
                .dynamicSize(true)
                .provisioner(provisioner(schema))
                .overwrite(overwrite)
                .metadataWriteInterval(200)
                .build();
    }
}
