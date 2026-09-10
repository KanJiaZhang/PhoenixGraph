package com.pengcheng.phoenixgraph.compute.algorithm;

import com.pengcheng.phoenixgraph.compute.GraphAlgorithm;
import com.pengcheng.phoenixgraph.core.Graph;

import java.util.Map;

/**
 * PageRank implementation skeleton.
 */
public class PageRankAlgorithm implements GraphAlgorithm<Map<Object, Double>> {

    private final double dampingFactor;
    private final int iterations;

    public PageRankAlgorithm(double dampingFactor, int iterations) {
        this.dampingFactor = dampingFactor;
        this.iterations = iterations;
    }

    @Override
    public String name() {
        return "pagerank";
    }

    @Override
    public Map<Object, Double> compute(Graph graph) {
        // TODO: implement PageRank over graph.vertices() / graph.edges().
        throw new UnsupportedOperationException("PageRank is not implemented yet");
    }
}
