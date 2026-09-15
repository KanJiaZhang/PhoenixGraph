package com.pengcheng.phoenixgraph.query.gremlin;

import com.pengcheng.phoenixgraph.query.storage.GraphQuery;
import com.pengcheng.phoenixgraph.query.storage.VertexQueryResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Executes Gremlin-style traversals over a {@link GraphQuery} storage backend.
 *
 * <p>Starts from one or more vertices ({@code g.V(id..., vertexName)}), then
 * applies the parsed steps in order. Supported built-in steps:
 * <ul>
 *   <li>vertex hops — {@code out/in/both(edgeName)}</li>
 *   <li>edge hops — {@code outE/inE/bothE(edgeName)}</li>
 *   <li>filter — {@code has(key[, value])}</li>
 *   <li>projection — {@code values(key...)} and {@code elementMap()}</li>
 *   <li>terminal — {@code count()}, {@code limit(n)}, {@code dedup()}</li>
 * </ul>
 * Custom steps / named logic can be added with
 * {@link #registerStep(String, GremlinStepRegistry.StepFactory)} and
 * {@link #registerLogic(String, String)}.
 *
 * <p>Queries are compiled once (parse + logic expansion + step resolution +
 * materialization planning) and the compiled plan is cached, so repeated
 * executions of the same query string skip compilation.
 */
public class GremlinGraphQuery {

    private final GraphQuery graphQuery;
    private final NeighborVertexResolver vertexResolver;
    private final GremlinStepRegistry registry;
    private final Map<String, String> logics = new LinkedHashMap<>();
    private final Map<String, CompiledQuery> compiledCache = new ConcurrentHashMap<>();
    private boolean cacheEnabled = true;
    private boolean adaptiveBatchEnabled = true;
    private int maxBatchSize = GremlinContext.DEFAULT_MAX_BATCH_SIZE;

    public GremlinGraphQuery(GraphQuery graphQuery) {
        this(graphQuery, NeighborVertexResolver.none());
    }

    public GremlinGraphQuery(GraphQuery graphQuery, NeighborVertexResolver vertexResolver) {
        this(graphQuery, vertexResolver, new GremlinStepRegistry());
    }

    public GremlinGraphQuery(GraphQuery graphQuery, NeighborVertexResolver vertexResolver,
                             GremlinStepRegistry registry) {
        this.graphQuery = Objects.requireNonNull(graphQuery, "graphQuery");
        this.vertexResolver = vertexResolver == null ? NeighborVertexResolver.none() : vertexResolver;
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /** Registers a custom step (a "code snippet") under a step name. */
    public GremlinGraphQuery registerStep(String name, GremlinStepRegistry.StepFactory factory) {
        registry.register(name, factory);
        compiledCache.clear();
        return this;
    }

    /** Enables or disables the per-query vertex cache (on by default). */
    public GremlinGraphQuery cacheEnabled(boolean cacheEnabled) {
        this.cacheEnabled = cacheEnabled;
        return this;
    }

    /** Enables or disables adaptive batch-size tuning for fan-out hops (on by default). */
    public GremlinGraphQuery adaptiveBatchEnabled(boolean adaptiveBatchEnabled) {
        this.adaptiveBatchEnabled = adaptiveBatchEnabled;
        return this;
    }

    /** Upper bound for the adaptive batch size; must be {@code >= 1}. */
    public GremlinGraphQuery maxBatchSize(int maxBatchSize) {
        if (maxBatchSize < 1) {
            throw new IllegalArgumentException("max batch size must be >= 1");
        }
        this.maxBatchSize = maxBatchSize;
        return this;
    }

    /**
     * Registers a named logic (a step chain such as
     * {@code .has('status','SUCCESS').count()}) that can be inlined into a query
     * via a trailing {@code { $name }} block.
     */
    public GremlinGraphQuery registerLogic(String name, String stepChain) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("logic name must not be blank");
        }
        logics.put(name, Objects.requireNonNull(stepChain, "stepChain"));
        compiledCache.clear();
        return this;
    }

    /** Parses and executes a Gremlin-style traversal query (compiled once, then cached). */
    public List<Object> execute(String gremlin) {
        Objects.requireNonNull(gremlin, "gremlin");
        CompiledQuery compiled = compiledCache.computeIfAbsent(gremlin, this::compile);
        return executeCompiled(compiled);
    }

    /** Executes an already-parsed traversal query (compiled on each call). */
    public List<Object> execute(GremlinQuery query) {
        Objects.requireNonNull(query, "query");
        return executeCompiled(compile(query));
    }

    private CompiledQuery compile(String gremlin) {
        return compile(GremlinQuery.parse(gremlin));
    }

    private CompiledQuery compile(GremlinQuery query) {
        List<GremlinQuery.Step> parsedSteps = new ArrayList<>(query.steps());
        if (query.logic() != null && !query.logic().isBlank()) {
            parsedSteps.addAll(expandLogic(query.logic()));
        }
        parsedSteps = optimizeSteps(parsedSteps);
        List<String>[] properties = planProperties(parsedSteps);
        List<String> startProperties = startProperties(parsedSteps);
        List<GremlinStep> steps = new ArrayList<>(parsedSteps.size());
        List<String> stepNames = new ArrayList<>(parsedSteps.size());
        for (GremlinQuery.Step step : parsedSteps) {
            steps.add(registry.resolve(step.name(), step.args()));
            stepNames.add(step.name());
        }
        return new CompiledQuery(query.vertexIds(), query.vertexName(), properties, startProperties,
                stepNames, steps);
    }

    /**
     * Rewrites a vertex hop immediately followed by {@code count()} into an edge
     * hop: {@code out/in/both(label).count()} → {@code outE/inE/bothE(label).count()}.
     * This avoids materialising the reached vertices (a vertex hop has exactly one
     * neighbour per edge, so the count is identical). Applied globally.
     */
    private static List<GremlinQuery.Step> optimizeSteps(List<GremlinQuery.Step> steps) {
        List<GremlinQuery.Step> result = new ArrayList<>(steps);
        for (int i = 0; i < result.size(); i++) {
            String edgeHop = edgeHopOf(result.get(i).name());
            if (edgeHop == null) {
                continue;
            }
            int next = i + 1;
            while (next < result.size() && isMeta(result.get(next).name())) {
                next++;
            }
            if (next < result.size() && result.get(next).name().equals("count")) {
                result.set(i, new GremlinQuery.Step(edgeHop, result.get(i).args()));
            }
        }
        return result;
    }

    private static String edgeHopOf(String name) {
        return switch (name) {
            case "out" -> "outE";
            case "in" -> "inE";
            case "both" -> "bothE";
            default -> null;
        };
    }

    /** Executes a query and returns the induced subgraph instead of the final traversers. */
    public Subgraph executeSubgraph(String gremlin) {
        Objects.requireNonNull(gremlin, "gremlin");
        CompiledQuery compiled = compiledCache.computeIfAbsent(gremlin, this::compile);
        GremlinContext context = newContext();
        context.collectSubgraph(true);
        runCompiled(context, compiled);
        return context.buildSubgraph();
    }

    private List<Object> executeCompiled(CompiledQuery compiled) {
        GremlinContext context = newContext();
        return runCompiled(context, compiled);
    }

    private GremlinContext newContext() {
        GremlinContext context = new GremlinContext(graphQuery, vertexResolver);
        context.cacheEnabled(cacheEnabled);
        context.adaptiveBatchEnabled(adaptiveBatchEnabled);
        context.maxBatchSize(maxBatchSize);
        return context;
    }

    private List<Object> runCompiled(GremlinContext context, CompiledQuery compiled) {
        List<Object> current = start(context, compiled.vertexIds, compiled.vertexName, compiled.startProperties);
        int hopsDone = 0;
        int hopFanOut = 0;
        boolean filterSinceHop = false;
        for (int i = 0; i < compiled.steps.size(); i++) {
            String name = compiled.stepNames.get(i);
            if (isHop(name)) {
                // Adaptive batch tuning: scale the next hop's batch size to the previous
                // hop's fan-out, unless a filter already reduced the traverser cardinality.
                if (hopsDone >= 1) {
                    context.adaptBatchSize(hopFanOut, filterSinceHop);
                }
                context.readProperties(compiled.properties[i]);
                current = compiled.steps.get(i).apply(context, current);
                hopFanOut = current.size();
                hopsDone++;
                filterSinceHop = false;
            } else {
                if (hopsDone >= 1 && name.equals("has")) {
                    filterSinceHop = true;
                }
                context.readProperties(compiled.properties[i]);
                current = compiled.steps.get(i).apply(context, current);
            }
        }
        return current;
    }

    /** A compiled query: resolved steps + precomputed property projection plan. */
    private static final class CompiledQuery {
        final List<Object> vertexIds;
        final String vertexName;
        final List<String>[] properties;
        final List<String> startProperties;
        final List<String> stepNames;
        final List<GremlinStep> steps;

        CompiledQuery(List<Object> vertexIds, String vertexName, List<String>[] properties,
                      List<String> startProperties, List<String> stepNames, List<GremlinStep> steps) {
            this.vertexIds = vertexIds;
            this.vertexName = vertexName;
            this.properties = properties;
            this.startProperties = startProperties;
            this.stepNames = stepNames;
            this.steps = steps;
        }
    }

    private List<GremlinQuery.Step> expandLogic(String logic) {
        String expanded = logic.trim();
        for (Map.Entry<String, String> entry : logics.entrySet()) {
            expanded = expanded.replace("$" + entry.getKey(), entry.getValue());
        }
        return GremlinQuery.parseSteps(expanded);
    }

    /**
     * Determines, for each hop step, the minimal set of property names its
     * output vertices must carry: {@code null} = none (skip the read), empty =
     * all properties, non-empty = only those property bins.
     */
    @SuppressWarnings("unchecked")
    private static List<String>[] planProperties(List<GremlinQuery.Step> steps) {
        List<String>[] properties = new List[steps.size()];
        for (int i = 0; i < steps.size(); i++) {
            if (isHop(steps.get(i).name())) {
                properties[i] = neededProperties(steps, i + 1);
            }
        }
        return properties;
    }

    private static List<String> startProperties(List<GremlinQuery.Step> steps) {
        return neededProperties(steps, 0);
    }

    /**
     * Computes the property names needed by the steps starting at {@code from}:
     * {@code null} = none, empty = all, non-empty = specific names. Scanning stops
     * at the next hop (which only needs ids) or a terminal step.
     */
    private static List<String> neededProperties(List<GremlinQuery.Step> steps, int from) {
        boolean all = false;
        boolean sawConsumer = false;
        Set<String> keys = new LinkedHashSet<>();
        for (int j = from; j < steps.size(); j++) {
            String name = steps.get(j).name();
            if (isHop(name)) {
                sawConsumer = true;
                break;
            }
            if (isMeta(name)) {
                continue;
            }
            sawConsumer = true;
            if (name.equals("elementMap") || name.equals("dedup")) {
                all = true;
                break;
            }
            if (name.equals("has")) {
                List<String> args = steps.get(j).args();
                if (!args.isEmpty()) {
                    keys.add(args.get(0));
                }
            } else if (name.equals("values")) {
                keys.addAll(steps.get(j).args());
            } else if (name.equals("count") || name.equals("limit")) {
                break;
            }
        }
        if (all || !sawConsumer) {
            return List.of();
        }
        if (keys.isEmpty()) {
            return null;
        }
        return List.copyOf(keys);
    }

    private static boolean isHop(String name) {
        return name.equals("out") || name.equals("in") || name.equals("both")
                || name.equals("outE") || name.equals("inE") || name.equals("bothE");
    }

    private static boolean isMeta(String name) {
        return name.equals("batch") || name.equals("barrier");
    }

    private static List<Object> start(GremlinContext context, List<Object> vertexIds, String vertexName,
                                      List<String> properties) {
        if (vertexIds.isEmpty()) {
            return List.of();
        }
        if (properties == null) {
            // Only the id/label are needed for the first hop — skip reading the record.
            List<Object> out = new ArrayList<>(vertexIds.size());
            for (Object vertexId : vertexIds) {
                context.recordVertex(vertexName, vertexId);
                out.add(new GremlinVertex(vertexName, vertexId, Map.of(), 0L, 0L));
            }
            return out;
        }
        List<VertexQueryResult> vertices = context.readVerticesCached(vertexName, vertexIds, properties);
        List<Object> out = new ArrayList<>(vertices.size());
        for (VertexQueryResult vertex : vertices) {
            if (vertex == null) {
                continue;
            }
            context.recordVertex(vertex.vertexName(), vertex.vertexId());
            out.add(new GremlinVertex(vertex.vertexName(), vertex.vertexId(), vertex.properties(),
                    vertex.createdTime(), vertex.lastUpdatedTime()));
        }
        return out;
    }
}
