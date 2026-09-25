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
import com.pengcheng.phoenixgraph.schema.VectorIndexSchemas;

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

    /**
     * The steps a client ends a traversal with ({@code toList()}, {@code toSet()}, {@code next()},
     * {@code iterate()}, ...). They hand the traversers on as they are - the answer is collected
     * differently, not computed differently - so property planning and the direction check look
     * through them, and a traversal that does nothing else still reads the element it returns.
     */
    private static final Set<String> TERMINAL_STEPS = Set.of(
            "toList", "toSet", "toBulkSet", "toIterator", "next", "iterate", "none", "discard");
    private final Map<String, String> logics = new LinkedHashMap<>();
    private final Map<String, HnswIndex> hnswIndexes = new LinkedHashMap<>();
    private Map<String, String> geoProperties = Map.of();

    /** The schema a named hop's direction is checked against ({@code null} = no check). */
    private GraphSchema schema;
    private FuzzyPatternRegistry fuzzyPatterns = FuzzyPatternRegistry.empty();
    private GeminiCluster geminiCluster;
    private Supplier<Map<String, HnswIndex>> hnswIndexSource;
    /** Highest number of query texts kept compiled; a full cache is dropped rather than grown. */
    public static final int MAX_COMPILED_QUERIES = 512;

    private final Map<String, CompiledQuery> compiledCache = new ConcurrentHashMap<>();
    private final Map<String, GremlinScript> scriptCache = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong scriptParses =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong scriptParseNanos =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong traversalCompiles =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong traversalCompileNanos =
            new java.util.concurrent.atomic.AtomicLong();
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
        // branch steps whose arguments are sub-traversals (TinkerPop's `__` children written as
        // out('x').has('k','v')): each is resolved with this registry and run on one element
        registry.register("union", args -> {
            List<String> subTraversals = requireSubTraversals(args, "union");
            return (ctx, in) -> {
                List<Object> out = new ArrayList<>();
                for (Object element : in) {
                    for (String sub : subTraversals) {
                        out.addAll(runSubTraversal(ctx, sub, List.of(element)));
                    }
                }
                return out;
            };
        });
        // local(...) applies its traversal to each element on its own: the flat pipeline has no global
        // barrier, so this is the union semantics of one child
        registry.register("local", args -> {
            List<String> subTraversals = requireSubTraversals(args, "local");
            return (ctx, in) -> {
                List<Object> out = new ArrayList<>();
                for (Object element : in) {
                    for (String sub : subTraversals) {
                        out.addAll(runSubTraversal(ctx, sub, List.of(element)));
                    }
                }
                return out;
            };
        });
        registry.register("coalesce", args -> {
            List<String> subTraversals = requireSubTraversals(args, "coalesce");
            return (ctx, in) -> {
                List<Object> out = new ArrayList<>();
                for (Object element : in) {
                    boolean matched = false;
                    for (String sub : subTraversals) {
                        List<Object> reached = runSubTraversal(ctx, sub, List.of(element));
                        if (!reached.isEmpty()) {
                            out.addAll(reached);
                            matched = true;
                            break;
                        }
                    }
                    if (!matched && element instanceof GremlinElement e) {
                        ctx.dropElement(e);
                    }
                }
                return out;
            };
        });
        registry.register("not", args -> {
            if (args.size() != 1) {
                throw new QueryException("not expects one child traversal, got " + args.size());
            }
            String sub = args.get(0);
            return (ctx, in) -> {
                List<Object> out = new ArrayList<>();
                for (Object element : in) {
                    if (runSubTraversal(ctx, sub, List.of(element)).isEmpty()) {
                        out.add(element);
                    } else if (element instanceof GremlinElement e) {
                        ctx.dropElement(e);
                    }
                }
                return out;
            };
        });
        registry.register("and", args -> {
            List<String> subTraversals = requireSubTraversals(args, "and");
            return (ctx, in) -> keepByChildren(ctx, in, subTraversals, true);
        });
        registry.register("or", args -> {
            List<String> subTraversals = requireSubTraversals(args, "or");
            return (ctx, in) -> keepByChildren(ctx, in, subTraversals, false);
        });
        // optional(t) is coalesce(t, identity); where(t) keeps the element when t reaches something;
        // choose(pred, a, b) picks a branch; repeat(t).times(n) runs t n times
        registry.register("optional", args -> {
            String child = singleSubTraversal(args, "optional");
            return (ctx, in) -> {
                List<Object> out = new ArrayList<>();
                for (Object element : in) {
                    List<Object> reached = runSubTraversal(ctx, child, List.of(element));
                    out.addAll(reached.isEmpty() ? List.of(element) : reached);
                }
                return out;
            };
        });
        registry.register("where", args -> {
            String child = singleSubTraversal(args, "where");
            return (ctx, in) -> {
                List<Object> out = new ArrayList<>();
                for (Object element : in) {
                    if (!runSubTraversal(ctx, child, List.of(element)).isEmpty()) {
                        out.add(element);
                    } else if (element instanceof GremlinElement e) {
                        ctx.dropElement(e);
                    }
                }
                return out;
            };
        });
        registry.register("choose", args -> {
            if (args.size() != 3) {
                throw new QueryException("choose expects choose(predicate, trueChild, falseChild), got "
                        + args.size());
            }
            return (ctx, in) -> {
                List<Object> out = new ArrayList<>();
                for (Object element : in) {
                    boolean condition = !runSubTraversal(ctx, args.get(0), List.of(element)).isEmpty();
                    out.addAll(runSubTraversal(ctx, condition ? args.get(1) : args.get(2), List.of(element)));
                }
                return out;
            };
        });
        registry.register("repeat", args -> {
            if (args.size() != 2) {
                throw new QueryException("repeat expects repeat(child).times(n)");
            }
            int times;
            try {
                times = Integer.parseInt(args.get(1).trim());
            } catch (NumberFormatException e) {
                throw new QueryException("times expects a number, got: " + args.get(1));
            }
            if (times < 0) {
                throw new QueryException("times must not be negative, got: " + times);
            }
            String child = args.get(0);
            return (ctx, in) -> {
                List<Object> current = in;
                for (int i = 0; i < times; i++) {
                    List<Object> next = new ArrayList<>();
                    for (Object element : current) {
                        next.addAll(runSubTraversal(ctx, child, List.of(element)));
                    }
                    current = next;
                }
                return current;
            };
        });
    }

    /** The one child traversal a step such as {@code optional}/{@code where} expects. */
    private static String singleSubTraversal(List<String> args, String stepName) {
        if (args.size() != 1) {
            throw new QueryException(stepName + " expects one child traversal, got " + args.size());
        }
        return args.get(0);
    }

    /** The child traversals a branch step was given, or an error naming what it expects. */
    private static List<String> requireSubTraversals(List<String> args, String stepName) {
        if (args.isEmpty()) {
            throw new QueryException(stepName + " expects at least one child traversal, e.g. "
                    + stepName + "(out('Payment'))");
        }
        return List.copyOf(args);
    }

    /** Keeps the elements for which every ({@code all}) or any child traversal reaches something. */
    private List<Object> keepByChildren(GremlinContext context, List<Object> in,
                                        List<String> subTraversals, boolean all) {
        List<Object> out = new ArrayList<>();
        for (Object element : in) {
            boolean every = true;
            boolean any = false;
            for (String sub : subTraversals) {
                boolean reached = !runSubTraversal(context, sub, List.of(element)).isEmpty();
                every &= reached;
                any |= reached;
            }
            if (all ? every : any) {
                out.add(element);
            } else if (element instanceof GremlinElement e) {
                context.dropElement(e);
            }
        }
        return out;
    }

    /**
     * Folds a {@code by(...)} modulator into the step before it: {@code order().by('age')} becomes
     * {@code order('age')}, {@code groupCount().by('status')} becomes {@code groupCount('status')},
     * {@code dedup().by('card')} and {@code path().by('name')} likewise. A {@code by} the engine cannot
     * attach to is a query error rather than a step of its own.
     */
    private static List<GremlinQuery.Step> foldModulators(List<GremlinQuery.Step> steps) {
        List<GremlinQuery.Step> out = new ArrayList<>();
        for (GremlinQuery.Step step : steps) {
            if (step.name().equals("times") && !out.isEmpty()
                    && out.get(out.size() - 1).name().equals("repeat")
                    && out.get(out.size() - 1).args().size() == 1) {
                GremlinQuery.Step repeat = out.get(out.size() - 1);
                out.set(out.size() - 1, new GremlinQuery.Step("repeat",
                        List.of(repeat.args().get(0), step.args().isEmpty() ? "1" : step.args().get(0))));
                continue;
            }
            if (!step.name().equals("by")) {
                out.add(step);
                continue;
            }
            GremlinQuery.Step folded = out.isEmpty() ? null : applyBy(out.get(out.size() - 1), step.args());
            if (folded == null) {
                throw new QueryException("by(...) must follow order(...), groupCount(...), dedup() or path()");
            }
            out.set(out.size() - 1, folded);
        }
        return out;
    }

    /** The step a {@code by(...)} turns the step before it into, or {@code null} when it cannot attach. */
    private static GremlinQuery.Step applyBy(GremlinQuery.Step previous, List<String> byArgs) {
        if (byArgs.isEmpty()) {
            return null;
        }
        String key = byArgs.get(0);
        return switch (previous.name()) {
            case "order" -> new GremlinQuery.Step("order",
                    byArgs.size() > 1 ? List.of(key, byArgs.get(1)) : List.of(key));
            case "groupCount" -> new GremlinQuery.Step("groupCount", List.of(key));
            case "dedup" -> new GremlinQuery.Step("dedup", List.of(key));
            case "path" -> new GremlinQuery.Step("path", List.of(key));
            default -> null;
        };
    }

    /**
     * Runs a child traversal (a step chain such as {@code out('Payment').has('status','SUCCESS')}) on one
     * element. The child's steps are resolved with the same registry as the outer query, so a branch step
     * can use every step the engine has. Properties are read in full: the child was not part of the outer
     * projection plan.
     */
    private List<Object> runSubTraversal(GremlinContext context, String stepChain, List<Object> in) {
        List<GremlinQuery.Step> steps = GremlinQuery.parseSteps("." + stepChain.trim());
        List<Object> current = in;
        for (GremlinQuery.Step step : steps) {
            context.readProperties(List.of());
            current = registry.resolve(step.name(), step.args()).apply(context, current);
        }
        return current;
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

    /**
     * Parses and executes a Gremlin-style traversal query (compiled once per query text, then cached).
     *
     * <p>The cache is keyed by the <b>whole</b> query text, so a caller that writes the seed id into the
     * text pays one compile per distinct text - a hundred ids are a hundred compiles, not a hundred per
     * round. Everything the parse produces is reused: the resolved steps, the property projection plan and
     * the closure expressions ({@code filter { ... }}, {@code map { ... }}), which are parsed when the step
     * is built and never per element. There is no Groovy involved (no {@code GroovyShell}, no
     * {@code GroovyClassLoader}): the expression language is this project's own parser.
     */
    public List<Object> execute(String gremlin) {
        Objects.requireNonNull(gremlin, "gremlin");
        return executeCompiled(compiled(gremlin));
    }
    /** How many query texts are compiled and held ({@link #MAX_COMPILED_QUERIES} is the bound). */
    public int compiledQueryCount() {
        return compiledCache.size();
    }

    /** How many script texts are parsed and held (the same bound as the compiled queries). */
    public int parsedScriptCount() {
        return scriptCache.size();
    }

    /**
     * A script and how this call obtained it: parsed now (with the time that took) or out of the cache.
     *
     * <p>A script is parsed once per text, like a traversal is compiled once per text. Writing the seed
     * id into the script text turns that into one parse per distinct text - the same trade the compiled
     * queries make, and the reason both caches are keyed by the whole text.
     */
    public record ScriptPlan(GremlinScript script, boolean parsed, long parseNanos) {
    }

    /** The cumulative parse/compile work of this engine; subtract two readings for one call's share. */
    public record CompileStats(long scriptParses, long scriptParseNanos, long traversalCompiles,
                               long traversalCompileNanos) {

        /** What the later reading did that the earlier one had not yet done. */
        public CompileStats minus(CompileStats before) {
            return new CompileStats(scriptParses - before.scriptParses,
                    scriptParseNanos - before.scriptParseNanos,
                    traversalCompiles - before.traversalCompiles,
                    traversalCompileNanos - before.traversalCompileNanos);
        }
    }

    /** The parse/compile work done so far, for a caller that wants the share of one call. */
    public CompileStats compileStats() {
        return new CompileStats(scriptParses.get(), scriptParseNanos.get(), traversalCompiles.get(),
                traversalCompileNanos.get());
    }

    /**
     * The parsed form of a script: parsed once per text and kept, so a UI that repeats the same script
     * pays the parse once. The text is keyed whole, as compiled queries are.
     */
    public ScriptPlan script(String text) {
        Objects.requireNonNull(text, "script");
        GremlinScript cached = scriptCache.get(text);
        if (cached != null) {
            return new ScriptPlan(cached, false, 0L);
        }
        if (scriptCache.size() >= MAX_COMPILED_QUERIES) {
            // same bound and same reason as the compiled queries: a cache that grows per text is a leak
            scriptCache.clear();
        }
        long start = System.nanoTime();
        GremlinScript parsed = GremlinScript.parse(text);
        long nanos = System.nanoTime() - start;
        scriptCache.put(text, parsed);
        scriptParses.incrementAndGet();
        scriptParseNanos.addAndGet(nanos);
        return new ScriptPlan(parsed, true, nanos);
    }

    /** Executes an already-parsed traversal query (compiled on each call). */
    public List<Object> execute(GremlinQuery query) {
        Objects.requireNonNull(query, "query");
        return executeCompiled(compile(query));
    }

    private CompiledQuery compiled(String gremlin) {
        CompiledQuery cached = compiledCache.get(gremlin);
        if (cached != null) {
            return cached;
        }
        if (compiledCache.size() >= MAX_COMPILED_QUERIES) {
            // Every distinct text adds an entry, so a client that generates a query per id (a dashboard
            // does) must not grow the cache forever. Dropping all of them costs one recompile each, which
            // is a small fraction of executing the query; keeping them costs memory for the process' life.
            compiledCache.clear();
        }
        long start = System.nanoTime();
        CompiledQuery fresh = compile(gremlin);
        traversalCompiles.incrementAndGet();
        traversalCompileNanos.addAndGet(System.nanoTime() - start);
        compiledCache.put(gremlin, fresh);
        return fresh;
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
        GremlinQuery.Source source = typedStart(query).source();
        List<GremlinQuery.Step> parsedSteps = new ArrayList<>(typedStart(query).steps());
        if (query.logic() != null && !query.logic().isBlank()) {
            parsedSteps.addAll(expandLogic(query.logic()));
        }
        parsedSteps = foldModulators(parsedSteps);
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
            if (conditions.isEmpty() || !GremlinSteps.canPushDown(conditions)) {
                // nothing to fold, or a condition that reads the element's label/id: the search
                // cannot answer it, so the steps stay where they are
                plannedSteps.add(step);
                pushedFilters.add(null);
                plannedPushdowns.add(null);
                plannedVertexCaps.add(null);
                continue;
            }
            plannedSteps.add(step);
            pushedFilters.add(GremlinSteps.hnswFilter(conditions));
            plannedPushdowns.add(null);
            plannedVertexCaps.add(null);
            i = next - 1; // the conditions became part of the nearest step
        }
        List<String>[] properties = planProperties(plannedSteps);
        List<String> startProperties = startProperties(plannedSteps);
        if (plannedSteps.stream().anyMatch(step -> step.name().equals("path"))) {
            // a path hands back the elements it walked, so the walk itself may read them in full: the
            // projection plan (which normally reads only what the next step consumes) would leave the
            // vertices of a path with no properties
            for (int i = 0; i < properties.length; i++) {
                properties[i] = List.of();
            }
            startProperties = List.of();
        }
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
        // A seed is normally not read: the first hop only needs its id. An id, however, does not carry
        // a type, and a hop that names no edge class reads every edge bin of the record - which a
        // record of another type has as well, so the traversal would walk the wrong neighbourhood
        // (g.V('1001','IP').both() used to answer with account 1001's neighbours). Those plans verify
        // the seeds first: a missing or mistyped id starts from nothing. A plan that reads the seed's
        // properties anyway does the same by dropping what is not there.
        boolean verifySeeds = startProperties == null
                && (untypedFirstHop(plannedSteps) || plannedSteps.stream()
                        .allMatch(step -> isSeedPassThrough(step.name())));
        return new CompiledQuery(source, properties, startProperties, verifySeeds, stepNames, steps,
                List.copyOf(plannedSteps));
    }

    /**
     * A step that hands the seed on unchanged, or counts it, without reading anything about it.
     *
     * <p>Only these leave a seed that does not exist in the answer: a hop reads its edges (none for a
     * missing vertex), a projection reads its record (absent), and an algorithm step builds the graph
     * around it (empty).
     */
    /**
     * Whether the plan's first step is a hop that names no edge class.
     *
     * <p>Such a hop reads every edge bin the record has, so it cannot tell a vertex of the type the
     * traversal asked for from a record of another type holding the same bins - which is why the
     * seeds are verified for these plans (see the {@code verifySeeds} decision in {@code compile}).
     */
    private static boolean untypedFirstHop(List<GremlinQuery.Step> steps) {
        for (GremlinQuery.Step step : steps) {
            if (isMeta(step.name())) {
                continue;
            }
            return isHop(step.name()) && step.args().isEmpty();
        }
        return false;
    }

    private static boolean isSeedPassThrough(String name) {
        return isMeta(name) || TERMINAL_STEPS.contains(name)
                || isCountStep(name) || name.equals("limit") || name.equals("dedup");
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
            boolean nowCounted = edgeCount;
            if (GremlinSteps.canPushDown(conditions)) {
                if (next < steps.size() && steps.get(next).name().equals("limit")) {
                    cap = (int) Math.min(Integer.MAX_VALUE, positiveLong(steps.get(next).args().get(0), "limit"));
                    next++;
                }
                if (!nowCounted && cap == 0 && next < steps.size() && isCountStep(steps.get(next).name())) {
                    nowCounted = true;
                    next++;
                }
            } else {
                // the conditions read the element's label or id, which the store's decode cannot see:
                // they stay steps, and nothing after them is folded into the read
                conditions = new ArrayList<>();
                next = i + 1;
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
            if (next < result.size() && isCountStep(result.get(next).name())) {
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
        CompiledQuery compiled = compiled(gremlin);
        GremlinContext context = newContext();
        context.collectSubgraph(true);
        List<Object> values = runCompiled(context, compiled);
        // An empty answer draws nothing: a traversal stopped by a filter touched the seed on its way
        // in, and drawing that lone vertex next to "0 items" reads as "this is the answer" when it is
        // precisely what the query excluded.
        return new Result(values, values.isEmpty() ? Subgraph.EMPTY : context.buildSubgraph());
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
        context.hiddenEdgeClasses(hiddenEdgeClasses());
        return context;
    }

    /**
     * The edge classes an unnamed hop must not read: the HNSW layer edges of the graph's vector indexes.
     *
     * <p>An index' layer edges are its own machinery, not a relationship of the data, so
     * {@code g.V(1001,'Account').out()} must not walk them and a UI must not draw them. They stay reachable
     * when a hop names one, and {@code nearest} still records the layer-0 links it searched so a similarity
     * answer can be drawn.
     */
    private Set<String> hiddenEdgeClasses() {
        if (schema == null) {
            return Set.of();
        }
        Set<String> names = new LinkedHashSet<>();
        for (VectorIndexSchemas.VectorProperty property : VectorIndexSchemas.vectorProperties(schema)) {
            for (EdgeSchema edge : schema.edgeSchemas()) {
                if (VectorIndexSchemas.levelOfLayerEdge(property.propertyName(), edge.name()) >= 0) {
                    names.add(edge.name());
                }
            }
        }
        return names;
    }

    private List<Object> runCompiled(GremlinContext context, CompiledQuery compiled) {
        if (compiled.source.distributed()) {
            // a partitioned scan: mappers in parallel, then the whole-set steps once (see runDistributed)
            return runDistributed(context, compiled).values();
        }
        if (compiled.stepNames.contains("path")) {
            return runPathCompiled(context, compiled);
        }
        List<Object> current = start(context, compiled.source, compiled.startProperties, compiled.verifySeeds);
        return applySteps(context, compiled, current, 0, compiled.steps.size());
    }

    /**
     * Applies {@code steps[from, to)} to the traversers, with the adaptive batch tuning the whole
     * pipeline uses. One loop, so a distributed run walks exactly the steps a single-process run walks.
     */
    private List<Object> applySteps(GremlinContext context, CompiledQuery compiled, List<Object> in,
                                    int from, int to) {
        List<Object> current = in;
        int hopsDone = 0;
        int hopFanOut = 0;
        boolean filterSinceHop = false;
        for (int i = from; i < to; i++) {
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

    /**
     * The steps that need the whole answer, so a distributed run does them in its reduce phase: an
     * order, a global cap, or an aggregation over every mapper's output. Everything before the first of
     * them runs inside the mappers (the attribute condition, the projections, the hops).
     */
    private static final Set<String> GLOBAL_STEPS = Set.of("order", "limit", "skip", "range", "tail",
            "sample", "dedup", "count", "sum", "mean", "max", "min", "groupCount", "fold", "cap",
            "group", "aggregate", "timeLimit", "path", "simplePath", "cyclicPath");

    /** Where the map phase ends: the first step that needs the whole set. */
    private static int mapStepCount(CompiledQuery compiled) {
        for (int i = 0; i < compiled.stepNames.size(); i++) {
            if (GLOBAL_STEPS.contains(compiled.stepNames.get(i))) {
                return i;
            }
        }
        return compiled.stepNames.size();
    }

    /** The answer of a distributed run. */
    private record DistributedRun(List<Object> values) {
    }

    /**
     * Runs a traversal whose start is a partitioned scan: {@code g.V(DIST)...} / {@code g.E(DIST)...}.
     *
     * <p>The <b>map</b> phase is one task per partition of the store, run in parallel: each scans its own
     * hash range (the attribute condition and the per-element steps run there, so the filtering happens
     * next to the data) and produces its own output. The <b>reduce</b> phase runs the remaining steps -
     * the ones that need the whole answer, such as {@code order}, {@code limit} or {@code count} - once
     * over the concatenation of the mappers' outputs, which keeps the answer identical to a single-process
     * run: that is what the tests compare.
     *
     * <p>With no global step the mappers are the whole answer and the reduce is a concatenation - the
     * shape a distributed computation wants, where nothing beyond the mapper outputs has to travel.
     */
    private DistributedRun runDistributed(GremlinContext reduceContext, CompiledQuery compiled) {
        GraphQuery store = graphQuery;
        if (!store.supportsPartitionedScan()) {
            throw new QueryException("g.V(DIST) needs a store that can scan in partitions, and "
                    + store.getClass().getSimpleName() + " cannot: it answers by key, so a whole-type"
                    + " scan stays in this process. Use a store whose tables are ordered ranges (the"
                    + " RocksDB-Edge scheme) or drop the DIST flag.");
        }
        if (compiled.stepNames.contains("path")) {
            throw new QueryException("g.V(DIST) and path() do not go together: a walk is built element by"
                    + " element inside one partition, and this engine does not keep a walk that crosses"
                    + " partitions. Drop the DIST flag or drop path().");
        }
        int partitions = Math.max(1, store.scanPartitionCount());
        int split = mapStepCount(compiled);
        boolean wantSubgraph = reduceContext.collectSubgraph();
        List<List<Object>> mapped = new ArrayList<>(Collections.nCopies(partitions, List.of()));
        List<Subgraph> subgraphs = new ArrayList<>(Collections.nCopies(partitions, Subgraph.EMPTY));
        int workers = Math.max(1, Math.min(partitions, Math.max(1, parallelism)));
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(workers,
                runnable -> {
                    Thread thread = new Thread(runnable, "phoenixgraph-map-" + System.nanoTime());
                    thread.setDaemon(true);
                    return thread;
                });
        try {
            List<java.util.concurrent.Future<List<Object>>> futures =
                    new ArrayList<>(partitions);
            for (int p = 0; p < partitions; p++) {
                final int partition = p;
                futures.add(pool.submit(() -> {
                    GremlinContext mapper = newContext();
                    mapper.collectSubgraph(wantSubgraph);
                    List<Object> elements = partitionStart(mapper, compiled.source, compiled.startProperties,
                            partition, partitions);
                    List<Object> out = split == 0 ? elements
                            : applySteps(mapper, compiled, elements, 0, split);
                    if (wantSubgraph) {
                        subgraphs.set(partition, mapper.buildSubgraph());
                    }
                    return out;
                }));
            }
            for (int p = 0; p < partitions; p++) {
                mapped.set(p, futures.get(p).get());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new QueryException("The distributed scan was interrupted");
        } catch (java.util.concurrent.ExecutionException failed) {
            Throwable cause = failed.getCause() == null ? failed : failed.getCause();
            throw cause instanceof QueryException query ? query
                    : new QueryException("A mapper of the distributed scan failed: " + cause.getMessage(),
                            cause);
        } finally {
            pool.shutdownNow();
        }

        // the mappers' subgraphs belong to the answer the caller builds from this context
        if (wantSubgraph) {
            for (Subgraph subgraph : subgraphs) {
                replay(reduceContext, subgraph);
            }
        }
        List<Object> merged = new ArrayList<>();
        for (List<Object> part : mapped) {
            merged.addAll(part);
        }
        if (split >= compiled.steps.size()) {
            return new DistributedRun(merged);
        }
        return new DistributedRun(applySteps(reduceContext, compiled, merged, split,
                compiled.steps.size()));
    }

    /** Replays a mapper's subgraph into the reducing context, so the drawn graph is their union. */
    private static void replay(GremlinContext context, Subgraph subgraph) {
        for (Subgraph.Vertex vertex : subgraph.vertices()) {
            context.recordVertex(vertex.label(), vertex.id());
        }
        for (Subgraph.Edge edge : subgraph.edges()) {
            context.recordEdge(edge.label(), edge.id(), edge.source(), edge.target(), edge.properties(),
                    edge.createdTime(), edge.lastUpdatedTime());
        }
    }

    /**
     * The elements of one partition of a scan: the vertices or edges the mapper starts from, read with
     * the projection the rest of the traversal needs.
     *
     * <p>A start that names no type ({@code g.V(DIST)} without a {@code has('Account', ...)} step) walks
     * every type of the schema, one after another - the partition is still one hash range, so the mappers
     * together cover the whole space.
     */
    private List<Object> partitionStart(GremlinContext context, GremlinQuery.Source source,
                                        List<String> properties, int partition, int partitions) {
        GraphQuery store = context.graphQuery();
        List<Object> out = new ArrayList<>();
        if (source.vertices()) {
            List<String> types = source.name() != null ? List.of(source.name())
                    : schema == null ? List.of()
                    : schema.vertexSchemas().stream().map(VertexSchema::name).toList();
            for (String vertexName : types) {
                store.scanVerticesPartition(vertexName, properties == null ? List.of() : properties,
                        partition, partitions, vertex -> {
                            context.recordVertex(vertex.vertexName(), vertex.vertexId());
                            out.add(new GremlinVertex(vertex.vertexName(), vertex.vertexId(),
                                    vertex.properties(), vertex.createdTime(), vertex.lastUpdatedTime()));
                        });
            }
            return out;
        }
        List<String> classes = source.name() != null ? List.of(source.name())
                : schema == null ? List.of()
                : schema.edgeSchemas().stream().map(EdgeSchema::name).toList();
        for (String edgeName : classes) {
            store.scanEdgesPartition(edgeName, properties == null ? List.of() : properties, partition,
                    partitions, context.scanLimit(), scanned -> {
                        EdgeQueryResult edge = scanned.edge();
                        out.add(new GremlinEdge(edge.edgeName(), edge.edgeId(), edge.side(),
                                edge.otherVertexId(), edge.properties(), edge.createdTime(),
                                edge.lastUpdatedTime(), scanned.source()));
                    });
        }
        return out;
    }

    /**
     * Executes a traversal that asks for {@code path()}: the steps before it are walked element by element,
     * remembering the edges and vertices visited, and the step itself turns each walk into a
     * {@link GremlinPath}. The steps after it run normally on the path objects.
     *
     * <p>The normal pipeline is a list transform (it knows the current elements but not where they came from),
     * so a path needs the walk itself. Only the steps a path is built from are walked this way - hops and the
     * filters that decide which walks survive ({@code has}, {@code filter}, {@code limit}, {@code dedup});
     * anything else before {@code path()} is refused with a message rather than silently losing the path.
     */
    private List<Object> runPathCompiled(GremlinContext context, CompiledQuery compiled) {
        int pathAt = compiled.stepNames.indexOf("path");
        for (int i = pathAt + 1; i < compiled.stepNames.size(); i++) {
            if (isHop(compiled.stepNames.get(i))) {
                throw new QueryException("A hop after path() is not supported; put path() at the end of the "
                        + "traversal (the steps after it only read the path objects)");
            }
        }
        List<List<Object>> paths = new ArrayList<>();
        for (Object seed : start(context, compiled.source, compiled.startProperties, compiled.verifySeeds)) {
            List<Object> one = new ArrayList<>(1);
            one.add(seed);
            paths.add(one);
        }
        for (int i = 0; i < pathAt; i++) {
            paths = walkPathStep(context, paths, compiled.plan.get(i));
        }
        List<Object> current = new ArrayList<>(paths.size());
        String pathKey = compiled.plan.get(pathAt).args().isEmpty() ? null
                : compiled.plan.get(pathAt).args().get(0);
        for (List<Object> path : paths) {
            if (pathKey == null) {
                current.add(new GremlinPath(path));
            } else {
                // path().by('name'): the walk of the values each element carries under that key
                List<Object> mapped = new ArrayList<>(path.size());
                for (Object element : path) {
                    mapped.add(element instanceof GremlinElement gremlinElement
                            ? gremlinElement.properties().get(pathKey) : null);
                }
                current.add(new GremlinPath(mapped));
            }
        }
        for (int i = pathAt + 1; i < compiled.steps.size(); i++) {
            context.readProperties(compiled.properties[i]);
            current = compiled.steps.get(i).apply(context, current);
        }
        return current;
    }

    /** One step of a path walk: a hop appends what it traversed, a filter drops the walks that do not match. */
    private List<List<Object>> walkPathStep(GremlinContext context, List<List<Object>> paths,
                                            GremlinQuery.Step step) {
        String name = step.name();
        if (name.equals("otherV")) {
            return expandOtherV(context, paths);
        }
        if (isHop(name)) {
            return expandPathHop(context, paths, step);
        }
        if (name.equals("simplePath") || name.equals("cyclicPath")) {
            boolean simple = name.equals("simplePath");
            List<List<Object>> out = new ArrayList<>();
            for (List<Object> path : paths) {
                if (repeatsAVertex(path) != simple) {
                    out.add(path);
                }
            }
            return out;
        }
        if (name.equals("has") || name.equals("filter") || name.equals("limit") || name.equals("dedup")) {
            return filterPaths(context, paths, registry.resolve(name, step.args()));
        }
        throw new QueryException("'" + name + "' before path() is not supported; a path is built from hops "
                + "(out/in/both/outE/inE/bothE/otherV) and the filters has/filter/limit/dedup");
    }

    /** Expands every walk by one hop: the edge traversed, and for a vertex hop the vertex it reached. */
    /**
     * Expands every walk by one hop, reading in <b>batches</b> rather than one walk at a time.
     *
     * <p>The first version called the edge step once per walk, which is one storage read per reached
     * vertex and made a two-hop walk O(neighbours) round trips. The whole point of the storage layer's
     * batch reads is that many vertices are read in one call, so this collects what every walk stands on,
     * reads their edges in one batched (and cached) pass per edge class, and resolves the far vertices of
     * all those edges in one more batched pass. The walks are then rebuilt in walk order, so the answer is
     * the same - only the number of round trips changes.
     */
    private List<List<Object>> expandPathHop(GremlinContext context, List<List<Object>> paths,
                                             GremlinQuery.Step step) {
        String name = step.name();
        boolean vertexHop = isVertexHop(name);
        EdgeSide side = edgeSideOf(name);
        List<String> edgeNames = step.args().isEmpty() ? null : step.args();

        // what each walk stands on for this hop: itself, or the far end of its last edge
        List<Object> standingIds = new ArrayList<>(paths.size());
        for (List<Object> path : paths) {
            standingIds.add(standingId(path.get(path.size() - 1)));
        }

        List<List<EdgeQueryResult>> edgesPerPath = readPathEdges(context, standingIds, edgeNames, side);

        // one flat list of the edges the walks reached, remembering which walk each belongs to
        List<GremlinEdge> flatEdges = new ArrayList<>();
        List<Integer> ownerPath = new ArrayList<>();
        for (int i = 0; i < paths.size(); i++) {
            for (EdgeQueryResult edge : edgesPerPath.get(i)) {
                if (edge.otherVertexId() == null) {
                    continue; // it has no far end to stand on
                }
                flatEdges.add(new GremlinEdge(edge.edgeName(), edge.edgeId(), edge.side(),
                        edge.otherVertexId(), edge.properties(), edge.createdTime(), edge.lastUpdatedTime(),
                        standingIds.get(i)));
                ownerPath.add(i);
            }
        }

        // every far vertex in one batched read (the vertex read is grouped by schema name inside)
        List<Object> farVertices = vertexHop && !flatEdges.isEmpty()
                ? GremlinSteps.otherV().apply(context, new ArrayList<>(flatEdges)) : List.of();

        List<List<Object>> out = new ArrayList<>(flatEdges.size());
        for (int i = 0; i < flatEdges.size(); i++) {
            if (vertexHop && i >= farVertices.size()) {
                continue;
            }
            List<Object> extended = new ArrayList<>(paths.get(ownerPath.get(i)));
            extended.add(flatEdges.get(i));
            if (vertexHop) {
                extended.add(farVertices.get(i));
            }
            out.add(extended);
        }
        return out;
    }

    /** The vertex a walk stands on for the next hop: itself, or the far end of its last edge. */
    private static Object standingId(Object last) {
        if (last instanceof GremlinEdge edge) {
            return edge.otherVertexId();
        }
        if (last instanceof GremlinVertex vertex) {
            return vertex.id();
        }
        return null;
    }

    /**
     * The edges of every standing vertex, read in one batched (and cached) call per edge class; the result
     * has one list per walk, in walk order.
     */
    private List<List<EdgeQueryResult>> readPathEdges(GremlinContext context, List<Object> standingIds,
                                                      List<String> edgeNames, EdgeSide side) {
        if (standingIds.isEmpty()) {
            return List.of();
        }
        if (edgeNames == null) {
            // an unnamed hop reads every class; the store call does not record the edges itself, so the
            // subgraph a UI draws gets them from here
            List<List<EdgeQueryResult>> all = context.readAllEdgeClassesBatched(standingIds, side, List.of());
            for (int i = 0; i < all.size(); i++) {
                context.recordEdges(standingIds.get(i), all.get(i));
            }
            return all;
        }
        if (edgeNames.size() == 1) {
            return context.readAllEdgesBatchCached(edgeNames.get(0), side, standingIds, List.of());
        }
        List<List<EdgeQueryResult>> merged = new ArrayList<>(standingIds.size());
        for (int i = 0; i < standingIds.size(); i++) {
            merged.add(new ArrayList<>());
        }
        for (String edgeName : edgeNames) {
            List<List<EdgeQueryResult>> part = context.readAllEdgesBatchCached(edgeName, side, standingIds,
                    List.of());
            for (int i = 0; i < part.size(); i++) {
                merged.get(i).addAll(part.get(i));
            }
        }
        return merged;
    }

    private static EdgeSide edgeSideOf(String name) {
        return switch (name) {
            case "out", "outE" -> EdgeSide.OUT;
            case "in", "inE" -> EdgeSide.IN;
            default -> EdgeSide.BOTH;
        };
    }

    /** {@code otherV()}: each walk's last element is an edge, and the vertex at its far end is appended. */
    private List<List<Object>> expandOtherV(GremlinContext context, List<List<Object>> paths) {
        List<List<Object>> out = new ArrayList<>();
        for (List<Object> path : paths) {
            Object last = path.get(path.size() - 1);
            List<Object> far = GremlinSteps.otherV().apply(context, List.of(last));
            if (far.isEmpty()) {
                continue;
            }
            List<Object> extended = new ArrayList<>(path);
            extended.add(far.get(0));
            out.add(extended);
        }
        return out;
    }

    /** The edge read a hop stands for: {@code out} reads like {@code outE}, and so on. */
    /** Whether a walk visits a vertex twice ({@code simplePath} keeps the ones that do not). */
    private static boolean repeatsAVertex(List<Object> path) {
        Set<String> seen = new LinkedHashSet<>();
        for (Object element : path) {
            if (element instanceof GremlinVertex vertex && !seen.add(String.valueOf(vertex.id()))) {
                return true;
            }
        }
        return false;
    }

    /** Applies a filter step to the walks' last elements and keeps the walks whose element survived. */
    private static List<List<Object>> filterPaths(GremlinContext context, List<List<Object>> paths,
                                                  GremlinStep step) {
        List<Object> lasts = new ArrayList<>(paths.size());
        for (List<Object> path : paths) {
            lasts.add(path.get(path.size() - 1));
        }
        List<Object> kept = step.apply(context, lasts);
        List<List<Object>> out = new ArrayList<>();
        int k = 0;
        for (List<Object> path : paths) {
            if (k < kept.size() && kept.get(k) == path.get(path.size() - 1)) {
                out.add(path);
                k++;
            }
        }
        return out;
    }

    /** A compiled query: resolved steps + precomputed property projection plan. */
    private static final class CompiledQuery {
        final GremlinQuery.Source source;
        final List<String>[] properties;
        final List<String> startProperties;
        final boolean verifySeeds;
        final List<String> stepNames;
        final List<GremlinStep> steps;
        /** The planned steps as parsed (their args), which the path walk needs to read the edge names. */
        final List<GremlinQuery.Step> plan;

        CompiledQuery(GremlinQuery.Source source, List<String>[] properties,
                      List<String> startProperties, boolean verifySeeds, List<String> stepNames,
                      List<GremlinStep> steps, List<GremlinQuery.Step> plan) {
            this.source = source;
            this.properties = properties;
            this.startProperties = startProperties;
            this.verifySeeds = verifySeeds;
            this.stepNames = stepNames;
            this.steps = steps;
            this.plan = plan;
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
            if (TERMINAL_STEPS.contains(name)) {
                continue; // the traversal ends here, but the elements are still the ones produced
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
            if (name.equals("elementMap") || name.equals("path")) {
                // both hand back the element itself (a map of it, or the walk that visited it), so every
                // property may be read
                all = true;
                break;
            }
            if (name.equals("hasNot") || name.equals("hasKey") || name.equals("hasValue")
                    || name.equals("valueMap") || name.equals("order") || name.equals("groupCount")
                    || name.equals("union") || name.equals("local") || name.equals("coalesce")
                    || name.equals("not") || name.equals("and") || name.equals("or")
                    || name.equals("optional") || name.equals("where") || name.equals("choose")
                    || name.equals("repeat")) {
                // these read the element's properties (which ones depends on the argument, or on a child
                // traversal), so the projection stays "all"
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
                // the element's own accessors (id, label, createdTime, lastUpdatedTime, an edge's
                // source/target) are not stored properties: the values() step answers them from the
                // element, so the store must not be asked to project them
                for (String key : steps.get(j).args()) {
                    if (!GremlinElementExpression.isPseudoName(key)) {
                        keys.add(key);
                    }
                }
            } else if (name.equals("map")) {
                // map { ... } reads the properties its expression names and hands on the value, so
                // nothing after it can read the element
                List<String> args = steps.get(j).args();
                if (!args.isEmpty()) {
                    keys.addAll(GremlinExpression.parse(args.get(0)).propertyKeys());
                }
                break;
            } else if (isCountStep(name)) {
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
            if (isMeta(name) || TERMINAL_STEPS.contains(name) || name.equals("has")
                    || name.equals("filter") || name.equals("limit") || name.equals("dedup")) {
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

    /** The steps that reduce the traversers to their number: {@code count()} and Groovy's {@code size()}. */
    private static boolean isCountStep(String name) {
        return name.equals("count") || name.equals("size");
    }

    private static List<Object> start(GremlinContext context, GremlinQuery.Source source,
                                      List<String> properties, boolean verifySeeds) {
        return switch (source.kind()) {
            case NONE, NO_VERTICES, NO_EDGES -> List.of();
            case VERTICES -> startVertices(context, source.ids(), source.name(), properties, verifySeeds);
            case VERTEX_TYPE -> startVertexScan(context, source.name(), properties);
            case EDGE_TYPE -> startEdgeScan(context, source.name(), properties);
            case EDGES -> startEdges(context, source.ids(), source.name(), properties);
            // a distributed start is materialised by its mappers (runDistributed), never by this switch
            case NO_VERTICES_DIST, VERTEX_TYPE_DIST, NO_EDGES_DIST, EDGE_TYPE_DIST -> throw new QueryException(
                    "A distributed start is read by its mappers, not in this process");
        };
    }

    /**
     * Starts from named edge ids: {@code g.E('pay_1', 'Payment')}. The store is asked for the edges of that
     * class with those ids (a store with an id index answers directly, one without walks the class once), and
     * each answer becomes an edge traverser carrying both of its endpoints - which is what {@code otherV()}
     * needs to know where the edge goes.
     */
    private static List<Object> startEdges(GremlinContext context, List<Object> edgeIds, String edgeName,
                                           List<String> properties) {
        if (edgeIds.isEmpty()) {
            return List.of();
        }
        List<ScannedEdge> edges = context.graphQuery().readEdgesByIds(edgeName,
                edgeIds, properties == null ? List.of() : properties);
        List<Object> out = new ArrayList<>(edges.size());
        for (ScannedEdge scanned : edges) {
            EdgeQueryResult edge = scanned.edge();
            context.recordVertex(null, scanned.source());
            context.recordVertex(null, scanned.target());
            out.add(new GremlinEdge(edge.edgeName(), edge.edgeId(), edge.side(), edge.otherVertexId(),
                    edge.properties(), edge.createdTime(), edge.lastUpdatedTime(), scanned.source()));
        }
        return out;
    }

    /**
     * A traversal whose start names no type, resolved against the first step that does.
     *
     * <p>{@code g.V()} and {@code g.E()} start from nothing - a step can produce its own elements (as
     * {@code fuzzyLink} does) - so when the first step is the TinkerPop form that names a type
     * ({@code has('Account', 'accountStatus', 'FROZEN')}) or only a type, that name is what the traversal
     * walks and the step keeps just its condition:
     *
     * <pre>
     * g.V().has('Account', 'accountStatus', 'FROZEN')   ==   g.V('Account').has('accountStatus', 'FROZEN')
     * g.E().has('Payment', 'status', 'SUCCESS')      ==   g.E('Payment').has('status', 'SUCCESS')
     * </pre>
     *
     * <p>A filter with no type to filter ({@code g.V().has('k', 'v')}) is refused with both spellings that
     * do name one: an empty start answers nothing, so saying what is missing beats an answer of zero rows.
     */
    private TypedStart typedStart(GremlinQuery query) {
        GremlinQuery.Source source = query.source();
        List<GremlinQuery.Step> steps = query.steps();
        boolean vertices = source.kind() == GremlinQuery.Source.Kind.NO_VERTICES
                || source.kind() == GremlinQuery.Source.Kind.NO_VERTICES_DIST;
        boolean edges = source.kind() == GremlinQuery.Source.Kind.NO_EDGES
                || source.kind() == GremlinQuery.Source.Kind.NO_EDGES_DIST;
        if (!vertices && !edges || steps.isEmpty()) {
            return new TypedStart(source, steps);
        }
        GremlinQuery.Step first = steps.get(0);
        String kind = vertices ? "g.V()" : "g.E()";
        if (!first.name().equals("has") || first.args().size() < 3) {
            if (first.name().equals("has")) {
                throw new QueryException(kind + " starts nothing: name the type it walks, either"
                        + " " + kind + ".has('Account', 'key', 'value') or " + (vertices
                        ? "g.V('Account')" : "g.E('Payment')") + ".has('key', 'value')");
            }
            return new TypedStart(source, steps);
        }
        String type = first.args().get(0);
        if (vertices && schema != null && schema.vertexSchema(type) == null) {
            throw new QueryException(kind + ".has('" + type + "', ...) names no vertex type of this graph: "
                    + (schema == null ? "the graph has no schema"
                            : String.join(", ", schema.vertexSchemas().stream()
                                    .map(VertexSchema::name).toList())));
        }
        if (edges && schema != null && schema.edgeSchema(type) == null) {
            throw new QueryException(kind + ".has('" + type + "', ...) names no edge class of this graph: "
                    + (schema == null ? "the graph has no schema"
                            : String.join(", ", schema.edgeSchemas().stream()
                                    .map(EdgeSchema::name).toList())));
        }
        List<GremlinQuery.Step> rest = new ArrayList<>(steps.size());
        // the type became the start; what is left of the step is the property condition
        rest.add(new GremlinQuery.Step("has",
                List.of(first.args().get(1), first.args().get(2))));
        rest.addAll(steps.subList(1, steps.size()));
        boolean distributed = source.distributed();
        if (vertices) {
            return new TypedStart(distributed ? GremlinQuery.Source.vertexTypeDistributed(type)
                    : GremlinQuery.Source.vertexType(type), List.copyOf(rest));
        }
        return new TypedStart(distributed ? GremlinQuery.Source.edgeTypeDistributed(type)
                : GremlinQuery.Source.edgeType(type), List.copyOf(rest));
    }

    /** A start and its steps after a type named by the first step became the start. */
    private record TypedStart(GremlinQuery.Source source, List<GremlinQuery.Step> steps) {
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
