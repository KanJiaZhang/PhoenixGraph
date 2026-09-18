package com.pengcheng.phoenixgraph.gemini;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Splits a graph the way Gemini does: the vertex array is cut into {@code p} <b>contiguous chunks</b>,
 * one per worker, and every edge is stored twice - once on the chunk its destination belongs to (the
 * outgoing, "sparse" copy a push walks) and once on the chunk its source belongs to (the incoming,
 * "dense" copy a pull walks).
 *
 * <p>Cutting a contiguous array is cheap and keeps the vertex access locality of the input (a vertex
 * state array is indexed by dense id, so a chunk is a range and no id conversion is needed), but a
 * naive cut balances the wrong thing: vertex counts ignore the power-law degrees, and edge counts
 * ignore how many vertices a partition has to touch. Gemini balances the hybrid metric
 * {@code alpha * |V_i| + |E^D_i|} ({@link #alpha} defaults to {@code 8 * (p - 1)}), which weighs the
 * vertices a partition owns - its memory access locality - against the incoming edges it has to
 * process.
 */
public final class GeminiPartitioner {

    /** How much a vertex weighs against an edge in the balancing metric. */
    private final double alpha;

    public GeminiPartitioner() {
        this(-1);
    }

    /** @param alpha the vertex weight; a negative value means {@code 8 * (p - 1)} as in the paper */
    public GeminiPartitioner(double alpha) {
        this.alpha = alpha;
    }

    /** One partition: the contiguous vertex range it owns and the edges it stores. */
    public record Partition(int id, int begin, int end, List<GeminiGraph.Edge> sparse,
                            List<GeminiGraph.Edge> dense) {

        public Partition {
            sparse = List.copyOf(sparse);
            dense = List.copyOf(dense);
        }

        /** The vertices this partition owns. */
        public int ownedVertices() {
            return end - begin;
        }

        public boolean owns(int vertex) {
            return vertex >= begin && vertex < end;
        }
    }

    /** How a partitioner cut the graph, with the numbers the caller may want to report. */
    public record Plan(List<Partition> partitions, String strategy, double alpha) {

        public Plan {
            partitions = List.copyOf(partitions);
        }

        /** The partition owning a vertex. */
        public Partition ownerOf(int vertex) {
            for (Partition partition : partitions) {
                if (partition.owns(vertex)) {
                    return partition;
                }
            }
            throw new IllegalArgumentException("Vertex " + vertex + " is in no partition");
        }

        /** How many edges each partition stores, in both directions together. */
        public List<Integer> storedEdges() {
            List<Integer> counts = new ArrayList<>(partitions.size());
            for (Partition partition : partitions) {
                counts.add(partition.sparse().size() + partition.dense().size());
            }
            return counts;
        }

        /** The mirror vertices of each partition: remote vertices it holds edges of. */
        public List<Integer> mirrorCounts() {
            List<Integer> counts = new ArrayList<>(partitions.size());
            for (Partition partition : partitions) {
                Set<Integer> mirrors = new LinkedHashSet<>();
                for (GeminiGraph.Edge edge : partition.sparse()) {
                    if (!partition.owns(edge.source())) {
                        mirrors.add(edge.source());
                    }
                }
                for (GeminiGraph.Edge edge : partition.dense()) {
                    if (!partition.owns(edge.target())) {
                        mirrors.add(edge.target());
                    }
                }
                counts.add(mirrors.size());
            }
            return counts;
        }
    }

    /** Cuts the vertex array into contiguous chunks balanced by {@code alpha * |V| + |E^D|}. */
    public Plan chunk(GeminiGraph graph, int partitions) {
        int count = Math.max(1, partitions);
        double alpha = this.alpha >= 0 ? this.alpha : 8.0 * (count - 1);
        int[] cuts = chunkBoundaries(graph, count, alpha);
        List<Partition> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(edgesOf(graph, i, cuts[i], cuts[i + 1], cuts));
        }
        return new Plan(out, "chunk", alpha);
    }

    /**
     * The cut positions: {@code p} contiguous ranges of nearly equal {@code alpha * |V_i| + |E^D_i|}.
     *
     * <p>The metric is evaluated with a running estimate of the incoming edges of the vertices seen so
     * far (each edge contributes to the metric of the partition owning its <b>source</b>, which is not
     * known until the cuts are), so the cut is refined in a few passes: start from a plain vertex cut,
     * compute the metric per partition, then move the cuts towards balance. Two refinement passes are
     * enough for the metric to settle, and a pass is linear in the edges.
     */
    private int[] chunkBoundaries(GeminiGraph graph, int partitions, double alpha) {
        int vertices = graph.vertexCount();
        int[] cuts = new int[partitions + 1];
        for (int i = 0; i <= partitions; i++) {
            cuts[i] = (int) Math.floor((double) vertices * i / partitions);
        }
        if (graph.edges().isEmpty() || partitions == 1) {
            return cuts;
        }
        double[] outWeight = new double[vertices];
        for (GeminiGraph.Edge edge : graph.edges()) {
            outWeight[edge.source()] += 1.0;
        }
        double[] prefix = new double[vertices + 1];
        for (int v = 0; v < vertices; v++) {
            prefix[v + 1] = prefix[v] + alpha + outWeight[v];
        }
        double total = prefix[vertices];
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 1; i < partitions; i++) {
                int low = Math.min(cuts[i - 1] + 1, vertices);
                int high = Math.max(low, vertices - (partitions - i));
                cuts[i] = positionOf(prefix, total * i / partitions, low, high);
            }
            // the cuts must not go backwards, and the last one is the end of the array
            for (int i = 1; i < partitions; i++) {
                cuts[i] = Math.max(cuts[i], cuts[i - 1]);
            }
        }
        cuts[0] = 0;
        cuts[partitions] = vertices;
        return cuts;
    }

    /**
     * The vertex index whose prefix weight is closest to {@code target}.
     *
     * <p>The window is clamped: a graph with fewer vertices than workers (a test, a small subgraph)
     * would otherwise ask for a cut position outside the array.
     */
    private static int positionOf(double[] prefix, double target, int low, int high) {
        int last = prefix.length - 1;
        int from = Math.max(0, Math.min(low, last));
        int to = Math.max(from, Math.min(high, last));
        int best = from;
        double bestDistance = Double.MAX_VALUE;
        for (int v = from; v <= to; v++) {
            double distance = Math.abs(prefix[v] - target);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = v;
            }
        }
        return best;
    }

    /** The edges of one partition: destined to its chunk (sparse) and sourced from it (dense). */
    private static Partition edgesOf(GeminiGraph graph, int id, int begin, int end, int[] cuts) {
        List<GeminiGraph.Edge> sparse = new ArrayList<>();
        List<GeminiGraph.Edge> dense = new ArrayList<>();
        for (GeminiGraph.Edge edge : graph.edges()) {
            if (edge.target() >= begin && edge.target() < end) {
                sparse.add(edge);
            }
            if (edge.source() >= begin && edge.source() < end) {
                dense.add(edge);
            }
        }
        return new Partition(id, begin, end, sparse, dense);
    }

    /** The vertices a partition owns, for a test or a report. */
    public static List<Integer> ownedVertices(Partition partition) {
        List<Integer> ids = new ArrayList<>(partition.ownedVertices());
        for (int v = partition.begin(); v < partition.end(); v++) {
            ids.add(v);
        }
        return ids;
    }
}
