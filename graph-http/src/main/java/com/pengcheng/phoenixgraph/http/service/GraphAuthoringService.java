package com.pengcheng.phoenixgraph.http.service;

import com.aerospike.client.AerospikeClient;
import com.pengcheng.phoenixgraph.http.config.GraphProperties;
import com.pengcheng.phoenixgraph.http.config.RocksDbGraphStores;
import com.pengcheng.phoenixgraph.http.config.StorageProperties;
import com.pengcheng.phoenixgraph.metrics.GraphMetrics;
import com.pengcheng.phoenixgraph.metrics.MetricsRegistry;
import com.pengcheng.phoenixgraph.metrics.MetricsStore;
import com.pengcheng.phoenixgraph.query.aerospike.AerospikeEdgeGraphQuery;
import com.pengcheng.phoenixgraph.query.aerospike.AerospikeGraphQuery;
import com.pengcheng.phoenixgraph.query.storage.GraphQuery;
import com.pengcheng.phoenixgraph.schema.GeoIndexSchemas;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchemaManagers;
import com.pengcheng.phoenixgraph.schema.GraphStorageType;
import com.pengcheng.phoenixgraph.schema.SchemaValidationResult;
import com.pengcheng.phoenixgraph.schema.SchemaValidator;
import com.pengcheng.phoenixgraph.http.dto.SchemaDraft;
import com.pengcheng.phoenixgraph.schema.json.SchemaJsonCodec;
import com.pengcheng.phoenixgraph.storage.MetricsBusinessLog;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeChunkStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeGeoIndex;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeMetricsStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import com.pengcheng.phoenixgraph.storage.log.JulBusinessLog;
import com.pengcheng.phoenixgraph.storage.log.JulErrorLog;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Creates and saves graph schemas: the model behind the graph-building screen.
 *
 * <p>Submitting a schema validates it, writes it to {@code <name>.json} in the working
 * directory (so the next start picks it up), registers it with the schema manager and
 * binds it to storage — which makes the graph queryable straight away, without a
 * restart. A schema that fails validation is rejected with the reasons, and a graph
 * whose name is already served is refused unless the caller asks to replace it.
 *
 * <p>The write path is the same one the loaders use: the stores are built by storage
 * type (Aerospike, Aerospike-Edge or one of the RocksDB schemes) and share the graph's
 * metrics, so a graph created here shows up in the dashboard like any other.
 */
@Service
public class GraphAuthoringService {

    /** Where a schema file is written, relative to the working directory. */
    public static final String SCHEMA_SUFFIX = ".json";

    private final GraphRegistry graphs;
    private final RocksDbGraphStores rocksDbStores;
    private final MetricsRegistry metrics;
    private final StorageProperties properties;
    private final AerospikeClient client;
    private final SchemaJsonCodec codec = new SchemaJsonCodec();
    private final SchemaValidator validator = new SchemaValidator();

    public GraphAuthoringService(GraphRegistry graphs, RocksDbGraphStores rocksDbStores,
                                 @Nullable MetricsRegistry metrics, StorageProperties properties,
                                 @Nullable AerospikeClient client) {
        this.graphs = Objects.requireNonNull(graphs, "graphs");
        this.rocksDbStores = Objects.requireNonNull(rocksDbStores, "rocksDbStores");
        this.metrics = metrics;
        this.properties = properties;
        this.client = client;
    }

    /** Checks a draft without saving anything; a draft that cannot be built is invalid, not an error. */
    public SchemaValidationResult validate(SchemaDraft draft) {
        GraphSchema schema;
        try {
            schema = draft == null ? null : draft.toSchema(nextId());
        } catch (RuntimeException e) {
            return new SchemaValidationResult(List.of(e.getMessage()), List.of());
        }
        if (schema == null) {
            return new SchemaValidationResult(List.of("The request carried no schema"), List.of());
        }
        return validator.validateResult(schema, schema.constraints());
    }

    /** The next free schema id (schema ids have to be unique across the process). */
    private int nextId() {
        int max = 0;
        for (GraphSchema schema : graphs.schemas()) {
            max = Math.max(max, schema.id());
        }
        return max + 1;
    }

    /**
     * Saves a schema, makes it live and reports what happened.
     *
     * @param replace when {@code false}, a name that is already served is an error
     */
    public SaveResult save(SchemaDraft draft, boolean replace) {
        GraphSchema schema;
        try {
            schema = draft.toSchema(nextId());
        } catch (RuntimeException e) {
            return new SaveResult(false, draft == null ? null : draft.name(), null,
                    List.of("Cannot read the schema: " + e.getMessage()), List.of(), null);
        }
        SchemaValidationResult validation = validator.validateResult(schema, schema.constraints());
        if (!validation.isValid()) {
            return new SaveResult(false, schema.name(), null, validation.errors(), validation.warnings(), null);
        }
        boolean known = graphs.schemas().stream().anyMatch(existing -> existing.name().equals(schema.name()));
        if (known && !replace) {
            return new SaveResult(false, schema.name(), null,
                    List.of("Graph '" + schema.name() + "' is already served; ask to replace it to overwrite"),
                    validation.warnings(), null);
        }

        String normalized = codec.toJson(schema);
        Path file;
        try {
            file = writeSchemaFile(schema.name(), normalized);
        } catch (IOException e) {
            return new SaveResult(false, schema.name(), null,
                    List.of("Cannot write " + schema.name() + SCHEMA_SUFFIX + ": " + e.getMessage()),
                    validation.warnings(), null);
        }

        GraphSchemaManagers.manager().registerOrReplace(schema);
        GraphBinding binding = bind(schema);
        graphs.register(binding);
        return new SaveResult(true, schema.name(), file.toAbsolutePath().toString(), List.of(),
                validation.warnings(), normalized);
    }

