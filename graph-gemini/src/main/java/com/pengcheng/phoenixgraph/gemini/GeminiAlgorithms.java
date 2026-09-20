package com.pengcheng.phoenixgraph.gemini;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The algorithms the cluster can run, written against Gemini's signal/slot API the way the
 * reference toolkits are: {@code sparseSignal}/{@code sparseSlot} for the push mode,
 * {@code denseSignal}/{@code denseSlot} for the pull mode, and an optional aggregate the driver
 * adds up.
 *
 * <p>The mode is not chosen here: the worker picks it per superstep from the number of active edges
 * ({@code sparse = activeEdges < |E| / 20}), so each algorithm supplies both halves unless one of
 * them is meaningless. PageRank is written the way the reference implementation is - the sparse slot
 * adds a pushed score into the neighbour, the dense signal adds up the incoming scores - while Louvain
 * is push-only, because its update accumulates a weight per community at the destination and that
 * accumulation is exactly what a local push does (the paper allows supplying one mode when the
 * algorithm is known to prefer it).
 */
public final class GeminiAlgorithms {

    private GeminiAlgorithms() {
    }

    /** The names the Gremlin steps and the launcher use. */
    public static final String PAGERANK = "pagerank";
    public static final String LOUVAIN = "louvain";
    public static final String CONNECTED_COMPONENTS = "cc";
    public static final String SSSP = "sssp";
    public static final String BFS = "bfs";

    /** Builds a computation by name; unknown names are a caller error, not a silent default. */
    public static GeminiComputation of(String name, Map<String, Object> params) {
        Map<String, Object> settings = params == null ? Map.of() : params;
        return switch (name == null ? "" : name.toLowerCase()) {
            case PAGERANK -> pagerank(intParam(settings, "iterations", 20),
                    doubleParam(settings, "damping", 0.85), intParam(settings, "vertices", 1));
            case LOUVAIN -> new LouvainComputation(intParam(settings, "iterations", 20),
                    doubleParam(settings, "resolution", 1.0),
                    doubleParam(settings, "totalWeight", 1.0));
            case CONNECTED_COMPONENTS -> connectedComponents();
            case SSSP -> sssp(intParam(settings, "root", 0), stringParam(settings, "weightProperty"));
            case BFS -> bfs(intParam(settings, "root", 0));
            default -> throw new IllegalArgumentException("Unknown Gemini algorithm '" + name
                    + "'; known: " + String.join(", ", PAGERANK, LOUVAIN, CONNECTED_COMPONENTS, SSSP, BFS));
        };
    }

    // ------------------------------------------------------------------ PageRank

