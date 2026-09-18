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
 */
final class GremlinDistAlgorithms {

    /** The marker a query uses to ask for the distributed engine. */
    static final String DIST = "DIST";

    private static final Set<String> DISTRIBUTED = Set.of(
            GeminiAlgorithms.PAGERANK, GeminiAlgorithms.LOUVAIN, GeminiAlgorithms.CONNECTED_COMPONENTS,
            GeminiAlgorithms.SSSP, GeminiAlgorithms.BFS);

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
            GeminiCluster.Result result = cluster.run(graph, algorithm, params(algorithm, args, graph));
            Map<Object, Object> values = new LinkedHashMap<>();
            for (int v = 0; v < graph.vertexCount(); v++) {
                Object value = result.value(graph.id(v));
                if (value != null) {
                    values.put(graph.id(v), value);
                }
            }
            return List.of(values);
        };
    }

    /** Whether the algorithm follows the edges as they are stored (out-edges) or both ways. */
    private static boolean directed(String algorithm) {
        return GeminiAlgorithms.PAGERANK.equals(algorithm) || GeminiAlgorithms.SSSP.equals(algorithm)
                || GeminiAlgorithms.BFS.equals(algorithm);
    }

    /** The graph to compute on: the reached vertices, the edge classes, and the weight property. */
    private static GeminiGraph.Scope scopeOf(List<Object> in, String algorithm) {
        Set<Object> vertexIds = new LinkedHashSet<>();
        Set<String> edgeNames = new LinkedHashSet<>();
        for (Object element : in) {
            if (element instanceof GremlinVertex vertex) {
                vertexIds.add(vertex.id());
            } else if (element instanceof GremlinEdge edge) {
                if (edge.label() != null) {
                    edgeNames.add(edge.label());
                }
                Object other = edge.otherVertexId();
                if (other != null) {
                    vertexIds.add(other);
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
     *   <li>{@code sssp(DIST[, rootVertexId])}, {@code bfs(DIST[, rootVertexId])}</li>
     * </ul>
     */
    private static Map<String, Object> params(String algorithm, List<String> args, GeminiGraph graph) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (args.size() > 1 && args.get(1) != null && !args.get(1).isBlank()) {
            String second = args.get(1).trim();
            switch (algorithm) {
                case GeminiAlgorithms.PAGERANK, GeminiAlgorithms.LOUVAIN -> params.put("iterations", number(second));
                case GeminiAlgorithms.SSSP, GeminiAlgorithms.BFS -> params.put("root", rootOf(second, graph));
                default -> {
                    // connected components takes no parameter
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
     */
    private static int rootOf(String text, GeminiGraph graph) {
        int dense = graph.denseId(text);
        if (dense < 0) {
            throw new QueryException("'" + text + "' is not among the vertices this step was given, so it"
                    + " cannot be the root of a shortest path run");
        }
        return dense;
    }

    /** The edge side a distributed traversal reads. */
    static EdgeSide side() {
        return EdgeSide.BOTH;
    }
}
