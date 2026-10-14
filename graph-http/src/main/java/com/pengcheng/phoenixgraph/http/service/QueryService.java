package com.pengcheng.phoenixgraph.http.service;

import com.pengcheng.phoenixgraph.metrics.GraphMetrics;
import com.pengcheng.phoenixgraph.metrics.Metrics;
import com.pengcheng.phoenixgraph.metrics.MetricsRegistry;
import com.pengcheng.phoenixgraph.query.QueryException;
import com.pengcheng.phoenixgraph.query.aerospike.AerospikeGraphQuery;
import com.pengcheng.phoenixgraph.query.aerospike.AerospikeHnswIndexFactory;
import com.pengcheng.phoenixgraph.query.cypher.CypherGraphQuery;
import com.pengcheng.phoenixgraph.query.cypher.CypherResult;
import com.pengcheng.phoenixgraph.http.dto.QueryResponse;
import com.pengcheng.phoenixgraph.http.dto.ScriptResponse;
import com.pengcheng.phoenixgraph.fuzzy.FuzzyPatternRegistry;
import com.pengcheng.phoenixgraph.gemini.GeminiCluster;
import com.pengcheng.phoenixgraph.query.gremlin.GremlinGraphQuery;
import com.pengcheng.phoenixgraph.query.gremlin.GremlinScript;
import com.pengcheng.phoenixgraph.query.result.Subgraph;
import com.pengcheng.phoenixgraph.query.traversal.NeighborVertexResolver;
import com.pengcheng.phoenixgraph.schema.GeoIndexSchemas;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchemaManagers;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes traversal queries against Aerospike, in one of two languages.
 *
 * <p>The graph name in the request selects the schema and the vertex set (see
 * {@link GraphResolver}). One engine per graph and language is created on first use:
 * a {@link GremlinGraphQuery} (which caches its compiled queries) and a
 * {@link CypherGraphQuery} (the openCypher subset of {@code graph-query-cypher}, which
 * resolves {@code $name} parameters from the request).
 *
 * <p>{@code ${paramName}} placeholders in the query text are substituted from the
 * request's {@code params} in both languages, so a client can ship a query template and
 * fill it in per call: {@code MATCH (a:Account {id: '${id}'}) RETURN a.id} as well as
 * the native {@code $id} form.
 */
@Service
public class QueryService {

    /** The languages the service can execute. */
    public static final String GREMLIN = "gremlin";
    public static final String CYPHER = "cypher";

    /** The most elements one answer carries unless {@code phoenixgraph.query.max-response-items} says otherwise. */
    public static final int DEFAULT_MAX_RESPONSE_ITEMS = 200_000;

    private static final Pattern PARAMETER = Pattern.compile("\\$\\{([^}]+)}");

    private final GraphResolver graphs;
    private final VectorIndexManager vectorIndexes;
    private final MetricsRegistry metrics;
    private final Map<String, GremlinGraphQuery> engines = new ConcurrentHashMap<>();

    /**
     * The fuzzy patterns of every graph. A pattern is data an analyst edits while the graph runs,
     * so the engine asks the registry on each execution instead of holding a snapshot - which is
     * why this is a field that can be wired after construction (a service built without one simply
     * has no patterns, and a fuzzy query says so).
     */
    private FuzzyPatternStore fuzzyPatterns;

    /** The cluster the DIST algorithm steps run on (null when this instance has none). */
    private GeminiCluster geminiCluster;

    /** The most elements one answer may carry (see {@link #cap}); {@code 0} disables the cap. */
    private int maxResponseItems = DEFAULT_MAX_RESPONSE_ITEMS;

    /** The longest query text accepted, in characters ({@code phoenixgraph.limits.max-query-length}). */
    private int maxQueryLength = 65536;

    /**
     * Whether scripts may use the admin-level features: whitelisted statics ({@code Math.max}), a
     * first-class closure stored in a variable and called, and regular expressions ({@code =~},
     * {@code ==~}, {@code /.../}). The development UI gate is the script toggle itself, so this
     * defaults to on; an admin deployment can set {@code phoenixgraph.script.advanced=false} (or wire
     * it to a role later) and the constructs are refused with a message that says so.
     */
    private boolean advancedScript = true;