    /**
     * PageRank in the reference formulation: {@code next[v]} is the sum of the incoming scores, each
     * divided by the out-degree of the vertex it came from - the shares, not the scores, are what
     * travels - and the answer is {@code (1 - d) / |V| + d * next[v]}.
     *
     * <p>Both halves of the division matter. The signal carries {@code value / outDegree}, because a
     * partition holds only the out-edges of a vertex whose destinations are its own, so it cannot count
     * the degree itself; and the value of a vertex is what arrived in the current superstep alone
     * ({@link GeminiComputation#accumulative()}), because a vertex nothing points at receives nothing.
     *
     * <p>A vertex with no out-edges contributes nothing back, so its mass is not redistributed (the
     * in-memory step spreads it, and on a graph where every vertex has an out-edge the two agree).
     */
    public static GeminiComputation pagerank(int iterations, double damping, int vertexCount) {
        int total = Math.max(1, vertexCount);
        return new GeminiComputation() {

            @Override
            public String name() {
                return PAGERANK;
            }

            @Override
            public boolean accumulative() {
                return true;
            }

            @Override
            public Object initialValue(int vertex, int outDegree, int inDegree) {
                return 1.0 / total; // every vertex starts with an equal share of the mass
            }

            @Override
            public Object sparseSignal(int vertex, Object value, WorkerState state) {
                double share = asDouble(value);
                int degree = state.outDegree(vertex);
                return degree <= 0 ? null : share / degree;
            }

            @Override
            public int sparseSlot(int vertex, Object message, List<GeminiGraph.Edge> edges, WorkerState state) {
                double contribution = asDouble(message);
                int activated = 0;
                for (GeminiGraph.Edge edge : edges) {
                    // a destination is owned here (the push lands where the out-edge is stored), and
                    // the contribution goes to the pending buffer, never on top of the live value
                    double weighted = contribution * edge.weight();
                    if (weighted != 0.0) {
                        state.accumulate(edge.target(), weighted);
                        state.activate(edge.target());
                        activated++;
                    }
                }
                return activated;
            }

            @Override
            public Object denseSignal(int vertex, List<GeminiGraph.Edge> inEdges, WorkerState state) {
                double sum = 0;
                for (GeminiGraph.Edge edge : inEdges) {
                    Object source = state.value(edge.source());
                    if (source == null) {
                        continue; // a source in another chunk: its owner pushes that share instead
                    }
                    int degree = state.outDegree(edge.source());
                    if (degree > 0) {
                        sum += asDouble(source) / degree * edge.weight();
                    }
                }
                return sum;
            }

            @Override
            public int denseSlot(int vertex, Object message, WorkerState state) {
                double sum = asDouble(message);
                if (sum == 0.0) {
                    return 0;
                }
                state.accumulate(vertex, sum);
                state.activate(vertex);
                return 1;
            }

            @Override
            public Map<Object, Double> aggregate() {
                // nothing to add up across workers: the score is the value, normalised at the end
                return Map.of("iterations", 1.0);
            }

            @Override
            public boolean converged(int activated, int superstep) {
                // a fixed number of iterations is this algorithm's stopping condition
                return superstep + 1 >= iterations;
            }

            @Override
            public int maxSupersteps() {
                return iterations;
            }

            @Override
            public Object result(int vertex, Object value, WorkerState state) {
                double sum = value == null ? 0.0 : asDouble(value);
                return (1 - damping) / total + damping * sum;
            }
        };
    }

    // ------------------------------------------------------------------ Connected components

    /**
     * The label propagation of the paper's Figure 4: a vertex broadcasts its label (sparse), a
     * destination keeps the smallest label it sees (both modes), and a vertex is active while its
     * label got smaller.
     */
    public static GeminiComputation connectedComponents() {
        return new GeminiComputation() {

            @Override
            public String name() {
                return CONNECTED_COMPONENTS;
            }

            @Override
            public Object initialValue(int vertex, int outDegree, int inDegree) {
                return (long) vertex;
            }

            @Override
            public Object sparseSignal(int vertex, Object value, WorkerState state) {
                return value;
            }

            @Override
            public int sparseSlot(int vertex, Object message, List<GeminiGraph.Edge> edges, WorkerState state) {
                long label = asLong(message);
                int activated = 0;
                for (GeminiGraph.Edge edge : edges) {
                    long current = asLong(state.value(edge.target()));
                    if (label < current) {
                        state.setValue(edge.target(), label);
                        state.activate(edge.target());
                        activated++;
                    }
                }
                return activated;
            }

            @Override
            public Object denseSignal(int vertex, List<GeminiGraph.Edge> inEdges, WorkerState state) {
                long smallest = Long.MAX_VALUE;
                for (GeminiGraph.Edge edge : inEdges) {
                    long source = asLong(state.value(edge.source()));
                    if (source < smallest) {
                        smallest = source;
                    }
                }
                return smallest == Long.MAX_VALUE ? null : smallest;
            }

            @Override
            public int denseSlot(int vertex, Object message, WorkerState state) {
                long label = asLong(message);
                long current = asLong(state.value(vertex));
                if (label < current) {
                    state.setValue(vertex, label);
                    state.activate(vertex);
                    return 1;
                }
                return 0;
            }
        };
    }

    // ------------------------------------------------------------------ SSSP

