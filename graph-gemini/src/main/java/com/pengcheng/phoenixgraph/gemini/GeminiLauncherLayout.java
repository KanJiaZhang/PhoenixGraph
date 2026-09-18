package com.pengcheng.phoenixgraph.gemini;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds what every worker has to be told: which vertices it owns, the edges it stores in both
 * directions, the out-degree of its vertices (the mode rule counts them), who owns each of its
 * mirrors, and where its vertices' out-edges live (so a push knows whom to send to).
 *
 * <p>This is the launcher's half of Gemini's partitioning: the cuts are made once over the whole
 * graph ({@link GeminiPartitioner}), and everything a worker needs afterwards is derived here, so a
 * worker can be started anywhere - in this process, or as a program that is handed its layout over
 * HTTP - and still run the same supersteps.
 */
public final class GeminiLauncherLayout {

    /** One worker's payload: everything it needs to run without seeing the whole graph. */
    public record WorkerPayload(int workerId, int begin, int end, int vertexCount, int partitions,
                                Map<Integer, String> peers,
                                List<GeminiGraph.Edge> sparse, List<GeminiGraph.Edge> dense,
                                Map<Integer, Integer> outDegree, Map<Integer, Integer> mirrorOwner,
                                Map<Integer, Set<Integer>> pushPeers) {

        public WorkerPayload {
            peers = Map.copyOf(peers);
            sparse = List.copyOf(sparse);
            dense = List.copyOf(dense);
            outDegree = Map.copyOf(outDegree);
            mirrorOwner = Map.copyOf(mirrorOwner);
            pushPeers = Map.copyOf(pushPeers);
        }
    }

    private GeminiLauncherLayout() {
    }

    /** Derives every worker's payload from the plan of a chunk partitioner. */
    public static List<WorkerPayload> payloads(GeminiGraph graph, GeminiPartitioner.Plan plan,
                                               Map<Integer, String> urls) {
        int partitions = plan.partitions().size();
        int[] owner = new int[graph.vertexCount()];
        for (GeminiPartitioner.Partition partition : plan.partitions()) {
            for (int v = partition.begin(); v < partition.end(); v++) {
                owner[v] = partition.id();
            }
        }
        // where each vertex's out-edges are held: a mirror of v lives on the worker that owns the
        // destination of an out-edge, which is where a sparse push has to deliver v's message
        Map<Integer, Set<Integer>> pushPeers = new LinkedHashMap<>();
        int[] outDegree = new int[graph.vertexCount()];
        for (GeminiGraph.Edge edge : graph.edges()) {
            outDegree[edge.source()]++;
            pushPeers.computeIfAbsent(edge.source(), k -> new LinkedHashSet<>()).add(owner[edge.target()]);
        }
        List<WorkerPayload> payloads = new ArrayList<>(partitions);
        for (GeminiPartitioner.Partition partition : plan.partitions()) {
            Map<Integer, Integer> owned = new LinkedHashMap<>();
            for (int v = partition.begin(); v < partition.end(); v++) {
                owned.put(v, outDegree[v]);
            }
            Map<Integer, Integer> mirrors = new LinkedHashMap<>();
            for (GeminiGraph.Edge edge : partition.sparse()) {
                if (!partition.owns(edge.source())) {
                    mirrors.put(edge.source(), owner[edge.source()]);
                }
            }
            for (GeminiGraph.Edge edge : partition.dense()) {
                if (!partition.owns(edge.target())) {
                    mirrors.put(edge.target(), owner[edge.target()]);
                }
            }
            Map<Integer, Set<Integer>> push = new LinkedHashMap<>();
            for (int v = partition.begin(); v < partition.end(); v++) {
                Set<Integer> targets = pushPeers.get(v);
                if (targets != null && !targets.isEmpty()) {
                    push.put(v, targets);
                }
            }
            payloads.add(new WorkerPayload(partition.id(), partition.begin(), partition.end(),
                    graph.vertexCount(), partitions, urls, partition.sparse(), partition.dense(),
                    owned, mirrors, push));
        }
        return payloads;
    }

    /** The layout of one payload (what a worker builds when it is loaded). */
    public static WorkerLayout layoutOf(WorkerPayload payload) {
        return new WorkerLayout(payload.workerId(), payload.begin(), payload.end(), payload.vertexCount(),
                payload.sparse(), payload.dense(), payload.peers(), payload.outDegree(),
                payload.mirrorOwner(), payload.pushPeers());
    }
}