    /** Wires the admin-level script features ({@code phoenixgraph.script.advanced}). */
    @Value("${phoenixgraph.script.advanced:true}")
    public void advancedScript(boolean advancedScript) {
        this.advancedScript = advancedScript;
    }

    /** Wires the patterns fuzzy linking reads ({@code fuzzyLink(patternId, context)}). */
    @Autowired(required = false)
    public void fuzzyPatterns(FuzzyPatternStore fuzzyPatterns) {
        this.fuzzyPatterns = fuzzyPatterns;
        engines.clear(); // a new registry is a new plan
    }

    /** Wires the Gemini cluster the distributed algorithm steps run on. */
    @Autowired(required = false)
    public void geminiCluster(GeminiCluster geminiCluster) {
        this.geminiCluster = geminiCluster;
        engines.clear();
    }

    /** Wires the cap on how much one answer may carry ({@code phoenixgraph.query.max-response-items}). */
    @Value("${phoenixgraph.query.max-response-items:" + DEFAULT_MAX_RESPONSE_ITEMS + "}")
    public void maxResponseItems(int maxResponseItems) {
        this.maxResponseItems = maxResponseItems;
    }

    /** Wires the longest query text accepted ({@code phoenixgraph.limits.max-query-length}). */
    @Value("${phoenixgraph.limits.max-query-length:65536}")
    public void maxQueryLength(int maxQueryLength) {
        this.maxQueryLength = maxQueryLength;
    }

    private final Map<String, CypherGraphQuery> cypherEngines = new ConcurrentHashMap<>();

    public QueryService(GraphResolver graphs, @Nullable VectorIndexManager vectorIndexes) {
        this(graphs, vectorIndexes, null);
    }

    @Autowired
    public QueryService(GraphResolver graphs, @Nullable VectorIndexManager vectorIndexes,
                        @Nullable MetricsRegistry metrics) {
        this.graphs = Objects.requireNonNull(graphs, "graphs");
        this.vectorIndexes = vectorIndexes;
        this.metrics = metrics;
    }

    /**
     * A service over a single pair of stores: every graph name shares them and
     * schemas are looked up by name in {@link GraphSchemaManagers}.
     */
    public static QueryService ambient(AerospikeVertexStore vertexStore, AerospikeEdgeStore edgeStore) {
        return new QueryService(GraphResolver.ambient(vertexStore, edgeStore), null);
    }

    /** The metrics of a graph, or a sink that forgets everything when none are wired. */
    private GraphMetrics metricsOf(String graphName) {
        return metrics == null ? GraphMetrics.noop() : metrics.forGraph(graphName);
    }

    /**
     * Runs a query and records what it cost: one count for the language and the caller,
     * the latency, the storage traffic it caused (the delta of the vertex and edge read
     * counters, in key-value operations) and the elements it returned.
     *
     * <p>The delta is read from the graph's counters before and after the call, so the
     * parallel seed reads a traversal fans out are all attributed to it.
     */
    private <T> T measured(String graphName, String language, String source, java.util.function.Supplier<T> body) {
        GraphMetrics graph = metricsOf(graphName);
        if (!graph.recording()) {
            return body.get();
        }
        long vertexReads = graph.value(Metrics.VERTEX_READ);
        long edgeReads = graph.value(Metrics.EDGE_READ);
        long kvReads = graph.value(Metrics.KV_READ_CALL);
        long kvWrites = graph.value(Metrics.KV_WRITE_CALL);
        long storageItems = graph.value(Metrics.items(Metrics.VERTEX_READ))
                + graph.value(Metrics.items(Metrics.EDGE_READ));
        long started = System.nanoTime();
        try {
            T result = body.get();
            graph.increment(Metrics.QUERY_ITEMS, elementsOf(result));
            return result;
        } catch (RuntimeException | Error e) {
            graph.increment(Metrics.queryFailed(language, source));
            throw e;
        } finally {
            graph.increment(Metrics.queryCount(language, source));
            graph.recordLatencyMillis(Metrics.queryLatency(language),
                    (System.nanoTime() - started) / 1_000_000L);
            graph.increment(Metrics.QUERY_VERTEX_READ, graph.value(Metrics.VERTEX_READ) - vertexReads);
            graph.increment(Metrics.QUERY_EDGE_READ, graph.value(Metrics.EDGE_READ) - edgeReads);
            graph.increment(Metrics.QUERY_KV_READ, graph.value(Metrics.KV_READ_CALL) - kvReads);
            graph.increment(Metrics.QUERY_KV_WRITE, graph.value(Metrics.KV_WRITE_CALL) - kvWrites);
            graph.increment(Metrics.QUERY_STORAGE_ITEMS,
                    graph.value(Metrics.items(Metrics.VERTEX_READ)) + graph.value(Metrics.items(Metrics.EDGE_READ))
                            - storageItems);
        }
    }