    /**
     * Single source shortest paths, unweighted unless a weight property was given: the sparse slot
     * relaxes every out-edge, the dense signal takes the smallest incoming relaxation, and the dense
     * slot keeps the minimum.
     */
    public static GeminiComputation sssp(int root, String weightProperty) {
        return new GeminiComputation() {

            @Override
            public String name() {
                return SSSP;
            }

            @Override
            public Object initialValue(int vertex, int outDegree, int inDegree) {
                return vertex == root ? 0.0 : Double.MAX_VALUE;
            }

            @Override
            public boolean startsActive(int vertex, int outDegree, int inDegree) {
                return vertex == root;
            }

            @Override
            public List<Integer> roots() {
                return List.of(root);
            }

            @Override
            public Object sparseSignal(int vertex, Object value, WorkerState state) {
                double distance = asDouble(value);
                return distance == Double.MAX_VALUE ? null : distance;
            }

            @Override
            public int sparseSlot(int vertex, Object message, List<GeminiGraph.Edge> edges, WorkerState state) {
                double distance = asDouble(message);
                int activated = 0;
                for (GeminiGraph.Edge edge : edges) {
                    double relaxed = distance + edge.weight();
                    double current = asDouble(state.value(edge.target()));
                    if (relaxed < current) {
                        state.setValue(edge.target(), relaxed);
                        state.activate(edge.target());
                        activated++;
                    }
                }
                return activated;
            }

            @Override
            public Object denseSignal(int vertex, List<GeminiGraph.Edge> inEdges, WorkerState state) {
                double smallest = Double.MAX_VALUE;
                for (GeminiGraph.Edge edge : inEdges) {
                    double source = asDouble(state.value(edge.source()));
                    if (source == Double.MAX_VALUE) {
                        continue;
                    }
                    double relaxed = source + edge.weight();
                    if (relaxed < smallest) {
                        smallest = relaxed;
                    }
                }
                return smallest == Double.MAX_VALUE ? null : smallest;
            }

            @Override
            public int denseSlot(int vertex, Object message, WorkerState state) {
                double relaxed = asDouble(message);
                if (relaxed < asDouble(state.value(vertex))) {
                    state.setValue(vertex, relaxed);
                    state.activate(vertex);
                    return 1;
                }
                return 0;
            }

            @Override
            public Object result(int vertex, Object value, WorkerState state) {
                return asDouble(value) == Double.MAX_VALUE ? -1.0 : value;
            }
        };
    }

    // ------------------------------------------------------------------ BFS

    /** Breadth first search: the frontier is the active set, and the depth is the value. */
    public static GeminiComputation bfs(int root) {
        return new GeminiComputation() {

            @Override
            public String name() {
                return BFS;
            }

            @Override
            public Object initialValue(int vertex, int outDegree, int inDegree) {
                return vertex == root ? 0L : -1L;
            }

            @Override
            public boolean startsActive(int vertex, int outDegree, int inDegree) {
                return vertex == root;
            }

            @Override
            public List<Integer> roots() {
                return List.of(root);
            }

            @Override
            public Object sparseSignal(int vertex, Object value, WorkerState state) {
                return value;
            }

            @Override
            public int sparseSlot(int vertex, Object message, List<GeminiGraph.Edge> edges, WorkerState state) {
                long depth = asLong(message) + 1;
                int activated = 0;
                for (GeminiGraph.Edge edge : edges) {
                    if (asLong(state.value(edge.target())) < 0) {
                        state.setValue(edge.target(), depth);
                        state.activate(edge.target());
                        activated++;
                    }
                }
                return activated;
            }

            @Override
            public Object denseSignal(int vertex, List<GeminiGraph.Edge> inEdges, WorkerState state) {
                // only the sources are read here: they are owned by this worker, while the
                // destination may be a mirror whose value lives somewhere else
                long smallest = Long.MAX_VALUE;
                for (GeminiGraph.Edge edge : inEdges) {
                    long source = asLong(state.value(edge.source()));
                    if (source >= 0 && source + 1 < smallest) {
                        smallest = source + 1;
                    }
                }
                return smallest == Long.MAX_VALUE ? null : smallest;
            }

            @Override
            public int denseSlot(int vertex, Object message, WorkerState state) {
                if (asLong(state.value(vertex)) < 0) {
                    state.setValue(vertex, message);
                    state.activate(vertex);
                    return 1;
                }
                return 0;
            }
        };
    }

    // ------------------------------------------------------------------ Louvain

