package com.pengcheng.phoenixgraph.http.service;

import com.aerospike.client.AerospikeClient;
import com.pengcheng.phoenixgraph.hnsw.CachedHnswMetadataStore;
import com.pengcheng.phoenixgraph.hnsw.CachingHnswStore;
import com.pengcheng.phoenixgraph.hnsw.HeapHnswVectorSource;
import com.pengcheng.phoenixgraph.hnsw.HnswConfig;
import com.pengcheng.phoenixgraph.hnsw.HnswEntryPoint;
import com.pengcheng.phoenixgraph.hnsw.HnswInsertResult;
import com.pengcheng.phoenixgraph.hnsw.HnswIndex;
import com.pengcheng.phoenixgraph.hnsw.HnswMetadata;
import com.pengcheng.phoenixgraph.hnsw.HnswNeighbor;
import com.pengcheng.phoenixgraph.hnsw.VectorCodec;
import com.pengcheng.phoenixgraph.http.config.StorageProperties;
import com.pengcheng.phoenixgraph.query.aerospike.AerospikeGraphQuery;
import com.pengcheng.phoenixgraph.query.aerospike.AerospikeHnswIndexFactory;
import com.pengcheng.phoenixgraph.query.aerospike.AerospikeHnswMetadataStore;
import com.pengcheng.phoenixgraph.query.aerospike.AerospikeHnswStore;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.VectorIndexConfig;
import com.pengcheng.phoenixgraph.schema.VectorIndexSchemas;
import com.pengcheng.phoenixgraph.storage.aerospike.StoredVertex;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the HNSW indexes of the served graphs and indexes the vertices that are
 * written through the REST API.
 *
 * <p>An index exists for every property a schema flags {@code "isVector": true}
 * ({@link VectorIndexSchemas}); nothing else has to be declared - the layer edge
 * classes are created on demand as the index grows (layer 0 when the graph is
 * prepared, one more layer per doubling of the dataset), and the build/query
 * tunables come from the property ({@code metric}, {@code m},
 * {@code efConstruction}, {@code efSearch}, defaults {@code L2}/15/100/30).
 *
 * <p>Entry points live in the dedicated metadata record (see
 * {@link CachedHnswMetadataStore}), which is read at most once per minute and
 * written by the instance that indexes a vertex, so several instances can share
 * one graph.
 *
 * <p>All indexes of a graph are opened together the first time they are needed
 * (query or write), and the same instances serve both paths - a query therefore
 * sees everything this instance indexed without waiting for a refresh.
 */
@Service
public class VectorIndexManager {

    /** How long a metadata record (entry points, size, layer count) may be reused. */
    public static final Duration METADATA_TTL = CachedHnswMetadataStore.DEFAULT_TTL;

    /** Ids whose vectors are read in one batch while reloading an index. */
    private static final int VECTOR_READ_BATCH = 500;

    private final GraphResolver graphs;
    private final AerospikeClient client;
    private final String namespace;
    private final String metadataSet;
    private final Map<String, Map<String, HnswIndex>> indexesByGraph = new ConcurrentHashMap<>();

    @Autowired
    public VectorIndexManager(GraphResolver graphs, AerospikeClient client, StorageProperties properties) {
        this(graphs, client, properties.namespace(), AerospikeHnswIndexFactory.DEFAULT_METADATA_SET);
    }

    public VectorIndexManager(GraphResolver graphs, AerospikeClient client, String namespace, String metadataSet) {
        this.graphs = Objects.requireNonNull(graphs, "graphs");
        this.client = Objects.requireNonNull(client, "client");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.metadataSet = Objects.requireNonNull(metadataSet, "metadataSet");
    }

    /**
     * Creates the layer edge classes the graph already needs: the bottom layer for
     * every vector property (a schema never has to list them) and, when an index has
     * been built, as many layers as its metadata promises.
     */
    @PostConstruct
    public void ensureLayerEdges() {
        for (GraphSchema schema : graphs.schemas()) {
            for (VectorIndexSchemas.VectorProperty property : VectorIndexSchemas.vectorProperties(schema)) {
                VectorIndexSchemas.ensureLayer(schema, property.vertexName(), property.propertyName(),
                        Math.max(topLevelOf(schema, property), 0));
            }
        }
    }