    /** How many elements a query answered with (a traverser list, a subgraph, some rows). */
    private static long elementsOf(Object result) {
        if (result instanceof java.util.Collection<?> collection) {
            return collection.size();
        }
        if (result instanceof Subgraph subgraph) {
            return subgraph.vertices().size() + subgraph.edges().size();
        }
        if (result instanceof QueryResponse response) {
            return elementsOf(response.result());
        }
        if (result instanceof CypherResult rows) {
            return rows.rows() == null ? 0 : rows.rows().size();
        }
        return result == null ? 0 : 1;
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

    // --- Cypher ---

    /**
     * Executes an openCypher query: {@code ${name}} placeholders are substituted
     * first, {@code $name} references are passed to the engine as parameters.
     */
    public CypherResult cypher(String graphName, String query, Map<String, String> params) {
        Objects.requireNonNull(query, "query");
        return cypherEngine(graphName).execute(substitute(query, params), cypherParameters(params));
    }

    /** Like {@link #cypher} but also reads the induced subgraph the match visited. */
    public CypherResult cypherSubgraph(String graphName, String query, Map<String, String> params) {
        Objects.requireNonNull(query, "query");
        return cypherEngine(graphName).executeSubgraph(substitute(query, params), cypherParameters(params));
    }

    /** Runs the query in the requested language ({@code gremlin} or {@code cypher}). */
    public Object query(String graphName, String query, Map<String, String> params, String language,
                        boolean returnSubgraph) {
        return query(graphName, query, params, language, returnSubgraph, false);
    }

    /**
     * Runs a query and decides what the response holds: the answer, the subgraph it
     * visited, or both ({@link QueryResponse}).
     *
     * <p>Both come from one execution, so asking for the value next to the graph costs
     * nothing extra - a {@code count()} has no graph of its own and a walk has no single
     * value, and a UI wants to show whichever the query produced.
     */
    public Object query(String graphName, String query, Map<String, String> params, String language,
                        boolean returnSubgraph, boolean includeResult) {
        return query(graphName, query, params, language, returnSubgraph, includeResult, Metrics.SOURCE_HTTP);
    }

    /** Runs a query, recording it under the given source (the UI says {@code ui}). */
    public Object query(String graphName, String query, Map<String, String> params, String language,
                        boolean returnSubgraph, boolean includeResult, String source) {
        return queryCapped(graphName, query, params, language, returnSubgraph, includeResult, source).body();
    }

    /**
     * The answer of a query together with whether it had to be capped to
     * {@link #maxResponseItems}.
     *
     * <p>The cap exists because an unbounded traversal over a dense graph can build a response no
     * client can read: four hops from a hub of the demo graph reach millions of traversers and
     * hundreds of megabytes of JSON, which a browser fails to parse (it reports it as "unexpected end
     * of JSON input"). Capping keeps the response finite and lets the caller say so - the HTTP
     * endpoint turns it into an {@code X-PhoenixGraph-Truncated} header and the UI into a hint to
     * narrow the query.
     */
    public record Answer(Object body, boolean truncated, int limit) {
    }

    /** Runs a query with the script mode decided from the text (see the overload below). */
    public Answer queryCapped(String graphName, String query, Map<String, String> params, String language,
                              boolean returnSubgraph, boolean includeResult, String source) {
        return queryCapped(graphName, query, params, language, returnSubgraph, includeResult, source, null);
    }

    /**
     * Runs a query and caps its answer (see {@link Answer}).
     *
     * @param script whether a Gremlin query is a Groovy-style script; {@code null} decides from the text
     */
    public Answer queryCapped(String graphName, String query, Map<String, String> params, String language,
                              boolean returnSubgraph, boolean includeResult, String source, Boolean script) {
        if (query == null) {
            throw new IllegalArgumentException("query must not be null");
        }
        if (query.isBlank()) {
            throw new QueryException("query must not be blank");
        }
        // a query is a traversal, not a document: the limit is here so a pasted megabyte of text cannot occupy a
        // parser and a slot; the message names the setting rather than only the length
        if (query.length() > maxQueryLength) {
            throw new IllegalArgumentException("The query is " + query.length() + " characters, over the limit of "
                    + maxQueryLength + " (phoenixgraph.limits.max-query-length)");
        }
        requireName(graphName);
        String selected = language == null || language.isBlank() ? GREMLIN : language.trim().toLowerCase();
        String caller = source == null || source.isBlank() ? Metrics.SOURCE_HTTP : source.trim().toLowerCase();
        Object body = measured(graphName, selected, caller, () -> execute(graphName, query, params, selected,
                returnSubgraph, includeResult, script));
        return cap(body);
    }

    /**
     * Caps an answer to {@link #maxResponseItems} elements (traversers, or subgraph vertices plus
     * edges), remembering that it happened. {@code 0} disables the cap.
     */
    private Answer cap(Object body) {
        if (maxResponseItems <= 0) {
            return new Answer(body, false, maxResponseItems);
        }
        boolean[] truncated = {false};
        Object capped = capValue(body, truncated);
        return new Answer(capped, truncated[0], maxResponseItems);
    }

    private Object capValue(Object body, boolean[] truncated) {
        if (body instanceof ScriptResponse response) {
            Object result = capValue(response.result(), truncated);
            Subgraph subgraph = (Subgraph) capValue(response.subgraph(), truncated);
            return result == response.result() && subgraph == response.subgraph()
                    ? response : new ScriptResponse(result, response.output(), subgraph, response.compile());
        }
        if (body instanceof Subgraph subgraph) {
            List<Subgraph.Vertex> vertices = capList(subgraph.vertices(), truncated);
            List<Subgraph.Edge> edges = capList(subgraph.edges(), truncated);
            return vertices == subgraph.vertices() && edges == subgraph.edges()
                    ? subgraph : new Subgraph(vertices, edges);
        }
        if (body instanceof QueryResponse response) {
            Object result = capValue(response.result(), truncated);
            Subgraph subgraph = (Subgraph) capValue(response.subgraph(), truncated);
            return result == response.result() && subgraph == response.subgraph()
                    ? response : new QueryResponse(result, subgraph);
        }
        if (body instanceof List<?> list) {
            return capList(list, truncated);
        }
        return body;
    }

    private <T> List<T> capList(List<T> list, boolean[] truncated) {
        if (list == null || list.size() <= maxResponseItems) {
            return list;
        }
        truncated[0] = true;
        return List.copyOf(list.subList(0, maxResponseItems));
    }

    private Object execute(String graphName, String query, Map<String, String> params, String selected,
                           boolean returnSubgraph, boolean includeResult, Boolean script) {
        return switch (selected) {
            case GREMLIN -> {
                String substituted = substitute(query, params);
                // a script is the same traversals written as statements (variables, print): the client may
                // say so, and when it does not the text decides
                boolean asScript = script != null ? script : GremlinScript.looksLikeScript(substituted);
                if (asScript) {
                    GremlinGraphQuery engine = engineFor(graphName);
                    // the script is parsed once per text and each of its traversals is compiled once per
                    // text: the counters around the run say how much of that this call had to do
                    GremlinGraphQuery.CompileStats before = engine.compileStats();
                    GremlinGraphQuery.ScriptPlan plan = engine.script(substituted);
                    GremlinScript.Result result = plan.script().execute(engine::executeWithSubgraph,
                            advancedScript);
                    GremlinGraphQuery.CompileStats spent = engine.compileStats().minus(before);
                    yield new ScriptResponse(result.value(), result.output(),
                            returnSubgraph ? result.subgraph() : Subgraph.EMPTY,
                            new ScriptResponse.Compile(plan.parseNanos() / 1e6, plan.parsed(),
                                    (int) spent.traversalCompiles(), spent.traversalCompileNanos() / 1e6));
                }
                if (!returnSubgraph) {
                    yield engineFor(graphName).execute(substituted);
                }
                GremlinGraphQuery.Result result = engineFor(graphName).executeWithSubgraph(substituted);
                yield includeResult
                        ? new QueryResponse(result.values(), result.subgraph())
                        : result.subgraph();
            }
            case CYPHER -> {
                String substituted = substitute(query, params);
                CypherGraphQuery engine = cypherEngine(graphName);
                yield returnSubgraph
                        ? engine.executeSubgraph(substituted, cypherParameters(params))
                        : engine.execute(substituted, cypherParameters(params));
            }
            default -> throw new QueryException("Unknown query language '" + selected
                    + "'; supported: " + GREMLIN + ", " + CYPHER);
        };
    }

    /**
     * Parameters reach the engine as the strings they are. Guessing types would break
     * ids - a vertex id is the record key, so {@code 135} and {@code "135"} are
     * different keys - while numeric comparisons still work because
     * {@code CypherExpression.Values.compare} compares a number with a numeric string
     * numerically.
     */
    private static Map<String, Object> cypherParameters(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return Map.of();
        }
        return new LinkedHashMap<>(params);
    }

