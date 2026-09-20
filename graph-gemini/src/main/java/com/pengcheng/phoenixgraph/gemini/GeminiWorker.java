package com.pengcheng.phoenixgraph.gemini;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One Gemini worker: the edges and vertex values of a chunk, and the two superstep halves of the
 * paper's execution model.
 *
 * <p>A superstep has two phases, both driven by the launcher:
 * <ol>
 *   <li><b>exchange</b> - in the sparse mode every active owned vertex pushes its message to the
 *       workers holding its out-edges; in the dense mode every worker sends the partial it computes
 *       along its own vertices' in-edges to the owner of the destination. Messages are batched per
 *       peer (one request per peer per round, like the ring schedule batches per partition) and
 *       buffered on arrival, so no two workers ever wait on each other;</li>
 *   <li><b>compute</b> - the buffered messages are applied through the slots, the local dense work is
 *       done, and the number of activated vertices is reported. That count is what decides
 *       convergence, and - summed over the workers - which mode the next superstep uses
 *       ({@code sparse = activeEdges < |E| / 20}, Gemini's rule from Ligra).</li>
 * </ol>
 *
 * <p>Local edge processing runs over mini-chunks of {@value #MINI_CHUNK} vertices with a shared
 * cursor, which is the paper's fine-grained work stealing reduced to a thread pool (a worker here is
 * one process, so there are no sockets to split).
 */
public final class GeminiWorker {

    /** The paper's mini-chunk: the granularity edge processing is handed out in. */
    public static final int MINI_CHUNK = 64;

    private final WorkerLayout layout;
    private final GeminiComputation computation;
    private final WorkerState state;
    private final long totalEdges;
    private final long totalVertexCount;
    private final Map<Integer, Map<Integer, List<Object>>> inbox = new ConcurrentHashMap<>();

    private final AtomicLong messagesSent = new AtomicLong();
    private final AtomicLong messagesReceived = new AtomicLong();
    private final AtomicLong bytesSent = new AtomicLong();
    private final AtomicInteger supersteps = new AtomicInteger();
    private final AtomicLong activeEdgesLast = new AtomicLong();
    private volatile String lastMode = "-";

    GeminiWorker(WorkerLayout layout, GeminiComputation computation, long totalEdges, long totalVertexCount) {
        this.layout = layout;
        this.computation = computation;
        this.state = new WorkerState(layout, (int) totalVertexCount);
        this.totalEdges = totalEdges;
        this.totalVertexCount = totalVertexCount;
    }

    public WorkerLayout layout() {
        return layout;
    }

    public int workerId() {
        return layout.workerId();
    }

    /** The values of the owned vertices, as dense id -> value. */
    public Map<Integer, Object> values() {
        Map<Integer, Object> out = new LinkedHashMap<>();
        for (int v = layout.begin(); v < layout.end(); v++) {
            Object value = state.value(v);
            if (value != null) {
                out.put(v, computation.result(v, value, state));
            }
        }
        return out;
    }

    /** The value of a vertex, for a test or a peer that asks. */
    public Object value(int vertex) {
        return state.value(vertex);
    }

    /** Initialises the values and the first active set (the paper's {@code processVertices} init). */
    public void initialize() {
        for (int v = layout.begin(); v < layout.end(); v++) {
            state.setValue(v, computation.initialValue(v, layout.outDegree(v), 0));
        }
        state.clearActive();
        for (int v = layout.begin(); v < layout.end(); v++) {
            if (computation.startsActive(v, layout.outDegree(v), 0)) {
                state.activate(v);
            }
        }
        for (int root : computation.roots()) {
            if (state.owns(root)) {
                state.activate(root);
            }
        }
    }

    /** How many edges the active owned vertices have - the input to the mode decision. */
    public long activeEdges() {
        return state.activeEdgeCount(layout);
    }

    /** The activations of the last superstep, for a report. */
    public int supersteps() {
        return supersteps.get();
    }

    public String lastMode() {
        return lastMode;
    }

    public long messagesSent() {
        return messagesSent.get();
    }

    public long messagesReceived() {
        return messagesReceived.get();
    }

    public long bytesSent() {
        return bytesSent.get();
    }

    public long activeEdgesLast() {
        return activeEdgesLast.get();
    }

    /** Whether the mode for a given active edge count would be sparse, using Gemini's rule. */
    public boolean sparseMode(long activeEdges) {
        return activeEdges < totalEdges / 20;
    }

    /**
     * Phase one of a superstep: produce the outgoing messages for this round and hand them to a
     * sender (the launcher's HTTP client, or a test's direct call).
     *
     * @return the batches to deliver, one per peer worker
     */
    public Map<Integer, Map<Integer, Object>> exchange(boolean sparse) {
        Map<Integer, Map<Integer, Object>> batches = new LinkedHashMap<>();
        if (sparse) {
            for (int v = layout.begin(); v < layout.end(); v++) {
                if (!state.isActive(v)) {
                    continue;
                }
                Object message = computation.sparseSignal(v, state.value(v), state);
                if (message == null) {
                    continue;
                }
                // one copy per worker that holds out-edges of v: each of them walks the edges it
                // holds, and their destinations are the vertices it owns - which is what makes the
                // push a local update on the receiving side
                for (int peer : layout.pushTargets(v)) {
                    batches.computeIfAbsent(peer, k -> new LinkedHashMap<>()).put(v, message);
                }
            }
        } else {
            // dense: each destination with in-edges sourced here gets one partial, addressed to its owner
            Map<Integer, Map<Integer, Object>> byOwner = new LinkedHashMap<>();
            for (int destination : layout.denseVerticesInOrder()) {
                Object message = computation.denseSignal(destination, layout.inEdgesOf(destination), state);
                if (message == null) {
                    continue;
                }
                byOwner.computeIfAbsent(layout.ownerOf(destination), k -> new LinkedHashMap<>())
                        .put(destination, message);
            }
            batches.putAll(byOwner);
        }
        long count = 0;
        for (Map<Integer, Object> batch : batches.values()) {
            count += batch.size();
        }
        messagesSent.addAndGet(count);
        bytesSent.addAndGet(count * 16L);
        return batches;
    }

    /**
     * Buffers a batch that arrived from a peer.
     *
     * <p>Every partial is kept: a vertex with sources on several workers gets one partial from each
     * of them, and merging them is the owner's job (two workers sending their own sum to the same
     * PageRank vertex must both be added). Replacing by vertex would silently drop all but the last.
     */
    public void receive(int round, Map<Integer, Object> messages) {
        Map<Integer, List<Object>> box = inbox.computeIfAbsent(round, k -> new ConcurrentHashMap<>());
        for (Map.Entry<Integer, Object> entry : messages.entrySet()) {
            box.computeIfAbsent(entry.getKey(), k -> new CopyOnWriteArrayList<>()).add(entry.getValue());
        }
        messagesReceived.addAndGet(messages.size());
    }

    /**
     * Phase two of a superstep: apply what arrived through the slots and do the local work.
     *
     * @return how many vertices the superstep activated
     */
    public int compute(int round, boolean sparse) {
        lastMode = sparse ? "sparse" : "dense";
        Map<Integer, List<Object>> arrived = inbox.getOrDefault(round, Map.of());
        int activated = 0;
        if (sparse) {
            // a pushed message is applied along the out-edges this worker holds for the sender; the
            // destinations are owned here, so the slot updates local values and local activations
            long local = 0;
            for (Map.Entry<Integer, List<Object>> entry : arrived.entrySet()) {
                int source = entry.getKey();
                if (!layout.hasOutEdges(source)) {
                    continue; // the bitmap test the paper adds to the sparse index
                }
                for (Object message : entry.getValue()) {
                    local += computation.sparseSlot(source, message, layout.outEdgesOf(source), state);
                }
            }
            activated = (int) Math.min(Integer.MAX_VALUE, local);
        } else {
            for (Map.Entry<Integer, List<Object>> entry : arrived.entrySet()) {
                int vertex = entry.getKey();
                if (!state.owns(vertex)) {
                    continue; // a partial for a vertex this worker does not own cannot be merged here
                }
                for (Object message : entry.getValue()) {
                    activated += computation.denseSlot(vertex, message, state);
                }
            }
        }
        // the accumulated contributions become the values the next superstep reads (Gemini's swap).
        // An accumulative algorithm (PageRank) computes a vertex's value from this superstep's
        // contributions alone, so a vertex that received none has none: leaving its previous value in
        // place would make a vertex nothing points at keep its initial value forever.
        if (computation.accumulative()) {
            state.clearOwnedValues();
        }
        state.commit();
        inbox.remove(round);
        supersteps.incrementAndGet();
        activeEdgesLast.set(state.activeEdgeCount(layout));
        return activated;
    }

    /** Hands the driver's shared state to the computation (Louvain's community weights). */
    public void share(Map<String, Object> shared) {
        computation.share(shared);
    }

    /** The per-worker summary the driver adds up (Louvain's community weights). */
    public Map<Object, Double> aggregate() {
        return computation.aggregate();
    }

    /** The total edge count of the whole graph, which the mode rule is relative to. */
    public long totalEdges() {
        return totalEdges;
    }

    public long totalVertexCount() {
        return totalVertexCount;
    }

    /** The state, for a test that wants to look at it. */
    WorkerState state() {
        return state;
    }

    /** A status line, for {@code GET /gemini/status}. */
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("worker", layout.workerId());
        out.put("begin", layout.begin());
        out.put("end", layout.end());
        out.put("owned", layout.end() - layout.begin());
        out.put("vertexCount", layout.vertexCount());
        out.put("sparseEdges", layout.sparseSources());
        out.put("denseEdges", layout.denseVertices());
        out.put("mirrors", layout.mirrors().size());
        out.put("supersteps", supersteps.get());
        out.put("mode", lastMode);
        out.put("messagesSent", messagesSent.get());
        out.put("messagesReceived", messagesReceived.get());
        out.put("bytesSent", bytesSent.get());
        out.put("algorithm", computation.name());
        return out;
    }
}