    /** Highest layer to provide for a vector property: the declared ones or the built index'. */
    private int topLevelOf(GraphSchema schema, VectorIndexSchemas.VectorProperty property) {
        int declared = property.topLevel();
        HnswMetadata metadata = new AerospikeHnswMetadataStore(client, namespace, metadataSet,
                AerospikeHnswIndexFactory.metadataRecordKey(schema, property.vertexName(), property.propertyName()))
                .read();
        return metadata == null ? declared : Math.max(declared, metadata.topLevel());
    }

    /**
     * The index per vertex type of a graph - the map a query engine registers with
     * {@code GremlinGraphQuery.hnswIndex}, since the {@code nearest(k)} step picks
     * its index by the traverser's vertex type. A vertex type with several vector
     * properties keeps its first one here.
     */
    public Map<String, HnswIndex> indexesOf(String graphName) {
        Map<String, HnswIndex> byVertexType = new LinkedHashMap<>();
        for (HnswIndex index : indexes(graphName).values()) {
            byVertexType.putIfAbsent(index.vertexName(), index);
        }
        return Map.copyOf(byVertexType);
    }

    /** Every vector index of one vertex type of a graph (one per vector property). */
    public List<HnswIndex> indexesOf(String graphName, String vertexSchemaName) {
        List<HnswIndex> matching = new ArrayList<>();
        for (HnswIndex index : indexes(graphName).values()) {
            if (index.vertexName().equals(vertexSchemaName)) {
                matching.add(index);
            }
        }
        return matching;
    }

    /** The index of one vector property, or {@code null} when the graph has none. */
    public HnswIndex index(String graphName, String vertexSchemaName, String vectorProperty) {
        return indexes(graphName).get(key(vertexSchemaName, vectorProperty));
    }

    /**
     * Rebuilds a vector index: clears its layer edge data, reads the vectors back
     * from storage (one scan) and builds fresh edges.
     *
     * <p>Needed when the graph itself changed outside the write path (a bulk import),
     * when the build parameters changed, or when stale links have to go: the layer
     * bins and the metadata record are dropped first, so nothing of the old graph
     * survives, and the rebuilt index replaces the cached one immediately.
     *
     * @param graphName          graph to rebuild
     * @param vertexSchemaName   vertex type, or {@code null} for every vector index of the graph
     * @param vectorPropertyName vector property, or {@code null} for the type's vector property
     */
    public List<VectorIndexRebuildResult> rebuild(String graphName, String vertexSchemaName,
                                                 String vectorPropertyName) {
        GraphBinding binding = graphs.require(graphName);
        List<VectorIndexSchemas.VectorProperty> properties =
                VectorIndexSchemas.vectorProperties(binding.schema());
        if (vertexSchemaName != null && !vertexSchemaName.isBlank()) {
            properties = properties.stream()
                    .filter(property -> property.vertexName().equals(vertexSchemaName)).toList();
        }
        if (vectorPropertyName != null && !vectorPropertyName.isBlank()) {
            properties = properties.stream()
                    .filter(property -> property.propertyName().equals(vectorPropertyName)).toList();
        }
        if (properties.isEmpty()) {
            throw new IllegalArgumentException("No vector index matches graph '" + graphName
                    + "', vertex '" + vertexSchemaName + "', property '" + vectorPropertyName + "'");
        }
        List<VectorIndexRebuildResult> results = new ArrayList<>(properties.size());
        for (VectorIndexSchemas.VectorProperty property : properties) {
            results.add(rebuild(binding, property));
        }
        // the cached indexes are stale (fresh edges, new metadata): reopen them so
        // queries pick the rebuilt graph up at once
        indexesByGraph.remove(binding.name());
        indexes(binding.name());
        return results;
    }

