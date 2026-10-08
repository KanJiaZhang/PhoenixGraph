package com.pengcheng.phoenixgraph.query.gremlin;

import com.pengcheng.phoenixgraph.gemini.GeminiAlgorithms;
import com.pengcheng.phoenixgraph.gemini.GeminiCluster;
import com.pengcheng.phoenixgraph.gemini.GeminiGraph;
import com.pengcheng.phoenixgraph.query.QueryException;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code DIST} form of the algorithm steps: the same traversal, handed to a Gemini cluster
 * instead of being computed in this process.
 *
 * <p>{@code g.V(...).pagerank(DIST)} reads like the in-memory step and answers in the same shape (one
 * map of vertex id to value), but the graph it runs on is cut into contiguous chunks, one per worker,
 * and the supersteps run between them. The marker is deliberate: a query either asks for the
 * distributed implementation or it does not, and asking for it without a cluster configured is an
 * error rather than a silent fall back to one process - the whole point of the step is to say which
 * engine answers.
 *
 * <p>What the cluster computes is the induced subgraph over the vertices the traversal reached, the
 * same graph the in-memory steps use, and only the edge classes the traversal went through
 * ({@code g.E('payment').pagerank(DIST)} means the payment edges) - so the two engines can be
 * compared on one query.
 *
 * <p>Two centralities are not one run of the cluster but many: a closeness score sums over the whole
 * graph as seen from one vertex, and a betweenness score sums over every source. The launcher runs the
 * cluster once per source (or per sampled source) and adds the answers up here, which is why those two
 * steps say in their javadoc what one run costs.
 */
final class GremlinDistAlgorithms {

    /** The marker a query uses to ask for the distributed engine. */
    static final String DIST = "DIST";

    private static final Set<String> DISTRIBUTED = Set.of(
            GeminiAlgorithms.PAGERANK, GeminiAlgorithms.LOUVAIN, GeminiAlgorithms.CONNECTED_COMPONENTS,
            GeminiAlgorithms.SSSP, GeminiAlgorithms.BFS, GeminiAlgorithms.DEGREE_CENTRALITY,
            GeminiAlgorithms.CLOSENESS_CENTRALITY, GeminiAlgorithms.BETWEENNESS_CENTRALITY,
            GeminiAlgorithms.EIGENVECTOR_CENTRALITY);

    private GremlinDistAlgorithms() {
    }

    /** Whether a step call asks for the distributed engine. */
    static boolean wantsDistributed(List<String> args) {
        return !args.isEmpty() && args.get(0) != null && args.get(0).trim().equalsIgnoreCase(DIST);
    }

    /** The algorithms that have a distributed implementation, for an error message. */
    static String known() {
        return String.join(", ", DISTRIBUTED);
    }

    /**
     * Runs one distributed algorithm over the traversers' subgraph.
     *
     * @param cluster      the cluster to run on
     * @param algorithm    the algorithm name (from the step)
     * @param args         the step arguments, the {@code DIST} marker first
     */
    static GremlinStep dist(GeminiCluster cluster, String algorithm, List<String> args) {
        if (cluster == null) {
            throw new QueryException("The " + algorithm + "(DIST) step needs a Gemini cluster; this engine has"
                    + " none configured, and a distributed step never falls back to a local one");
        }
        return (ctx, in) -> {
            GeminiGraph.Scope scope = scopeOf(in, algorithm);
            if (scope.vertexIds().isEmpty()) {
                return List.of(Map.of());
            }
            GeminiGraph graph = GeminiGraph.of(ctx.graphQuery(), scope, directed(algorithm));
            Map<Object, Object> values = switch (algorithm) {
                // a closeness score is a sum over destinations, and a betweenness score a sum over
                // sources: neither is visible from one vertex's superstep, so the launcher runs the
                // cluster once per source and adds the runs up
                case GeminiAlgorithms.CLOSENESS_CENTRALITY -> closeness(cluster, graph, samples(args));
                case GeminiAlgorithms.BETWEENNESS_CENTRALITY -> betweenness(cluster, graph, samples(args));
                default -> oneRun(cluster, algorithm, args, graph);
            };
            if (GeminiAlgorithms.EIGENVECTOR_CENTRALITY.equals(algorithm)) {
                // the run answers with the vector up to a constant factor (each superstep normalises it
                // so the values stay finite); what the caller asked for is the unit vector, signed the
                // same way the in-memory step signs its answer
                values = new LinkedHashMap<>(GremlinAlgorithms.normalizeScores(numeric(values)));
            }
            return List.of(values);
        };
    }

    /** One distributed run of an algorithm: the values the workers hold, by the ids the traversal used. */
    private static Map<Object, Object> oneRun(GeminiCluster cluster, String algorithm, List<String> args,
                                              GeminiGraph graph) {
        GeminiCluster.Result result = cluster.run(graph, algorithm, params(algorithm, args, graph));
        Map<Object, Object> values = new LinkedHashMap<>();
        for (int v = 0; v < graph.vertexCount(); v++) {
            Object value = result.value(graph.id(v));
            if (value != null) {
                values.put(graph.id(v), value);
            }
        }
        return values;
    }

