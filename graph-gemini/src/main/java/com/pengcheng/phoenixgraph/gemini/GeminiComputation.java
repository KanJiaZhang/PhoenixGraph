package com.pengcheng.phoenixgraph.gemini;

import java.util.List;
import java.util.Map;

/**
 * What an algorithm provides to a Gemini worker: the paper's {@code signal}/{@code slot} pair for the
 * two modes, plus how a vertex starts and how the run knows it is done.
 *
 * <p>The mode is Gemini's: a <b>sparse</b> (push) superstep sends an active vertex's message to the
 * partitions that hold its outgoing edges, and each of them walks those edges and applies
 * {@link #sparseSlot}; a <b>dense</b> (pull) superstep has every partition aggregate its own
 * vertices' contributions along its incoming edges with {@link #denseSignal} and send the partial to
 * the vertex's owner, which merges them with {@link #denseSlot}. Either way a vertex's value lives on
 * the worker that owns it, and no algorithm has to know which one that is.
 *
 * <p>Values and messages are {@code Object}s (a {@code Double} for PageRank, a {@code Long} for a
 * component label, a distance, ...) so one worker implementation serves every algorithm; the
 * algorithms in {@link GeminiAlgorithms} box and unbox at the edges of the superstep.
 *
 * <p>{@link #aggregate()} is the paper's {@code reduce}: a per-community or per-partition summary the
 * driver adds up across workers (Louvain needs the total weight of each community every round).
 */
public interface GeminiComputation {

    /** The name the driver uses to pick the computation on a worker. */
    String name();

    /** Whether the algorithm treats the graph as directed. */
    default boolean directed() {
        return false;
    }

    /** The starting value of a vertex. */
    Object initialValue(int vertex, int outDegree, int inDegree);

    /** Whether a vertex starts active (PageRank starts everywhere, SSSP at its root only). */
    default boolean startsActive(int vertex, int outDegree, int inDegree) {
        return true;
    }

    /** Starts a vertex that becomes active on its own (a shortest-path root). */
    default List<Integer> roots() {
        return List.of();
    }

    /**
     * Whether a vertex's value is the contributions of the current superstep alone.
     *
     * <p>PageRank sums the incoming scores into the next value and normalises them at the end, so a
     * vertex that received nothing has nothing: the worker clears its owned values before it moves the
     * pending ones in. An algorithm that sets its values outright (components, distances, labels) says
     * no here, and its values stay as they were set.
     */
    default boolean accumulative() {
        return false;
    }

    /**
     * Sparse mode: the message an active vertex pushes to the partitions holding its out-edges.
     *
     * <p>The message is what the receiver applies along the edges it holds, so it usually carries the
     * vertex's share rather than its value ({@code value / outDegree} is what a PageRank push sends).
     * The out-degree comes from the state, because a partition only holds a vertex's out-edges whose
     * destinations are in its own chunk - the divisor has to be the degree in the whole graph.
     */
    Object sparseSignal(int vertex, Object value, WorkerState state);

    /**
     * Sparse mode: applies a pushed message along the out-edges this partition holds for
     * {@code vertex} (their destinations are owned here).
     *
     * @param edges destinations and weights of the mirror's out-edges stored here
     * @return how many vertices the update activated
     */
    int sparseSlot(int vertex, Object message, List<GeminiGraph.Edge> edges, WorkerState state);

    /** Dense mode: aggregates the contributions of the sources this partition owns for a vertex. */
    Object denseSignal(int vertex, List<GeminiGraph.Edge> inEdges, WorkerState state);

    /**
     * Dense mode: merges a partial a partition sent for a vertex it owns.
     *
     * @return how many vertices the update activated
     */
    int denseSlot(int vertex, Object message, WorkerState state);

    /** How the driver merges two results (the paper's {@code reduce}, used for the activations). */
    default int reduce(int a, int b) {
        return a + b;
    }

    /**
     * What a worker reports to the driver after a superstep: the totals the next round needs
     * (Louvain's community weights), or an empty map when the algorithm needs none.
     */
    default Map<Object, Double> aggregate() {
        return Map.of();
    }

    /**
     * What the driver collected from every worker and hands back before the next superstep.
     *
     * <p>Louvain needs the total weight per community, which no worker can know alone; the driver
     * adds up what the workers reported through {@link #aggregate()} and passes the sum in here.
     */
    default void share(Map<String, Object> shared) {
    }

    /** Whether the run has converged given the activations of the superstep and its number. */
    default boolean converged(int activated, int superstep) {
        return activated == 0;
    }

    /** A hard limit, so a wrong algorithm cannot loop for ever (the driver reports it). */
    default int maxSupersteps() {
        return 200;
    }

    /** Reads the value of a vertex the way the result should be reported. */
    /**
     * The value a vertex contributes to the answer (PageRank normalises here, SSSP marks unreached).
     *
     * <p>The state is there for the totals only the workers know: the vertex count a PageRank score is
     * relative to is the whole graph's, not this worker's chunk.
     */
    default Object result(int vertex, Object value, WorkerState state) {
        return value;
    }
}
