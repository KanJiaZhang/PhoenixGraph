package com.pengcheng.phoenixgraph.gemini;

import java.util.ArrayList;
import java.util.List;

/**
 * The graph state one worker holds for one algorithm: the value of every vertex it knows about, the
 * active set, and the totals an algorithm accumulates while it runs.
 *
 * <p>Values are indexed by the <b>global</b> dense id: Gemini cuts a contiguous vertex array and each
 * worker only touches its own chunk, but the array itself is the whole vertex space, which keeps the
 * indexing trivial and is what the paper's design does (only the owned parts are actually touched).
 * A worker therefore holds a value for a remote vertex only when it owns it - the dense mode never
 * reads a remote value, because the partition that owns the source computes the partial and sends it.
 */
public final class WorkerState {

    private final Object[] values;
    /** Contributions of the superstep in progress: committed at the end, like Gemini's next array. */
    private final double[] pending;
    private final boolean[] pendingSet;
    private final boolean[] active;
    private final WorkerLayout layout;
    private final int totalVertexCount;
    private final int vertexCount;
    private final int begin;
    private final int end;
    private long written;

    WorkerState(WorkerLayout layout, int totalVertexCount) {
        this.layout = layout;
        this.totalVertexCount = totalVertexCount;
        this.vertexCount = layout.vertexCount();
        this.begin = layout.begin();
        this.end = layout.end();
        this.values = new Object[vertexCount];
        this.pending = new double[vertexCount];
        this.pendingSet = new boolean[vertexCount];
        this.active = new boolean[vertexCount];
    }

    /** The value of a vertex ({@code null} when it was never written). */
    public Object value(int vertex) {
        return values[vertex];
    }

    /**
     * The out-degree of a vertex in the whole graph.
     *
     * <p>A partition holds a vertex's out-edges only where their destinations are, so the degree a
     * contribution is divided by cannot be counted locally - it is the layout's global figure.
     */
    public int outDegree(int vertex) {
        return layout.outDegree(vertex);
    }

    /** How many vertices the whole graph has (the denominator of a PageRank score). */
    public int totalVertexCount() {
        return totalVertexCount;
    }

    /**
     * Clears the values of this worker's chunk before a superstep's contributions are committed.
     *
     * <p>This is what makes an accumulative algorithm's value "the contributions of one superstep":
     * without it, a vertex that received nothing would keep the value it had - and a PageRank vertex
     * that nothing points at would keep its starting value forever.
     */
    public void clearOwnedValues() {
        for (int v = begin; v < end; v++) {
            values[v] = 0.0; // no contributions is a zero sum, not a missing value
        }
    }

    /** Writes the value of a vertex (owned by this worker). */
    public void setValue(int vertex, Object value) {
        values[vertex] = value;
        written++;
    }

    /**
     * Adds to the value a vertex will have next superstep (the accumulation a push or a partial is).
     *
     * <p>Gemini computes a superstep from the values of the previous one: PageRank sums the incoming
     * scores into {@code next} and only then moves {@code next} into {@code curr}. Accumulating into
     * the live value instead would count each vertex's own score as well, which is why the pending
     * buffer exists.
     */
    public void accumulate(int vertex, double delta) {
        pending[vertex] += delta;
        pendingSet[vertex] = true;
    }

    /** The pending contribution of a vertex (0 when it has none). */
    public double pending(int vertex) {
        return pending[vertex];
    }

    /** Moves every pending contribution into the value, clearing the buffer. Returns how many moved. */
    public int commit() {
        int committed = 0;
        for (int v = 0; v < pendingSet.length; v++) {
            if (!pendingSet[v]) {
                continue;
            }
            values[v] = pending[v];
            pending[v] = 0.0;
            pendingSet[v] = false;
            committed++;
        }
        return committed;
    }

    public boolean isActive(int vertex) {
        return active[vertex];
    }

    public void activate(int vertex) {
        active[vertex] = true;
    }

    public void deactivate(int vertex) {
        active[vertex] = false;
    }

    public int vertexCount() {
        return vertexCount;
    }

    /** Whether this worker owns the vertex (its value lives here). */
    public boolean owns(int vertex) {
        return vertex >= begin && vertex < end;
    }

    /** How many vertices this worker's chunk holds. */
    public int ownedVertices() {
        return end - begin;
    }

    /** How many values were written, for a report on the work done. */
    public long writes() {
        return written;
    }

    /** The vertices of this worker's chunk that are active. */
    public List<Integer> activeOwned() {
        List<Integer> out = new ArrayList<>();
        for (int v = begin; v < end; v++) {
            if (active[v]) {
                out.add(v);
            }
        }
        return out;
    }

    /** The sum of the out-degrees of the active owned vertices - Gemini's mode switch input. */
    public long activeEdgeCount(WorkerLayout layout) {
        long total = 0;
        for (int v = begin; v < end; v++) {
            if (active[v]) {
                total += layout.outDegree(v);
            }
        }
        return total;
    }

    /** Clears every active flag of this worker's chunk (the next frontier is filled by the slots). */
    public void clearActive() {
        for (int v = begin; v < end; v++) {
            active[v] = false;
        }
    }
}
