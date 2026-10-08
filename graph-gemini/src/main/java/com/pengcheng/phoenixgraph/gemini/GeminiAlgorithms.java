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
 * algorithm is known to prefer it). {@link GeminiComputation#preferredMode()} is how an algorithm says
 * so: the betweenness pass, whose frontier is a level, names the push mode and has no pull half.
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
    /**
     * The centralities: how important a vertex is - by how many edges touch it, by how close the rest
     * of the graph is to it, by how many shortest paths go through it, by how much influence arrives.
     *
     * <p>{@link #CLOSENESS_CENTRALITY} has a name here but no computation of its own: the launcher
     * assembles it from the cluster's breadth-first runs (one root per source, see
     * {@link GremlinDistAlgorithms} in the query layer), because a closeness score is a sum over
     * destinations that no single vertex's superstep can see.
     */
    public static final String DEGREE_CENTRALITY = "degree";
    public static final String CLOSENESS_CENTRALITY = "closeness";
    public static final String BETWEENNESS_CENTRALITY = "betweenness";
    public static final String EIGENVECTOR_CENTRALITY = "eigenvector";

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
            case SSSP -> sssp(intParam(settings, "root", 0), stringParam(settings, "weightProperty"),
                    intParam(settings, "vertices", 0));
            case BFS -> bfs(intParam(settings, "root", 0), intParam(settings, "vertices", 0));
            case DEGREE_CENTRALITY -> degreeCentrality();
            case EIGENVECTOR_CENTRALITY -> eigenvectorCentrality(intParam(settings, "iterations", 30));
            case BETWEENNESS_CENTRALITY -> betweennessCentrality(intParam(settings, "root", 0),
                    intParam(settings, "vertices", 1));
            case CLOSENESS_CENTRALITY -> throw new IllegalArgumentException(CLOSENESS_CENTRALITY
                    + " is assembled by the launcher from the cluster's breadth-first runs; it is not a"
                    + " single computation");
            default -> throw new IllegalArgumentException("Unknown Gemini algorithm '" + name
                    + "'; known: " + String.join(", ", PAGERANK, LOUVAIN, CONNECTED_COMPONENTS, SSSP, BFS,
                    DEGREE_CENTRALITY, EIGENVECTOR_CENTRALITY, BETWEENNESS_CENTRALITY));
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
     * slot keeps the minimum. The vertex count (when the launcher knows it) raises the superstep limit,
     * so a path graph longer than {@link GeminiComputation#maxSupersteps()}'s default still finishes.
     */
    public static GeminiComputation sssp(int root, String weightProperty, int vertexCount) {
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

            @Override
            public int maxSupersteps() {
                return Math.max(200, vertexCount + 2);
            }
        };
    }

    // ------------------------------------------------------------------ BFS

    /**
     * Breadth first search: the frontier is the active set, and the depth is the value. The vertex
     * count (when the launcher knows it) raises the superstep limit, so a path longer than the default
     * still reaches its end.
     */
    public static GeminiComputation bfs(int root, int vertexCount) {
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

            @Override
            public int maxSupersteps() {
                return Math.max(200, vertexCount + 2);
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

    // ------------------------------------------------------------------ degree centrality

    /**
     * Degree centrality: the number of edges the graph hands a vertex - its out-edges when the run is
     * directed, every incident edge when it is not (the launcher reads both directions then).
     *
     * <p>The degree is already in the layout (a partition holds the out-degrees of the whole graph), so
     * this run is a distributed read rather than a message round: every worker reports its own chunk's
     * degrees and the run is over at once. It is still a cluster run - which is what {@code degree(DIST)}
     * promises - and the figure is the one the in-memory step counts, because both count the edge
     * records the traversal's graph is made of.
     */
    public static GeminiComputation degreeCentrality() {
        return new GeminiComputation() {

            @Override
            public String name() {
                return DEGREE_CENTRALITY;
            }

            @Override
            public Object initialValue(int vertex, int outDegree, int inDegree) {
                return (long) outDegree;
            }

            @Override
            public boolean startsActive(int vertex, int outDegree, int inDegree) {
                return false; // no frontier, no messages: the degree is already known where it lives
            }

            @Override
            public Object sparseSignal(int vertex, Object value, WorkerState state) {
                return null;
            }

            @Override
            public int sparseSlot(int vertex, Object message, List<GeminiGraph.Edge> edges, WorkerState state) {
                return 0;
            }

            @Override
            public Object denseSignal(int vertex, List<GeminiGraph.Edge> inEdges, WorkerState state) {
                return null;
            }

            @Override
            public int denseSlot(int vertex, Object message, WorkerState state) {
                return 0;
            }

            @Override
            public boolean converged(int activated, int superstep) {
                return true;
            }

            @Override
            public int maxSupersteps() {
                return 1;
            }
        };
    }

    // ------------------------------------------------------------------ eigenvector centrality

    /**
     * Eigenvector centrality by power iteration: a vertex's score is the weighted sum of the scores of
     * the vertices that point at it, so the vector converges to the principal eigenvector of the
     * adjacency matrix.
     *
     * <p>Both halves are written: the push sends the score along the out-edges and adds it to the
     * destination, the pull reads the sources this partition owns and sums their scores - the same sum
     * either way, which is what makes the mode the driver picks a question of speed alone.
     *
     * <p>The vector is normalised every superstep by its L2 norm: without it the values grow by the
     * dominant eigenvalue each round ({@code λ^iterations} overflows a double long before a large graph
     * settles). The norm is a sum over every worker's chunk, so it travels the driver's aggregate/share
     * path - the reduce the paper puts between supersteps. What comes back is the vector up to a
     * constant factor; the launcher normalises the values it collected, which is also where the sign is
     * fixed, so a distributed run and an in-memory one answer the same thing.
     */
    public static GeminiComputation eigenvectorCentrality(int iterations) {
        return new GeminiComputation() {

            private double norm = 1.0;

            @Override
            public String name() {
                return EIGENVECTOR_CENTRALITY;
            }

            @Override
            public boolean accumulative() {
                // the new score of a vertex is the influence that arrived this superstep, and a vertex
                // nothing points at has none
                return true;
            }

            @Override
            public Object initialValue(int vertex, int outDegree, int inDegree) {
                return 1.0; // the all-ones vector: every vertex starts equally important
            }

            @Override
            public Map<Object, Double> aggregate(WorkerState state) {
                // the sum of squares of this worker's chunk; the driver adds the parts up
                double squares = 0;
                for (int v = 0; v < state.vertexCount(); v++) {
                    Object value = state.owns(v) ? state.value(v) : null;
                    if (value instanceof Number number) {
                        squares += number.doubleValue() * number.doubleValue();
                    }
                }
                return Map.of("sum:squares", squares);
            }

            @Override
            public void share(Map<String, Object> shared) {
                double squares = asDouble(shared.getOrDefault("sum:squares", 0.0));
                if (squares > 0) {
                    norm = Math.sqrt(squares);
                }
            }

            @Override
            public Object sparseSignal(int vertex, Object value, WorkerState state) {
                return asDouble(value) / norm; // the share of influence, not the raw score
            }

            @Override
            public int sparseSlot(int vertex, Object message, List<GeminiGraph.Edge> edges, WorkerState state) {
                double contribution = asDouble(message);
                int activated = 0;
                for (GeminiGraph.Edge edge : edges) {
                    state.accumulate(edge.target(), contribution * edge.weight());
                    state.activate(edge.target());
                    activated++;
                }
                return activated;
            }

            @Override
            public Object denseSignal(int vertex, List<GeminiGraph.Edge> inEdges, WorkerState state) {
                double sum = 0;
                for (GeminiGraph.Edge edge : inEdges) {
                    Object source = state.value(edge.source());
                    if (source != null) {
                        sum += asDouble(source) / norm * edge.weight();
                    }
                }
                return sum;
            }

            @Override
            public int denseSlot(int vertex, Object message, WorkerState state) {
                state.accumulate(vertex, asDouble(message));
                state.activate(vertex);
                return 1;
            }

            @Override
            public boolean converged(int activated, int superstep) {
                // a fixed number of iterations is this algorithm's stopping condition, like PageRank's
                return superstep + 1 >= iterations;
            }

            @Override
            public int maxSupersteps() {
                return iterations;
            }

            @Override
            public Object result(int vertex, Object value, WorkerState state) {
                return value == null ? 0.0 : value;
            }
        };
    }

    // ------------------------------------------------------------------ betweenness centrality

    /**
     * Brandes' betweenness centrality for one source: the shortest-path counts of a forward
     * breadth-first pass, then the dependency pass that walks back down the levels.
     *
     * <p>Two passes in one run: both walk the same graph, and the value of a vertex carries what both
     * need - {@code [distance, sigma, delta]}, the hops from the source, the number of shortest paths
     * that reach it, and the dependency it carries. The push-only mode is forced because the frontier
     * of both passes <em>is</em> a level, and the level is what the push filters on.
     *
     * <p>The clock is the superstep number, which the driver shares with every worker:
     * <ul>
     *   <li><b>forward</b> - a vertex whose distance is the current round pushes its sigma, and a
     *       destination one level out accumulates it. A vertex discovered this round takes the message as
     *       its sigma, one already at that distance adds it, so two predecessors at the same level (or
     *       parallel edges) count every shortest path;</li>
     *   <li><b>backward</b> - the round in which nothing was discovered is the end of the forward pass,
     *       and the largest distance is then known from the clock (the vertices discovered last stand at
     *       level {@code round-1}). A vertex pushes {@code (1+delta)/sigma} when the clock counts down to
     *       its level, and a predecessor one level below adds {@code sigma * message} to its delta - which
     *       is Brandes' {@code delta[v] += sigma[v]/sigma[w] * (1 + delta[w])}, with the division done by
     *       the sender because the receiver's sigma is its own.</li>
     * </ul>
     * The pass stops with the push from level 2 into level 1: a push from level 1 would only reach the
     * source, whose delta is never part of the answer, so it stays zero.
     *
     * <p>One source per run. The launcher runs this per source (or per sampled source) and adds the
     * deltas up; a multi-source form would have to keep a distance map per vertex, which is the whole
     * distance matrix.
     */
    public static GeminiComputation betweennessCentrality(int root, int vertexCount) {
        return new BetweennessComputation(root, vertexCount);
    }

    /** The two passes of Brandes' algorithm over one source, as one run. */
    static final class BetweennessComputation implements GeminiComputation {

        private final int root;
        private final int vertexCount;
        /** The round about to run, from the shared clock. */
        private int round;
        /** How many vertices this worker discovered in the round that just finished. */
        private int discovered;
        private boolean backward;
        /** The largest distance the forward pass reached, known when it ends. */
        private int maxLevel;

        BetweennessComputation(int root, int vertexCount) {
            this.root = root;
            this.vertexCount = Math.max(1, vertexCount);
        }

        @Override
        public String name() {
            return BETWEENNESS_CENTRALITY;
        }

        @Override
        public Mode preferredMode() {
            return Mode.SPARSE;
        }

        @Override
        public Object initialValue(int vertex, int outDegree, int inDegree) {
            return vertex == root ? new double[]{0, 1, 0} : new double[]{-1, 0, 0};
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
        public Map<Object, Double> aggregate() {
            // how much of the graph this worker discovered in the last round: the driver adds the parts
            // up, and the sum tells every worker whether the forward pass is over
            return Map.of("sum:discovered", (double) discovered);
        }

        @Override
        public void share(Map<String, Object> shared) {
            round = asInt(shared.get("superstep"), round);
            double found = asDouble(shared.getOrDefault("sum:discovered", 0.0));
            discovered = 0;
            if (!backward && round > 0 && found == 0) {
                backward = true;
                maxLevel = round - 1;
            }
        }

        @Override
        public Object sparseSignal(int vertex, Object value, WorkerState state) {
            double[] triple = asTriple(value);
            if (!backward) {
                return triple[0] == round ? triple[1] : null;
            }
            int level = 2 * maxLevel + 1 - round;
            if (level <= 1 || triple[0] != level || triple[1] <= 0) {
                return null;
            }
            return (1 + triple[2]) / triple[1];
        }

        @Override
        public int sparseSlot(int vertex, Object message, List<GeminiGraph.Edge> edges, WorkerState state) {
            int activated = 0;
            double sent = asDouble(message);
            for (GeminiGraph.Edge edge : edges) {
                double[] target = asTriple(state.value(edge.target()));
                if (!backward) {
                    int level = round + 1;
                    if (target[0] < 0) {
                        target[0] = level;
                        target[1] = sent;
                        state.activate(edge.target()); // the new level is the next frontier
                        activated++;
                        discovered++;
                    } else if (target[0] == level) {
                        target[1] += sent;
                    }
                } else {
                    int level = 2 * maxLevel + 1 - round;
                    if (target[0] == level - 1) {
                        target[2] += sent * target[1];
                        activated++;
                    }
                }
            }
            return activated;
        }

        @Override
        public Object denseSignal(int vertex, List<GeminiGraph.Edge> inEdges, WorkerState state) {
            throw new IllegalStateException(name() + " runs push-only; the launcher must honour"
                    + " preferredMode()");
        }

        @Override
        public int denseSlot(int vertex, Object message, WorkerState state) {
            throw new IllegalStateException(name() + " runs push-only; the launcher must honour"
                    + " preferredMode()");
        }

        @Override
        public boolean converged(int activated, int superstep) {
            if (!backward) {
                if (activated > 0) {
                    return false;
                }
                backward = true;
                maxLevel = superstep; // the frontier that found nothing stood at this level
            }
            // the dependency pass ends with the round that pushes level 2 into level 1
            return superstep >= 2 * maxLevel - 1;
        }

        @Override
        public int maxSupersteps() {
            return 2 * vertexCount + 4; // both passes are bounded by the number of vertices
        }

        @Override
        public Object result(int vertex, Object value, WorkerState state) {
            return asTriple(value)[2];
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

    /** A value or a message read as an int, or the fallback when it is missing. */
    private static int asInt(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    /** The {@code [distance, sigma, delta]} a betweenness run keeps per vertex. */
    private static double[] asTriple(Object value) {
        if (value instanceof double[] triple) {
            return triple;
        }
        throw new IllegalStateException("A betweenness value is not a triple: " + value);
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
