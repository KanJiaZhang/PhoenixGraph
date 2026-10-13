package com.pengcheng.phoenixgraph.query.gremlin;

import com.pengcheng.phoenixgraph.fuzzy.FuzzyPatternRegistry;
import com.pengcheng.phoenixgraph.gemini.GeminiAlgorithms;
import com.pengcheng.phoenixgraph.gemini.GeminiCluster;
import com.pengcheng.phoenixgraph.hnsw.HnswFilter;
import com.pengcheng.phoenixgraph.hnsw.HnswIndex;
import com.pengcheng.phoenixgraph.query.QueryDeadline;
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
import com.pengcheng.phoenixgraph.schema.VertexPropertySchema;
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
    private boolean fullTextIndexEnabled;
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
                GeminiAlgorithms.CONNECTED_COMPONENTS, GeminiAlgorithms.SSSP, GeminiAlgorithms.BFS,
                GeminiAlgorithms.DEGREE_CENTRALITY, GeminiAlgorithms.CLOSENESS_CENTRALITY,
                GeminiAlgorithms.BETWEENNESS_CENTRALITY, GeminiAlgorithms.EIGENVECTOR_CENTRALITY}) {
            registry.register(algorithm, args -> {
                if (GremlinDistAlgorithms.wantsDistributed(args)) {
                    return GremlinDistAlgorithms.dist(geminiCluster, algorithm, args);
                }
                // no marker: the run decides. A distributed start (g.V(DIST)...) asks for the cluster, so
                // "where does this query run" is answered once, at the start, and an algorithm in the
                // middle of it runs there too; a query that runs in this process uses the in-memory step.
                GremlinStep local = inMemoryAlgorithm(algorithm, args);
                return (ctx, in) -> ctx.distributedAlgorithms()
                        ? GremlinDistAlgorithms.dist(geminiCluster, algorithm, args).apply(ctx, in)
                        : local.apply(ctx, in);
            });
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
                throw new QueryException("circle expects circle('{\"latitude\":..,\"longitude\":..}',"
                        + " radiusMetres), got " + args.size() + " arguments");
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
        // the full-text step reads the candidate set of a TextP predicate from the inverted index;
        // the compiler writes it for g.V('Type').has(fullTextField, TextP...) when the index is on
        registry.register("fullText", args -> {
            if (args.size() != 3) {
                throw new QueryException("fullText expects fullText(vertexType, property, term), got "
                        + args.size() + " arguments");
            }
            return GremlinSteps.fullText(args.get(0), args.get(1), args.get(2));
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
        // flatMap(child): run the child on each element and concatenate the answers (TinkerPop's flatMap,
        // the flattened form of map). A closure/expression body is evaluated per element and its list
        // result is flattened, so flatMap{ ... } answers a list the way flatMap(traversal) does.
        registry.register("flatMap", args -> {
            if (args.size() != 1) {
                throw new QueryException("flatMap expects one child traversal or expression, got "
                        + args.size());
            }
            String body = args.get(0).trim();
            if (looksLikeTraversal(body)) {
                return (ctx, in) -> {
                    List<Object> out = new ArrayList<>();
                    for (Object element : in) {
                        out.addAll(runSubTraversal(ctx, body, List.of(element)));
                    }
                    return out;
                };
            }
            GremlinExpression expression = GremlinExpression.parse(body);
            return (ctx, in) -> {
                List<Object> out = new ArrayList<>();
                for (Object element : in) {
                    flattenInto(out, expression.evaluate(GremlinSteps.requireElement(element)));
                }
                return out;
            };
        });
        // group().by(key).by(value): group the traversers by a key and answer one map. The value spec is
        // run over each group as a whole; a step that reduces (count/sum/mean/min/max/fold) answers one
        // value, anything else (a property key, a child traversal) answers the list it produced.
        registry.register("group", args -> {
            List<String> specs = bySpecsOf(args);
            return (ctx, in) -> groupStep(ctx, specs, in);
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
            List<String> bodies = new ArrayList<>();
            for (String arg : args) {
                if (!arg.startsWith("by:")) {
                    bodies.add(arg);
                }
            }
            List<String> bySpecs = bySpecsOf(args);
            if (bodies.size() == 2 && bodies.get(0).matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
                // where(label, P): a comparison over the labels, which only the stateful path can read
                // (the compiler puts a query with this form on that path, so this step never runs here)
                return (ctx, in) -> {
                    throw new QueryException("where('" + bodies.get(0) + "', ...) needs the stateful path");
                };
            }
            if (bodies.size() != 1) {
                throw new QueryException("where expects where(child), where(predicate) or"
                        + " where(label, predicate), got " + bodies.size() + " arguments");
            }
            String body = childTraversal(bodies.get(0));
            String spec = bySpecs.isEmpty() ? null : bySpecs.get(0);
            if (GremlinPredicate.isPredicate(body)) {
                // where(P).by(spec): the by-mapped value is tested against the predicate
                GremlinPredicate predicate = GremlinPredicate.parse(body);
                return (ctx, in) -> {
                    List<Object> out = new ArrayList<>();
                    for (Object element : in) {
                        Object tested = byValue(ctx, element, spec);
                        if (tested != null && predicate.test(tested)) {
                            out.add(element);
                        } else if (element instanceof GremlinElement e) {
                            ctx.dropElement(e);
                        }
                    }
                    return out;
                };
            }
            // where(child).by(spec): the by-mapped value is what the child traversal runs on
            return (ctx, in) -> {
                List<Object> out = new ArrayList<>();
                for (Object element : in) {
                    Object mapped = byValue(ctx, element, spec);
                    if (mapped != null && !runSubTraversal(ctx, body, List.of(mapped)).isEmpty()) {
                        out.add(element);
                    } else if (element instanceof GremlinElement e) {
                        ctx.dropElement(e);
                    }
                }
                return out;
            };
        });
        registry.register("choose", args -> {
            // choose(pred, trueChild, falseChild): the predicate decides by answering something or not
            if (args.size() == 3) {
                return (ctx, in) -> {
                    List<Object> out = new ArrayList<>();
                    for (Object element : in) {
                        boolean condition = !runSubTraversal(ctx, args.get(0), List.of(element)).isEmpty();
                        out.addAll(runSubTraversal(ctx, condition ? args.get(1) : args.get(2),
                                List.of(element)));
                    }
                    return out;
                };
            }
            // choose(pred).option(value, child)...: the predicate's answer is compared with each option's
            // value; the first match runs, otherwise the option(child) with no value (the default), and a
            // traverser no option matches is dropped
            if (args.isEmpty()) {
                throw new QueryException("choose expects a predicate, e.g. choose(values('status'))");
            }
            String predicate = args.get(0);
            List<Object[]> choices = new ArrayList<>(); // {value or null, child}
            String fallback = null;
            for (int i = 1; i < args.size(); i++) {
                String arg = args.get(i);
                if (arg.startsWith("optionValue:")) {
                    String value = arg.substring("optionValue:".length());
                    if (i + 1 >= args.size() || !args.get(i + 1).startsWith("optionChild:")) {
                        throw new QueryException("option(value, child) lost its child traversal");
                    }
                    choices.add(new Object[] {value, args.get(++i).substring("optionChild:".length())});
                } else if (arg.startsWith("optionDefault:")) {
                    fallback = arg.substring("optionDefault:".length());
                } else {
                    throw new QueryException("choose has an unexpected argument: " + arg);
                }
            }
            if (choices.isEmpty() && fallback == null) {
                throw new QueryException("choose(predicate) needs at least one option(...) or a"
                        + " choose(predicate, trueChild, falseChild) form");
            }
            String defaultChild = fallback;
            return (ctx, in) -> {
                List<Object> out = new ArrayList<>();
                for (Object element : in) {
                    List<Object> answered = runSubTraversal(ctx, predicate, List.of(element));
                    Object key = answered.isEmpty() ? null : answered.get(0);
                    String child = defaultChild;
                    for (Object[] choice : choices) {
                        if (sameElement(choice[0], key) || String.valueOf(choice[0]).equalsIgnoreCase(
                                key == null ? null : String.valueOf(key))) {
                            child = (String) choice[1];
                            break;
                        }
                    }
                    if (child != null) {
                        out.addAll(runSubTraversal(ctx, child, List.of(element)));
                    }
                }
                return out;
            };
        });
        registry.register("repeat", args -> {
            RepeatPlan plan = parseRepeat(args);
            if (plan.until() != null || plan.emit() != null) {
                // a loop with a condition runs on the stateful path, which is where the compiler puts a
                // query that has one (needsTraverserState); this step is built but never applied there
                return (ctx, in) -> {
                    throw new QueryException("repeat with until()/emit() needs the stateful path");
                };
            }
            int times = plan.times();
            String child = plan.child();
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
        return childTraversal(args.get(0));
    }

    /** The child traversals a branch step was given, or an error naming what it expects. */
    private static List<String> requireSubTraversals(List<String> args, String stepName) {
        if (args.isEmpty()) {
            throw new QueryException(stepName + " expects at least one child traversal, e.g. "
                    + stepName + "(out('Payment'))");
        }
        List<String> children = new java.util.ArrayList<>(args.size());
        for (String argument : args) {
            children.add(childTraversal(argument));
        }
        return List.copyOf(children);
    }

    /**
     * A child traversal, with TinkerPop's anonymous marker accepted: {@code union(__.out('Payment'))} and
     * {@code union(out('Payment'))} mean the same thing here. The marker is only a marker - this engine
     * writes a child as the steps themselves - so accepting it costs one strip and removes a difference
     * that has nothing to do with the question being asked.
     */
    private static String childTraversal(String argument) {
        String text = argument == null ? "" : argument.trim();
        while (text.startsWith("__")) {
            text = text.substring(2).trim();
            if (text.startsWith(".")) {
                text = text.substring(1).trim();
            }
        }
        return text;
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
            // times(n), until(c) and emit(c) are modulators on the repeat in front of them: they are
            // folded into its arguments (times as the bare number, the two conditions marked) so the
            // loop executor reads the whole loop from one step
            if (isRepeatModulator(step.name()) && !out.isEmpty()
                    && out.get(out.size() - 1).name().equals("repeat")) {
                out.set(out.size() - 1, attachToRepeat(out.get(out.size() - 1), step));
                continue;
            }
            if (step.name().equals("until") || step.name().equals("emit")) {
                throw new QueryException(step.name() + "(...) must follow repeat(...): write"
                        + " repeat(child)." + step.name() + "(...). The"
                        + " " + step.name() + "(...).repeat(...) form is not supported yet.");
            }
            // from(x)/to(x) are modulators on path(): they keep the part of the walk between the two
            // labels, which only the stateful path can resolve (a label lives on the traverser)
            if ((step.name().equals("from") || step.name().equals("to"))
                    && !out.isEmpty() && out.get(out.size() - 1).name().equals("path")) {
                out.set(out.size() - 1, attachRangeToPath(out.get(out.size() - 1), step));
                continue;
            }
            if (step.name().equals("from") || step.name().equals("to")) {
                throw new QueryException(step.name() + "(...) must follow path(), e.g."
                        + " path().from('a').to('b')");
            }
            // option(value, child) / option(child) are modulators on choose(...)
            if (step.name().equals("option")) {
                if (out.isEmpty() || !out.get(out.size() - 1).name().equals("choose")) {
                    throw new QueryException("option(...) must follow choose(...), e.g."
                            + " choose(values('status')).option('ACTIVE', out('x'))");
                }
                out.set(out.size() - 1, attachOption(out.get(out.size() - 1), step));
                continue;
            }
            if (!step.name().equals("by")) {
                out.add(step);
                continue;
            }
            GremlinQuery.Step folded = out.isEmpty() ? null : applyBy(out.get(out.size() - 1), step.args());
            if (folded == null) {
                throw new QueryException("by(...) must follow order(...), groupCount(...), dedup(), path(),"
                        + " group(...), where(...), math(...), project(...), select(...) or sack(...)");
            }
            out.set(out.size() - 1, folded);
        }
        return out;
    }

    /**
     * Attaches a {@code choose(...).option(...)}: an {@code option(value, child)} is paired as
     * {@code optionValue:} + {@code optionChild:}, a bare {@code option(child)} is the default
     * ({@code optionDefault:}). The pairs are read back in order by the {@code choose} step.
     */
    private static GremlinQuery.Step attachOption(GremlinQuery.Step choose, GremlinQuery.Step option) {
        List<String> args = new ArrayList<>(choose.args());
        if (option.args().size() == 1) {
            args.add("optionDefault:" + childTraversal(option.args().get(0)));
        } else if (option.args().size() == 2) {
            args.add("optionValue:" + option.args().get(0));
            args.add("optionChild:" + childTraversal(option.args().get(1)));
        } else {
            throw new QueryException("option expects option(child) or option(value, child), got "
                    + option.args().size() + " arguments");
        }
        return new GremlinQuery.Step("choose", List.copyOf(args));
    }

    /** The modulators a {@code repeat} absorbs: the loop bound and its two conditions. */
    private static boolean isRepeatModulator(String name) {
        return name.equals("times") || name.equals("until") || name.equals("emit");
    }

    /**
     * Attaches one {@code repeat} modulator to the step that carries the loop. {@code times(n)} becomes
     * the bare number right after the child; {@code until(c)} / {@code emit(c)} are appended marked, so
     * the loop executor can tell them from the bound and from each other. A modulator that was written
     * more than once replaces the earlier one, as TinkerPop does.
     */
    private static GremlinQuery.Step attachToRepeat(GremlinQuery.Step repeat, GremlinQuery.Step modulator) {
        if (modulator.args().size() > 1) {
            throw new QueryException(modulator.name() + " expects at most one child traversal, got "
                    + modulator.args().size());
        }
        String text = modulator.args().isEmpty() ? "" : childTraversal(modulator.args().get(0));
        List<String> args = new ArrayList<>(repeat.args());
        if (modulator.name().equals("times")) {
            String bound = text.isBlank() ? "1" : text;
            if (args.size() >= 2 && !args.get(1).startsWith(UNTIL_MARKER)
                    && !args.get(1).startsWith(EMIT_MARKER)) {
                args.set(1, bound);
            } else {
                args.add(1, bound); // the bound sits before the conditions, however they were ordered
            }
            return new GremlinQuery.Step("repeat", List.copyOf(args));
        }
        String marker = modulator.name().equals("until") ? UNTIL_MARKER : EMIT_MARKER;
        args.removeIf(arg -> arg.startsWith(marker));
        args.add(marker + text);
        return new GremlinQuery.Step("repeat", List.copyOf(args));
    }

    /** The marker that introduces a repeat's {@code until} condition in the folded arguments. */
    private static final String UNTIL_MARKER = "until:";
    /** The marker that introduces a repeat's {@code emit} condition in the folded arguments. */
    private static final String EMIT_MARKER = "emit:";

    /**
     * What a folded {@code repeat} step says: the body, an optional {@code times} bound, and the
     * {@code until} / {@code emit} conditions (an empty {@code emit} text means "emit every iteration").
     */
    private record RepeatPlan(String child, Integer times, String until, String emit) {
    }

    /** Reads the folded arguments of a {@code repeat} step, whichever modulators it carries. */
    private static RepeatPlan parseRepeat(List<String> args) {
        if (args.isEmpty() || args.get(0).isBlank()) {
            throw new QueryException("repeat expects a child traversal, e.g. repeat(out('knows')).times(2)");
        }
        Integer times = null;
        String until = null;
        String emit = null;
        for (int i = 1; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg.startsWith(UNTIL_MARKER)) {
                until = arg.substring(UNTIL_MARKER.length());
            } else if (arg.startsWith(EMIT_MARKER)) {
                emit = arg.substring(EMIT_MARKER.length());
            } else {
                try {
                    times = Integer.parseInt(arg.trim());
                } catch (NumberFormatException e) {
                    throw new QueryException("times expects a number, got: " + arg);
                }
                if (times < 0) {
                    throw new QueryException("times must not be negative, got: " + times);
                }
            }
        }
        if (times == null && until == null) {
            throw new QueryException("repeat needs a bound: write repeat(child).times(n) or"
                    + " repeat(child).until(...). (An unbounded repeat would run until the graph runs out,"
                    + " which a cyclic graph never does.)");
        }
        if (until != null && until.isBlank()) {
            throw new QueryException("until(...) needs a condition, e.g. until(has('name','x')) or"
                    + " until(loops().is(3))");
        }
        return new RepeatPlan(args.get(0), times, until, emit);
    }

    /**
     * Attaches a {@code path().from(x)} / {@code path().to(x)} modulator: the label is marked in the
     * path step's arguments, so the stateful path can slice the walk between the two labels.
     */
    private static GremlinQuery.Step attachRangeToPath(GremlinQuery.Step path, GremlinQuery.Step modulator) {
        if (modulator.args().size() != 1 || modulator.args().get(0).isBlank()) {
            throw new QueryException(modulator.name() + "(...) expects one label, e.g. path()."
                    + modulator.name() + "('a')");
        }
        String marker = (modulator.name().equals("from") ? FROM_MARKER : TO_MARKER)
                + modulator.args().get(0).trim();
        List<String> args = new ArrayList<>(path.args());
        String prefix = marker.substring(0, marker.indexOf(':') + 1);
        args.removeIf(arg -> arg.startsWith(prefix));
        args.add(marker);
        return new GremlinQuery.Step("path", List.copyOf(args));
    }

    /** The marker that introduces a path's {@code from} label in the folded arguments. */
    static final String FROM_MARKER = "from:";
    /** The marker that introduces a path's {@code to} label in the folded arguments. */
    static final String TO_MARKER = "to:";

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
            case "path" -> {
                // path().by('name') keeps the by key; a from/to marker already on the step survives
                List<String> args = new ArrayList<>(previous.args());
                args.add(0, key);
                yield new GremlinQuery.Step("path", List.copyOf(args));
            }
            // project/select keep their keys and collect the by(...) specs after them, marked so the
            // step can tell a key from a modulator
            case "project", "select", "sack", "group", "where" -> {
                List<String> args = new ArrayList<>(previous.args());
                args.add("by:" + key);
                yield new GremlinQuery.Step(previous.name(), List.copyOf(args));
            }
            // math('a + b').by('age') collects the modulator after the expression: the stateful path
            // reads each variable's value (its label, or the traverser for _) and applies the spec
            case "math" -> {
                if (byArgs.size() > 1) {
                    throw new QueryException("math().by(...) expects one modulator, got " + byArgs.size());
                }
                List<String> args = new ArrayList<>(previous.args());
                args.add("by:" + key);
                yield new GremlinQuery.Step("math", List.copyOf(args));
            }
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
     * Turns the full-text index rewrite on or off.
     *
     * <p>When {@code true} and the store supports {@link GraphQuery#supportsFullTextSearch()},
     * a {@code g.V('Type').has(fullTextField, TextP.containing(term))} start is rewritten to read
     * the candidate set from the inverted index instead of scanning the type. When {@code false}
     * (the default) the predicate scans every element, exactly as it always did - which is what
     * the {@code indexed vs non-indexed} comparison measures.
     */
    public GremlinGraphQuery fullTextIndex(boolean enabled) {
        this.fullTextIndexEnabled = enabled;
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
     * <p>Shortest paths have no in-memory implementation (they are small enough to run over the
     * traversers in one process only when the graph is; the step says so instead of answering with
     * something else), and every other algorithm answers from this process.
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
            case GeminiAlgorithms.DEGREE_CENTRALITY -> GremlinAlgorithms.degree();
            case GeminiAlgorithms.CLOSENESS_CENTRALITY -> GremlinAlgorithms.closeness();
            case GeminiAlgorithms.BETWEENNESS_CENTRALITY -> GremlinAlgorithms.betweenness();
            case GeminiAlgorithms.EIGENVECTOR_CENTRALITY -> GremlinAlgorithms.eigenvector();
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
        // a start of g.V('Type').has(fullTextField, TextP.containing(term)) reads the candidate set
        // from the inverted index (when the index is on and the store supports it): the empty start
        // is replaced by a fullText step and the has stays as the exact verifier
        if (fullTextIndexEnabled && graphQuery.supportsFullTextSearch() && schema != null) {
            FullTextStart fullText = rewriteFullTextStart(source, parsedSteps);
            source = fullText.source();
            parsedSteps = fullText.steps();
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
        if (plannedSteps.stream().anyMatch(step -> step.name().equals("loops"))
                && plannedSteps.stream().noneMatch(step -> step.name().equals("repeat"))) {
            // loops() outside a repeat would silently answer 0, which looks like data; TinkerPop refuses it
            throw new QueryException("loops() answers how many times a traverser has been through a"
                    + " repeat body, so it needs a repeat(...) in the query");
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
    /**
     * Rewrites a {@code g.V('Type').has(fullTextField, TextP.containing(term))} start to read
     * the candidate set from the inverted index: the start becomes empty and a {@code fullText}
     * step in front produces the candidates, while the original {@code has} stays to verify each
     * candidate against the stored text (character intersection alone does not guarantee that
     * the characters are contiguous). Any other start is returned unchanged.
     */
    private FullTextStart rewriteFullTextStart(GremlinQuery.Source source, List<GremlinQuery.Step> steps) {
        if (source.kind() != GremlinQuery.Source.Kind.VERTEX_TYPE || steps.isEmpty()) {
            return new FullTextStart(source, steps);
        }
        GremlinQuery.Step first = steps.get(0);
        if (!first.name().equals("has") || first.args().size() != 2) {
            return new FullTextStart(source, steps);
        }
        String key = first.args().get(0);
        GremlinPredicate predicate = GremlinPredicate.parse(first.args().get(1));
        String term = predicate == null ? null : predicate.containingTerm();
        if (term == null || term.isEmpty()) {
            return new FullTextStart(source, steps);
        }
        VertexSchema vertexSchema = schema.vertexSchema(source.name());
        if (vertexSchema == null) {
            return new FullTextStart(source, steps);
        }
        VertexPropertySchema property = vertexSchema.property(key);
        if (property == null || !property.isFullText()) {
            return new FullTextStart(source, steps);
        }
        List<GremlinQuery.Step> out = new ArrayList<>(steps.size() + 1);
        out.add(new GremlinQuery.Step("fullText", List.of(source.name(), key, term)));
        out.addAll(steps);
        return new FullTextStart(GremlinQuery.Source.none(), out);
    }

    /** A start and its steps after the full-text rewrite. */
    private record FullTextStart(GremlinQuery.Source source, List<GremlinQuery.Step> steps) {
    }

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
        // profile() and explain() are terminal steps: they answer with what the run did (or would do)
        // instead of the traversers, so nothing else in the pipeline has to know about them
        if (compiled.stepNames.contains("explain")) {
            return List.of(explain(compiled));
        }
        if (compiled.stepNames.contains("profile")) {
            return profile(context, compiled);
        }
        if (compiled.stepNames.contains("path") && !compiled.traverserMode) {
            return runPathCompiled(context, compiled);
        }
        // a query that binds labels and asks for path() runs on the stateful path, where every traverser
        // carries its own walk - element for element the walk runPathCompiled builds for the same chain
        if (compiled.traverserMode) {
            return runTraverserCompiled(context, compiled);
        }
        List<Object> current = start(context, compiled.source, compiled.startProperties, compiled.verifySeeds);
        return applySteps(context, compiled, current, 0, compiled.steps.size(), null);
    }

    /**
     * The stateful path (TRAVERSER-DESIGN.md): the values flow as {@link GremlinTraverser}s, so a step can
     * read what a traverser was labelled with. Every step that does not need state keeps its own
     * implementation - it is applied to the traversers' values through {@link #adapt} - which is what
     * keeps the two paths answering the same thing.
     */
    private List<Object> runTraverserCompiled(GremlinContext context, CompiledQuery compiled) {
        List<Object> started = start(context, compiled.source, compiled.startProperties, compiled.verifySeeds);
        List<GremlinTraverser> traversers = new ArrayList<>(started.size());
        boolean walk = compiled.pathAware;
        for (Object value : started) {
            // a query that asks for path() pays for the walk; one that does not carries a null walk
            traversers.add(walk ? GremlinTraverser.of(value).withPathElement(value)
                    : GremlinTraverser.of(value));
        }
        for (int i = 0; i < compiled.steps.size(); i++) {
            String name = compiled.stepNames.get(i);
            GremlinQuery.Step planned = compiled.plan.get(i);
            traversers = switch (name) {
                case "as" -> GremlinTraverserSteps.as(traversers, planned.args());
                case "select" -> GremlinTraverserSteps.select(traversers, keysOf(planned.args()),
                        bySpecsOf(planned.args()),
                        (value, child) -> runSubTraversal(context, child, List.of(value)));
                case "project" -> GremlinTraverserSteps.project(traversers, keysOf(planned.args()),
                        bySpecsOf(planned.args()),
                        (value, child) -> runSubTraversal(context, child, List.of(value)));
                case "where" -> GremlinTraverserSteps.whereLabel(traversers, planned.args(),
                        (value, child) -> runSubTraversal(context, child, List.of(value)));
                case "path" -> pathStep(traversers, planned.args());
                case "simplePath" -> walkFilter(traversers, true);
                case "cyclicPath" -> walkFilter(traversers, false);
                case "repeat" -> repeatStep(context, planned.args(), traversers);
                case "loops" -> loopsStep(traversers);
                case "subgraph" -> subgraphStep(context, planned.args(), traversers);
                case "tree" -> treeStep(context, planned.args(), traversers);
                case "match" -> matchStep(context, planned.args(), traversers);
                case "math" -> mathStep(context, planned.args(), traversers);
                case "branch" -> branchStep(context, planned.args(), traversers);
                // union is an ordinary step on the list path; with labels it needs the traversers, so the
                // stateful path handles it here and the list path keeps running exactly what it ran before
                case "union" -> unionStep(context, planned.args(), traversers);
                case "withSack" -> withSack(traversers, planned.args());
                case "sack" -> sack(traversers, planned.args());
                case "sideEffect" -> {
                    // the child runs for its effect; its answer is not the traversal's, the traverser passes on
                    String child = planned.args().get(0);
                    for (GremlinTraverser traverser : traversers) {
                        runSubTraversal(context, child, List.of(traverser.value()));
                    }
                    yield traversers;
                }
                // a hop runs with the batched read (GremlinSteps.hopPerInput), so a hop after a label keeps
                // the labels and still costs one storage call per class
                default -> isHop(name) ? traverserHop(context, compiled, i, traversers)
                        : adapt(context, compiled, i, traversers);
            };
        }
        List<Object> answer = new ArrayList<>(traversers.size());
        for (GremlinTraverser traverser : traversers) {
            answer.add(traverser.value());
        }
        return answer;
    }

    /**
     * {@code repeat(child)} on the stateful path: the same loop the list path runs, but on traversers, so a
     * label bound before the loop stays readable after it. The folded arguments carry the bound
     * ({@code times(n)}) and the conditions ({@code until(c)} / {@code emit(c)}); a repeat that has neither a
     * bound nor a condition is refused, because an unbounded loop over a cyclic graph never ends.
     *
     * <p>The semantics match TinkerPop's post-repeat forms: {@code repeat(child).until(c)} runs the body
     * and then tests {@code c}, so it is a do/while and the traversers that satisfy {@code c} are the
     * answer; {@code repeat(child).emit(c)} runs the body and hands on every reached traverser that
     * satisfies {@code c} (all of them when {@code c} is empty) while the loop keeps going; with both, a
     * traverser that satisfies {@code emit} is emitted and a traverser that satisfies {@code until} leaves
     * the loop, and one that satisfies both is emitted once. {@code child} dying early ends the loop, and
     * {@code times(n)} bounds it. The {@code loops()} count is carried on the traversers, so a condition
     * may read it ({@code until(loops().is(3))}).
     */
    private List<GremlinTraverser> repeatStep(GremlinContext context, List<String> args,
                                              List<GremlinTraverser> traversers) {
        RepeatPlan plan = parseRepeat(args);
        String child = plan.child();
        Integer times = plan.times();
        String until = plan.until();
        String emit = plan.emit();
        List<GremlinTraverser> frontier = traversers;
        List<GremlinTraverser> emitted = new ArrayList<>();
        int loop = 0;
        while (!frontier.isEmpty() && (times == null || loop < times)) {
            loop++;
            if (loop > MAX_REPEAT_ITERATIONS) {
                throw new QueryException("A repeat ran " + MAX_REPEAT_ITERATIONS + " times without its"
                        + " until() condition being met and without a times(n) bound: check the condition"
                        + " (a cyclic graph keeps the loop alive forever)");
            }
            List<GremlinTraverser> next = new ArrayList<>();
            for (GremlinTraverser traverser : frontier) {
                for (Object value : runSubTraversal(context, child, List.of(traverser.value()))) {
                    next.add(traverser.withValue(value).withLoops(loop));
                }
            }
            List<GremlinTraverser> continuing = new ArrayList<>();
            for (GremlinTraverser traverser : next) {
                boolean emittedHere = false;
                if (emit != null && (emit.isEmpty() || predicateHolds(context, emit, traverser, loop))) {
                    emitted.add(traverser);
                    emittedHere = true;
                }
                if (until == null || !predicateHolds(context, until, traverser, loop)) {
                    continuing.add(traverser);
                } else if (!emittedHere) {
                    emitted.add(traverser); // an until() result is the answer even without emit()
                }
            }
            frontier = continuing;
        }
        // a plain times() loop answers with what it reached; one that asked for emit/until answers with
        // what those steps collected (the terminal traversers and/or every emitted iteration)
        return emit == null && until == null ? frontier : emitted;
    }

    /** How far an unbounded {@code repeat(child).until(...)} may go before the engine gives up. */
    private static final int MAX_REPEAT_ITERATIONS = 100_000;

    /**
     * Whether a child traversal used as a loop condition reaches anything for this traverser. The loop
     * count is handed in, so {@code until(loops().is(n))} can read it: {@code loops()} answers the count
     * instead of the value for that step.
     */
    private boolean predicateHolds(GremlinContext context, String condition,
                                   GremlinTraverser traverser, int loop) {
        List<GremlinQuery.Step> steps = GremlinQuery.parseSteps("." + condition.trim());
        List<Object> current = List.of(traverser.value());
        for (GremlinQuery.Step step : steps) {
            context.readProperties(List.of());
            if (step.name().equals("loops")) {
                List<Object> counts = new ArrayList<>(current.size());
                for (int i = 0; i < current.size(); i++) {
                    counts.add(loop);
                }
                current = counts;
                continue;
            }
            current = registry.resolve(step.name(), step.args()).apply(context, current);
        }
        return !current.isEmpty();
    }

    /** {@code loops()}: the traverser's value becomes how many times it has been through its repeat body. */
    private static List<GremlinTraverser> loopsStep(List<GremlinTraverser> traversers) {
        List<GremlinTraverser> out = new ArrayList<>(traversers.size());
        for (GremlinTraverser traverser : traversers) {
            out.add(traverser.withValue(traverser.loops()));
        }
        return out;
    }

    /**
     * {@code subgraph('x')}: collect every element each traverser has walked over - the whole walk, or the
     * value itself when the query carries no walk - into a named subgraph, and hand the traverser on.
     * {@code cap('x')} answers the assembled {@link Subgraph}. This is TinkerPop's subgraph side effect:
     * the collection is the point, the traversal continues until something reads it.
     */
    private List<GremlinTraverser> subgraphStep(GremlinContext context, List<String> args,
                                                List<GremlinTraverser> traversers) {
        String name = sideEffectName(args, "subgraph");
        Map<String, Object> collected = context.namedSubgraphs().computeIfAbsent(name,
                key -> new LinkedHashMap<>());
        for (GremlinTraverser traverser : traversers) {
            List<Object> walk = traverser.path();
            if (walk == null || walk.isEmpty()) {
                collectElement(collected, traverser.value());
            } else {
                for (Object element : walk) {
                    collectElement(collected, element);
                }
            }
        }
        return traversers;
    }

    /** Adds one element to a named subgraph, deduplicated by label and id. */
    private static void collectElement(Map<String, Object> collected, Object element) {
        if (element instanceof GremlinElement gremlinElement) {
            collected.putIfAbsent(gremlinElement.label() + "#" + gremlinElement.id(), element);
        }
    }

    /**
     * {@code tree('x')}: merge the traversers' walks into one nested map (a path tree) and collect it
     * under the name; {@code tree()} with no name answers the tree itself. A node is keyed by the element
     * it stands for ({@code label#id}), so the same vertex reached twice becomes one node - which is what
     * makes it a tree rather than a list of walks, and keeps the answer JSON-friendly.
     */
    @SuppressWarnings("unchecked")
    private List<GremlinTraverser> treeStep(GremlinContext context, List<String> args,
                                            List<GremlinTraverser> traversers) {
        Map<Object, Object> tree = new LinkedHashMap<>();
        for (GremlinTraverser traverser : traversers) {
            List<Object> walk = traverser.path();
            if (walk == null || walk.isEmpty()) {
                walk = List.of(traverser.value());
            }
            Map<Object, Object> node = tree;
            for (Object element : walk) {
                Object nodeKey = treeKey(element);
                Object child = node.get(nodeKey);
                if (!(child instanceof Map)) {
                    child = new LinkedHashMap<>();
                    node.put(nodeKey, child);
                }
                node = (Map<Object, Object>) child;
            }
        }
        if (args.isEmpty()) {
            // tree() answers with the tree: one value, keeping the labels of the first traverser
            return List.of(traversers.isEmpty() ? GremlinTraverser.of(tree)
                    : traversers.get(0).withValue(tree));
        }
        context.namedTrees().put(sideEffectName(args, "tree"), tree);
        return traversers;
    }

    /** How a tree names a node: an element by label and id, anything else by value. */
    private static Object treeKey(Object element) {
        return element instanceof GremlinElement gremlinElement
                ? gremlinElement.label() + "#" + gremlinElement.id() : element;
    }

    /** The one name a side-effect step ({@code subgraph}, {@code tree}) expects. */
    private static String sideEffectName(List<String> args, String stepName) {
        if (args.size() != 1 || args.get(0).isBlank()) {
            throw new QueryException(stepName + " expects one name, e.g. " + stepName + "('x')");
        }
        return args.get(0).trim();
    }

    /**
     * Whether a {@code math(...)} step needs the stateful path: a {@code by(...)} modulator or a named
     * variable reads the traverser's labels. {@code math('_ ...')} with no modulator stays on the list path.
     */
    static boolean mathNeedsTraverserState(List<String> args) {
        String expression = null;
        for (String arg : args) {
            if (arg.startsWith("by:")) {
                return true;
            }
            if (expression == null) {
                expression = arg;
            }
        }
        return expression != null
                && GremlinNumbers.variables(expression).stream().anyMatch(name -> !name.equals("_"));
    }

    /**
     * {@code math(expression).by(spec...)} on the stateful path: every variable the expression names is
     * resolved to a number - {@code _} is the traverser's own value, any other name is a label bound with
     * {@code as(name)} - and the {@code by()} modulators are applied to those values in the order the
     * variables first appear, cycling when fewer modulators were written (as TinkerPop's ring does).
     *
     * <p>A value the modulator cannot produce filters the traverser (a missing property is not an error);
     * a value that is not a number is an error, because a math on text has no meaning to return. An
     * unbound variable is an error too: silently reading 0 would look like data.
     */
    private List<GremlinTraverser> mathStep(GremlinContext context, List<String> args,
                                            List<GremlinTraverser> traversers) {
        String expression = null;
        List<String> specs = new ArrayList<>();
        for (String arg : args) {
            if (arg.startsWith("by:")) {
                specs.add(arg.substring(3));
            } else if (expression == null) {
                expression = arg;
            }
        }
        List<String> variables = GremlinNumbers.variables(expression == null ? "" : expression);
        List<GremlinTraverser> out = new ArrayList<>(traversers.size());
        for (GremlinTraverser traverser : traversers) {
            Map<String, Double> bindings = new LinkedHashMap<>();
            boolean productive = true;
            for (int i = 0; i < variables.size(); i++) {
                String variable = variables.get(i);
                String spec = specs.isEmpty() ? null : specs.get(i % specs.size());
                Object raw;
                if (variable.equals("_")) {
                    raw = traverser.value();
                } else if (traverser.labels().containsKey(variable)) {
                    raw = traverser.labels().get(variable);
                } else {
                    throw new QueryException("math('" + expression + "') uses the variable '" + variable
                            + "', which is not bound: bind it with as('" + variable + "') before the step");
                }
                Object value = spec == null ? raw : byValue(context, raw, spec);
                if (value == null) {
                    productive = false; // a modulator that produced nothing filters the traverser
                    break;
                }
                if (!(value instanceof Number number)) {
                    throw new QueryException("math('" + expression + "') variable '" + variable + "' must be"
                            + " a number, got: " + value);
                }
                bindings.put(variable, number.doubleValue());
            }
            if (productive) {
                out.add(traverser.withValue(GremlinNumbers.math(expression, bindings)));
            }
        }
        return out;
    }

    /** One {@code by()} spec applied to a resolved variable value (a property key, or a child traversal). */
    private Object byValue(GremlinContext context, Object value, String spec) {
        String by = spec == null ? "" : spec.trim();
        if (by.isEmpty()) {
            return value;
        }
        if (!by.contains("(") && !by.contains(".")) {
            // a bare property key: read it off the element, or nothing when the value is not an element
            return value instanceof GremlinElement element ? GremlinElementExpression.value(element, by) : null;
        }
        List<Object> reached = runSubTraversal(context, by, List.of(value));
        return reached.isEmpty() ? null : reached.get(0);
    }

    /** How many patterns a single {@code match(...)} may carry (the solver explores their orders). */
    private static final int MAX_MATCH_PATTERNS = 8;

    /**
     * {@code match(pattern...)}: a conjunction of patterns over the same traverser, sharing the labels they
     * bind. Each pattern is a step chain such as {@code as('a').out('created').as('b')}; a leading
     * {@code as(x)} whose label is already bound starts the pattern at that labelled value, otherwise it
     * binds the current value. Every pattern that can be satisfied is; the answer is one traverser per
     * complete binding set, carrying the incoming value and the union of the bindings, so
     * {@code select(...)}/{@code where(...)} read them.
     *
     * <p>The solver tries the patterns in any order (the order they are written is tried first), which is
     * what lets a pattern that reads {@code b} run after a pattern that binds it - the join, not the
     * written order, decides. Duplicate binding sets are collapsed.
     */
    private List<GremlinTraverser> matchStep(GremlinContext context, List<String> args,
                                             List<GremlinTraverser> traversers) {
        if (args.isEmpty()) {
            throw new QueryException("match expects at least one pattern, e.g."
                    + " match(as('a').out('created').as('b'))");
        }
        if (args.size() > MAX_MATCH_PATTERNS) {
            throw new QueryException("match supports up to " + MAX_MATCH_PATTERNS + " patterns, got "
                    + args.size());
        }
        List<List<GremlinQuery.Step>> patterns = new ArrayList<>(args.size());
        for (String pattern : args) {
            List<GremlinQuery.Step> steps = GremlinQuery.parseSteps("." + childTraversal(pattern));
            if (steps.isEmpty()) {
                throw new QueryException("match pattern must not be empty: " + pattern);
            }
            patterns.add(steps);
        }
        List<GremlinTraverser> out = new ArrayList<>();
        // one sequence of bindings is one answer, however many pattern orders found it
        java.util.Set<Map<String, Object>> seen = new java.util.LinkedHashSet<>();
        for (GremlinTraverser traverser : traversers) {
            List<GremlinTraverser> solutions = new ArrayList<>();
            solveMatch(context, traverser, patterns, solutions);
            for (GremlinTraverser solution : solutions) {
                // match answers the start value with the union of the bindings (TinkerPop's shape), so the
                // value the last pattern happened to leave does not leak into the answer
                GremlinTraverser merged = new GremlinTraverser(traverser.value(), solution.labels(),
                        traverser.path(), traverser.sack(), traverser.loops());
                if (seen.add(merged.labels())) {
                    out.add(merged);
                }
            }
        }
        return out;
    }

    /** Recursive join: apply any remaining pattern and continue with the rest until all are satisfied. */
    private void solveMatch(GremlinContext context, GremlinTraverser state,
                            List<List<GremlinQuery.Step>> remaining, List<GremlinTraverser> out) {
        if (remaining.isEmpty()) {
            out.add(state);
            return;
        }
        for (int i = 0; i < remaining.size(); i++) {
            List<GremlinTraverser> answers = applyMatchPattern(context, state, remaining.get(i));
            if (answers.isEmpty()) {
                continue;
            }
            List<List<GremlinQuery.Step>> rest = new ArrayList<>(remaining);
            rest.remove(i);
            for (GremlinTraverser next : answers) {
                solveMatch(context, next, rest, out);
            }
        }
    }

    /** Runs one pattern on one traverser, threading the bindings through every step. */
    private List<GremlinTraverser> applyMatchPattern(GremlinContext context, GremlinTraverser start,
                                                     List<GremlinQuery.Step> steps) {
        List<GremlinTraverser> states = new ArrayList<>();
        states.add(start);
        for (int i = 0; i < steps.size() && !states.isEmpty(); i++) {
            List<GremlinTraverser> next = new ArrayList<>();
            for (GremlinTraverser state : states) {
                next.addAll(applyMatchStep(context, state, steps.get(i), i == 0));
            }
            states = next;
        }
        return states;
    }

    /** One step of a match pattern: {@code as} binds or checks a label, everything else runs on the value. */
    private List<GremlinTraverser> applyMatchStep(GremlinContext context, GremlinTraverser state,
                                                  GremlinQuery.Step step, boolean first) {
        String name = step.name();
        if (name.equals("as")) {
            GremlinTraverser current = state;
            List<String> labels = step.args();
            // a pattern that starts at an already-bound label begins from that label's value
            if (first && !labels.isEmpty() && current.labels().containsKey(labels.get(0))) {
                current = current.withValue(current.labels().get(labels.get(0)));
                labels = labels.subList(1, labels.size());
            }
            for (String label : labels) {
                if (current.labels().containsKey(label)) {
                    if (!sameElement(current.value(), current.labels().get(label))) {
                        return List.of(); // the label already holds another value: this pattern cannot hold
                    }
                } else {
                    current = current.withLabel(label, current.value());
                }
            }
            return List.of(current);
        }
        if (name.equals("where") && step.args().size() == 2
                && step.args().get(0).matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
            return GremlinTraverserSteps.whereLabel(List.of(state), step.args().get(0), step.args().get(1));
        }
        if (name.equals("select")) {
            return GremlinTraverserSteps.select(List.of(state), keysOf(step.args()), bySpecsOf(step.args()),
                    (value, child) -> runSubTraversal(context, child, List.of(value)));
        }
        // every other step keeps its own implementation, run on this traverser's value; each value it
        // reaches is one way the pattern can continue, so the pattern branches like the pipeline does
        context.readProperties(List.of());
        List<Object> reached = registry.resolve(name, step.args()).apply(context, List.of(state.value()));
        List<GremlinTraverser> out = new ArrayList<>(reached.size());
        for (Object value : reached) {
            out.add(state.withValue(value));
        }
        return out;
    }

    /**
     * {@code simplePath()}/{@code cyclicPath()}: keep the traversers whose walk visits no vertex twice, or
     * the ones that do - {@code repeatsAVertex} answers "does it repeat", so a simple walk is the one where
     * that is <b>false</b>. Both read the walk, so asking for them costs the same as asking for path().
     */
    private static List<GremlinTraverser> walkFilter(List<GremlinTraverser> traversers, boolean simple) {
        List<GremlinTraverser> out = new ArrayList<>();
        for (GremlinTraverser traverser : traversers) {
            if (repeatsAVertex(traverser.path()) != simple) {
                out.add(traverser);
            }
        }
        return out;
    }

    /**
     * {@code branch(child...)}: every traverser goes to the <b>first</b> child that answers something; a
     * traverser no child answers for is dropped. The child's answer becomes the traverser's value, so the
     * labels bound before it are still readable afterwards.
     */
    private List<GremlinTraverser> branchStep(GremlinContext context, List<String> children,
                                              List<GremlinTraverser> traversers) {
        List<GremlinTraverser> out = new ArrayList<>();
        for (GremlinTraverser traverser : traversers) {
            for (String child : children) {
                List<Object> answer = runSubTraversal(context, child, List.of(traverser.value()));
                if (!answer.isEmpty()) {
                    for (Object value : answer) {
                        out.add(traverser.withValue(value));
                    }
                    break;
                }
            }
        }
        return out;
    }

    /** {@code union(child...)}: every child runs and their answers are concatenated, child by child. */
    private List<GremlinTraverser> unionStep(GremlinContext context, List<String> children,
                                             List<GremlinTraverser> traversers) {
        List<GremlinTraverser> out = new ArrayList<>();
        for (GremlinTraverser traverser : traversers) {
            for (String child : children) {
                for (Object value : runSubTraversal(context, child, List.of(traverser.value()))) {
                    out.add(traverser.withValue(value));
                }
            }
        }
        return out;
    }

    /**
     * {@code path()}: the traverser's walk becomes its value, and its labels survive into the next step.
     * {@code path().from('a').to('b')} keeps only the part of the walk between the two labelled elements
     * (both ends included), the labels having been bound by {@code as(...)} earlier in the chain.
     */
    private static List<GremlinTraverser> pathStep(List<GremlinTraverser> traversers, List<String> args) {
        String key = null;
        String from = null;
        String to = null;
        for (String arg : args) {
            if (arg.startsWith(FROM_MARKER)) {
                from = arg.substring(FROM_MARKER.length());
            } else if (arg.startsWith(TO_MARKER)) {
                to = arg.substring(TO_MARKER.length());
            } else if (key == null) {
                key = arg;
            }
        }
        List<GremlinTraverser> out = new ArrayList<>(traversers.size());
        for (GremlinTraverser traverser : traversers) {
            List<Object> walk = traverser.path() == null ? new ArrayList<>()
                    : new ArrayList<>(traverser.path());
            Object value = traverser.value();
            // a step that is not a hop (values(), a filter) does not extend the walk, but the walk ends at
            // the value the traverser stands on, which the list path shows as its last element too
            if (walk.isEmpty() || !java.util.Objects.equals(walk.get(walk.size() - 1), value)) {
                walk.add(value);
            }
            if (from != null || to != null) {
                // both ends are found on the full walk first, then the slice is taken: from(x).to(y) and
                // to(y).from(x) are the same part of the walk
                int start = from == null ? 0 : labelPosition(traverser, walk, from, "from");
                int end = to == null ? walk.size() - 1 : labelPosition(traverser, walk, to, "to");
                walk = start > end ? new ArrayList<>() : new ArrayList<>(walk.subList(start, end + 1));
            }
            if (key != null) {
                // path().by('name'): the walk of what each element carries under that key, as path().by does
                List<Object> mapped = new ArrayList<>(walk.size());
                for (Object element : walk) {
                    mapped.add(element instanceof GremlinElement gremlinElement
                            ? gremlinElement.properties().get(key) : null);
                }
                walk = mapped;
            }
            out.add(traverser.withValue(new GremlinPath(walk)));
        }
        return out;
    }

    /**
     * Where a {@code from}/{@code to} label sits on a walk: the first element that is the value the label
     * holds (the same vertex, edge or value). A label that was never bound, or whose value is not on this
     * walk, is an error - a quietly empty slice would look like the traversal found nothing.
     */
    private static int labelPosition(GremlinTraverser traverser, List<Object> walk, String label,
                                     String modulator) {
        if (!traverser.labels().containsKey(label)) {
            throw new QueryException("path()." + modulator + "('" + label + "') has no such label: bind it"
                    + " with as('" + label + "') before the path. Bound here: "
                    + (traverser.labels().isEmpty() ? "nothing" : traverser.labels().keySet()));
        }
        Object bound = traverser.labels().get(label);
        for (int i = 0; i < walk.size(); i++) {
            if (sameElement(walk.get(i), bound)) {
                return i;
            }
        }
        throw new QueryException("path()." + modulator + "('" + label + "'): the value of that label is not"
                + " on this walk, so there is nothing to slice at");
    }

    /** Whether two walk elements are the same value (an element by label and id, a number by value). */
    private static boolean sameElement(Object left, Object right) {
        if (left instanceof GremlinElement a && right instanceof GremlinElement b) {
            return java.util.Objects.equals(a.id(), b.id()) && java.util.Objects.equals(a.label(), b.label());
        }
        if (left instanceof Number a && right instanceof Number b) {
            return Double.compare(a.doubleValue(), b.doubleValue()) == 0;
        }
        return java.util.Objects.equals(left, right);
    }

    /** {@code withSack(seed)}: every traverser gets its own accumulator, starting at the seed. */
    private static List<GremlinTraverser> withSack(List<GremlinTraverser> traversers, List<String> args) {
        if (args.size() != 1) {
            throw new QueryException("withSack expects one seed value, got " + args.size() + " arguments");
        }
        Object seed = GremlinSteps.literalValue(args.get(0));
        List<GremlinTraverser> out = new ArrayList<>(traversers.size());
        for (GremlinTraverser traverser : traversers) {
            out.add(traverser.withSack(seed));
        }
        return out;
    }

    /**
     * {@code sack()}: with no argument the traverser's value becomes its sack; with a modulator
     * ({@code assign}, {@code sum}, {@code min}, {@code max}) and a {@code by(...)} spec, the sack absorbs
     * the value that spec reads off the traverser (a property key, or a child traversal).
     */
    private static List<GremlinTraverser> sack(List<GremlinTraverser> traversers, List<String> args) {
        List<String> specs = bySpecsOf(args);
        String modulator = null;
        for (String arg : args) {
            if (!arg.startsWith("by:")) {
                modulator = arg.trim().toLowerCase(java.util.Locale.ROOT);
            }
        }
        List<GremlinTraverser> out = new ArrayList<>(traversers.size());
        for (GremlinTraverser traverser : traversers) {
            if (modulator == null) {
                out.add(traverser.withValue(traverser.sack()));
                continue;
            }
            Object value = specs.isEmpty() ? traverser.value()
                    : GremlinTraverserSteps.read(traverser.value(), specs.get(0));
            out.add(traverser.withSack(combine(modulator, traverser.sack(), value)));
        }
        return out;
    }

    /** The sack arithmetic: assign/sum/min/max, the four TinkerPop's {@code sack()} offers. */
    private static Object combine(String modulator, Object sack, Object value) {
        return switch (modulator) {
            case "assign" -> value;
            case "sum" -> number(sack) + number(value);
            case "min" -> Math.min(number(sack), number(value));
            case "max" -> Math.max(number(sack), number(value));
            default -> throw new QueryException("sack('" + modulator + "') is not a modulator: use assign,"
                    + " sum, min or max");
        };
    }

    private static double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new QueryException("sack arithmetic needs numbers, got: " + value);
        }
    }

    /** The keys of a {@code select}/{@code project} step: its arguments up to the {@code by:} specs. */
    private static List<String> keysOf(List<String> args) {
        List<String> keys = new ArrayList<>(args.size());
        for (String arg : args) {
            if (!arg.startsWith("by:")) {
                keys.add(arg);
            }
        }
        return keys;
    }

    /** The {@code by(...)} specs a step carries, folded into its arguments as {@code by:<spec>}. */
    private static List<String> bySpecsOf(List<String> args) {
        List<String> specs = new ArrayList<>();
        for (String arg : args) {
            if (arg.startsWith("by:")) {
                specs.add(arg.substring(3));
            }
        }
        return specs;
    }

    /**
     * Whether a {@code flatMap}/{@code map} argument is a child traversal rather than a closure
     * expression: it parses as steps and every step name is one the registry knows. An expression such as
     * {@code [1, 2, 3]} or {@code element.value('age')} is not a step chain and answers false.
     */
    private boolean looksLikeTraversal(String body) {
        try {
            List<GremlinQuery.Step> steps = GremlinQuery.parseSteps("." + childTraversal(body));
            if (steps.isEmpty()) {
                return false;
            }
            for (GremlinQuery.Step step : steps) {
                registry.resolve(step.name(), step.args());
            }
            return true;
        } catch (QueryException e) {
            return false;
        }
    }

    /** Adds a value to a flatMap answer: a list/set/array contributes its elements, anything else itself. */
    private static void flattenInto(List<Object> out, Object value) {
        if (value instanceof Iterable<?> iterable) {
            for (Object element : iterable) {
                out.add(element);
            }
        } else if (value instanceof Object[] array) {
            out.addAll(java.util.Arrays.asList(array));
        } else if (value != null) {
            out.add(value);
        }
    }

    /** {@code group().by(key).by(value)}: one map of the traversers, grouped and valued. */
    private List<Object> groupStep(GremlinContext context, List<String> specs, List<Object> in) {
        String keySpec = specs.isEmpty() ? null : specs.get(0);
        String valueSpec = specs.size() > 1 ? specs.get(1) : null;
        Map<Object, List<Object>> groups = new LinkedHashMap<>();
        for (Object element : in) {
            Object key = keySpec == null ? element : groupKey(context, element, keySpec);
            groups.computeIfAbsent(key == null ? "(none)" : key, ignored -> new ArrayList<>()).add(element);
        }
        Map<Object, Object> answer = new LinkedHashMap<>();
        for (Map.Entry<Object, List<Object>> group : groups.entrySet()) {
            answer.put(group.getKey(), groupValue(context, group.getValue(), valueSpec));
        }
        return List.of(answer);
    }

    /** One group's key: a property of the element, or the first value a child traversal reaches. */
    private Object groupKey(GremlinContext context, Object element, String spec) {
        if (isPropertySpec(spec)) {
            return element instanceof GremlinElement gremlinElement
                    ? GremlinElementExpression.value(gremlinElement, spec) : null;
        }
        List<Object> reached = runSubTraversal(context, spec, List.of(element));
        return reached.isEmpty() ? null : reached.get(0);
    }

    /**
     * One group's value. No spec, or a spec that is not a reducing step, answers the list it produced
     * (a property key maps each element; a child traversal runs over the whole group). A reducing step
     * ({@code count/sum/mean/min/max/fold}) answers its single value instead.
     */
    private Object groupValue(GremlinContext context, List<Object> group, String spec) {
        if (spec == null) {
            return new ArrayList<>(group);
        }
        if (isPropertySpec(spec)) {
            List<Object> values = new ArrayList<>();
            for (Object element : group) {
                if (element instanceof GremlinElement gremlinElement) {
                    Object value = GremlinElementExpression.value(gremlinElement, spec);
                    if (value != null) {
                        values.add(value);
                    }
                }
            }
            return values;
        }
        List<Object> reached = runSubTraversal(context, spec, group);
        if (isReducingSpec(spec)) {
            return reached.isEmpty() ? null : reached.get(0);
        }
        return reached;
    }

    /** Whether a {@code by} spec is a bare property key (no call and no chain) rather than a traversal. */
    private static boolean isPropertySpec(String spec) {
        return !spec.contains("(") && !spec.contains(".");
    }

    /** Whether a {@code by} spec ends in a step that collapses a group to one value. */
    private static boolean isReducingSpec(String spec) {
        String last = spec.contains(".") ? spec.substring(spec.lastIndexOf('.') + 1) : spec;
        String name = last.contains("(") ? last.substring(0, last.indexOf('(')).trim() : last.trim();
        return name.equals("count") || name.equals("sum") || name.equals("mean") || name.equals("max")
                || name.equals("min") || name.equals("fold");
    }

    /**
     * One hop on the stateful path: the step runs for one traverser at a time, and every value it reaches
     * becomes a traverser with the labels its input carried.
     */
    private List<GremlinTraverser> traverserHop(GremlinContext context, CompiledQuery compiled, int index,
                                                List<GremlinTraverser> traversers) {
        context.readProperties(compiled.properties[index]);
        List<Object> values = new ArrayList<>(traversers.size());
        for (GremlinTraverser traverser : traversers) {
            values.add(traverser.value());
        }
        // one batched read for the whole hop (the same helper the list path uses), sliced back per input so
        // each reached value keeps the labels of the traverser it came from. A query that asked for path()
        // reads the edges it walked too (one more batched pass), so its walk holds the same elements the list
        // path's walk holds: the edge, then the vertex it reached
        boolean walk = compiled.pathAware;
        List<List<Object>> reached = walk
                ? GremlinSteps.hopPerInputWithPath(context, values, compiled.plan.get(index))
                : GremlinSteps.hopPerInput(context, values, compiled.plan.get(index));
        List<GremlinTraverser> out = new ArrayList<>();
        String name = compiled.plan.get(index).name();
        boolean vertexHop = name.equals("out") || name.equals("in") || name.equals("both");
        for (int i = 0; i < traversers.size(); i++) {
            GremlinTraverser traverser = traversers.get(i);
            List<Object> mine = i < reached.size() ? reached.get(i) : List.of();
            if (!walk) {
                for (Object value : mine) {
                    out.add(traverser.withValue(value));
                }
                continue;
            }
            for (int j = 0; j + 1 < mine.size(); j += 2) {
                Object edge = mine.get(j);
                Object value = mine.get(j + 1);
                GremlinTraverser next = vertexHop && edge != null ? traverser.withPathElement(edge)
                        : traverser;
                out.add(next.withValue(value).withPathElement(value));
            }
        }
        return out;
    }

    /**
     * Runs one non-stateful step over the traversers' values and re-wraps the answer: labels are carried
     * along by matching each output to the input it came from (by identity when the step filtered, by
     * index when it mapped one to one, and from the first input when the step reduced everything to one).
     */
    private List<GremlinTraverser> adapt(GremlinContext context, CompiledQuery compiled, int index,
                                         List<GremlinTraverser> traversers) {
        String name = compiled.stepNames.get(index);
        if (!ADAPTABLE_STEPS.contains(name)) {
            throw new QueryException("'" + name + "' cannot be used after as()/select()/project() yet: the"
                    + " stateful path (TRAVERSER-DESIGN.md) covers " + ADAPTABLE_STEPS + " for now");
        }
        List<Object> values = new ArrayList<>(traversers.size());
        for (GremlinTraverser traverser : traversers) {
            values.add(traverser.value());
        }
        context.readProperties(compiled.properties[index]);
        List<Object> mapped = compiled.steps.get(index).apply(context, values);
        List<GremlinTraverser> out = new ArrayList<>(mapped.size());
        Map<Object, GremlinTraverser> byValue = new java.util.IdentityHashMap<>();
        for (GremlinTraverser traverser : traversers) {
            byValue.putIfAbsent(traverser.value(), traverser);
        }
        GremlinTraverser fallback = traversers.isEmpty() ? GremlinTraverser.of(null) : traversers.get(0);
        for (int i = 0; i < mapped.size(); i++) {
            GremlinTraverser source = byValue.get(mapped.get(i));
            if (source == null) {
                source = mapped.size() == traversers.size() ? traversers.get(i) : fallback;
            }
            out.add(source.withValue(mapped.get(i)));
        }
        return out;
    }

    /**
     * The steps the stateful path lets through for now: the ones whose answer per traverser does not depend
     * on which traverser it was (so carrying the labels along is unambiguous). Hops and the rest come with
     * the next batches of TRAVERSER-DESIGN.md.
     */
    private static final Set<String> ADAPTABLE_STEPS = Set.of("has", "filter", "hasLabel", "hasId",
            "hasNot", "hasKey", "hasValue", "values", "id", "label", "is", "range", "skip", "limit",
            "tail", "dedup", "order", "count", "sum", "mean", "max", "min", "groupCount", "fold",
            "unfold", "constant", "identity", "math", "format", "dateTime",
            // the side-effect steps of the context: they collect and hand the traversers on, so the
            // stateful path can use them through the adapter like any other step
            "aggregate", "store", "cap");

    /**
     * {@code explain()}: the plan this query compiled to - the start, the steps, and the property
     * projection each step asked for ({@code null} = no properties, empty = all of them).
     */
    private static Map<String, Object> explain(CompiledQuery compiled) {
        List<Map<String, Object>> steps = new ArrayList<>(compiled.stepNames.size());
        for (int i = 0; i < compiled.stepNames.size(); i++) {
            String name = compiled.stepNames.get(i);
            if (name.equals("profile") || name.equals("explain")) {
                continue; // the reporter itself is not part of the plan it describes
            }
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("step", name);
            List<String> projection = compiled.properties[i];
            step.put("properties", projection == null ? "none" : projection.isEmpty() ? "all" : projection);
            steps.add(step);
        }
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("source", compiled.source.kind().name().toLowerCase(java.util.Locale.ROOT));
        plan.put("name", compiled.source.name());
        plan.put("seeds", compiled.source.ids().size());
        plan.put("steps", steps);
        plan.put("pathAware", compiled.stepNames.contains("path"));
        plan.put("verifySeeds", compiled.verifySeeds);
        return plan;
    }

    /**
     * {@code profile()}: runs the traversal with a recorder next to every step and answers with what each
     * one saw - how many traversers went in and came out, and how long it took. The recorder is only
     * created when the step asks for it, so a normal run pays nothing for it.
     *
     * <p>The storage counters are not here: they belong to the store and are on the metrics endpoint.
     */
    private List<Object> profile(GremlinContext context, CompiledQuery compiled) {
        List<Map<String, Object>> recorded = new ArrayList<>();
        long started = System.nanoTime();
        List<Object> current = start(context, compiled.source, compiled.startProperties, compiled.verifySeeds);
        current = applySteps(context, compiled, current, 0, compiled.steps.size(), recorded);
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("steps", recorded);
        profile.put("totalMillis", (System.nanoTime() - started) / 1e6);
        profile.put("traversers", current.size());
        return List.of(profile);
    }

    /**
     * Applies {@code steps[from, to)} to the traversers, with the adaptive batch tuning the whole
     * pipeline uses. One loop, so a distributed run walks exactly the steps a single-process run walks.
     */
    private List<Object> applySteps(GremlinContext context, CompiledQuery compiled, List<Object> in,
                                    int from, int to, List<Map<String, Object>> profile) {
        List<Object> current = in;
        int hopsDone = 0;
        int hopFanOut = 0;
        boolean filterSinceHop = false;
        for (int i = from; i < to; i++) {
            // the request's deadline, if it has one: a step boundary is the natural place, because every
            // step (a hop, a filter, an algorithm) is bounded work the caller can recognise
            QueryDeadline.check();
            String name = compiled.stepNames.get(i);
            int before = current.size();
            long started = System.nanoTime();
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
            if (profile != null && !name.equals("profile")) {
                Map<String, Object> step = new LinkedHashMap<>();
                step.put("step", name);
                step.put("in", before);
                step.put("out", current.size());
                step.put("millis", (System.nanoTime() - started) / 1e6);
                profile.add(step);
            }
            // and after it: a step that took the whole budget must not let the next one start
            QueryDeadline.check();
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
            "group", "aggregate", "timeLimit", "path", "simplePath", "cyclicPath",
            // an algorithm runs over the whole reached subgraph: pagerank/louvain/cc/sssp/bfs and the
            // centralities are the cluster's steps in a distributed run and the reduce phase's in any case
            "pagerank", "louvain", "cc", "sssp", "bfs",
            "degree", "closeness", "betweenness", "eigenvector");

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
        // the algorithm steps of this run are the cluster's (a distributed query runs distributed end to
        // end); without a cluster configured GremlinDistAlgorithms says so when such a step is reached
        reduceContext.distributedAlgorithms(true);
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
                    mapper.distributedAlgorithms(true);
                    List<Object> elements = partitionStart(mapper, compiled.source, compiled.startProperties,
                            partition, partitions);
                    List<Object> out = split == 0 ? elements
                            : applySteps(mapper, compiled, elements, 0, split, null);
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
                compiled.steps.size(), null));
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
                            // 同 startVertexScan：分布式扫描的起点也不进 subgraph
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
        /**
         * Whether this query needs the stateful path (see TRAVERSER-DESIGN.md): a step that reads or
         * writes per-traverser state ({@code as/select/project/where('a',P)}) is in the chain. Decided at
         * compile time, so a query that does not need it runs exactly the code it ran before.
         */
        final boolean traverserMode;

        /** Whether the chain asks for {@code path()}: on the stateful path that is what makes a walk. */
        final boolean pathAware;

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
            this.traverserMode = needsTraverserState(plan);
            // the walk is also what simplePath()/cyclicPath() read, and what subgraph()/tree() collect
            this.pathAware = stepNames.contains("path") || stepNames.contains("simplePath")
                    || stepNames.contains("cyclicPath") || stepNames.contains("subgraph")
                    || stepNames.contains("tree");
        }
    }

    /** The steps that read or write a traverser's labels, and therefore need the stateful path. */
    private static final Set<String> STATEFUL_STEPS = Set.of("as", "select", "project", "withSack", "sack",
            "sideEffect", "branch", "loops", "subgraph", "tree", "match");

    /** Whether the plan needs per-traverser state (a labelled step, or {@code where(label, P)}). */
    private static boolean needsTraverserState(List<GremlinQuery.Step> plan) {
        for (GremlinQuery.Step step : plan) {
            if (STATEFUL_STEPS.contains(step.name())) {
                return true;
            }
            // a repeat with an until()/emit() condition needs per-traverser state: the loop count and the
            // emitted traversers travel with each one (the folded arguments carry the markers)
            if (step.name().equals("repeat") && step.args().stream()
                    .anyMatch(arg -> arg.startsWith(UNTIL_MARKER) || arg.startsWith(EMIT_MARKER))) {
                return true;
            }
            // path().from(x)/to(x) resolves labels, which live on the traverser
            if (step.name().equals("path") && step.args().stream()
                    .anyMatch(arg -> arg.startsWith(FROM_MARKER) || arg.startsWith(TO_MARKER))) {
                return true;
            }
            // math with a variable or a by() modulator reads labels, which live on the traverser
            if (step.name().equals("math") && mathNeedsTraverserState(step.args())) {
                return true;
            }
            if (step.name().equals("where") && whereLabels(step.args())) {
                return true;
            }
        }
        return false;
    }

    /** Whether a folded {@code where} carries the two-argument label form ({@code where('a', P)}). */
    private static boolean whereLabels(List<String> args) {
        List<String> bodies = new ArrayList<>(args.size());
        for (String arg : args) {
            if (!arg.startsWith("by:")) {
                bodies.add(arg);
            }
        }
        return bodies.size() == 2 && bodies.get(0).matches("[A-Za-z_$][A-Za-z0-9_$]*");
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
                    || name.equals("valueMap") || name.equals("propertyMap") || name.equals("order")
                    || name.equals("groupCount")
                    || name.equals("union") || name.equals("local") || name.equals("coalesce")
                    || name.equals("not") || name.equals("and") || name.equals("or")
                    || name.equals("optional") || name.equals("where") || name.equals("choose")
                    || name.equals("flatMap") || name.equals("group")
                    || name.equals("repeat")
                    // these hand the element's own properties on (properties() makes them traversers,
                    // format() writes them into a template), so the projection has to be everything
                    || name.equals("properties") || name.equals("key") || name.equals("value")
                    || name.equals("format") || name.equals("profile") || name.equals("explain")
                    // the labelled steps may read any property of the element a label holds
                    // (select().by('name')), so their projection has to be all of them
                    || name.equals("as") || name.equals("select") || name.equals("project")
                    || name.equals("where")
                    // the sack reads a property through by()
                    || name.equals("withSack") || name.equals("sack") || name.equals("sideEffect")
                    // subgraph()/tree() collect the elements of the walk, so they read them in full
                    || name.equals("subgraph") || name.equals("tree")
                    // match() binds labels and reads properties through its patterns
                    || name.equals("match")
                    // math() reads a property through by() and a labelled value through its variable
                    || name.equals("math")) {
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
     *
     * <p>A scan is a <b>set of inputs</b>, not an answer, so it does <b>not</b> record the vertices it
     * walked into the subgraph: {@code g.V('Sale').group().by('orderYear').by(count())} asks for a
     * count, and drawing the 8,000 sales it scanned would bury that answer under the whole type. A
     * graph is drawn when the question starts from a named id ({@code g.V('曹操','Entity')}) or when a
     * hop reaches something; a scan only feeds the steps that follow it.
     */
    private static List<Object> startVertexScan(GremlinContext context, String vertexName,
                                                List<String> properties) {
        requireScan(context, "g.V('" + vertexName + "')");
        List<VertexQueryResult> vertices = context.graphQuery()
                .scanVertices(vertexName, properties == null ? List.of() : properties, context.scanLimit());
        List<Object> out = new ArrayList<>(vertices.size());
        for (VertexQueryResult vertex : vertices) {
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
