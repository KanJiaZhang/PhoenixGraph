package com.pengcheng.phoenixgraph.query.gremlin;

import com.pengcheng.phoenixgraph.fuzzy.FuzzyPatternRegistry;
import com.pengcheng.phoenixgraph.gemini.GeminiAlgorithms;
import com.pengcheng.phoenixgraph.gemini.GeminiCluster;
import com.pengcheng.phoenixgraph.hnsw.HnswFilter;
import com.pengcheng.phoenixgraph.hnsw.HnswIndex;
import com.pengcheng.phoenixgraph.query.QueryException;
import com.pengcheng.phoenixgraph.query.result.Subgraph;
import com.pengcheng.phoenixgraph.query.traversal.NeighborVertexResolver;
import com.pengcheng.phoenixgraph.query.storage.EdgePropertyFilter;
import com.pengcheng.phoenixgraph.query.storage.EdgeQueryResult;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
import com.pengcheng.phoenixgraph.query.storage.GraphQuery;
import com.pengcheng.phoenixgraph.query.storage.ScannedEdge;
import com.pengcheng.phoenixgraph.schema.geo.GeoSearch;
import com.pengcheng.phoenixgraph.storage.TimeRange;
import com.pengcheng.phoenixgraph.query.storage.VertexQueryResult;
import com.pengcheng.phoenixgraph.schema.EdgeDirection;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.VertexSchema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Executes Gremlin-style traversals over a {@link GraphQuery} storage backend.
 *
 * <p>Starts from one or more vertices ({@code g.V(id..., vertexName)}), then
 * applies the parsed steps in order. Supported built-in steps:
 * <ul>
 *   <li>vertex hops — {@code out/in/both(edgeName)}</li>
 *   <li>vertex hops — {@code out/in/both(edgeName)}</li>
 *   <li>edge hops — {@code outE/inE/bothE(edgeName)}, plus {@code otherV()} to hop
 *       from an edge to its far endpoint ({@code bothE().filter(...).otherV()})</li>
 *   <li>filter — {@code has(key[, value])} and {@code filter(expression)}</li>
 *   <li>vector search — {@code nearest(k[, ef])} over an HNSW index ({@link #hnswIndex})</li>
 *   <li>filter — {@code has(key[, value])} and {@code filter(expression)} / {@code filter { ... }}</li>
 *   <li>projection — {@code values(key...)}, {@code map { it.get().value('key') }} and {@code elementMap()}</li>
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
    private final Map<String, HnswIndex> hnswIndexes = new LinkedHashMap<>();
    private Map<String, String> geoProperties = Map.of();

    /** The schema a named hop's direction is checked against ({@code null} = no check). */
    private GraphSchema schema;
    private FuzzyPatternRegistry fuzzyPatterns = FuzzyPatternRegistry.empty();
    private GeminiCluster geminiCluster;
    private Supplier<Map<String, HnswIndex>> hnswIndexSource;
    private final Map<String, CompiledQuery> compiledCache = new ConcurrentHashMap<>();
    private boolean cacheEnabled = true;
    private int scanLimit = 0;
    private boolean adaptiveBatchEnabled = true;
    private int maxBatchSize = GremlinContext.DEFAULT_MAX_BATCH_SIZE;
    private int parallelism = GremlinContext.DEFAULT_PARALLELISM;

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
        // the vector-search step resolves its index per traverser vertex type
        registry.register("nearest", args -> {
            if (args.isEmpty() || args.size() > 2) {
                throw new QueryException("nearest expects nearest(k) or nearest(k, ef), got " + args.size()
                        + " arguments");
            }
            int k = positiveInt(args.get(0), "nearest");
            int ef = args.size() > 1 ? positiveInt(args.get(1), "nearest") : 0;
            return GremlinSteps.nearest(vertexType -> resolvedHnswIndexes().get(vertexType), k, ef);
        });
        // the algorithm steps exist in two forms: the in-memory one (no argument) and the one that
        // runs on a Gemini cluster, asked for with the DIST marker. A query that asks for DIST and has
        // no cluster is an error, never a quiet fallback - the marker is there to say which engine runs.
        for (String algorithm : new String[]{GeminiAlgorithms.PAGERANK, GeminiAlgorithms.LOUVAIN,
                GeminiAlgorithms.CONNECTED_COMPONENTS, GeminiAlgorithms.SSSP, GeminiAlgorithms.BFS}) {
            registry.register(algorithm, args -> GremlinDistAlgorithms.wantsDistributed(args)
                    ? GremlinDistAlgorithms.dist(geminiCluster, algorithm, args)
                    : inMemoryAlgorithm(algorithm, args));
        }
        // the fuzzyLink step turns a context into the key of a link node: patterns are data, so the
        // registry is consulted per execution (a pattern added in the builder page is used at once)
        registry.register("fuzzyLink", args -> {
            if (args.isEmpty() || args.size() > 2) {
                throw new QueryException("fuzzyLink expects fuzzyLink(patternId) or"
                        + " fuzzyLink(patternId, contextJson), got " + args.size() + " arguments");
            }
            int patternId = nonNegativeInt(args.get(0), "fuzzyLink");
            String context = args.size() > 1 ? args.get(1) : null;
            return GremlinSteps.fuzzyLink(fuzzyPatternsSource(), patternId, context);
        });
        // the circle step resolves the geo property of the traverser's vertex type; its third
        // argument is the query's limit, which the compile step pushes in (see #pushLimits)
        registry.register("circle", args -> {
            if (args.isEmpty() || args.size() > 3) {
                throw new QueryException("circle expects circle(pointJson[, radiusMeters][, limit]),"
                        + " got " + args.size() + " arguments");
            }
            Double radius = arg(args, 1, "circle") == null ? null : positiveDouble(args.get(1), "circle");
            Integer limit = arg(args, 2, "circle") == null ? null : positiveInt(args.get(2), "circle");
            try {
                GeoSearch search = GeoSearch.parse(args.get(0), radius);
                return GremlinSteps.circle(vertexType -> geoProperties.get(vertexType), graphQuery,
                        limit == null ? search : search.withMaxResults(limit));
            } catch (IllegalArgumentException e) {
                // a point or radius the step cannot read is a query error like any other
                throw new QueryException(e.getMessage(), e);
            }
        });
    }

    /**
     * Declares which property of which vertex type is a geo property (declared with
     * {@code isGeo} in the schema). The {@code circle} step uses it to find the index of the
     * type its traversers are on, exactly like {@link #hnswIndex} does for vector search.
     */
    public GremlinGraphQuery geoProperties(Map<String, String> geoPropertyByVertexType) {
        this.geoProperties = geoPropertyByVertexType == null ? Map.of() : Map.copyOf(geoPropertyByVertexType);
        compiledCache.clear();
        return this;
    }

    /**
     * Wires the schema a hop's direction is checked against: a hop that the schema says can never
     * match the vertex type the traversal stands on is rejected with the direction to use instead
     * (see {@link #checkHopDirections}). Without a schema the check is skipped.
     */
    public GremlinGraphQuery schema(GraphSchema schema) {
        this.schema = schema;
        compiledCache.clear();
        return this;
    }

    /**
     * The Gemini cluster the {@code (DIST)} forms of the algorithm steps run on.
     *
     * <p>One cluster serves every graph this engine answers for: a run loads the workers with the
     * graph it is about to compute, so a cluster is not bound to a graph.
     */
    public GremlinGraphQuery geminiCluster(GeminiCluster cluster) {
        this.geminiCluster = cluster;
        compiledCache.clear();
        return this;
    }

    /** The cluster this engine runs distributed algorithm steps on, or {@code null}. */
    public GeminiCluster geminiCluster() {
        return geminiCluster;
    }

    /**
     * The in-memory form of an algorithm step: the default when no {@code DIST} marker is given.
     *
     * <p>Three of the five have an in-memory implementation (they are small enough to run over the
     * traversers in one process); a shortest path step exists only in its distributed form, and says
     * so instead of answering with something else.
     */
    private static GremlinStep inMemoryAlgorithm(String algorithm, java.util.List<String> args) {
        if (!args.isEmpty()) {
            throw new QueryException(algorithm + " takes no argument unless it is asked to run distributed:"
                    + " write " + algorithm + "() or " + algorithm + "(DIST)");
        }
        return switch (algorithm) {
            case GeminiAlgorithms.PAGERANK -> GremlinAlgorithms.pagerank();
            case GeminiAlgorithms.LOUVAIN -> GremlinAlgorithms.louvain();
            case GeminiAlgorithms.CONNECTED_COMPONENTS -> GremlinAlgorithms.cc();
            default -> throw new QueryException(algorithm + " has no in-memory implementation; ask for the"
                    + " distributed one with " + algorithm + "(DIST)");
        };
    }

    /**
     * The patterns fuzzy linking uses. The registry is read per execution, so a pattern an analyst
     * adds is used by the next query without rebuilding the engine or clearing a plan.
     */
    public GremlinGraphQuery fuzzyPatterns(FuzzyPatternRegistry patterns) {
        this.fuzzyPatterns = patterns == null ? FuzzyPatternRegistry.empty() : patterns;
        compiledCache.clear();
        return this;
    }

    /** The patterns currently registered (empty when the graph has none). */
    public FuzzyPatternRegistry fuzzyPatterns() {
        return fuzzyPatterns;
    }

    private FuzzyPatternRegistry fuzzyPatternsSource() {
        return fuzzyPatterns == null ? FuzzyPatternRegistry.empty() : fuzzyPatterns;
    }

    /**
     * Resolves the vector indexes through a supplier instead of a fixed map, so a
     * long-lived engine always searches the indexes a service currently holds
     * (after a rebuild, for instance) without being recreated.
     */
    public GremlinGraphQuery hnswIndexSource(Supplier<Map<String, HnswIndex>> source) {
        this.hnswIndexSource = source;
        compiledCache.clear();
        return this;
    }

    private Map<String, HnswIndex> resolvedHnswIndexes() {
        Supplier<Map<String, HnswIndex>> source = hnswIndexSource;
        if (source == null) {
            return hnswIndexes;
        }
        Map<String, HnswIndex> resolved = source.get();
        return resolved == null ? hnswIndexes : resolved;
    }

    /**
     * Registers an HNSW index for a vertex type, enabling
     * {@code g.V(id, 'Type').nearest(k)} over that type.
     */
    public GremlinGraphQuery hnswIndex(HnswIndex index) {
        Objects.requireNonNull(index, "index");
        hnswIndexes.put(index.vertexName(), index);
        compiledCache.clear();
        return this;
    }

    /** Registers an HNSW index under an explicit vertex type name. */
    public GremlinGraphQuery hnswIndex(String vertexName, HnswIndex index) {
        hnswIndexes.put(Objects.requireNonNull(vertexName, "vertexName"), Objects.requireNonNull(index, "index"));
        compiledCache.clear();
        return this;
    }

    /** The HNSW indexes registered for vector search, keyed by vertex type. */
    public Map<String, HnswIndex> hnswIndexes() {
        return Map.copyOf(resolvedHnswIndexes());
    }

    /** A pattern id: zero is a pattern like any other. */
    private static int nonNegativeInt(String value, String stepName) {
        try {
            int n = Integer.parseInt(value.trim());
            if (n < 0) {
                throw new QueryException(stepName + " expects a pattern id, got: " + value);
            }
            return n;
        } catch (NumberFormatException e) {
            throw new QueryException(stepName + " expects a pattern id, got: " + value);
        }
    }

    private static int positiveInt(String value, String stepName) {
        try {
            int n = Integer.parseInt(value.trim());
            if (n < 1) {
                throw new QueryException(stepName + " expects a positive number, got: " + value);
            }
            return n;
        } catch (NumberFormatException e) {
            throw new QueryException(stepName + " expects a number, got: " + value);
        }
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

    /**
     * How many elements a traversal that starts from a whole type may read ({@code 0} = no cap).
     *
     * <p>A scan reads as much as the store holds, which is what an analytical query asks for; the cap
     * is for an embedding that wants every scan to stay bounded, so one policy covers every query a
     * caller can write.
     */
    public GremlinGraphQuery scanLimit(int scanLimit) {
        if (scanLimit < 0) {
            throw new IllegalArgumentException("scan limit must be >= 0");
        }
        this.scanLimit = scanLimit;
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
     * How many storage read batches a query may issue concurrently ({@code 1} =
     * sequential). Multi-seed queries and wide fan-outs are split into batches of
     * at most {@code batch(n)} ids and read in parallel. Defaults to
     * {@link GremlinContext#DEFAULT_PARALLELISM}.
     */
    public GremlinGraphQuery parallelism(int parallelism) {
        if (parallelism < 1) {
            throw new IllegalArgumentException("parallelism must be >= 1");
        }
        this.parallelism = parallelism;
        compiledCache.clear();
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

    /**
     * Rewrites {@code createdBetween(a, b) ... <hop> [has|filter] limit(k)} so the window carries
     * the cap ({@code createdBetween(a, b, k)}) and the limit step goes away.
     *
     * <p>A hub's edges live in the aggregated tables, where a read walks the chunks in creation
     * time: with the cap the read stops after k edges instead of materialising the whole window,
     * which is what a UI expansion needs. The traversers are the same k edges either way.
     */
    private static List<GremlinQuery.Step> pushLimitsIntoWindow(List<GremlinQuery.Step> steps) {
        List<GremlinQuery.Step> out = new ArrayList<>(steps.size());
        for (int i = 0; i < steps.size(); i++) {
            GremlinQuery.Step step = steps.get(i);
            if (!step.name().equals("createdBetween") || step.args().size() > 2) {
                out.add(step);
                continue;
            }
            int hop = -1;
            for (int j = i + 1; j < steps.size(); j++) {
                if (isHop(steps.get(j).name())) {
                    hop = j;
                    break;
                }
            }
            if (hop < 0) {
                out.add(step);
                continue;
            }
            int next = hop + 1;
            while (next < steps.size() && isFilter(steps.get(next).name())) {
                next++;
            }
            if (next >= steps.size() || !steps.get(next).name().equals("limit")) {
                out.add(step);
                continue;
            }
            List<String> args = new ArrayList<>(step.args());
            args.add(steps.get(next).args().get(0));
            out.add(new GremlinQuery.Step("createdBetween", List.copyOf(args)));
            // everything up to the limit stays, the limit itself is now the window's cap
            for (int j = i + 1; j < next; j++) {
                out.add(steps.get(j));
            }
            i = next;
        }
        return out;
    }

    /**
     * Rewrites {@code circle(...)} followed by {@code limit(k)} into one {@code circle} step
     * carrying the limit ({@code circle(pointJson, radiusMeters, k)}), so the limit is applied
     * inside the index search rather than after every hit was read.
     *
     * <p>The rewrite is only about <i>where</i> the limit runs: the result of
     * {@code circle(...).limit(k)} is the k nearest points either way, but here the storage
     * stops early instead of materialising the whole circle.
     */
    private static List<GremlinQuery.Step> pushLimitsIntoCircle(List<GremlinQuery.Step> steps) {
        List<GremlinQuery.Step> out = new ArrayList<>(steps.size());
        for (int i = 0; i < steps.size(); i++) {
            GremlinQuery.Step step = steps.get(i);
            if (!step.name().equals("circle") || i + 1 >= steps.size()
                    || !steps.get(i + 1).name().equals("limit")) {
                out.add(step);
                continue;
            }
            List<String> args = new ArrayList<>(step.args());
            while (args.size() < 2) {
                args.add(""); // no radius given: it comes from the point JSON
            }
            args.add(steps.get(i + 1).args().get(0));
            out.add(new GremlinQuery.Step("circle", List.copyOf(args)));
            i++; // the limit became part of the circle
        }
        return out;
    }

    private CompiledQuery compile(GremlinQuery query) {
        checkHopDirections(query);
        List<GremlinQuery.Step> parsedSteps = new ArrayList<>(query.steps());
        if (query.logic() != null && !query.logic().isBlank()) {
            parsedSteps.addAll(expandLogic(query.logic()));
        }
        parsedSteps = optimizeSteps(parsedSteps);
        // a circle step absorbs the limit directly following it: the geo search keeps only that
        // many hits while it walks the index, so the vertex reads, the collected subgraph and
        // the rendered graph stop at the limit instead of covering the whole circle
        parsedSteps = pushLimitsIntoCircle(parsedSteps);
        // a limit after the first hop of a windowed traversal becomes the window's cap, so the
        // aggregated tables stop reading once they have that many edges
        parsedSteps = pushLimitsIntoWindow(parsedSteps);
        // a limit directly after a vertex hop becomes the hop's own cap, so the hop stops reading once
        // it has that many neighbours instead of materialising every one and then truncating
        List<Integer> vertexCaps = new ArrayList<>();
        parsedSteps = pushVertexHopLimits(parsedSteps, vertexCaps);
        // the conditions and the cap of an edge hop are decided here, so the resolution loop below
        // can hand them to the step (the steps that were folded away are gone from the plan). Only
        // a store that evaluates them while it decodes gets them: for every other backend the
        // steps stay where they are and the traversal filters what it read, as it always did.
        List<EdgePushdown> edgePushdowns = new ArrayList<>();
        if (graphQuery.supportsEdgeReadPushdown()) {
            parsedSteps = pushEdgeConditions(parsedSteps, edgePushdowns);
        } else {
            for (int i = 0; i < parsedSteps.size(); i++) {
                edgePushdowns.add(null);
            }
        }
        // a nearest step absorbs the has/filter conditions directly following it: they
        // are evaluated inside the graph walk, so the search returns the closest k that
        // *match* instead of the k closest of which some happen to match
        List<GremlinQuery.Step> plannedSteps = new ArrayList<>(parsedSteps.size());
        List<HnswFilter> pushedFilters = new ArrayList<>(parsedSteps.size());
        List<EdgePushdown> plannedPushdowns = new ArrayList<>(parsedSteps.size());
        List<Integer> plannedVertexCaps = new ArrayList<>(parsedSteps.size());
        for (int i = 0; i < parsedSteps.size(); i++) {
            GremlinQuery.Step step = parsedSteps.get(i);
            if (!step.name().equals("nearest")) {
                plannedSteps.add(step);
                pushedFilters.add(null);
                plannedPushdowns.add(edgePushdowns.get(i));
                plannedVertexCaps.add(vertexCaps.get(i));
                continue;
            }
            List<GremlinQuery.Step> conditions = new ArrayList<>();
            int next = i + 1;
            while (next < parsedSteps.size() && isFilter(parsedSteps.get(next).name())) {
                conditions.add(parsedSteps.get(next));
                next++;
            }
            plannedSteps.add(step);
            pushedFilters.add(conditions.isEmpty() ? null : GremlinSteps.hnswFilter(conditions));
            plannedPushdowns.add(null);
            plannedVertexCaps.add(null);
            i = next - 1; // the conditions became part of the nearest step
        }
        List<String>[] properties = planProperties(plannedSteps);
        List<String> startProperties = startProperties(plannedSteps);
        List<GremlinStep> steps = new ArrayList<>(plannedSteps.size());
        List<String> stepNames = new ArrayList<>(plannedSteps.size());
        for (int i = 0; i < plannedSteps.size(); i++) {
            GremlinQuery.Step step = plannedSteps.get(i);
            HnswFilter pushed = pushedFilters.get(i);
            EdgePushdown edgePushed = plannedPushdowns.get(i);
            Integer vertexCap = plannedVertexCaps.get(i);
            if (pushed != null) {
                steps.add(nearestStep(step, pushed));
            } else if (edgePushed != null) {
                steps.add(edgeStep(step, edgePushed));
            } else if (vertexCap != null) {
                steps.add(vertexStep(step, vertexCap));
            } else {
                steps.add(registry.resolve(step.name(), step.args()));
            }
            stepNames.add(step.name());
        }
        // A seed is normally not read: the first hop only needs its id, and reading a hub's record for nothing
        // is the cost the fast path avoids. That is also why a count of a seed that does not exist used to
        // answer one - nothing ever looked. When the plan does nothing but pass the seed along or count it,
        // the seeds are verified instead, so a missing id is absent from the answer rather than counted.
        // A hop (or a count written as a hop) reads the seed's edges, and an algorithm step reads the graph
        // around it, so neither needs the check - they answer nothing for a vertex that is not there.
        boolean verifySeeds = startProperties == null && plannedSteps.stream()
                .allMatch(step -> isSeedPassThrough(step.name()));
        return new CompiledQuery(query.source(), properties, startProperties, verifySeeds, stepNames, steps);
    }

    /**
     * A step that hands the seed on unchanged, or counts it, without reading anything about it.
     *
     * <p>Only these leave a seed that does not exist in the answer: a hop reads its edges (none for a
     * missing vertex), a projection reads its record (absent), and an algorithm step builds the graph
     * around it (empty).
     */
    private static boolean isSeedPassThrough(String name) {
        return isMeta(name) || name.equals("count") || name.equals("limit") || name.equals("dedup");
    }

    /** What an edge hop pushes into its read: the conditions and the cap of the following steps. */
    private record EdgePushdown(List<EdgePropertyFilter> filters, int maxResults) {
    }

    /**
     * Folds the conditions and the cap that follow an edge hop into the hop itself.
     *
     * <p>{@code outE('Payment').has('status','SUCCESS')} does not change what is read - the bin
     * holds every payment - but it changes how much has to be built: the store evaluates the
     * condition while it decodes and never turns a payment that fails it into an object. The same
     * goes for a cap: {@code outE('Payment').limit(10)} only ever needs the ten newest payments of
     * a hub. A count becomes a store-side count of the matching edges, which builds none of them.
     *
     * <p>Only the steps <b>directly</b> after the hop are folded (a {@code batch} in between is not
     * disturbed), and only for an edge hop of a named class: a condition over "every edge class"
     * cannot be pushed into a single bin read and is applied to what was read instead.
     *
     * @param pushdowns receives one entry per input step ({@code null} = nothing was pushed), so
     *                    the caller can resolve the compiled steps with it
     * @return the plan without the steps that became part of a hop
     */
    private static List<GremlinQuery.Step> pushEdgeConditions(List<GremlinQuery.Step> steps,
                                                             List<EdgePushdown> pushdowns) {
        List<GremlinQuery.Step> out = new ArrayList<>(steps.size());
        for (int i = 0; i < steps.size(); i++) {
            GremlinQuery.Step step = steps.get(i);
            boolean edgeHop = isEdgeHop(step.name());
            boolean edgeCount = isEdgeCount(step.name());
            if (!edgeHop && !edgeCount) {
                add(out, pushdowns, step, null);
                continue;
            }
            List<GremlinQuery.Step> conditions = new ArrayList<>();
            int next = i + 1;
            while (next < steps.size() && isFilter(steps.get(next).name())) {
                conditions.add(steps.get(next));
                next++;
            }
            int cap = 0;
            if (next < steps.size() && steps.get(next).name().equals("limit")) {
                cap = (int) Math.min(Integer.MAX_VALUE, positiveLong(steps.get(next).args().get(0), "limit"));
                next++;
            }
            boolean nowCounted = edgeCount;
            if (!nowCounted && cap == 0 && next < steps.size() && steps.get(next).name().equals("count")) {
                nowCounted = true;
                next++;
            }
            String edgeName = optionalSingle(step.args(), step.name());
            boolean pushed = !conditions.isEmpty() || cap > 0 || (nowCounted && !edgeCount);
            if (edgeName == null || !pushed) {
                // nothing to push (or an unnamed class): the step keeps its own resolution
                add(out, pushdowns, step, null);
                continue;
            }
            List<EdgePropertyFilter> filters = GremlinSteps.edgeFilters(conditions);
            if (nowCounted && !edgeCount) {
                add(out, pushdowns, new GremlinQuery.Step(countStepName(step.name()), step.args()),
                        new EdgePushdown(filters, 0));
            } else {
                add(out, pushdowns, step, new EdgePushdown(filters, cap));
            }
            i = next - 1; // the conditions (and the cap) became part of the hop
        }
        return out;
    }

    private static void add(List<GremlinQuery.Step> steps, List<EdgePushdown> pushdowns,
                            GremlinQuery.Step step, EdgePushdown pushdown) {
        steps.add(step);
        pushdowns.add(pushdown);
    }

    /** Resolves an edge hop that carries pushed-down conditions and a cap. */
    private GremlinStep edgeStep(GremlinQuery.Step step, EdgePushdown pushdown) {
        String edgeName = optionalSingle(step.args(), step.name());
        if (isEdgeCount(step.name())) {
            return GremlinSteps.countEdges(edgeName, sideOf(step.name()), pushdown.filters());
        }
        return switch (step.name()) {
            case "outE" -> GremlinSteps.outE(edgeName, pushdown.filters(), pushdown.maxResults());
            case "inE" -> GremlinSteps.inE(edgeName, pushdown.filters(), pushdown.maxResults());
            default -> GremlinSteps.bothE(edgeName, pushdown.filters(), pushdown.maxResults());
        };
    }

    /**
     * Folds a limit directly after a vertex hop into the hop's own cap. A vertex hop that is capped
     * stops reading once it has enough neighbours, so {@code out('Payment').limit(3)} on a hub reads
     * three edges instead of the whole bin and then throwing the rest away.
     *
     * <p>Only a limit with nothing in between folds: a vertex {@code has}/{@code filter} cannot be
     * pushed into the store, so it must run before the limit and keeps it in place.
     *
     * @param caps receives one entry per input step ({@code null} = no cap), so the caller can resolve
     *             the capped hop with {@link #vertexStep}
     */
    private static List<GremlinQuery.Step> pushVertexHopLimits(List<GremlinQuery.Step> steps, List<Integer> caps) {
        List<GremlinQuery.Step> out = new ArrayList<>(steps.size());
        for (int i = 0; i < steps.size(); i++) {
            GremlinQuery.Step step = steps.get(i);
            if (isVertexHop(step.name()) && i + 1 < steps.size()
                    && steps.get(i + 1).name().equals("limit")) {
                int cap = (int) Math.min(Integer.MAX_VALUE,
                        positiveLong(steps.get(i + 1).args().get(0), "limit"));
                out.add(step);
                caps.add(cap);
                i++; // the limit became the hop's cap
            } else {
                out.add(step);
                caps.add(null);
            }
        }
        return out;
    }

    /** Resolves a vertex hop whose cap came from a folded {@code limit}. */
    private GremlinStep vertexStep(GremlinQuery.Step step, int cap) {
        String edgeName = optionalSingle(step.args(), step.name());
        return switch (step.name()) {
            case "out" -> GremlinSteps.out(edgeName, cap);
            case "in" -> GremlinSteps.in(edgeName, cap);
            default -> GremlinSteps.both(edgeName, cap);
        };
    }

    /** The {@code countOutE} name an edge hop takes when it is only counted. */
    private static String countStepName(String edgeHop) {
        return "count" + Character.toUpperCase(edgeHop.charAt(0)) + edgeHop.substring(1);
    }

    private static EdgeSide sideOf(String stepName) {
        if (stepName.equals("outE") || stepName.equals("countOutE")) {
            return EdgeSide.OUT;
        }
        if (stepName.equals("inE") || stepName.equals("countInE")) {
            return EdgeSide.IN;
        }
        return EdgeSide.BOTH;
    }

    private static boolean isEdgeHop(String name) {
        return name.equals("outE") || name.equals("inE") || name.equals("bothE");
    }

    private static boolean isVertexHop(String name) {
        return name.equals("out") || name.equals("in") || name.equals("both");
    }

    private static boolean isEdgeCount(String name) {
        return name.equals("countOutE") || name.equals("countInE") || name.equals("countBothE");
    }

    private static long positiveLong(String value, String stepName) {
        try {
            long n = Long.parseLong(value.trim());
            if (n < 1) {
                throw new QueryException(stepName + " expects a positive number, got: " + value);
            }
            return n;
        } catch (NumberFormatException e) {
            throw new QueryException(stepName + " expects a number, got: " + value);
        }
    }

    /**
     * An optional step argument: at most one token, blank means "not given".
     *
     * <p>Rejects nothing extra: a folded step that carries several class names cannot be pushed
     * into a single bin read, so it returns {@code null} (the hop keeps its own resolution).
     */
    private static String optionalSingle(List<String> args, String stepName) {
        if (args.isEmpty()) {
            return null;
        }
        if (args.size() > 1) {
            return null;
        }
        String value = args.get(0);
        return value == null || value.isBlank() ? null : value;
    }

    /** Resolves a {@code nearest} step that carries pushed-down conditions. */
    private GremlinStep nearestStep(GremlinQuery.Step step, HnswFilter filter) {
        if (step.args().isEmpty() || step.args().size() > 2) {
            throw new QueryException("nearest expects nearest(k) or nearest(k, ef), got "
                    + step.args().size() + " arguments");
        }
        int k = positiveInt(step.args().get(0), "nearest");
        int ef = step.args().size() > 1 ? positiveInt(step.args().get(1), "nearest") : 0;
        return GremlinSteps.nearest(vertexType -> resolvedHnswIndexes().get(vertexType), k, ef, filter);
    }

    private static boolean isFilter(String name) {
        return name.equals("has") || name.equals("filter");
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
                // an edge hop that is only counted can be counted by the store: no decoding, and
                // no vertex reads (executed by GremlinSteps.countEdges, which falls back to the
                // edge hop when a subgraph is being collected)
                boolean countable = result.get(i).args().size() <= 1;
                for (int j = i + 1; j < next; j++) {
                    countable = countable && !isFilter(result.get(j).name());
                }
                if (countable && (edgeHop.equals("outE") || edgeHop.equals("inE") || edgeHop.equals("bothE"))) {
                    result.set(i, new GremlinQuery.Step("count" + Character.toUpperCase(edgeHop.charAt(0))
                            + edgeHop.substring(1), result.get(i).args()));
                    result.remove(next);
                    continue;
                }
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
        return executeWithSubgraph(gremlin).subgraph();
    }

    /**
     * Executes a query and returns both the final traversers and the subgraph they
     * visited, from a single pass over the graph.
     *
     * <p>This is what a UI needs: the traversers are the query's answer (a count, a
     * list of values, ...) while the subgraph is what gets drawn.
     */
    public Result executeWithSubgraph(String gremlin) {
        Objects.requireNonNull(gremlin, "gremlin");
        CompiledQuery compiled = compiledCache.computeIfAbsent(gremlin, this::compile);
        GremlinContext context = newContext();
        context.collectSubgraph(true);
        List<Object> values = runCompiled(context, compiled);
        return new Result(values, context.buildSubgraph());
    }

    /** The answer of a query and the subgraph it visited. */
    public record Result(List<Object> values, Subgraph subgraph) {

        public Result {
            // values may contain nulls (values('missing')), unlike Subgraph's own lists
            values = Collections.unmodifiableList(new ArrayList<>(values));
            subgraph = subgraph == null ? Subgraph.EMPTY : subgraph;
        }
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
        context.scanLimit(scanLimit);
        context.parallelism(parallelism);
        return context;
    }

    private List<Object> runCompiled(GremlinContext context, CompiledQuery compiled) {
        List<Object> current = start(context, compiled.source, compiled.startProperties, compiled.verifySeeds);
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
                if (hopsDone >= 1 && (name.equals("has") || name.equals("filter"))) {
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
        final GremlinQuery.Source source;
        final List<String>[] properties;
        final List<String> startProperties;
        final boolean verifySeeds;
        final List<String> stepNames;
        final List<GremlinStep> steps;

        CompiledQuery(GremlinQuery.Source source, List<String>[] properties,
                      List<String> startProperties, boolean verifySeeds, List<String> stepNames,
                      List<GremlinStep> steps) {
            this.source = source;
            this.properties = properties;
            this.startProperties = startProperties;
            this.verifySeeds = verifySeeds;
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
            if (isHop(steps.get(i).name()) || producesVertices(steps.get(i).name())) {
                properties[i] = neededProperties(steps, i + 1);
            }
        }
        return properties;
    }

    /** Steps that read vertices of their own and hand them on (not hops over the traversers). */
    private static boolean producesVertices(String name) {
        return name.equals("fuzzyLink");
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
            if (name.equals("fuzzyLink")) {
                // the step produces the link node and replaces its input: a context that is meant to
                // come from the traverser needs everything the vertex has, otherwise nothing after
                // the step reads the vertex that was there before
                if (steps.get(j).args().size() < 2) {
                    all = true;
                }
                break;
            }
            if (name.equals("elementMap")) {
                all = true;
                break;
            }
            if (name.equals("has")) {
                List<String> args = steps.get(j).args();
                if (!args.isEmpty()) {
                    keys.add(args.get(0));
                }
            } else if (name.equals("filter")) {
                for (String arg : steps.get(j).args()) {
                    keys.addAll(GremlinFilterExpression.parse(arg).propertyKeys());
                }
            } else if (name.equals("values")) {
                keys.addAll(steps.get(j).args());
            } else if (name.equals("map")) {
                // map { ... } reads one property (or the label/id) off the element and hands on the
                // value, so nothing after it can read the element
                List<String> args = steps.get(j).args();
                if (!args.isEmpty()) {
                    keys.addAll(GremlinElementExpression.pathKeys(args.get(0)));
                }
                break;
            } else if (name.equals("count")) {
                // count() consumes the elements, so nothing after it needs properties
                break;
            }
            // limit()/dedup() pass the elements through: keep looking for consumers
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
                || name.equals("outE") || name.equals("inE") || name.equals("bothE")
                || name.equals("otherV") || name.equals("nearest") || name.equals("circle");
    }

    /**
     * Rejects a hop the schema says can never match the vertex type the traversal is standing on,
     * and names the direction that does match.
     *
     * <p>{@code g.V('+86-138-10008','Phone').in('PhoneToAccount')} reads an account's phone link the
     * wrong way round: the class runs {@code Phone -> Account}, so no edge of it points at a Phone
     * and the answer is empty for a reason the caller cannot see. The direction is fixed by the
     * schema, so the traversal knows it before it reads anything - and says so instead.
     *
     * <p>The check follows the declared vertex type along the traversal and gives up as soon as the
     * type is no longer known (an edge hop, {@code otherV()}, a vector or fuzzy step, ...), so it
     * never guesses. A store without a schema (the tests, an in-memory graph) skips it entirely.
     */
    private void checkHopDirections(GremlinQuery query) {
        if (schema == null) {
            return;
        }
        Set<String> types = startTypes(query.source());
        if (types == null || types.isEmpty()) {
            return;
        }
        for (GremlinQuery.Step step : query.steps()) {
            String name = step.name();
            if (isMeta(name) || name.equals("has") || name.equals("filter")
                    || name.equals("limit") || name.equals("dedup")) {
                continue; // the traverser goes through unchanged
            }
            boolean edgeHop = name.equals("outE") || name.equals("inE") || name.equals("bothE");
            int direction = switch (name) {
                case "out", "outE" -> 1;
                case "in", "inE" -> -1;
                case "both", "bothE" -> 2;
                default -> 0;
            };
            if (direction == 0) {
                return; // whatever comes out is not the vertex type we were tracking
            }
            if (step.args().size() != 1) {
                return; // "every class" or several classes: nothing single to check
            }
            EdgeSchema edge = schema.edgeSchema(step.args().get(0));
            if (edge == null) {
                return; // the resolver reports the unknown class
            }
            String sourceName = edge.sourceVertexName();
            String targetName = edge.targetVertexName();
            if (edge.direction() == EdgeDirection.DIRECTED
                    && sourceName != null && targetName != null) {
                if (direction == 1 && types.stream().noneMatch(sourceName::equals)) {
                    throw wrongDirection(name, edge, types, true);
                }
                if (direction == -1 && types.stream().noneMatch(targetName::equals)) {
                    throw wrongDirection(name, edge, types, false);
                }
            }
            if (edgeHop) {
                return; // the next traverser is an edge, not a vertex
            }
            Set<String> next = new LinkedHashSet<>();
            if (direction != 1 && sourceName != null) {
                next.add(sourceName); // an in-edge reaches the class's source vertex
            }
            if (direction != -1 && targetName != null) {
                next.add(targetName); // an out-edge reaches its target
            }
            types = next.isEmpty() ? null : next;
            if (types == null) {
                return;
            }
        }
    }

    /** The vertex type a traversal starts from, or {@code null} when it does not declare a known one. */
    private Set<String> startTypes(GremlinQuery.Source source) {
        if (source.kind() != GremlinQuery.Source.Kind.VERTICES
                && source.kind() != GremlinQuery.Source.Kind.VERTEX_TYPE) {
            return null;
        }
        VertexSchema vertex = source.name() == null ? null : schema.vertexSchema(source.name());
        return vertex == null ? null : Set.of(vertex.name());
    }

    /** The error a hop in the direction the schema does not have is rejected with. */
    private static QueryException wrongDirection(String stepName, EdgeSchema edge,
                                                Set<String> types, boolean triedOut) {
        String suffix = stepName.endsWith("E") ? "E" : "";
        String other = triedOut ? "in" : "out";
        return new QueryException(stepName + "('" + edge.name() + "') cannot match "
                + String.join("/", new TreeSet<>(types)) + ": " + edge.name() + " runs "
                + edge.sourceVertexName() + " -> " + edge.targetVertexName()
                + ", so use " + other + suffix + "('" + edge.name() + "') or both" + suffix
                + "('" + edge.name() + "')");
    }

    /** An optional step argument: blank means "not given". */
    private static String arg(List<String> args, int index, String stepName) {
        if (index >= args.size()) {
            return null;
        }
        String value = args.get(index);
        return value == null || value.isBlank() ? null : value;
    }

    /** Parses a positive step argument that may be a decimal (a radius in metres). */
    private static double positiveDouble(String text, String stepName) {
        try {
            double value = Double.parseDouble(text.trim());
            if (!(value > 0) || !Double.isFinite(value)) {
                throw new QueryException(stepName + " expects a positive number, got " + text);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new QueryException(stepName + " expects a number, got '" + text + "'");
        }
    }

    private static boolean isMeta(String name) {
        return name.equals("batch") || name.equals("barrier");
    }

    private static List<Object> start(GremlinContext context, GremlinQuery.Source source,
                                      List<String> properties, boolean verifySeeds) {
        return switch (source.kind()) {
            case NONE -> List.of();
            case VERTICES -> startVertices(context, source.ids(), source.name(), properties, verifySeeds);
            case VERTEX_TYPE -> startVertexScan(context, source.name(), properties);
            case EDGE_TYPE -> startEdgeScan(context, source.name(), properties);
        };
    }

    /**
     * Starts from a whole vertex type: {@code g.V('Account')}. A keyed store cannot answer this, so
     * the requirement is stated instead of surfacing as an exception from inside the store.
     */
    private static List<Object> startVertexScan(GremlinContext context, String vertexName,
                                                List<String> properties) {
        requireScan(context, "g.V('" + vertexName + "')");
        List<VertexQueryResult> vertices = context.graphQuery()
                .scanVertices(vertexName, properties == null ? List.of() : properties, context.scanLimit());
        List<Object> out = new ArrayList<>(vertices.size());
        for (VertexQueryResult vertex : vertices) {
            context.recordVertex(vertex.vertexName(), vertex.vertexId());
            out.add(new GremlinVertex(vertex.vertexName(), vertex.vertexId(), vertex.properties(),
                    vertex.createdTime(), vertex.lastUpdatedTime()));
        }
        return out;
    }

    /**
     * Starts from a whole edge class: {@code g.E('payment')}. Each edge becomes a traverser with both
     * of its endpoints: {@code otherVertexId} is the far one, exactly as after an edge hop, so
     * {@code values}/{@code otherV} read it the same way, and {@code sourceVertexId} remembers where
     * the edge was found so an algorithm over the reached graph has the whole edge.
     */
    private static List<Object> startEdgeScan(GremlinContext context, String edgeName,
                                              List<String> properties) {
        requireScan(context, "g.E('" + edgeName + "')");
        List<ScannedEdge> edges = context.graphQuery()
                .scanEdges(edgeName, properties == null ? List.of() : properties, context.scanLimit());
        List<Object> out = new ArrayList<>(edges.size());
        for (ScannedEdge scanned : edges) {
            EdgeQueryResult edge = scanned.edge();
            out.add(new GremlinEdge(edge.edgeName(), edge.edgeId(), edge.side(), edge.otherVertexId(),
                    edge.properties(), edge.createdTime(), edge.lastUpdatedTime(), scanned.source()));
        }
        return out;
    }

    /** A starting scan needs a store that walks a type; a keyed store is told so before anything runs. */
    private static void requireScan(GremlinContext context, String step) {
        if (!context.graphQuery().supportsScan()) {
            throw new QueryException(step + " starts from a whole type, which "
                    + context.graphQuery().getClass().getSimpleName()
                    + " cannot read: a keyed store answers by id. Scan a type on a store whose tables"
                    + " are ordered ranges (the RocksDB-Edge scheme is the analytical one), or name"
                    + " the ids instead.");
        }
    }

    private static List<Object> startVertices(GremlinContext context, List<Object> vertexIds,
                                              String vertexName, List<String> properties,
                                              boolean verifySeeds) {
        if (vertexIds.isEmpty()) {
            return List.of();
        }
        if (properties == null && !verifySeeds) {
            // Only the id/label are needed for the first hop — skip reading the record.
            List<Object> out = new ArrayList<>(vertexIds.size());
            for (Object vertexId : vertexIds) {
                context.recordVertex(vertexName, vertexId);
                out.add(new GremlinVertex(vertexName, vertexId, Map.of(), 0L, 0L));
            }
            return out;
        }
        // an empty projection means every property: a verified seed needs its record, not a projection
        List<String> projection = properties == null ? List.of() : properties;
        // the seed ids are read in parallel batches (one request per id for a handful of seeds)
        List<VertexQueryResult> vertices = context.readSeedVertices(vertexName, vertexIds, projection);
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
