package com.pengcheng.phoenixgraph.compute;

import com.pengcheng.phoenixgraph.core.Graph;

/**
 * A reusable graph algorithm with a typed result.
 *
 * @param <R> the result type produced by {@link #compute(Graph)}
 */
public interface GraphAlgorithm<R> {

    /** Stable, unique algorithm name (e.g. "pagerank", "bfs"). */
    String name();

    /** Runs this algorithm against the given graph. */
    R compute(Graph graph);
}
