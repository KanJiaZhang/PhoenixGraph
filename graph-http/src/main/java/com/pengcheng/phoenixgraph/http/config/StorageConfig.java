package com.pengcheng.phoenixgraph.http.config;

import org.springframework.beans.factory.annotation.Value;
import com.aerospike.client.AerospikeClient;
import com.aerospike.client.policy.ClientPolicy;
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
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeNearlineStore;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchemaManagers;
import com.pengcheng.phoenixgraph.schema.json.SchemaJsonCodec;
import com.pengcheng.phoenixgraph.schema.GeoIndexSchemas;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeGeoIndex;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Wires the Aerospike storage stores as Spring beans and loads the configured
 * graph schemas (from the working directory first, then from the classpath).
 *
 * <p>The primary graph ({@code phoenixgraph.storage.schema} / {@code .vertex-set})
 * backs the write endpoints; {@code phoenixgraph.storage.graphs} adds further
 * graphs served by the same instance, each with its own vertex set.
 */
@Configuration
@EnableConfigurationProperties({StorageProperties.class, LimitsProperties.class})
public class StorageConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(StorageConfig.class);

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
        // failIfNotConnected=false is the difference between "a node that lost the store refuses to boot" and
        // "a node that lost the store starts, serves the graphs it can read (the RocksDB ones), answers 503 for
        // the Aerospike ones, and connects by itself when the store comes back". The first is a crash loop in a
        // cluster whose store is briefly unreachable; the second is a degraded node, which is what an operator
        // (and a readiness probe) can act on. Operations still fail per request - see the 503 mapping in
        // ApiExceptionHandler - and this is also what makes the failure mode testable
        // (verification/verify-failure-modes.mjs).
        ClientPolicy policy = new ClientPolicy();
        policy.failIfNotConnected = false;
        return new AerospikeClient(policy, properties.host(), properties.port());
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
     *
     * <p>Graphs come from two places: the configured list (
     * {@code phoenixgraph.storage.schema} plus {@code .graphs}) and, when
     * {@code phoenixgraph.storage.schema-dir} is set, every {@code *.json} in that directory.
     * The directory is how an operator adds a graph without editing a configuration file
     * that lives inside the jar: mount a directory of schemas and restart. An explicitly
     * configured graph wins over a file of the same name, so mounting a directory can never
     * silently re-point a graph somebody deliberately configured.
     */
    @Bean
    public GraphRegistry graphRegistry(AerospikeClient client, StorageProperties properties,
                                       RocksDbGraphStores rocksDbStores, MetricsRegistry metrics) {
        List<GraphBinding> bindings = new ArrayList<>();
        Map<String, String> vertexSets = new LinkedHashMap<>();
        for (GraphProperties definition : properties.graphDefinitions()) {
            vertexSets.put(definition.schema(), definition.vertexSet());
        }
        Map<String, GraphSchema> discovered = discoveredSchemas(properties);
        for (Map.Entry<String, GraphSchema> found : discovered.entrySet()) {
            vertexSets.putIfAbsent(found.getKey(), vertexSetFor(found.getValue().name()));
            // the rest of the service looks schemas up by name (authoring, vector indexes), so a discovered
            // graph has to be registered, not only bound
            GraphSchemaManagers.manager().register(found.getValue());
        }
        for (Map.Entry<String, GraphSchema> entry : loadOrder(properties, vertexSets.keySet(), discovered).entrySet()) {
            bindings.add(bind(entry.getValue(), vertexSets.get(entry.getKey()), client, properties,
                    rocksDbStores, metrics));
        }
        return new GraphRegistry(bindings);
    }

    /**
     * The configured graphs in order, then the ones found in the schema directory.
     *
     * <p>A discovered schema is used as it was parsed: asking the schema manager for it by name would look in
     * the working directory and the classpath, and a mounted schema directory is deliberately neither.
     */
    private Map<String, GraphSchema> loadOrder(StorageProperties properties, Set<String> names,
                                               Map<String, GraphSchema> discovered) {
        Map<String, GraphSchema> schemas = new LinkedHashMap<>();
        for (String name : names) {
            if (schemas.containsKey(name)) {
                continue;
            }
            GraphSchema found = discovered.get(name);
            schemas.put(name, found != null ? found : loadSchema(name));
        }
        return schemas;
    }

    /**
     * The schemas under {@code phoenixgraph.storage.schema-dir}, as {@code name -> schema}.
     *
     * <p>The name is the file's own name (so {@code sales.json} is the graph {@code sales}), and
     * the vertex set is derived from it because a schema file does not say which set holds its
     * data - the alternative would be inventing a second configuration language inside JSON. An
     * entry in {@code phoenixgraph.storage.graphs} is how you point a graph at a set of your
     * choosing, and it wins.
     */
    private Map<String, GraphSchema> discoveredSchemas(StorageProperties properties) {
        String dir = properties.schemaDir();
        if (dir == null || dir.isBlank()) {
            return Map.of();
        }
        Path directory = Path.of(dir);
        if (!Files.isDirectory(directory)) {
            LOGGER.warn("phoenixgraph.storage.schema-dir={} is not a directory: no graph was discovered there", dir);
            return Map.of();
        }
        SchemaJsonCodec codec = new SchemaJsonCodec();
        Map<String, GraphSchema> schemas = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter((path) -> path.getFileName().toString().endsWith(".json"))
                    .sorted().toList()) {
                String fileName = file.getFileName().toString();
                String name = fileName.substring(0, fileName.length() - ".json".length());
                try {
                    GraphSchema schema = codec.fromJson(Files.readString(file));
                    schemas.put(name, schema);
                    LOGGER.info("graph '{}' discovered in {} (vertex set {})", name, dir, vertexSetFor(name));
                } catch (RuntimeException | IOException broken) {
                    // a broken file must not stop the service: the other graphs are still served, and the
                    // operator gets a line naming the file
                    LOGGER.error("ignoring {}: {}", file, broken.getMessage());
                }
            }
        } catch (IOException unreadable) {
            LOGGER.error("cannot read phoenixgraph.storage.schema-dir={}: {}", dir, unreadable.getMessage());
        }
        return schemas;
    }

    /** The vertex set of a discovered graph: its name, with what an Aerospike set name cannot hold folded. */
    static String vertexSetFor(String graphName) {
        return graphName.replaceAll("[^A-Za-z0-9_]", "_");
    }

    /** Binds one schema to the storage its own declaration asks for. */
    private GraphBinding bind(GraphSchema schema, String vertexSet, AerospikeClient client,
                              StorageProperties properties, RocksDbGraphStores rocksDbStores,
                              MetricsRegistry metrics) {
        GraphMetrics graphMetrics = metrics.forGraph(schema.name());
        if (RocksDbGraphStores.isRocksDb(schema)) {
            GraphQuery query = rocksDbStores.open(schema, graphMetrics);
            MetricsStore store = rocksDbStores.metricsStore(schema.name());
            if (store != null) {
                metrics.bind(schema.name(), store);
            }
            return GraphBinding.of(schema, query);
        }
        // an Aerospike graph declares its geo bins as geo2dsphere secondary indexes; a
        // community server refuses (the query then scans) which is not an error here
        if (GeoIndexSchemas.hasGeoProperties(schema)) {
            AerospikeGeoIndex.ensureIndexes(client, properties.namespace(), vertexSet, schema, null);
        }
        AerospikeVertexStore vertexStore = new AerospikeVertexStore(client, properties.namespace(),
                vertexSet, new MetricsBusinessLog(new JulBusinessLog(), graphMetrics),
                new JulErrorLog()).metrics(graphMetrics);
        AerospikeEdgeStore edgeStore = new AerospikeEdgeStore(client, properties.namespace(),
                vertexSet, schema,
                new MetricsBusinessLog(new JulBusinessLog(), graphMetrics), new JulErrorLog())
                .metrics(graphMetrics);
        // every Aerospike graph persists its metrics in the same cluster, in a system set
        metrics.bind(schema.name(), new AerospikeMetricsStore(client, properties.namespace()));
        if (schema.storage().type() == GraphStorageType.AEROSPIKE_EDGE) {
            // big vertices spread their edges over three tiers: online (the inline bin),
            // near-line (TTL'd KVs, pre-partitioned by volume) and cold (the aggregated
            // Edge_Out / Edge_In tables). A read is routed by which spans its window overlaps.
            AerospikeEdgeChunkStore chunks = new AerospikeEdgeChunkStore(client, properties.namespace(),
                    vertexSet).metrics(graphMetrics);
            AerospikeNearlineStore nearline = new AerospikeNearlineStore(client, properties.namespace(),
                    vertexSet).metrics(graphMetrics);
            GraphQuery query = new AerospikeEdgeGraphQuery(
                    new AerospikeGraphQuery(vertexStore, edgeStore, schema), chunks, schema,
                    nearline, java.time.Clock.systemUTC(),
                    com.pengcheng.phoenixgraph.storage.aerospike.EdgeTiering.DEFAULT_NEARLINE_WINDOW_MILLIS);
            return new GraphBinding(schema, vertexStore, edgeStore, client, properties.namespace(), query);
        }
        return new GraphBinding(schema, vertexStore, edgeStore, client, properties.namespace());
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