    private VectorIndexRebuildResult rebuild(GraphBinding binding, VectorIndexSchemas.VectorProperty property) {
        long start = System.currentTimeMillis();
        GraphSchema schema = binding.schema();
        String vertexName = property.vertexName();
        String propertyName = property.propertyName();
        int vectorIndex = schema.vertexSchema(vertexName).property(propertyName).id();
        AerospikeHnswMetadataStore records = new AerospikeHnswMetadataStore(client, namespace, metadataSet,
                AerospikeHnswIndexFactory.metadataRecordKey(schema, vertexName, propertyName));
        HnswMetadata previous = records.read();

        // 1. which vertices belong to the index? The layer-0 graph reaches every indexed
        //    vertex (it is connected by construction), which works even for data written
        //    before records kept their key; a scan adds anything it can address.
        LinkedHashSet<Object> ids = new LinkedHashSet<>(walkLayerZero(binding, vertexName, propertyName,
                previous == null ? List.of() : previous.entryPoints()));
        ids.addAll(binding.vertexStore().scanVerticesWithProperty(vectorIndex, List.of(vectorIndex)).stream()
                .map(StoredVertex::vertexId).toList());

        // 2. read the vectors back in batches (one storage read per batch)
        HeapHnswVectorSource vectors = new HeapHnswVectorSource();
        List<Object> reloaded = new ArrayList<>(ids.size());
        List<Object> batch = new ArrayList<>(VECTOR_READ_BATCH);
        for (Object id : ids) {
            batch.add(id);
            if (batch.size() == VECTOR_READ_BATCH) {
                reloadVectors(binding, vertexName, vectorIndex, batch, vectors, reloaded);
                batch.clear();
            }
        }
        reloadVectors(binding, vertexName, vectorIndex, batch, vectors, reloaded);

        // 3. drop the old index: layer bins of every layer in use + the metadata record
        int layers = Math.max(previous == null ? 0 : previous.topLevel() + 1, property.declaredLevels());
        layers = Math.max(layers, 1);
        List<EdgeSchema> layerSchemas = new ArrayList<>(layers);
        for (int level = 0; level < layers; level++) {
            VectorIndexSchemas.ensureLayer(schema, vertexName, propertyName, level);
            layerSchemas.add(VectorIndexSchemas.layerSchema(schema, vertexName, propertyName, level));
        }
        binding.edgeStore().clearEdgeClasses(layerSchemas, reloaded);
        records.delete();

        // 4. build fresh edges from the vectors in memory (the heap source keeps the
        //    build free of per-insert vector reads; writes are already batched)
        HnswConfig config = AerospikeHnswIndexFactory.config(property.config(), previous);
        HnswIndex index = HnswIndex.builder()
                .vertexName(vertexName)
                .vectorProperty(propertyName)
                .store(new CachingHnswStore(new AerospikeHnswStore(binding.edgeStore(), schema, vertexName, propertyName)))
                .vectors(vectors)
                .metadataStore(new CachedHnswMetadataStore(records, METADATA_TTL))
                .config(config)
                .dynamicSize(true)
                .provisioner(AerospikeHnswIndexFactory.provisioner(schema))
                .overwrite(true)
                .metadataWriteInterval(200)
                .build();
        long links = 0;
        long pruned = 0;
        for (Object id : reloaded) {
            HnswInsertResult result = index.insert(id);
            links += result.links();
            pruned += result.pruned();
        }
        index.flush();

        return new VectorIndexRebuildResult(binding.name(), vertexName, propertyName, reloaded.size(),
                index.topLevel() + 1, links, pruned, (System.currentTimeMillis() - start) / 1000.0);
    }