    /** The stored JSON of a schema: the working directory first, then the classpath. */
    public String storedJson(String graphName) {
        Path file = Path.of(graphName + SCHEMA_SUFFIX);
        try {
            if (Files.isRegularFile(file)) {
                return Files.readString(file, StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
            // fall through to the classpath copy
        }
        try (InputStream in = GraphAuthoringService.class.getResourceAsStream("/" + graphName + SCHEMA_SUFFIX)) {
            if (in != null) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
            // no stored copy at all
        }
        return null;
    }

    /**
     * An existing graph described the way the editor edits it: the editor opens a graph by
     * turning its schema into a draft, changing it, and saving the draft back.
     */
    public SchemaDraft draftOf(String graphName) {
        GraphSchema known = graphs.schemas().stream()
                .filter(schema -> schema.name().equals(graphName))
                .findFirst()
                .orElse(null);
        if (known != null) {
            return SchemaDraft.toDraft(known);
        }
        String json = storedJson(graphName);
        if (json == null) {
            throw new IllegalArgumentException("No schema definition found for '" + graphName + "'");
        }
        return SchemaDraft.toDraft(codec.fromJson(json));
    }

    private Path writeSchemaFile(String name, String json) throws IOException {
        Path file = Path.of(name + SCHEMA_SUFFIX);
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }

    /** Builds the stores for a schema and returns its binding. */
    private GraphBinding bind(GraphSchema schema) {
        GraphMetrics graphMetrics = metrics == null ? GraphMetrics.noop() : metrics.forGraph(schema.name());
        if (RocksDbGraphStores.isRocksDb(schema)) {
            GraphQuery query = rocksDbStores.open(schema, graphMetrics);
            MetricsStore store = rocksDbStores.metricsStore(schema.name());
            if (store != null && metrics != null) {
                metrics.bind(schema.name(), store);
            }
            return GraphBinding.of(schema, query);
        }

        String vertexSet = vertexSetOf(schema);
        if (client == null) {
            throw new IllegalStateException("No Aerospike client; cannot serve graph '" + schema.name() + "'");
        }
        if (GeoIndexSchemas.hasGeoProperties(schema)) {
            AerospikeGeoIndex.ensureIndexes(client, properties.namespace(), vertexSet, schema, null);
        }
        AerospikeVertexStore vertexStore = new AerospikeVertexStore(client, properties.namespace(), vertexSet,
                new MetricsBusinessLog(new JulBusinessLog(), graphMetrics), new JulErrorLog())
                .metrics(graphMetrics);
        AerospikeEdgeStore edgeStore = new AerospikeEdgeStore(client, properties.namespace(), vertexSet, schema,
                new MetricsBusinessLog(new JulBusinessLog(), graphMetrics), new JulErrorLog())
                .metrics(graphMetrics);
        if (metrics != null) {
            metrics.bind(schema.name(), new AerospikeMetricsStore(client, properties.namespace()));
        }
        if (schema.storage() != null && schema.storage().type() == GraphStorageType.AEROSPIKE_EDGE) {
            AerospikeEdgeChunkStore chunks = new AerospikeEdgeChunkStore(client, properties.namespace(), vertexSet)
                    .metrics(graphMetrics);
            GraphQuery query = new AerospikeEdgeGraphQuery(
                    new AerospikeGraphQuery(vertexStore, edgeStore, schema), chunks, schema);
            return new GraphBinding(schema, vertexStore, edgeStore, client, properties.namespace(), query);
        }
        return new GraphBinding(schema, vertexStore, edgeStore, client, properties.namespace());
    }

    /** The Aerospike set a new graph writes into: its own name, which keeps graphs apart. */
    private String vertexSetOf(GraphSchema schema) {
        for (GraphProperties definition : properties.graphDefinitions()) {
            if (definition.schema().equals(schema.name())) {
                return definition.vertexSet();
            }
        }
        return schema.name() + "_v1";
    }

    /**
     * The outcome of a save.
     *
     * @param saved    whether the schema was accepted
     * @param graph    the graph name
     * @param file     the file it was written to
     * @param errors   why it was refused (empty when saved)
     * @param warnings what to look at but was accepted
     * @param json     the normalized JSON that was written (null when refused)
     */
    public record SaveResult(boolean saved, String graph, String file, List<String> errors,
                             List<String> warnings, String json) {

        public SaveResult {
            errors = errors == null ? List.of() : List.copyOf(errors);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        /** A view for the HTTP layer. */
        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("saved", saved);
            out.put("graph", graph);
            out.put("file", file);
            out.put("errors", errors);
            out.put("warnings", warnings);
            if (json != null) {
                out.put("json", json);
            }
            return out;
        }
    }

    /** Every storage type the editor can offer, in the order to show them. */
    public List<Map<String, Object>> storageTypes() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (GraphStorageType type : GraphStorageType.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", type.configName());
            entry.put("aerospike", type.isAerospike());
            entry.put("rocksdb", type.isRocksDb());
            out.add(entry);
        }
        return out;
    }
}
