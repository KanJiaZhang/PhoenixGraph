package com.pengcheng.phoenixgraph.query.gremlin;

import com.pengcheng.phoenixgraph.query.QueryException;
import com.pengcheng.phoenixgraph.query.storage.EdgeQueryResult;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-memory graph algorithms run over the vertices reached by a traversal.
 *
 * <p>The algorithms build the induced subgraph over the traversed vertices by
 * reading every edge type (via {@code GraphQuery#readAllEdgesBatch}) and keeping
 * only edges whose other endpoint is also in the vertex set. Homogeneous vertices
 * are supported; heterogeneous vertices are not supported yet.
 *
 * <p>Each step returns a single {@link Map} (wrapped in a list): vertex id to
 * PageRank score / Louvain community id / connected-component id / centrality score.
 */
final class GremlinAlgorithms {

    private GremlinAlgorithms() {
    }

    static GremlinStep pagerank() {
        return (ctx, in) -> List.of(pageRank(buildAdjacency(ctx, vertexIds(in), EdgeSide.OUT)));
    }

    static GremlinStep louvain() {
        return (ctx, in) -> List.of(louvain(buildAdjacency(ctx, vertexIds(in), EdgeSide.BOTH)));
    }

    static GremlinStep cc() {
        return (ctx, in) -> List.of(connectedComponents(buildAdjacency(ctx, vertexIds(in), EdgeSide.BOTH)));
    }

    /** How many iterations the in-memory eigenvector run takes (the distributed form can be told). */
    static final int EIGENVECTOR_ITERATIONS = 30;

    static GremlinStep degree() {
        return (ctx, in) -> List.of(degreeCentrality(buildAdjacency(ctx, vertexIds(in), EdgeSide.BOTH)));
    }

    static GremlinStep closeness() {
        return (ctx, in) -> List.of(closenessCentrality(buildAdjacency(ctx, vertexIds(in), EdgeSide.BOTH)));
    }

    static GremlinStep betweenness() {
        return (ctx, in) -> List.of(betweennessCentrality(buildAdjacency(ctx, vertexIds(in), EdgeSide.BOTH)));
    }

    static GremlinStep eigenvector() {
        return (ctx, in) -> List.of(eigenvectorCentrality(buildAdjacency(ctx, vertexIds(in), EdgeSide.OUT)));
    }

    // --- internals ---

    /**
     * The vertices a run is about: the traversers themselves, and for edge traversers both of their
     * endpoints - a traversal that starts from an edge class ({@code g.E('payment').cc()}) names its
     * vertices through the edges it walked.
     */
    static List<Object> vertexIds(List<Object> in) {
        Set<Object> ids = new LinkedHashSet<>();
        for (Object o : in) {
            if (o instanceof GremlinVertex vertex) {
                ids.add(vertex.id());
            } else if (o instanceof GremlinEdge edge) {
                for (Object endpoint : edge.endpoints()) {
                    if (endpoint != null) {
                        ids.add(endpoint);
                    }
                }
            } else {
                throw new QueryException("Algorithm step expects vertices or edges but got: " + o);
            }
        }
        return new ArrayList<>(ids);
    }

    private static Map<Object, List<Object>> buildAdjacency(GremlinContext ctx, List<Object> ids,
                                                            EdgeSide direction) {
        Map<Object, List<Object>> adj = new LinkedHashMap<>();
        for (Object id : ids) {
            adj.put(id, new ArrayList<>());
        }
        if (ids.isEmpty()) {
            return adj;
        }
        Map<Object, Object> canonical = canonicalIds(ids);
        List<List<EdgeQueryResult>> edgesByVertex = ctx.graphQuery().readAllEdgesBatch(ids, direction, List.of());
        for (int i = 0; i < ids.size(); i++) {
            for (EdgeQueryResult edge : edgesByVertex.get(i)) {
                Object other = canonical.get(edge.otherVertexId());
                if (other != null) {
                    adj.get(ids.get(i)).add(other);
                }
            }
        }
        return adj;
    }

    /**
     * The ids of this run, looked up by identity and by text.
     *
     * <p>A vertex id arrives in the form the query wrote it (text: a query is text) and the store
     * hands endpoints back in the form it stored them (numbers as numbers). Both name the same vertex,
     * so an endpoint is matched by identity first and by its text form second - and it is rewritten to
     * the form this run uses, so the answer names every vertex exactly once.
     */
    private static Map<Object, Object> canonicalIds(List<Object> ids) {
        Map<Object, Object> canonical = new LinkedHashMap<>();
        for (Object id : ids) {
            canonical.putIfAbsent(id, id);
            canonical.putIfAbsent(String.valueOf(id), id);
        }
        return canonical;
    }

    /** Iterative PageRank over a directed adjacency map. */
    static Map<Object, Double> pageRank(Map<Object, List<Object>> outAdj) {
        int n = outAdj.size();
        if (n == 0) {
            return Map.of();
        }
        double d = 0.85;
        Map<Object, Double> pr = new LinkedHashMap<>();
        for (Object v : outAdj.keySet()) {
            pr.put(v, 1.0 / n);
        }

        Map<Object, List<Object>> inAdj = new LinkedHashMap<>();
        for (Object v : outAdj.keySet()) {
            inAdj.put(v, new ArrayList<>());
        }
        for (Map.Entry<Object, List<Object>> e : outAdj.entrySet()) {
            for (Object u : e.getValue()) {
                inAdj.computeIfAbsent(u, k -> new ArrayList<>()).add(e.getKey());
            }
        }

        for (int iter = 0; iter < 30; iter++) {
            double dangling = 0;
            for (Object v : outAdj.keySet()) {
                if (outAdj.get(v).isEmpty()) {
                    dangling += pr.get(v);
                }
            }
            Map<Object, Double> next = new LinkedHashMap<>();
            for (Object v : outAdj.keySet()) {
                double rank = (1 - d) / n + d * dangling / n;
                for (Object u : inAdj.getOrDefault(v, List.of())) {
                    rank += d * pr.get(u) / outAdj.get(u).size();
                }
                next.put(v, rank);
            }
            pr = next;
        }
        return pr;
    }

    /** Connected components over an undirected adjacency map. */
    static Map<Object, Long> connectedComponents(Map<Object, List<Object>> adj) {
        Map<Object, Long> comp = new LinkedHashMap<>();
        long compId = 0;
        for (Object v : adj.keySet()) {
            if (comp.containsKey(v)) {
                continue;
            }
            Deque<Object> stack = new ArrayDeque<>();
            stack.push(v);
            comp.put(v, compId);
            while (!stack.isEmpty()) {
                Object cur = stack.pop();
                for (Object n : adj.getOrDefault(cur, List.of())) {
                    if (!comp.containsKey(n)) {
                        comp.put(n, compId);
                        stack.push(n);
                    }
                }
            }
            compId++;
        }
        return comp;
    }

    /**
     * Degree centrality: how many edges the induced subgraph gives a vertex. The adjacency was read in
     * both directions, so the count is every incident edge record (a self-loop twice) - the same figure
     * the distributed step reads from its partition, which is why the two engines agree on a graph.
     */
    static Map<Object, Long> degreeCentrality(Map<Object, List<Object>> adj) {
        Map<Object, Long> degree = new LinkedHashMap<>();
        for (Map.Entry<Object, List<Object>> entry : adj.entrySet()) {
            degree.put(entry.getKey(), (long) entry.getValue().size());
        }
        return degree;
    }

    /**
     * Closeness centrality, Wasserman-Faust normalised: {@code (r-1)^2 / ((n-1) * sum of distances)},
     * where {@code r} is how many vertices the vertex can reach (itself included) and the sum runs over
     * those. Dividing by {@code r-1} rather than {@code n-1} is what keeps a vertex of a small component
     * from being punished for the vertices it cannot reach; a vertex that reaches only itself scores
     * zero. The graph is undirected, so "can reach" and "is reached by" are the same set and the
     * distributed step's breadth-first runs accumulate the very same sum.
     */
    static Map<Object, Double> closenessCentrality(Map<Object, List<Object>> adj) {
        int n = adj.size();
        Map<Object, Double> closeness = new LinkedHashMap<>();
        for (Object v : adj.keySet()) {
            Map<Object, Integer> distance = breadthFirstDistances(adj, v);
            int reached = distance.size(); // the vertex itself is at distance zero, so it counts
            double sum = 0;
            for (int d : distance.values()) {
                sum += d;
            }
            closeness.put(v, reached <= 1 || sum <= 0.0
                    ? 0.0
                    : (reached - 1.0) * (reached - 1.0) / ((n - 1.0) * sum));
        }
        return closeness;
    }

    /** The hop distance from a source to every vertex it reaches (one breadth-first walk). */
    static Map<Object, Integer> breadthFirstDistances(Map<Object, List<Object>> adj, Object source) {
        Map<Object, Integer> distance = new LinkedHashMap<>();
        Deque<Object> queue = new ArrayDeque<>();
        distance.put(source, 0);
        queue.add(source);
        while (!queue.isEmpty()) {
            Object v = queue.poll();
            int next = distance.get(v) + 1;
            for (Object w : adj.getOrDefault(v, List.of())) {
                if (!distance.containsKey(w)) {
                    distance.put(w, next);
                    queue.add(w);
                }
            }
        }
        return distance;
    }

    /**
     * Betweenness centrality by Brandes' algorithm: for every source a breadth-first pass counts the
     * shortest paths, and the dependencies are accumulated on the way back up the levels. The graph is
     * undirected, so every pair is walked from both of its endpoints and the total is halved; dividing by
     * the number of unordered pairs leaves a vertex that every shortest path goes through at one.
     */
    static Map<Object, Double> betweennessCentrality(Map<Object, List<Object>> adj) {
        int n = adj.size();
        Map<Object, Double> betweenness = new LinkedHashMap<>();
        for (Object v : adj.keySet()) {
            betweenness.put(v, 0.0);
        }
        for (Object source : adj.keySet()) {
            brandesFrom(adj, source, betweenness);
        }
        double pairs = (n - 1.0) * (n - 2.0) / 2.0;
        Map<Object, Double> out = new LinkedHashMap<>();
        for (Map.Entry<Object, Double> entry : betweenness.entrySet()) {
            out.put(entry.getKey(), pairs <= 0.0 ? 0.0 : entry.getValue() / 2.0 / pairs);
        }
        return out;
    }

    /** One source of Brandes' algorithm: the forward pass, then the dependency pass back down. */
    private static void brandesFrom(Map<Object, List<Object>> adj, Object source,
                                    Map<Object, Double> betweenness) {
        Map<Object, Integer> distance = new LinkedHashMap<>();
        Map<Object, Double> sigma = new LinkedHashMap<>();
        Map<Object, Double> delta = new LinkedHashMap<>();
        Map<Object, List<Object>> predecessors = new LinkedHashMap<>();
        List<Object> order = new ArrayList<>();
        Deque<Object> queue = new ArrayDeque<>();
        distance.put(source, 0);
        sigma.put(source, 1.0);
        queue.add(source);
        while (!queue.isEmpty()) {
            Object v = queue.poll();
            order.add(v);
            for (Object w : adj.getOrDefault(v, List.of())) {
                if (!distance.containsKey(w)) {
                    distance.put(w, distance.get(v) + 1);
                    queue.add(w);
                }
                // every edge record that continues a shortest path carries its share of the paths: two
                // predecessors at the same level (or parallel edges) both count, which is what the
                // distributed pass accumulates too
                if (distance.get(w) == distance.get(v) + 1) {
                    sigma.merge(w, sigma.get(v), Double::sum);
                    predecessors.computeIfAbsent(w, k -> new ArrayList<>()).add(v);
                }
            }
        }
        for (Object v : order) {
            delta.putIfAbsent(v, 0.0);
        }
        for (int i = order.size() - 1; i >= 0; i--) {
            Object w = order.get(i);
            for (Object v : predecessors.getOrDefault(w, List.of())) {
                delta.merge(v, sigma.get(v) / sigma.get(w) * (1 + delta.get(w)), Double::sum);
            }
            if (!w.equals(source)) {
                betweenness.merge(w, delta.get(w), Double::sum); // the source's own dependency is not a score
            }
        }
    }

    /**
     * Eigenvector centrality by power iteration over the adjacency as stored (out-edges): a vertex's
     * score is the sum of the scores of the vertices that point at it, so the vector settles on the
     * principal eigenvector of the adjacency matrix. A vertex nothing points at scores zero.
     *
     * <p>The vector is normalised every round, or it would grow by the dominant eigenvalue each time
     * and overflow after a few dozen iterations; the result is normalised once more (unit L2 length,
     * sign fixed), which is exactly what the launcher does with what a distributed run collected - so
     * {@code eigenvector()} and {@code eigenvector(DIST)} answer the same vector, not just the same
     * direction.
     */
    static Map<Object, Double> eigenvectorCentrality(Map<Object, List<Object>> outAdj) {
        Map<Object, List<Object>> inAdj = new LinkedHashMap<>();
        for (Object v : outAdj.keySet()) {
            inAdj.put(v, new ArrayList<>());
        }
        for (Map.Entry<Object, List<Object>> entry : outAdj.entrySet()) {
            for (Object target : entry.getValue()) {
                inAdj.computeIfAbsent(target, k -> new ArrayList<>()).add(entry.getKey());
            }
        }
        Map<Object, Double> x = new LinkedHashMap<>();
        for (Object v : outAdj.keySet()) {
            x.put(v, 1.0);
        }
        for (int iteration = 0; iteration < EIGENVECTOR_ITERATIONS; iteration++) {
            Map<Object, Double> next = new LinkedHashMap<>();
            for (Object v : outAdj.keySet()) {
                double sum = 0;
                for (Object u : inAdj.getOrDefault(v, List.of())) {
                    sum += x.getOrDefault(u, 0.0);
                }
                next.put(v, sum);
            }
            Map<Object, Double> normalised = normalizeScores(next);
            if (maxChange(x, normalised) < 1e-12) {
                x = normalised;
                break; // converged: the distributed run's fixed iteration count cannot do better
            }
            x = normalised;
        }
        return normalizeScores(x);
    }

    /**
     * A score vector scaled to unit L2 length, with the sign fixed so its largest entry is positive.
     *
     * <p>Both engines end here. The sign of a principal eigenvector is arbitrary, and leaving it to the
     * order an iteration happened to visit vertices would make a distributed run differ from an
     * in-memory one by a sign; the launcher and the step therefore fix it the same way.
     */
    static Map<Object, Double> normalizeScores(Map<Object, Double> scores) {
        double squares = 0;
        double largest = 0;
        for (double value : scores.values()) {
            squares += value * value;
            if (Math.abs(value) > Math.abs(largest)) {
                largest = value;
            }
        }
        double norm = Math.sqrt(squares);
        double sign = largest < 0 ? -1.0 : 1.0;
        Map<Object, Double> out = new LinkedHashMap<>();
        for (Map.Entry<Object, Double> entry : scores.entrySet()) {
            out.put(entry.getKey(), norm == 0.0 ? 0.0 : entry.getValue() / norm * sign);
        }
        return out;
    }

    /** The largest absolute difference between two vectors (how a power iteration knows it settled). */
    private static double maxChange(Map<Object, Double> before, Map<Object, Double> after) {
        double largest = 0;
        for (Map.Entry<Object, Double> entry : after.entrySet()) {
            double change = Math.abs(entry.getValue() - before.getOrDefault(entry.getKey(), 0.0));
            largest = Math.max(largest, change);
        }
        return largest;
    }

    /** Single-level greedy Louvain (modularity optimization) over an undirected adjacency map. */
    static Map<Object, Long> louvain(Map<Object, List<Object>> adj) {
        int n = adj.size();
        if (n == 0) {
            return Map.of();
        }
        List<Object> vertices = new ArrayList<>(adj.keySet());
        Map<Object, Long> community = new LinkedHashMap<>();
        long id = 0;
        for (Object v : vertices) {
            community.put(v, id++);
        }

        double m = 0;
        Map<Long, Double> tot = new LinkedHashMap<>(); // community total degree
        Map<Long, Double> in = new LinkedHashMap<>();  // 2 * community internal edges
        for (Object v : vertices) {
            long c = community.get(v);
            double k = adj.get(v).size();
            tot.merge(c, k, Double::sum);
            m += k;
        }
        m /= 2.0;
        if (m <= 0) {
            return community; // no edges -> each vertex its own community
        }

        boolean changed = true;
        int passes = 0;
        while (changed && passes < 20) {
            changed = false;
            passes++;
            for (Object v : vertices) {
                long cur = community.get(v);
                double k = adj.get(v).size();
                Map<Long, Double> neigh = new LinkedHashMap<>();
                for (Object neighbor : adj.get(v)) {
                    neigh.merge(community.get(neighbor), 1.0, Double::sum);
                }
                double kInCur = neigh.getOrDefault(cur, 0.0);
                double bestGain = 0;
                long bestComm = cur;
                for (Map.Entry<Long, Double> e : neigh.entrySet()) {
                    long c = e.getKey();
                    if (c == cur) {
                        continue;
                    }
                    double kIn = e.getValue();
                    double gain = (in.getOrDefault(c, 0.0) + 2 * kIn) / (2 * m)
                            - Math.pow((tot.getOrDefault(c, 0.0) + k) / (2 * m), 2)
                            - (in.getOrDefault(cur, 0.0) / (2 * m)
                            - Math.pow(tot.getOrDefault(cur, 0.0) / (2 * m), 2)
                            - Math.pow(k / (2 * m), 2));
                    if (gain > bestGain) {
                        bestGain = gain;
                        bestComm = c;
                    }
                }
                if (bestComm != cur) {
                    double kInNew = neigh.getOrDefault(bestComm, 0.0);
                    in.merge(cur, -2 * kInCur, Double::sum);
                    in.merge(bestComm, 2 * kInNew, Double::sum);
                    tot.merge(cur, -k, Double::sum);
                    tot.merge(bestComm, k, Double::sum);
                    community.put(v, bestComm);
                    changed = true;
                }
            }
        }
        return community;
    }
}
