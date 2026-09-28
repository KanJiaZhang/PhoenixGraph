package com.pengcheng.phoenixgraph.query.gremlin;

import com.pengcheng.phoenixgraph.query.QueryException;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
import com.pengcheng.phoenixgraph.storage.TimeRange;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Registry mapping Gremlin step names to {@link GremlinStep} factories.
 *
 * <p>The built-in steps are {@code out}/{@code in}/{@code both} (vertex hops),
 * {@code outE}/{@code inE}/{@code bothE} (edge hops), {@code otherV} (edge to its
 * far endpoint), {@code has}, {@code filter}, {@code values}, {@code elementMap},
 * {@code count}, {@code limit} and {@code dedup}. {@code nearest(k[, ef])} - the
 * HNSW vector search - is registered by {@link GremlinGraphQuery} (it needs the
 * indexes of the graph). Custom steps (code snippets) can be added with
 * {@link #register(String, StepFactory)}.
 */
public class GremlinStepRegistry {

    /** Builds a step from its parsed argument tokens. */
    @FunctionalInterface
    public interface StepFactory {
        GremlinStep create(List<String> args);
    }

    private final Map<String, StepFactory> factories = new LinkedHashMap<>();

    public GremlinStepRegistry() {
        registerBuiltIns();
    }

    /** Registers (or overrides) a step under the given name. */
    public GremlinStepRegistry register(String name, StepFactory factory) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("step name must not be blank");
        }
        factories.put(name, Objects.requireNonNull(factory, "factory"));
        return this;
    }

    /** Resolves a parsed step to its executable {@link GremlinStep}. */
    public GremlinStep resolve(String name, List<String> args) {
        StepFactory factory = factories.get(name);
        if (factory == null) {
            throw new QueryException("Unknown Gremlin step: " + name);
        }
        return factory.create(args);
    }

    private void registerBuiltIns() {
        register("out", args -> GremlinSteps.out(edgeNames(args)));
        register("in", args -> GremlinSteps.in(edgeNames(args)));
        register("both", args -> GremlinSteps.both(edgeNames(args)));
        register("outE", args -> GremlinSteps.outE(edgeNames(args)));
        // the shapes the compiler rewrites <hop>E(...).count() into
        register("countOutE", args -> GremlinSteps.countEdges(optionalSingle(args, "countOutE"), EdgeSide.OUT));
        register("countInE", args -> GremlinSteps.countEdges(optionalSingle(args, "countInE"), EdgeSide.IN));
        register("countBothE", args -> GremlinSteps.countEdges(optionalSingle(args, "countBothE"), EdgeSide.BOTH));
        register("inE", args -> GremlinSteps.inE(edgeNames(args)));
        register("bothE", args -> GremlinSteps.bothE(edgeNames(args)));
        register("otherV", args -> {
            if (!args.isEmpty()) {
                throw new QueryException("otherV expects no arguments, got " + args.size());
            }
            return GremlinSteps.otherV();
        });
        // the endpoint steps: the vertex at one end of an edge (or both), read in one batch
        register("outV", args -> {
            requireNoArgs(args, "outV");
            return GremlinSteps.outV();
        });
        register("inV", args -> {
            requireNoArgs(args, "inV");
            return GremlinSteps.inV();
        });
        register("bothV", args -> {
            requireNoArgs(args, "bothV");
            return GremlinSteps.bothV();
        });
        register("batch", args -> GremlinSteps.batch(positiveInt(single(args, "batch"))));
        register("barrier", args -> GremlinSteps.barrier(positiveInt(single(args, "barrier"))));
        register("has", args -> {
            if (args.size() == 1) {
                return GremlinSteps.has(args.get(0), null);
            }
            if (args.size() == 2) {
                return GremlinSteps.has(args.get(0), args.get(1));
            }
            if (args.size() == 3) {
                // the TinkerPop form: the label first, then the property
                return GremlinSteps.hasLabel(args.get(0), args.get(1), args.get(2));
            }
            throw new QueryException("has expects 1, 2 or 3 arguments, got " + args.size());
        });
        register("filter", args -> {
            if (args.isEmpty()) {
                throw new QueryException("filter expects an expression such as "
                        + "filter(relation.equals('directedBy') || xrelation.equals('directed'))");
            }
            // several arguments are treated as OR alternatives
            return GremlinSteps.filter(String.join(" || ", args));
        });
        register("values", args -> {
            if (args.isEmpty()) {
                throw new QueryException("values expects at least one property key");
            }
            return GremlinSteps.values(args);
        });
        register("createdBetween", args -> {
            if (args.size() < 2 || args.size() > 3) {
                throw new QueryException("createdBetween expects createdBetween(from, to), got "
                        + args.size() + " arguments");
            }
            // the bounds are read when the step runs, not when the query is compiled: a compiled query
            // is cached and reused, and a relative bound such as -2h must count back from that moment
            List<String> bounds = List.copyOf(args);
            return (ctx, in) -> {
                try {
                    TimeRange range = TimeRange.parse(bounds.get(0), bounds.get(1),
                            System.currentTimeMillis());
                    ctx.edgeTimeRange(range, bounds.size() > 2 ? positiveInt(bounds.get(2)) : 0);
                } catch (IllegalArgumentException e) {
                    throw new QueryException(e.getMessage(), e);
                }
                return in;
            };
        });
        register("elementMap", args -> GremlinSteps.elementMap());
        register("map", args -> GremlinSteps.map(single(args, "map")));
        // the terminal steps a client ends a traversal with: the answer is what the engine already
        // returns, or the same answer collected differently
        register("toList", args -> {
            requireNoArgs(args, "toList");
            return GremlinSteps.toList();
        });
        register("toIterator", args -> {
            requireNoArgs(args, "toIterator");
            return GremlinSteps.toList();
        });
        register("toSet", args -> {
            requireNoArgs(args, "toSet");
            return GremlinSteps.toSet();
        });
        register("toBulkSet", args -> {
            requireNoArgs(args, "toBulkSet");
            return GremlinSteps.toBulkSet();
        });
        register("next", args -> GremlinSteps.next(
                args.isEmpty() ? 1 : positiveInt(single(args, "next"))));
        register("iterate", args -> {
            requireNoArgs(args, "iterate");
            return GremlinSteps.nothing();
        });
        register("none", args -> {
            requireNoArgs(args, "none");
            return GremlinSteps.nothing();
        });
        register("discard", args -> {
            requireNoArgs(args, "discard");
            return GremlinSteps.nothing();
        });
        register("count", args -> GremlinSteps.count());
        // size() is the Groovy spelling of the same reduction, so `toList().size()` works
        register("size", args -> GremlinSteps.count());
        register("limit", args -> GremlinSteps.limit(parseLong(single(args, "limit"))));
        register("dedup", args -> GremlinSteps.dedup(args.isEmpty() ? null : single(args, "dedup")));
        // by() is a modulator: the compiler folds it into the step before it (order/groupCount/dedup/path),
        // so this registration is only reached when it stood somewhere it cannot attach to
        register("by", args -> {
            throw new QueryException("by(...) must follow order(...), groupCount(...), dedup() or path()");
        });
        // the map/filter/reduce shapes a TinkerPop client writes (see GremlinSteps)
        register("identity", args -> {
            requireNoArgs(args, "identity");
            return GremlinSteps.identity();
        });
        register("constant", args -> GremlinSteps.constant(single(args, "constant")));
        register("id", args -> {
            requireNoArgs(args, "id");
            return GremlinSteps.id();
        });
        register("label", args -> {
            requireNoArgs(args, "label");
            return GremlinSteps.label();
        });
        register("hasId", args -> GremlinSteps.hasId(requireArgs(args, "hasId")));
        register("hasLabel", args -> GremlinSteps.hasLabel(requireArgs(args, "hasLabel")));
        // the labelled steps: they only run on the stateful path (GremlinGraphQuery.runTraverserCompiled),
        // which the compiler chooses when one of them is in the chain. Registered so the compiler can
        // resolve them, and an error if a list-path run ever reaches one.
        GremlinStep stateful = (ctx, in) -> {
            throw new QueryException("as()/select()/project() need the stateful path; this run did not"
                    + " choose it (see TRAVERSER-DESIGN.md)");
        };
        register("as", args -> {
            requireArgs(args, "as");
            return stateful;
        });
        register("select", args -> stateful);
        // the side-effect steps: containers, the sack and cap need the stateful path too
        register("withSack", args -> stateful);
        register("sack", args -> stateful);
        // aggregate/store/cap work on the context, so both paths can use them (and a child traversal
        // collects into the same container as its parent)
        register("aggregate", args -> GremlinSteps.aggregate(single(args, "aggregate")));
        register("store", args -> GremlinSteps.aggregate(single(args, "store")));
        register("cap", args -> GremlinSteps.cap(single(args, "cap")));
        register("sideEffect", args -> stateful);
        register("project", args -> {
            requireArgs(args, "project");
            return stateful;
        });
        register("properties", args -> {
            requireNoArgs(args, "properties");
            return GremlinSteps.properties();
        });
        // profile() and explain() are terminal: the runner answers with what the traversal did (or would
        // do), so as steps they are pass-through and cost nothing when nobody asks for them
        register("profile", args -> {
            requireNoArgs(args, "profile");
            return GremlinSteps.identity();
        });
        register("explain", args -> {
            requireNoArgs(args, "explain");
            return GremlinSteps.identity();
        });
        register("key", args -> {
            requireNoArgs(args, "key");
            return GremlinSteps.propertyKey();
        });
        // TinkerPop writes value() without arguments for a property's value; values(keys) is the
        // projection step and keeps its meaning
        register("value", args -> {
            requireNoArgs(args, "value");
            return GremlinSteps.propertyValue();
        });
        register("math", args -> GremlinSteps.math(single(args, "math")));
        register("format", args -> GremlinSteps.format(single(args, "format")));
        register("dateTime", args -> {
            requireNoArgs(args, "dateTime");
            return GremlinSteps.dateTime();
        });
        register("dateAdd", args -> {
            if (args.size() != 2) {
                throw new QueryException("dateAdd expects dateAdd(unit, amount), got " + args.size()
                        + " arguments");
            }
            long amount;
            try {
                amount = Long.parseLong(args.get(1).trim());
            } catch (NumberFormatException e) {
                throw new QueryException("dateAdd expects a whole number of units, got: " + args.get(1));
            }
            return GremlinSteps.dateAdd(args.get(0), amount);
        });
        register("dateDiff", args -> {
            if (args.size() != 2) {
                throw new QueryException("dateDiff expects dateDiff(unit, other), got " + args.size()
                        + " arguments");
            }
            return GremlinSteps.dateDiff(args.get(0), args.get(1));
        });
        register("hasNot", args -> GremlinSteps.hasNot(single(args, "hasNot")));
        register("hasKey", args -> GremlinSteps.hasKey(requireArgs(args, "hasKey")));
        register("hasValue", args -> GremlinSteps.hasValue(requireArgs(args, "hasValue")));
        register("is", args -> GremlinSteps.is(single(args, "is")));
        register("range", args -> {
            if (args.size() != 2) {
                throw new QueryException("range expects range(low, high), got " + args.size());
            }
            return GremlinSteps.range(parseLong(args.get(0)), parseLong(args.get(1)));
        });
        register("skip", args -> GremlinSteps.range(parseLong(single(args, "skip")), -1));
        register("tail", args -> GremlinSteps.tail(args.isEmpty() ? 1 : positiveInt(single(args, "tail"))));
        register("sample", args -> GremlinSteps.sample(positiveInt(single(args, "sample"))));
        register("fold", args -> {
            requireNoArgs(args, "fold");
            return GremlinSteps.fold();
        });
        register("unfold", args -> {
            requireNoArgs(args, "unfold");
            return GremlinSteps.unfold();
        });
        register("sum", args -> {
            requireNoArgs(args, "sum");
            return GremlinSteps.sum();
        });
        register("mean", args -> {
            requireNoArgs(args, "mean");
            return GremlinSteps.mean();
        });
        register("max", args -> {
            requireNoArgs(args, "max");
            return GremlinSteps.max();
        });
        register("min", args -> {
            requireNoArgs(args, "min");
            return GremlinSteps.min();
        });
        register("order", args -> {
            if (args.size() > 2) {
                throw new QueryException("order expects order([key[, asc|desc]]), got " + args.size());
            }
            boolean descending = args.size() > 1
                    && (args.get(1).equalsIgnoreCase("desc") || args.get(1).equalsIgnoreCase("descending"));
            return GremlinSteps.order(args.isEmpty() ? null : args.get(0), descending);
        });
        register("groupCount", args -> GremlinSteps.groupCount(args.isEmpty() ? null
                : single(args, "groupCount")));
        register("valueMap", args -> GremlinSteps.valueMap(args));
        register("toLower", args -> {
            requireNoArgs(args, "toLower");
            return GremlinSteps.toLower();
        });
        register("toUpper", args -> {
            requireNoArgs(args, "toUpper");
            return GremlinSteps.toUpper();
        });
        register("trim", args -> {
            requireNoArgs(args, "trim");
            return GremlinSteps.trimString();
        });
        register("length", args -> {
            requireNoArgs(args, "length");
            return GremlinSteps.length();
        });
        register("asString", args -> {
            requireNoArgs(args, "asString");
            return GremlinSteps.asString();
        });
        register("asNumber", args -> {
            requireNoArgs(args, "asNumber");
            return GremlinSteps.asNumber();
        });
        register("asBool", args -> {
            requireNoArgs(args, "asBool");
            return GremlinSteps.asBool();
        });
        register("asDate", args -> {
            requireNoArgs(args, "asDate");
            return GremlinSteps.asDate();
        });
        register("concat", args -> GremlinSteps.concat(single(args, "concat")));
        register("lTrim", args -> {
            requireNoArgs(args, "lTrim");
            return GremlinSteps.lTrim();
        });
        register("rTrim", args -> {
            requireNoArgs(args, "rTrim");
            return GremlinSteps.rTrim();
        });
        register("replace", args -> {
            if (args.size() != 2) {
                throw new QueryException("replace expects replace(from, to), got " + args.size());
            }
            return GremlinSteps.replace(args.get(0), args.get(1));
        });
        register("split", args -> GremlinSteps.split(single(args, "split")));
        register("substring", args -> {
            if (args.isEmpty() || args.size() > 2) {
                throw new QueryException("substring expects substring(start[, end]), got " + args.size());
            }
            int start = (int) parseLong(args.get(0));
            int end = args.size() > 1 ? (int) parseLong(args.get(1)) : -1;
            return GremlinSteps.substring(start, end);
        });
        register("combine", args -> GremlinSteps.combine(single(args, "combine")));
        register("intersect", args -> GremlinSteps.intersect(single(args, "intersect")));
        register("difference", args -> GremlinSteps.difference(single(args, "difference")));
        register("disjunct", args -> GremlinSteps.disjunct(single(args, "disjunct")));
        register("all", args -> GremlinSteps.allOf(single(args, "all")));
        register("any", args -> GremlinSteps.anyOf(single(args, "any")));
        register("none", args -> {
            // none() is the terminal that emits nothing; none(p) is the list predicate
            if (args.isEmpty()) {
                return GremlinSteps.nothing();
            }
            return GremlinSteps.noneOf(single(args, "none"));
        });
        register("coin", args -> {
            if (args.size() != 1) {
                throw new QueryException("coin expects coin(probability), got " + args.size());
            }
            try {
                return GremlinSteps.coin(Double.parseDouble(args.get(0)));
            } catch (NumberFormatException e) {
                throw new QueryException("coin expects a number between 0 and 1, got: " + args.get(0));
            }
        });
        register("inject", args -> {
            if (args.isEmpty()) {
                throw new QueryException("inject expects at least one value");
            }
            return GremlinSteps.inject(args);
        });
        register("timeLimit", args -> {
            parseLong(single(args, "timeLimit"));
            return GremlinSteps.timeLimit();
        });
        // these two are walked by the path execution; reaching them means there was no path()
        register("simplePath", args -> {
            requireNoArgs(args, "simplePath");
            return GremlinSteps.needsPath("simplePath");
        });
        register("cyclicPath", args -> {
            requireNoArgs(args, "cyclicPath");
            return GremlinSteps.needsPath("cyclicPath");
        });
        register("times", args -> {
            throw new QueryException("times(n) must follow repeat(child), e.g. repeat(out('Payment')).times(2)");
        });
        // path() is executed by the path walk (GremlinGraphQuery#runPathCompiled); this is the resolved
        // step the compiler validates, so the name is known like every other one
        register("path", args -> {
            // path() carries the walk; path(key) (or path().by(key)) carries the key's values instead
            if (args.size() > 1) {
                throw new QueryException("path expects path() or path(key), got " + args.size());
            }
            return GremlinSteps.path();
        });
        register("pagerank", args -> GremlinAlgorithms.pagerank());
        register("louvain", args -> GremlinAlgorithms.louvain());
        register("cc", args -> GremlinAlgorithms.cc());
    }

    /** Hop steps accept zero args (all edge classes) or one or several (a list of classes). */
    private static List<String> edgeNames(List<String> args) {
        return args.isEmpty() ? null : List.copyOf(args);
    }

    private static String single(List<String> args, String stepName) {
        if (args.size() != 1) {
            throw new QueryException(stepName + " expects exactly 1 argument, got " + args.size());
        }
        return args.get(0);
    }

    /** A step that needs at least one argument. */
    private static List<String> requireArgs(List<String> args, String stepName) {
        if (args.isEmpty()) {
            throw new QueryException(stepName + " expects at least 1 argument");
        }
        return List.copyOf(args);
    }

    /** A terminal step that takes no arguments. */
    private static void requireNoArgs(List<String> args, String stepName) {
        if (!args.isEmpty()) {
            throw new QueryException(stepName + " expects no arguments, got " + args.size());
        }
    }

    /** Hop steps accept zero args (all edge classes) or exactly one (a single edge class). */
    private static String optionalSingle(List<String> args, String stepName) {
        return args.isEmpty() ? null : single(args, stepName);
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new QueryException("limit expects a number, got: " + value);
        }
    }

    private static int positiveInt(String value) {
        try {
            int n = Integer.parseInt(value);
            if (n < 1) {
                throw new QueryException("batch size must be >= 1, got: " + value);
            }
            return n;
        } catch (NumberFormatException e) {
            throw new QueryException("batch expects a number, got: " + value);
        }
    }
}
