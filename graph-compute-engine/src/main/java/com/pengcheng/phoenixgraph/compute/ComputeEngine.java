package com.pengcheng.phoenixgraph.compute;

import com.pengcheng.phoenixgraph.core.Graph;

/**
 * Executes graph analytics algorithms over a {@link Graph}.
 */
public interface ComputeEngine {

    /** Runs the given algorithm and returns its result. */
    <R> R run(Graph graph, GraphAlgorithm<R> algorithm);
}