    /**
     * Closeness centrality, assembled from the cluster's breadth-first runs.
     *
     * <p>One run per source answers how far everything is from that source. The graph is undirected, so
     * "how far is everything from s" and "how far is s from everything" are the same column of the
     * distance matrix: a run fills in the total distance of every vertex at once, and the score is the
     * Wasserman-Faust form the in-memory step computes - a vertex of a small component is measured
     * against the vertices it can reach, not against the whole graph, and a vertex that reaches nobody
     * scores zero.
     *
     * <p>The work is {@code k} runs of a breadth-first pass, each on the cluster (the same graph, one
     * root at a time); {@code k = n} is exact (the default), a smaller {@code k} samples the sources and
     * scales the sums by {@code n/k}.
     */
    private static Map<Object, Object> closeness(GeminiCluster cluster, GeminiGraph graph, int samples) {
        int n = graph.vertexCount();
        List<Integer> sources = sampleSources(n, samples);
        double[] total = new double[n];
        int[] reached = new int[n];
        for (int root : sources) {
            GeminiCluster.Result result = cluster.run(graph, GeminiAlgorithms.BFS,
                    Map.of("root", root, "vertices", n));
            for (int v = 0; v < n; v++) {
                double distance = result.number(graph.id(v));
                if (distance >= 0) {
                    reached[v]++;
                    total[v] += distance; // the vertex itself is at zero: reached, but no distance to add
                }
            }
        }
        // n sources in all: a sampled one stands for n/k of them, which is what turns the sum of every
        // source's distance into the sum of the distances to every destination
        double scale = (double) n / sources.size();
        Map<Object, Object> values = new LinkedHashMap<>();
        for (int v = 0; v < n; v++) {
            double sum = total[v] * scale;
            double reach = (reached[v] - 1) * scale;
            values.put(graph.id(v), sum <= 0 || reach <= 0 ? 0.0 : reach * reach / ((n - 1.0) * sum));
        }
        return values;
    }

    /**
     * Betweenness centrality, one Brandes pass per source (or per sampled source) on the cluster.
     *
     * <p>The run of one source accumulates the dependency of every vertex on the shortest paths from that
     * source ({@link GeminiAlgorithms#betweennessCentrality}); adding the runs up and halving the total
     * is the undirected count over pairs, which is then divided by the number of unordered pairs - the
     * same two normalisations the in-memory step applies, so the two engines answer the same figure. A
     * sampled source stands for the sources that were not run (Brandes-Pich style), and the estimate is
     * scaled by {@code n/k}.
     */
    private static Map<Object, Object> betweenness(GeminiCluster cluster, GeminiGraph graph, int samples) {
        int n = graph.vertexCount();
        List<Integer> sources = sampleSources(n, samples);
        Map<Object, Double> total = new LinkedHashMap<>();
        for (Object id : graph.ids()) {
            total.put(id, 0.0);
        }
        for (int root : sources) {
            GeminiCluster.Result result = cluster.run(graph, GeminiAlgorithms.BETWEENNESS_CENTRALITY,
                    Map.of("root", root, "vertices", n));
            for (Object id : graph.ids()) {
                total.merge(id, result.number(id), Double::sum);
            }
        }
        // every pair is walked from both of its endpoints, and a sampled source stands for n/k of them
        double scale = n / (2.0 * sources.size());
        double pairs = (n - 1.0) * (n - 2.0) / 2.0;
        Map<Object, Object> values = new LinkedHashMap<>();
        for (Map.Entry<Object, Double> entry : total.entrySet()) {
            values.put(entry.getKey(), pairs <= 0 ? 0.0 : entry.getValue() * scale / pairs);
        }
        return values;
    }

    /**
     * The sources a per-source centrality runs from: every vertex ({@code samples <= 0}, the exact
     * answer) or the requested number of them, spread evenly over the dense numbering.
     *
     * <p>Evenly and deterministically: a sample that changed between two runs of the same query could
     * not be compared with anything, and the nearest thing to a random source that a hash-ordered vertex
     * set can offer is a stride through the numbering.
     */
    private static List<Integer> sampleSources(int vertexCount, int samples) {
        List<Integer> sources = new ArrayList<>();
        if (samples <= 0 || samples >= vertexCount) {
            for (int v = 0; v < vertexCount; v++) {
                sources.add(v);
            }
            return sources;
        }
        for (int i = 0; i < samples; i++) {
            sources.add((int) ((long) i * vertexCount / samples));
        }
        return sources;
    }

    /** The number of sources a centrality samples: the second argument after {@code DIST}, 0 when absent. */
    private static int samples(List<String> args) {
        if (args.size() > 2) {
            throw new QueryException("A centrality takes at most one parameter besides DIST, got "
                    + (args.size() - 1));
        }
        if (args.size() < 2 || args.get(1) == null || args.get(1).isBlank()) {
            return 0; // every source: the exact answer
        }
        return number(args.get(1).trim());
    }