    /**
     * Louvain's local moving phase: every vertex looks at the communities of its neighbours, moves to
     * the one with the best modularity gain, and the communities are renumbered until nothing moves.
     *
     * <p>Push-only: a vertex pushes its community to its neighbours, and a destination accumulates the
     * weight it receives per community (that accumulation is local to the worker owning the
     * destination, which is exactly what the sparse slot provides). Each superstep also needs the
     * total weight per community, which the driver collects through {@link #aggregate()} and hands
     * back before the next round.
     */
    public static GeminiComputation louvain(int iterations, double resolution) {
        return new LouvainComputation(iterations, resolution);
    }

    /** Louvain with the graph's total edge weight, which the modularity gain is relative to. */
    public static GeminiComputation louvain(int iterations, double resolution, double totalWeight) {
        return new LouvainComputation(iterations, resolution, totalWeight);
    }

    /** Kept as a named class: Louvain carries the most state of the five. */
    static final class LouvainComputation implements GeminiComputation {

        private final int maxIterations;
        private final double resolution;
        private final Map<Integer, Integer> community = new LinkedHashMap<>();
        private final Map<Integer, Double> degree = new LinkedHashMap<>();
        private final Map<Integer, Map<Integer, Double>> neighbourWeight = new LinkedHashMap<>();
        private Map<Integer, Double> communityDegree = Map.of();
        private double totalWeight = 1.0;
        private int moved;

        LouvainComputation(int maxIterations, double resolution) {
            this(maxIterations, resolution, 1.0);
        }

        LouvainComputation(int maxIterations, double resolution, double totalWeight) {
            this.maxIterations = maxIterations;
            this.resolution = resolution;
            this.totalWeight = totalWeight > 0 ? totalWeight : 1.0;
        }

        @Override
        public String name() {
            return LOUVAIN;
        }

        @Override
        public Object initialValue(int vertex, int outDegree, int inDegree) {
            community.put(vertex, vertex);
            degree.put(vertex, (double) (outDegree + inDegree));
            return (long) vertex;
        }

        /** Every vertex takes part every round: local moving looks at all of them. */
        @Override
        public boolean startsActive(int vertex, int outDegree, int inDegree) {
            return true;
        }

        @Override
        public Object sparseSignal(int vertex, Object value, WorkerState state) {
            // the community is what a neighbour needs, so it is what the push mode sends
            return (long) communityOf(vertex);
        }

        @Override
        public int sparseSlot(int vertex, Object message, List<GeminiGraph.Edge> edges, WorkerState state) {
            int communityId = (int) asLong(message);
            for (GeminiGraph.Edge edge : edges) {
                neighbourWeight.computeIfAbsent(edge.target(), k -> new LinkedHashMap<>())
                        .merge(communityId, edge.weight(), Double::sum);
            }
            // the decision waits for the whole neighbourhood: a vertex with in-edges from several
            // workers sees them as separate partials, and only the owner can compare them
            return 0;
        }

        @Override
        public Object denseSignal(int vertex, List<GeminiGraph.Edge> inEdges, WorkerState state) {
            Map<Integer, Double> partial = new LinkedHashMap<>();
            for (GeminiGraph.Edge edge : inEdges) {
                partial.merge(community.getOrDefault(edge.source(), edge.source()), edge.weight(), Double::sum);
            }
            return partial.isEmpty() ? null : partial;
        }

        @SuppressWarnings("unchecked")
        @Override
        public int denseSlot(int vertex, Object message, WorkerState state) {
            Map<Integer, Double> partial = (Map<Integer, Double>) message;
            for (Map.Entry<Integer, Double> entry : partial.entrySet()) {
                neighbourWeight.computeIfAbsent(vertex, k -> new LinkedHashMap<>())
                        .merge(entry.getKey(), entry.getValue(), Double::sum);
            }
            return decide(vertex);
        }

