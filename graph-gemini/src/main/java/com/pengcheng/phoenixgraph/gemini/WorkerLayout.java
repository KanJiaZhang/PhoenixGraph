package com.pengcheng.phoenixgraph.gemini;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The edges one worker holds, in both directions, with the two index tricks the paper adds to plain
 * CSR/CSC:
 *
 * <ul>
 *   <li><b>sparse side</b> - the out-edges of the vertices whose destinations this worker owns,
 *       grouped by source. A bitmap marks which sources have edges here at all, so a pushed message
 *       for a vertex with none is dropped in one bit test instead of an index lookup
 *       (the paper's bitmap-assisted CSR);</li>
 *   <li><b>dense side</b> - the in-edges of the vertices whose sources this worker owns, grouped by
 *       destination. Only vertices that actually have such edges are listed and their offsets are
 *       compressed, so a dense superstep walks a list proportional to the edges rather than to the
 *       whole vertex space (the paper's doubly-compressed CSC).</li>
 * </ul>
 *
 * <p>A vertex the worker does not own but holds edges of is a <b>mirror</b>: its edges are here, its
 * value is not. Which worker owns what is decided by the chunk, so it is answered by a range test.
 */
public final class WorkerLayout {

    private final int workerId;
    private final int begin;
    private final int end;
    private final int vertexCount;

    /** sparse side: source -> destinations stored here (CSR by source). */
    private final Map<Integer, List<GeminiGraph.Edge>> outEdges = new LinkedHashMap<>();
    private final int[] outBegin;
    private final int[] outEnd;
    /** the bitmap: whether a vertex has any out-edge stored here. */
    private final boolean[] hasOutEdges;
    /** sparse side flattened, in the order of the vertex array (the paper's contiguous chunks). */
    private final int[] outTarget;
    private final double[] outWeight;
    /** where a vertex's out-neighbours start in the flattened arrays (per mirrored source). */
    private final Map<Integer, Integer> outOffset = new LinkedHashMap<>();

    /** dense side: destination -> sources owned here (doubly-compressed CSC). */
    private final int[] denseVertex;
    private final int[] denseOffset;
    private final int[] denseSource;
    private final double[] denseWeight;
    private final Map<Integer, Integer> densePosition;

    /** The peers this worker talks to (partition id -> base url). */
    private final Map<Integer, String> peers;

    WorkerLayout(int workerId, int begin, int end, int vertexCount,
                 List<GeminiGraph.Edge> sparse, List<GeminiGraph.Edge> dense, Map<Integer, String> peers,
                 Map<Integer, Integer> globalOutDegree, Map<Integer, Integer> mirrorOwner,
                 Map<Integer, Set<Integer>> pushPeers) {
        this.workerId = workerId;
        this.begin = begin;
        this.end = end;
        this.vertexCount = vertexCount;
        this.peers = Map.copyOf(peers);
        this.globalOutDegree.putAll(globalOutDegree == null ? Map.of() : globalOutDegree);
        this.mirrorOwners.putAll(mirrorOwner == null ? Map.of() : mirrorOwner);
        if (pushPeers != null) {
            this.pushPeers.putAll(pushPeers);
        }

        // --- sparse side: group the out-edges by source, then flatten them in vertex order ---
        for (GeminiGraph.Edge edge : sparse) {
            outEdges.computeIfAbsent(edge.source(), k -> new ArrayList<>()).add(edge);
        }
        List<Integer> sources = new ArrayList<>(outEdges.keySet());
        sources.sort(Integer::compareTo);
        this.hasOutEdges = new boolean[vertexCount];
        int total = 0;
        for (Integer source : sources) {
            hasOutEdges[source] = true;
            outOffset.put(source, total);
            total += outEdges.get(source).size();
        }
        this.outTarget = new int[total];
        this.outWeight = new double[total];
        this.outBegin = new int[sources.size()];
        this.outEnd = new int[sources.size()];
        for (int i = 0; i < sources.size(); i++) {
            int source = sources.get(i);
            List<GeminiGraph.Edge> edges = outEdges.get(source);
            outBegin[i] = outOffset.get(source);
            outEnd[i] = outBegin[i] + edges.size();
            for (int j = 0; j < edges.size(); j++) {
                outTarget[outBegin[i] + j] = edges.get(j).target();
                outWeight[outBegin[i] + j] = edges.get(j).weight();
            }
        }

        // --- dense side: group the in-edges by destination, keeping only the vertices that have any ---
        Map<Integer, List<GeminiGraph.Edge>> inByDestination = new LinkedHashMap<>();
        for (GeminiGraph.Edge edge : dense) {
            inByDestination.computeIfAbsent(edge.target(), k -> new ArrayList<>()).add(edge);
        }
        List<Integer> destinations = new ArrayList<>(inByDestination.keySet());
        destinations.sort(Integer::compareTo);
        this.denseVertex = new int[destinations.size()];
        this.denseOffset = new int[destinations.size() + 1];
        this.densePosition = new LinkedHashMap<>();
        int denseTotal = 0;
        for (int i = 0; i < destinations.size(); i++) {
            denseVertex[i] = destinations.get(i);
            denseOffset[i] = denseTotal;
            densePosition.put(destinations.get(i), i);
            denseTotal += inByDestination.get(destinations.get(i)).size();
        }
        denseOffset[destinations.size()] = denseTotal;
        this.denseSource = new int[denseTotal];
        this.denseWeight = new double[denseTotal];
        for (int i = 0; i < destinations.size(); i++) {
            List<GeminiGraph.Edge> edges = inByDestination.get(destinations.get(i));
            for (int j = 0; j < edges.size(); j++) {
                denseSource[denseOffset[i] + j] = edges.get(j).source();
                denseWeight[denseOffset[i] + j] = edges.get(j).weight();
            }
        }
    }

    public int workerId() {
        return workerId;
    }

    public int begin() {
        return begin;
    }

    public int end() {
        return end;
    }

    public int vertexCount() {
        return vertexCount;
    }

    /** Whether this worker owns a vertex (its value lives here). */
    public boolean owns(int vertex) {
        return vertex >= begin && vertex < end;
    }

    /** The worker that owns a vertex (this one when the vertex is in its chunk). */
    public int ownerOf(int vertex) {
        if (owns(vertex)) {
            return workerId;
        }
        // the caller cuts chunks, so ownership is a range test once the ranges are known; a worker
        // that holds a mirror knows the owner from the layout it was given
        Integer owner = mirrorOwners.get(vertex);
        if (owner == null) {
            throw new IllegalStateException("No owner known for vertex " + vertex
                    + "; the layout must list every mirror's partition");
        }
        return owner;
    }

    /** The peers this worker sends to and receives from. */
    public Map<Integer, String> peers() {
        return peers;
    }

    /** Whether a vertex has out-edges stored here (the bitmap test of the sparse mode). */
    public boolean hasOutEdges(int vertex) {
        return vertex >= 0 && vertex < hasOutEdges.length && hasOutEdges[vertex];
    }

    /** The out-edges stored here for a source (the mirror's push targets). */
    public List<GeminiGraph.Edge> outEdgesOf(int source) {
        Integer offset = outOffset.get(source);
        if (offset == null) {
            return List.of();
        }
        int endIndex = offset + outEdges.get(source).size();
        List<GeminiGraph.Edge> edges = new ArrayList<>(endIndex - offset);
        for (int i = offset; i < endIndex; i++) {
            edges.add(new GeminiGraph.Edge(source, outTarget[i], outWeight[i]));
        }
        return edges;
    }

    /** The global out-degree of a vertex - what Gemini's mode rule counts for an active vertex. */
    public int outDegree(int vertex) {
        Integer degree = globalOutDegree.get(vertex);
        return degree == null ? 0 : degree;
    }

    /** The out-edges stored here for a source, which may be fewer than its global out-degree. */
    public int localOutDegree(int vertex) {
        List<GeminiGraph.Edge> edges = outEdges.get(vertex);
        return edges == null ? 0 : edges.size();
    }

    /**
     * The workers holding out-edges of an owned vertex: a sparse push sends one batch to each, and
     * each of them walks the edges it holds (whose destinations it owns).
     */
    public Set<Integer> pushTargets(int vertex) {
        Set<Integer> targets = pushPeers.get(vertex);
        return targets == null ? Set.of() : targets;
    }

    /** How many sources have out-edges here. */
    public int sparseSources() {
        return outBegin.length;
    }

    /** How many vertices have in-edges sourced here. */
    public int denseVertices() {
        return denseVertex.length;
    }

    /** The vertices with in-edges sourced here (the doubly-compressed list). */
    public int[] denseVerticesInOrder() {
        return denseVertex;
    }

    /** The in-edges sourced here for a destination. */
    public List<GeminiGraph.Edge> inEdgesOf(int destination) {
        Integer position = densePosition.get(destination);
        if (position == null) {
            return List.of();
        }
        List<GeminiGraph.Edge> edges = new ArrayList<>(denseOffset[position + 1] - denseOffset[position]);
        for (int i = denseOffset[position]; i < denseOffset[position + 1]; i++) {
            edges.add(new GeminiGraph.Edge(denseSource[i], destination, denseWeight[i]));
        }
        return edges;
    }

    /** The vertices whose in-edges are stored here, for a report. */
    List<Integer> denseDestinations() {
        List<Integer> out = new ArrayList<>(denseVertex.length);
        for (int vertex : denseVertex) {
            out.add(vertex);
        }
        return out;
    }

    /** The mirrors: remote vertices this worker holds edges of. */
    public Set<Integer> mirrors() {
        Set<Integer> mirrors = new LinkedHashSet<>();
        for (Integer source : outEdges.keySet()) {
            if (!owns(source)) {
                mirrors.add(source);
            }
        }
        for (int destination : denseVertex) {
            if (!owns(destination)) {
                mirrors.add(destination);
            }
        }
        return mirrors;
    }

    /** The owner partition of every mirror, so a message can be addressed to it. */
    private final Map<Integer, Integer> mirrorOwners = new LinkedHashMap<>();

    /** The global out-degree of every owned vertex: what the mode rule is computed from. */
    private final Map<Integer, Integer> globalOutDegree = new LinkedHashMap<>();

    /** For an owned vertex, the workers holding its out-edges: where a sparse push has to go. */
    private final Map<Integer, Set<Integer>> pushPeers = new LinkedHashMap<>();

    Map<Integer, Integer> mirrorOwners() {
        return mirrorOwners;
    }

    Map<Integer, Set<Integer>> pushPeers() {
        return pushPeers;
    }
}