    /** A score map read as doubles, whatever the wire handed back. */
    private static Map<Object, Double> numeric(Map<Object, Object> values) {
        Map<Object, Double> out = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> entry : values.entrySet()) {
            out.put(entry.getKey(), entry.getValue() instanceof Number number ? number.doubleValue() : 0.0);
        }
        return out;
    }

    /** Whether the algorithm follows the edges as they are stored (out-edges) or both ways. */
    private static boolean directed(String algorithm) {
        // a centrality that counts influence (PageRank, eigenvector) follows the edges the way they are
        // stored; the ones that count paths (closeness, betweenness) and the community steps are
        // undirected, so they see both directions of every edge
        return GeminiAlgorithms.PAGERANK.equals(algorithm) || GeminiAlgorithms.SSSP.equals(algorithm)
                || GeminiAlgorithms.BFS.equals(algorithm)
                || GeminiAlgorithms.EIGENVECTOR_CENTRALITY.equals(algorithm);
    }

    /** The graph to compute on: the reached vertices, the edge classes, and the weight property. */
    private static GeminiGraph.Scope scopeOf(List<Object> in, String algorithm) {
        Set<Object> vertexIds = new LinkedHashSet<>();
        Set<String> edgeNames = new LinkedHashSet<>();
        for (Object element : in) {
            if (element instanceof GremlinVertex vertex) {
                vertexIds.add(vertex.id());
            } else if (element instanceof GremlinEdge edge) {
                if (edge.label() != null && !edge.label().isBlank()) {
                    edgeNames.add(edge.label());
                }
                for (Object endpoint : edge.endpoints()) {
                    if (endpoint != null) {
                        vertexIds.add(endpoint);
                    }
                }
            } else {
                throw new QueryException("A distributed algorithm step expects vertices or edges, got: " + element);
            }
        }
        // a shortest path step can be told which edge property carries the weight, and how heavy a
        // hop is when there is none
        String weightProperty = GeminiAlgorithms.SSSP.equals(algorithm) ? "weight" : null;
        return new GeminiGraph.Scope(new ArrayList<>(vertexIds), edgeNames, weightProperty);
    }

    /**
     * The parameters of a step: the arguments after {@code DIST} are positional, because a query is
     * text and a map would have to be written into it.
     *
     * <ul>
     *   <li>{@code pagerank(DIST[, iterations])}, {@code louvain(DIST[, iterations])}</li>
     *   <li>{@code eigenvector(DIST[, iterations])}</li>
     *   <li>{@code sssp(DIST[, rootVertexId])}, {@code bfs(DIST[, rootVertexId])}</li>
     *   <li>{@code closeness(DIST[, samples])}, {@code betweenness(DIST[, samples])} - the sources the
     *       per-source runs start from, all of them when absent</li>
     * </ul>
     */
    private static Map<String, Object> params(String algorithm, List<String> args, GeminiGraph graph) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (args.size() > 1 && args.get(1) != null && !args.get(1).isBlank()) {
            String second = args.get(1).trim();
            switch (algorithm) {
                case GeminiAlgorithms.PAGERANK, GeminiAlgorithms.LOUVAIN,
                        GeminiAlgorithms.EIGENVECTOR_CENTRALITY -> params.put("iterations", number(second));
                case GeminiAlgorithms.SSSP, GeminiAlgorithms.BFS -> params.put("root", rootOf(second, graph));
                default -> {
                    // connected components and degree take no parameter
                }
            }
        } else if (GeminiAlgorithms.SSSP.equals(algorithm) || GeminiAlgorithms.BFS.equals(algorithm)) {
            // no root named: the traversal's first vertex is the one the caller started from
            params.put("root", 0);
        }
        if (args.size() > 2 && args.get(2) != null && !args.get(2).isBlank()) {
            throw new QueryException(algorithm + "(DIST, ...) takes at most one parameter besides DIST, got "
                    + (args.size() - 1));
        }
        return params;
    }

    private static int number(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new QueryException("Expected a number in a DIST algorithm step, got '" + text + "'");
        }
    }

    /**
     * The root of a shortest path run: a vertex id from the traversal, translated to the dense id the
     * cluster works with, so the step can be written as {@code sssp(DIST)} (the traversal's first
     * vertex) or {@code sssp(DIST, 'a1')}.
     *
     * <p>A query is text, so the id it names arrives as one: a graph whose ids are numbers
     * ({@code 135}) is looked up by their text form, which is the same id spelled the way a caller can
     * spell it.
     */
    private static int rootOf(String text, GeminiGraph graph) {
        int dense = graph.denseId(text);
        if (dense >= 0) {
            return dense;
        }
        for (int v = 0; v < graph.vertexCount(); v++) {
            if (text.equals(String.valueOf(graph.id(v)))) {
                return v;
            }
        }
        throw new QueryException("'" + text + "' is not among the vertices this step was given, so it"
                + " cannot be the root of a shortest path run");
    }

    /** The edge side a distributed traversal reads. */
    static EdgeSide side() {
        return EdgeSide.BOTH;
    }
}
