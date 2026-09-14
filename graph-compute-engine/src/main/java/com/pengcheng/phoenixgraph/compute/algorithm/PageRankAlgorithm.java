package com.pengcheng.phoenixgraph.compute.algorithm;

import com.pengcheng.phoenixgraph.compute.GraphAlgorithm;
import com.pengcheng.phoenixgraph.core.Edge;
import com.pengcheng.phoenixgraph.core.Graph;
import com.pengcheng.phoenixgraph.core.Vertex;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * PageRank over a graph.
 *
 * <p>Computes a score for every vertex using the classic power-iteration
 * formulation:
 * <pre>
 *   PR(v) = (1 - d) / N + d * sum_{u -&gt; v} PR(u) / outDegree(u)
 * </pre>
 * where {@code d} is the damping factor and {@code N} the vertex count. Rank
 * mass from dangling vertices (those with no outgoing edges) is redistributed
 * uniformly across all vertices, keeping the total rank equal to 1.
 *
 * <p>Edges are followed according to their
 * {@link com.pengcheng.phoenixgraph.schema.EdgeDirection}: DIRECTED edges link
 * source -&gt; target only, while UNDIRECTED and BIDIRECTIONAL edges link both
 * ways. Edges whose endpoints are not both present in the graph are ignored.
 */
public class PageRankAlgorithm implements GraphAlgorithm<Map<Object, Double>> {

    private final double dampingFactor;
    private final int iterations;

    public PageRankAlgorithm(double dampingFactor, int iterations) {
        if (!(dampingFactor >= 0.0 && dampingFactor <= 1.0)) {
            throw new IllegalArgumentException("dampingFactor must be in [0, 1]: " + dampingFactor);
        }
        if (iterations < 0) {
            throw new IllegalArgumentException("iterations must be >= 0: " + iterations);
        }
        this.dampingFactor = dampingFactor;
        this.iterations = iterations;
    }

    @Override
    public String name() {
        return "pagerank";
    }

    @Override
    public Map<Object, Double> compute(Graph graph) {
        List<Object> vertices = new ArrayList<>();
        try (Stream<Vertex> stream = graph.vertices()) {
            stream.map(Vertex::id).forEach(vertices::add);
        }

        int n = vertices.size();
        if (n == 0) {
            return new HashMap<>();
        }

        // out-links: vertex -> outgoing neighbours, honouring edge direction
        Map<Object, List<Object>> outLinks = new HashMap<>();
        for (Object v : vertices) {
            outLinks.put(v, new ArrayList<>());
        }
        try (Stream<Edge> edges = graph.edges()) {
            edges.forEach(edge -> {
                List<Object> fromSource = outLinks.get(edge.source());
                List<Object> fromTarget = outLinks.get(edge.destination());
                boolean sourceKnown = fromSource != null;
                boolean targetKnown = fromTarget != null;
                if (sourceKnown && targetKnown) {
                    if (edge.isOutgoing(edge.source())) {
                        fromSource.add(edge.destination());
                    }
                    if (edge.isOutgoing(edge.destination())) {
                        fromTarget.add(edge.source());
                    }
                }
            });
        }

        Map<Object, Double> rank = new HashMap<>();
        for (Object v : vertices) {
            rank.put(v, 1.0 / n);
        }

        double base = (1.0 - dampingFactor) / n;
        for (int i = 0; i < iterations; i++) {
            double danglingRank = 0.0;
            for (Object v : vertices) {
                if (outLinks.get(v).isEmpty()) {
                    danglingRank += rank.get(v);
                }
            }
            double redistribution = base + dampingFactor * danglingRank / n;

            Map<Object, Double> next = new HashMap<>();
            for (Object v : vertices) {
                next.put(v, redistribution);
            }
            for (Object v : vertices) {
                List<Object> neighbours = outLinks.get(v);
                if (neighbours.isEmpty()) {
                    continue;
                }
                double share = dampingFactor * rank.get(v) / neighbours.size();
                for (Object neighbour : neighbours) {
                    next.merge(neighbour, share, Double::sum);
                }
            }
            rank = next;
        }
        return rank;
    }
}
