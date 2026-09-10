package com.pengcheng.phoenixgraph.compute.algorithm;

import com.pengcheng.phoenixgraph.compute.GraphAlgorithm;
import com.pengcheng.phoenixgraph.core.Graph;

import java.util.List;

/**
 * Breadth-first search implementation skeleton starting from a source vertex.
 */
public class BreadthFirstSearch implements GraphAlgorithm<List<Object>> {

    private final Object sourceId;

    public BreadthFirstSearch(Object sourceId) {
        this.sourceId = sourceId;
    }

    @Override
    public String name() {
        return "bfs";
    }

    @Override
    public List<Object> compute(Graph graph) {
        // TODO: implement BFS traversal from sourceId over graph.edges().
        throw new UnsupportedOperationException("BFS is not implemented yet");
    }
}
