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
 * PageRank score / Louvain community id / connected-component id.
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