    /** Collects the indexed vertices by walking the bottom layer from the entry points. */
    private List<Object> walkLayerZero(GraphBinding binding, String vertexName, String propertyName,
                                       List<HnswEntryPoint> entryPoints) {
        AerospikeHnswStore store = new AerospikeHnswStore(binding.edgeStore(), binding.schema(),
                vertexName, propertyName);
        LinkedHashSet<Object> seen = new LinkedHashSet<>();
        List<Object> frontier = new ArrayList<>();
        for (HnswEntryPoint point : entryPoints) {
            if (seen.add(point.vertexId())) {
                frontier.add(point.vertexId());
            }
        }
        while (!frontier.isEmpty()) {
            List<Object> next = new ArrayList<>();
            for (List<HnswNeighbor> neighbours : store.neighboursBatch(frontier, 0)) {
                for (HnswNeighbor neighbour : neighbours) {
                    if (seen.add(neighbour.vertexId())) {
                        next.add(neighbour.vertexId());
                    }
                }
            }
            frontier = next;
        }
        return List.copyOf(seen);
    }

    /** Reads the vectors of one batch of ids into the heap source and the reload list. */
    private void reloadVectors(GraphBinding binding, String vertexName, int vectorIndex, List<Object> batch,
                               HeapHnswVectorSource vectors, List<Object> reloaded) {
        if (batch.isEmpty()) {
            return;
        }
        List<StoredVertex> stored = binding.vertexStore().readVertices(new ArrayList<>(batch),
                List.of(vectorIndex));
        for (StoredVertex vertex : stored) {
            if (vertex == null || vertex.properties().get(vectorIndex) == null) {
                continue;
            }
            float[] vector = VectorCodec.toFloats(vertex.properties().get(vectorIndex).value());
            if (vector == null) {
                continue;
            }
            vectors.put(vertex.vertexId(), vector);
            reloaded.add(vertex.vertexId());
        }
    }

    /**
     * Indexes the vector properties of a written vertex. The vector is read back
     * from storage, so a write that does not touch the vector property (or a
     * vertex type without one) is simply skipped.
     *
     * @return the insert results, one per vector property that had a vector
     */
    public List<HnswInsertResult> indexVertex(String graphName, String vertexSchemaName, Object vertexId) {
        List<HnswIndex> indexes = indexesOf(graphName, vertexSchemaName);
        if (indexes.isEmpty()) {
            return List.of();
        }
        List<HnswInsertResult> results = new ArrayList<>(indexes.size());
        for (HnswIndex index : indexes) {
            HnswInsertResult result = index.insertIfPresent(vertexId);
            if (result != null) {
                results.add(result);
            }
        }
        return results;
    }

    /** Whether the graph has any vector index (cheap: does not open anything). */
    public boolean hasVectorIndexes(String graphName) {
        return !VectorIndexSchemas.vectorProperties(graphs.require(graphName).schema()).isEmpty();
    }

    /** The vector properties of a graph, for diagnostics. */
    public List<VectorIndexSchemas.VectorProperty> vectorProperties(String graphName) {
        return VectorIndexSchemas.vectorProperties(graphs.require(graphName).schema());
    }

    /** Index configuration of a graph's vector property, for diagnostics. */
    public VectorIndexConfig vectorConfig(String graphName, String vertexName, String propertyName) {
        VectorIndexSchemas.VectorProperty property =
                VectorIndexSchemas.vectorProperty(graphs.require(graphName).schema(), vertexName, propertyName);
        return property == null ? null : property.config();
    }

    /** Opens (once) every vector index of a graph, keyed by {@code vertex|property}. */
    private Map<String, HnswIndex> indexes(String graphName) {
        return indexesByGraph.computeIfAbsent(graphs.require(graphName).name(), name -> {
            GraphBinding binding = graphs.require(name);
            AerospikeGraphQuery query = new AerospikeGraphQuery(binding.vertexStore(), binding.edgeStore(),
                    binding.schema());
            Map<String, HnswIndex> opened = new LinkedHashMap<>();
            for (VectorIndexSchemas.VectorProperty property : VectorIndexSchemas.vectorProperties(binding.schema())) {
                opened.put(key(property.vertexName(), property.propertyName()),
                        AerospikeHnswIndexFactory.open(query, binding.edgeStore(), binding.schema(), client,
                                namespace, metadataSet, property, METADATA_TTL));
            }
            return Map.copyOf(opened);
        });
    }

    private static String key(String vertexSchemaName, String vectorProperty) {
        return vertexSchemaName + "|" + vectorProperty;
    }
}
