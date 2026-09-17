package com.pengcheng.phoenixgraph.http.service;

import com.pengcheng.phoenixgraph.query.aerospike.AerospikeGraphQuery;
import com.pengcheng.phoenixgraph.query.aerospike.AerospikeHnswIndexFactory;
import com.pengcheng.phoenixgraph.query.gremlin.GremlinGraphQuery;
import com.pengcheng.phoenixgraph.query.gremlin.NeighborVertexResolver;
import com.pengcheng.phoenixgraph.query.gremlin.Subgraph;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchemaManagers;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes Gremlin traversal queries against Aerospike.
 *
 * <p>The graph name in the request selects the schema and the vertex set (see
 * {@link GraphResolver}): one {@link GremlinGraphQuery} engine is cached per
 * graph, and each engine caches its own compiled queries. {@code ${paramName}}
 * placeholders in the query string are substituted from the request's
 * {@code params} map before execution.
 */
@Service
public class QueryService {

    private static final Pattern PARAMETER = Pattern.compile("\\$\\{([^}]+)}");

    private final GraphResolver graphs;
    private final VectorIndexManager vectorIndexes;
    private final Map<String, GremlinGraphQuery> engines = new ConcurrentHashMap<>();

    public QueryService(GraphResolver graphs, @Nullable VectorIndexManager vectorIndexes) {
        this.graphs = Objects.requireNonNull(graphs, "graphs");
        this.vectorIndexes = vectorIndexes;
    }

    /**
     * A service over a single pair of stores: every graph name shares them and
     * schemas are looked up by name in {@link GraphSchemaManagers}.
     */
    public static QueryService ambient(AerospikeVertexStore vertexStore, AerospikeEdgeStore edgeStore) {
        return new QueryService(GraphResolver.ambient(vertexStore, edgeStore), null);
    }

    /** Substitutes {@code ${...}} parameters and executes the query, returning the raw traversers. */
    public List<Object> query(String graphName, String query, Map<String, String> params) {
        Objects.requireNonNull(query, "query");
        return engineFor(graphName).execute(substitute(query, params));
    }

    /** Substitutes {@code ${...}} parameters and executes the query, returning the induced subgraph. */
    public Subgraph querySubgraph(String graphName, String query, Map<String, String> params) {
        Objects.requireNonNull(query, "query");
        return engineFor(graphName).executeSubgraph(substitute(query, params));
    }

    private GremlinGraphQuery engineFor(String graphName) {
        return engines.computeIfAbsent(requireName(graphName), name -> {
            GraphBinding binding = graphs.require(name);
            AerospikeGraphQuery graphQuery = new AerospikeGraphQuery(
                    binding.vertexStore(), binding.edgeStore(), binding.schema());
            GremlinGraphQuery engine = new GremlinGraphQuery(graphQuery,
                    NeighborVertexResolver.from(binding.schema()));
            // vector search: every property the schema flags isVector gets a nearest(k)
            // step (the index is shared with the write path, so freshly written
            // vertices are searchable immediately)
            if (vectorIndexes != null) {
                // resolved per query, so a rebuilt index is used without restarting
                engine.hnswIndexSource(() -> vectorIndexes.indexesOf(name));
            } else if (binding.hasKeyValueAccess()) {
                AerospikeHnswIndexFactory.open(graphQuery, binding.edgeStore(), binding.schema(),
                                binding.client(), binding.namespace(), AerospikeHnswIndexFactory.DEFAULT_METADATA_SET,
                                VectorIndexManager.METADATA_TTL)
                        .forEach(engine::hnswIndex);
            }
            return engine;
        });
    }

    private static String requireName(String graphName) {
        if (graphName == null || graphName.isBlank()) {
            throw new IllegalArgumentException("graphName must not be blank");
        }
        return graphName;
    }

    /** The graphs this instance serves (drives the UI's graph dropdown). */
    public List<GraphSchema> graphs() {
        return graphs.schemas();
    }

    /** Replaces every {@code ${key}} with {@code params[key]}; throws when a key is missing. */
    static String substitute(String query, Map<String, String> params) {
        Map<String, String> values = params == null ? Map.of() : params;
        Matcher matcher = PARAMETER.matcher(query);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1).trim();
            String value = values.get(key);
            if (value == null) {
                throw new IllegalArgumentException("Missing value for query parameter '" + key + "'");
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