        /**
         * Moves a vertex to the community with the best modularity gain.
         *
         * <p>The local-moving rule: joining community c gains
         * {@code w(v,c) - resolution * k_v * sigma_tot(c) / 2m}, where w(v,c) is the weight from v to
         * c, k_v its degree and sigma_tot(c) the total degree of c - which no worker can know alone,
         * so the driver collects it through {@link #aggregate()} and hands it back in
         * {@link #share}. The vertex's own community is in the same comparison, so leaving it is
         * considered too.
         */
        private int decide(int vertex) {
            Map<Integer, Double> weights = neighbourWeight.remove(vertex);
            int current = community.getOrDefault(vertex, vertex);
            if (weights == null || weights.isEmpty()) {
                return 0;
            }
            double k = degree.getOrDefault(vertex, 0.0);
            double denominator = Math.max(2 * totalWeight, 1e-9);
            int best = current;
            double bestGain = weights.getOrDefault(current, 0.0)
                    - resolution * k * communityDegree.getOrDefault(current, 0.0) / denominator;
            // Only communities with a smaller id are joined. The original Louvain updates vertices
            // one after another, so the first of two vertices to move takes the other with it; a round
            // that updates everyone at once would instead let neighbours swap labels for ever (each
            // joins the other's community every round). Since a community is named after its smallest
            // member, the vertex that should merge is always the one with the larger id, so joining
            // downwards reaches the same communities - and the id only ever decreases, which is what
            // makes the round terminate instead of churn.
            List<Integer> candidates = new ArrayList<>();
            for (Integer candidate : weights.keySet()) {
                if (candidate <= current) {
                    candidates.add(candidate);
                }
            }
            candidates.sort(Integer::compareTo);
            for (int candidate : candidates) {
                double gain = weights.get(candidate)
                        - resolution * k * communityDegree.getOrDefault(candidate, 0.0) / denominator;
                if (gain > bestGain + 1e-12) {
                    bestGain = gain;
                    best = candidate;
                }
            }
            if (best != current) {
                community.put(vertex, best);
                moved++;
                return 1;
            }
            return 0;
        }

        @Override
        public void share(Map<String, Object> shared) {
            Map<Integer, Double> totals = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : shared.entrySet()) {
                if (entry.getKey().startsWith("community:") && entry.getValue() instanceof Number number) {
                    totals.put(Integer.parseInt(entry.getKey().substring("community:".length())),
                            number.doubleValue());
                } else if (entry.getKey().equals("totalWeight") && entry.getValue() instanceof Number number) {
                    totalWeight = number.doubleValue();
                }
            }
            communityDegree = totals;
            moved = 0;
        }

        @Override
        public Map<Object, Double> aggregate() {
            Map<Object, Double> out = new LinkedHashMap<>();
            for (Map.Entry<Integer, Integer> entry : community.entrySet()) {
                out.merge("community:" + entry.getValue(), degree.getOrDefault(entry.getKey(), 0.0),
                        Double::sum);
            }
            // the total weight travels with the totals, so a round trip through the driver keeps it
            out.put("totalWeight", totalWeight);
            return out;
        }

        @Override
        public int maxSupersteps() {
            return maxIterations;
        }

        @Override
        public Object result(int vertex, Object value, WorkerState state) {
            return (long) communityOf(vertex);
        }

        /** How many vertices moved in the last round (a test asserts on it, the driver logs it). */
        public int lastMoved() {
            return moved;
        }

        /** The community of a vertex, for a test. */
        int communityOf(int vertex) {
            return community.getOrDefault(vertex, vertex);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** A value or a message read as a double (the wire may hand either boxed type back). */
    private static double asDouble(Object value) {
        return ((Number) value).doubleValue();
    }

    /** A value or a message read as a long. */
    private static long asLong(Object value) {
        return ((Number) value).longValue();
    }

    private static int intParam(Map<String, Object> settings, String name, int fallback) {
        Object value = settings.get(name);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static double doubleParam(Map<String, Object> settings, String name, double fallback) {
        Object value = settings.get(name);
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    private static String stringParam(Map<String, Object> settings, String name) {
        Object value = settings.get(name);
        return value == null ? null : String.valueOf(value);
    }

    /** The weight and degrees a Louvain worker needs from the launcher (it reads them from storage). */
    public static Map<String, Object> louvainParams(double totalWeight, double[] degree) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("totalWeight", totalWeight);
        params.put("degree", degree);
        return params;
    }
}