    private CypherGraphQuery cypherEngine(String graphName) {
        return cypherEngines.computeIfAbsent(requireName(graphName), name -> {
            GraphBinding binding = graphs.require(name);
            return new CypherGraphQuery(binding.graphQuery(),
                    NeighborVertexResolver.from(binding.schema()));
        });
    }

    // --- Gremlin ---

    private GremlinGraphQuery engineFor(String graphName) {
        return engines.computeIfAbsent(requireName(graphName), name -> {
            GraphBinding binding = graphs.require(name);
            GremlinGraphQuery engine = new GremlinGraphQuery(binding.graphQuery(),
                    NeighborVertexResolver.from(binding.schema()))
                    .geoProperties(geoPropertyByVertexType(binding.schema()))
                    .schema(binding.schema());
            if (fuzzyPatterns != null) {
                engine.fuzzyPatterns(fuzzyPatterns.registryOf(name));
            }
            if (geminiCluster != null) {
                engine.geminiCluster(geminiCluster);
            }
            // vector search: every property the schema flags isVector gets a nearest(k)
            // step (the index is shared with the write path, so freshly written
            // vertices are searchable immediately)
            if (vectorIndexes != null) {
                // resolved per query, so a rebuilt index is used without restarting
                engine.hnswIndexSource(() -> vectorIndexes.indexesOf(name));
            } else if (binding.hasKeyValueAccess() && binding.hasAerospikeStores()) {
                AerospikeHnswIndexFactory.open(
                                new AerospikeGraphQuery(binding.vertexStore(), binding.edgeStore(), binding.schema()),
                                binding.edgeStore(), binding.schema(),
                                binding.client(), binding.namespace(), AerospikeHnswIndexFactory.DEFAULT_METADATA_SET,
                                VectorIndexManager.METADATA_TTL)
                        .forEach(engine::hnswIndex);
            }
            return engine;
        });
    }

    /**
     * The geo property of every vertex type that declares one ({@code isGeo}), which is what
     * the {@code geoNear} step resolves its search against.
     */
    private static Map<String, String> geoPropertyByVertexType(GraphSchema schema) {
        Map<String, String> byType = new LinkedHashMap<>();
        for (GeoIndexSchemas.GeoProperty property : GeoIndexSchemas.geoProperties(schema)) {
            byType.putIfAbsent(property.vertexName(), property.propertyName());
        }
        return Map.copyOf(byType);
    }

    /**
     * The graph name a request names, or a 400.
     *
     * <p>{@code null} and blank are separate messages on purpose: a client that left the field out needs to
     * hear that, and one that sent {@code ""} needs to hear that an empty name is not a graph either. Left
     * alone, a missing name reached a map lookup and came back as a 500 NullPointerException.
     */
    private static String requireName(String graphName) {
        if (graphName == null) {
            throw new IllegalArgumentException("graphName must not be null");
        }
        if (graphName.isBlank()) {
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
