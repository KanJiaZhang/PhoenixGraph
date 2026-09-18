package com.pengcheng.phoenixgraph.http.config;

import org.springframework.beans.factory.annotation.Value;
import com.aerospike.client.AerospikeClient;
import com.pengcheng.phoenixgraph.http.service.GraphBinding;
import com.pengcheng.phoenixgraph.metrics.GraphMetrics;
import com.pengcheng.phoenixgraph.metrics.InMemoryMetricsStore;
import com.pengcheng.phoenixgraph.gemini.GeminiCluster;
import com.pengcheng.phoenixgraph.metrics.MetricsRegistry;
import com.pengcheng.phoenixgraph.metrics.MetricsStore;
import com.pengcheng.phoenixgraph.storage.MetricsBusinessLog;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeMetricsStore;
import com.pengcheng.phoenixgraph.storage.log.JulBusinessLog;
import com.pengcheng.phoenixgraph.storage.log.JulErrorLog;
import com.pengcheng.phoenixgraph.http.service.GraphRegistry;
import com.pengcheng.phoenixgraph.query.aerospike.AerospikeEdgeGraphQuery;
import com.pengcheng.phoenixgraph.query.aerospike.AerospikeGraphQuery;
import com.pengcheng.phoenixgraph.query.storage.GraphQuery;
import com.pengcheng.phoenixgraph.schema.GraphStorageType;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeChunkStore;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchemaManagers;
import com.pengcheng.phoenixgraph.schema.json.SchemaJsonCodec;
import com.pengcheng.phoenixgraph.schema.GeoIndexSchemas;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeGeoIndex;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Wires the Aerospike storage stores as Spring beans and loads the configured
 * graph schemas (from the working directory first, then from the classpath).
 *
 * <p>The primary graph ({@code phoenixgraph.storage.schema} / {@code .vertex-set})
 * backs the write endpoints; {@code phoenixgraph.storage.graphs} adds further
 * graphs served by the same instance, each with its own vertex set.
 */
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {

    /**
     * The Gemini cluster the {@code (DIST)} algorithm steps run on.
     *
     * <p>One cluster serves every graph: a run loads the workers with the graph it is about to compute
     * on, and a lock serialises the runs (a cluster is a set of workers, one computation at a time).
     * The defaults are four workers on four ports in this process; {@code phoenixgraph.gemini.mode}
     * can be set to {@code process} to start each worker as its own program, which is the standalone
     * (or YARN container) deployment.
     */
    @Bean(destroyMethod = "close")
    public GeminiCluster geminiCluster(
            @Value("${phoenixgraph.gemini.workers:4}") int workers,
            @Value("${phoenixgraph.gemini.first-port:19100}") int firstPort,
            @Value("${phoenixgraph.gemini.mode:local}") String mode) {
        GeminiCluster cluster = new GeminiCluster(new GeminiCluster.Config(Math.max(1, workers), firstPort,
                "process".equalsIgnoreCase(mode) ? GeminiCluster.Mode.PROCESS : GeminiCluster.Mode.LOCAL,
                "127.0.0.1"));
        return cluster.start();
    }

    @Bean(destroyMethod = "close")
    public AerospikeClient aerospikeClient(StorageProperties properties) {
        return new AerospikeClient(properties.host(), properties.port());
    }

    /**
     * The system metrics of every graph, flushed to storage on a timer and on shutdown.
     * Graphs whose storage can keep them bind their own {@link MetricsStore} below; the
     * rest keep counting in memory.
     */
    /**
     * Flushing on shutdown has to happen while the stores are still open, so this bean depends
     * on them: Spring destroys dependents first, which flushes the counters into a live database
     * instead of a closed handle.
     */
    @Bean(destroyMethod = "close")
    @org.springframework.context.annotation.DependsOn("rocksDbGraphStores")
    public MetricsRegistry metricsRegistry() {
        return new MetricsRegistry(new InMemoryMetricsStore());
    }

    /**
     * The stores behind the write endpoints. They carry the same metrics as the query
     * stores of the primary graph, so a write through the REST API shows up in the same
     * counters as a write done by a loader.
     */
    @Bean
    public AerospikeVertexStore vertexStore(AerospikeClient client, StorageProperties properties,
                                            MetricsRegistry metrics) {
        return new AerospikeVertexStore(client, properties.namespace(), properties.vertexSet(),
                new MetricsBusinessLog(new JulBusinessLog(), primaryMetrics(properties, metrics)),
                new JulErrorLog()).metrics(primaryMetrics(properties, metrics));
    }

    /** The metrics of the primary graph, or a sink that forgets everything when there is none. */
    private static GraphMetrics primaryMetrics(StorageProperties properties, MetricsRegistry metrics) {
        List<GraphProperties> definitions = properties.graphDefinitions();
        return definitions.isEmpty() ? GraphMetrics.noop() : metrics.forGraph(definitions.get(0).schema());
    }

    @Bean
    public GraphSchema graphSchema(StorageProperties properties) {
        List<GraphProperties> definitions = properties.graphDefinitions();
        if (!definitions.isEmpty()) {
            return loadSchema(definitions.get(0).schema());
        }
        // Empty schema: edge degree limits default to unlimited and edge schema
        // lookups fail with a clear error at write time.
        return new GraphSchema(0, "unconfigured", null);
    }

    @Bean
    public AerospikeEdgeStore edgeStore(AerospikeClient client, StorageProperties properties,
                                        GraphSchema graphSchema, MetricsRegistry metrics) {
        return new AerospikeEdgeStore(client, properties.namespace(), properties.vertexSet(), graphSchema,
                new MetricsBusinessLog(new JulBusinessLog(), primaryMetrics(properties, metrics)),
                new JulErrorLog()).metrics(primaryMetrics(properties, metrics));
    }

    /**
     * Every graph this instance can query, each bound to its own vertex set. All
     * schemas are also registered with {@link GraphSchemaManagers} so schema-aware
     * storage code can look them up by name.
     */
    /**
     * Opens the RocksDB directories the schemas declare. Every directory is closed when the
     * application shuts down (and only one process may hold one open).
     */
    @Bean(destroyMethod = "close")
    public RocksDbGraphStores rocksDbGraphStores(StorageProperties properties) {
        return new RocksDbGraphStores(properties.rocksdbRoot());
    }

    /**
     * Every graph this instance can query, each bound to the storage its schema declares:
     * Aerospike (the default) reads through the vertex/edge stores, RocksDB-Inline and
     * RocksDB-Edge open their own directory and read through their own query.
     */
    @Bean
    public GraphRegistry graphRegistry(AerospikeClient client, StorageProperties properties,
                                       RocksDbGraphStores rocksDbStores, MetricsRegistry metrics) {
        List<GraphBinding> bindings = new ArrayList<>();
        for (GraphProperties definition : properties.graphDefinitions()) {
            GraphSchema schema = loadSchema(definition.schema());
            GraphMetrics graphMetrics = metrics.forGraph(schema.name());
            if (RocksDbGraphStores.isRocksDb(schema)) {
                GraphQuery query = rocksDbStores.open(schema, graphMetrics);
                MetricsStore store = rocksDbStores.metricsStore(schema.name());
                if (store != null) {
                    metrics.bind(schema.name(), store);
                }
                bindings.add(GraphBinding.of(schema, query));
                continue;
            }
            // an Aerospike graph declares its geo bins as geo2dsphere secondary indexes; a
            // community server refuses (the query then scans) which is not an error here
            if (GeoIndexSchemas.hasGeoProperties(schema)) {
                AerospikeGeoIndex.ensureIndexes(client, properties.namespace(), definition.vertexSet(),
                        schema, null);
            }
            AerospikeVertexStore vertexStore = new AerospikeVertexStore(client, properties.namespace(),
                    definition.vertexSet(), new MetricsBusinessLog(new JulBusinessLog(), graphMetrics),
                    new JulErrorLog()).metrics(graphMetrics);
            AerospikeEdgeStore edgeStore = new AerospikeEdgeStore(client, properties.namespace(),
                    definition.vertexSet(), schema,
                    new MetricsBusinessLog(new JulBusinessLog(), graphMetrics), new JulErrorLog())
                    .metrics(graphMetrics);
            // every Aerospike graph persists its metrics in the same cluster, in a system set
            metrics.bind(schema.name(), new AerospikeMetricsStore(client, properties.namespace()));
            if (schema.storage().type() == GraphStorageType.AEROSPIKE_EDGE) {
                // big vertices live in the aggregated Edge_Out / Edge_In tables, written by a bulk
                // import; the read path merges them with the edges still inline in the vertex record
                AerospikeEdgeChunkStore chunks = new AerospikeEdgeChunkStore(client, properties.namespace(),
                        definition.vertexSet()).metrics(graphMetrics);
                GraphQuery query = new AerospikeEdgeGraphQuery(
                        new AerospikeGraphQuery(vertexStore, edgeStore, schema), chunks, schema);
                bindings.add(new GraphBinding(schema, vertexStore, edgeStore, client,
                        properties.namespace(), query));
                continue;
            }
            bindings.add(new GraphBinding(schema, vertexStore, edgeStore, client, properties.namespace()));
        }
        return new GraphRegistry(bindings);
    }

    /** Loads a schema from the working directory first, then from the classpath. */
    private GraphSchema loadSchema(String name) {
        try {
            GraphSchema registered = GraphSchemaManagers.manager().get(name);
            if (registered != null) {
                return registered;
            }
            return GraphSchemaManagers.manager().load(name);
        } catch (RuntimeException missingOnDisk) {
            GraphSchema fromClasspath = loadFromClasspath(name);
            if (fromClasspath != null) {
                GraphSchemaManagers.manager().register(fromClasspath);
                return fromClasspath;
            }
            throw missingOnDisk;
        }
    }

    private GraphSchema loadFromClasspath(String name) {
        String resource = "/" + name + ".json";
        try (InputStream in = StorageConfig.class.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            return new SchemaJsonCodec().fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
    }
}
