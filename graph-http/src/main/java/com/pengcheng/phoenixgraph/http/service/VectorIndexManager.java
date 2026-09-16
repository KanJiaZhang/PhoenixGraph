package com.pengcheng.phoenixgraph.http.service;

import com.aerospike.client.AerospikeClient;
import com.pengcheng.phoenixgraph.hnsw.CachedHnswMetadataStore;
import com.pengcheng.phoenixgraph.hnsw.HnswInsertResult;
import com.pengcheng.phoenixgraph.hnsw.HnswIndex;
import com.pengcheng.phoenixgraph.hnsw.HnswMetadata;
import com.pengcheng.phoenixgraph.http.config.StorageProperties;
import com.pengcheng.phoenixgraph.query.aerospike.AerospikeGraphQuery;
import com.pengcheng.phoenixgraph.query.aerospike.AerospikeHnswIndexFactory;
import com.pengcheng.phoenixgraph.query.aerospike.AerospikeHnswMetadataStore;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.VectorIndexConfig;
import com.pengcheng.phoenixgraph.schema.VectorIndexSchemas;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
