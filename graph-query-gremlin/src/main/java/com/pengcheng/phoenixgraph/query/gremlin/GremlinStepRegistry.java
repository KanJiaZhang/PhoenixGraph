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
        register("batch", args -> GremlinSteps.batch(positiveInt(single(args, "batch"))));
        register("barrier", args -> GremlinSteps.barrier(positiveInt(single(args, "barrier"))));
        register("has", args -> {
            if (args.size() == 1) {
                return GremlinSteps.has(args.get(0), null);
            }
            if (args.size() == 2) {
                return GremlinSteps.has(args.get(0), args.get(1));
            }
            throw new QueryException("has expects 1 or 2 arguments, got " + args.size());
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
            try {
                TimeRange range = TimeRange.parse(args.get(0), args.get(1));
                int cap = args.size() > 2 ? positiveInt(args.get(2)) : 0;
                return GremlinSteps.createdBetween(range, cap);
            } catch (IllegalArgumentException e) {
                throw new QueryException(e.getMessage(), e);
            }
        });
        register("elementMap", args -> GremlinSteps.elementMap());
        register("count", args -> GremlinSteps.count());
        register("limit", args -> GremlinSteps.limit(parseLong(single(args, "limit"))));
        register("dedup", args -> GremlinSteps.dedup());
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
