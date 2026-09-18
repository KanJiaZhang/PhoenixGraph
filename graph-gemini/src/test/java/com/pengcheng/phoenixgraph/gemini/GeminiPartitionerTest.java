package com.pengcheng.phoenixgraph.gemini;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The partitioning half of Gemini: contiguous chunks, the dual edge copy, the mirrors, and the
 * out-degrees the mode rule needs.
 *
 * <p>The graph used throughout is the paper's own example (Figure 5): six vertices, and edges
 * {@code 0->1, 1->0, 1->4, 2->3, 3->2, 4->5, 5->4, 5->0, 0->2, 2->5}, cut into three chunks of two
 * vertices each.
 */
class GeminiPartitionerTest {

    private static GeminiGraph paperGraph() {
        List<Object> ids = new ArrayList<>();
        for (int v = 0; v < 6; v++) {
            ids.add(v);
        }
        List<GeminiGraph.Edge> edges = new ArrayList<>();
        for (int[] pair : new int[][]{{0, 1}, {1, 0}, {1, 4}, {2, 3}, {3, 2}, {4, 5}, {5, 4}, {5, 0}, {0, 2}, {2, 5}}) {
            edges.add(new GeminiGraph.Edge(pair[0], pair[1], 1.0));
        }
        return GeminiGraph.of(ids, edges);
    }

    @Test
    void theVertexArrayIsCutIntoContiguousChunks() {
        GeminiPartitioner.Plan plan = new GeminiPartitioner().chunk(paperGraph(), 3);

        assertEquals(3, plan.partitions().size());
        assertEquals("chunk", plan.strategy());
        for (int i = 0; i < 3; i++) {
            GeminiPartitioner.Partition partition = plan.partitions().get(i);
            assertEquals(i * 2, partition.begin(), "chunk " + i + " starts where the previous ended");
            assertEquals(i * 2 + 2, partition.end());
            assertEquals(2, partition.ownedVertices());
        }
        assertEquals(6, plan.partitions().get(2).end(), "the last chunk reaches the end of the array");
    }

    @Test
    void everyEdgeIsStoredTwiceOncePerEndpoint() {
        GeminiGraph graph = paperGraph();
        GeminiPartitioner.Plan plan = new GeminiPartitioner().chunk(graph, 3);

        int sparse = 0;
        int dense = 0;
        for (GeminiPartitioner.Partition partition : plan.partitions()) {
            sparse += partition.sparse().size();
            dense += partition.dense().size();
            for (GeminiGraph.Edge edge : partition.sparse()) {
                assertTrue(partition.owns(edge.target()),
                        "a sparse edge is stored where its destination lives: " + edge);
            }
            for (GeminiGraph.Edge edge : partition.dense()) {
                assertTrue(partition.owns(edge.source()),
                        "a dense edge is stored where its source lives: " + edge);
            }
        }
        assertEquals(graph.edges().size(), sparse, "one sparse copy per edge");
        assertEquals(graph.edges().size(), dense, "one dense copy per edge");
    }

    @Test
    void aPartitionKnowsItsMirrors() {
        GeminiPartitioner.Plan plan = new GeminiPartitioner().chunk(paperGraph(), 3);

        // chunk 0 owns 0 and 1; edge 2->3 is stored on chunk 1 (its destination), so chunk 1 holds a
        // mirror of 2, and edge 1->4 puts a mirror of 4 on chunk 2
        List<Integer> mirrors = plan.mirrorCounts();
        assertEquals(3, mirrors.size());
        assertTrue(mirrors.get(1) > 0, "chunk 1 holds remote vertices: " + mirrors);
        assertTrue(mirrors.get(2) > 0, "chunk 2 holds remote vertices: " + mirrors);
    }

    @Test
    void theHybridMetricBalancesChunksWithHeavyDegrees() {
        // one vertex has all the edges: a vertex-balanced cut would put it with five idle vertices,
        // the hybrid metric spreads the edges instead
        List<Object> ids = new ArrayList<>();
        for (int v = 0; v < 100; v++) {
            ids.add(v);
        }
        List<GeminiGraph.Edge> edges = new ArrayList<>();
        for (int v = 1; v < 100; v++) {
            edges.add(new GeminiGraph.Edge(0, v, 1.0));
        }
        GeminiGraph graph = GeminiGraph.of(ids, edges);

        // balancing the incoming edges alone (alpha 0) hands almost every vertex to one chunk, because
        // the heavy vertex's edges dwarf the vertices; the hybrid metric keeps the vertex counts even
        GeminiPartitioner.Plan edgesOnly = new GeminiPartitioner(0).chunk(graph, 4);
        GeminiPartitioner.Plan balanced = new GeminiPartitioner().chunk(graph, 4);

        assertEquals(100, balanced.partitions().stream().mapToInt(GeminiPartitioner.Partition::ownedVertices).sum(),
                "every vertex belongs to exactly one chunk");
        assertTrue(spread(balanced) <= 6, "the hybrid metric keeps the chunks close: " + spread(balanced));
        assertTrue(spread(edgesOnly) > spread(balanced),
                "balancing edges alone is lopsided: " + spread(edgesOnly) + " vs " + spread(balanced));
        assertEquals(24.0, balanced.alpha(), "the default alpha is the paper's 8 * (p - 1)");
    }

    /** How far the largest and smallest chunks differ in vertex count. */
    private static int spread(GeminiPartitioner.Plan plan) {
        int largest = 0;
        int smallest = Integer.MAX_VALUE;
        for (GeminiPartitioner.Partition partition : plan.partitions()) {
            largest = Math.max(largest, partition.ownedVertices());
            smallest = Math.min(smallest, partition.ownedVertices());
        }
        return largest - smallest;
    }

    @Test
    void everyPayloadCarriesWhatAWorkerNeeds() {
        GeminiGraph graph = paperGraph();
        GeminiPartitioner.Plan plan = new GeminiPartitioner().chunk(graph, 3);
        Map<Integer, String> urls = new LinkedHashMap<>();
        for (int i = 0; i < 3; i++) {
            urls.put(i, "http://127.0.0.1:" + (19100 + i));
        }

        List<GeminiLauncherLayout.WorkerPayload> payloads = GeminiLauncherLayout.payloads(graph, plan, urls);

        assertEquals(3, payloads.size());
        for (GeminiLauncherLayout.WorkerPayload payload : payloads) {
            assertEquals(3, payload.partitions());
            assertEquals(3, payload.peers().size(), "a worker can reach every peer");
            assertFalse(payload.sparse().isEmpty());
            assertFalse(payload.dense().isEmpty());
            for (Map.Entry<Integer, Integer> entry : payload.outDegree().entrySet()) {
                assertTrue(plan.ownerOf(entry.getKey()).owns(entry.getKey()));
                assertTrue(entry.getValue() >= 0);
            }
            assertFalse(payload.mirrorOwner().isEmpty(), "chunk " + payload.workerId() + " has mirrors");
            for (Map.Entry<Integer, Set<Integer>> entry : payload.pushPeers().entrySet()) {
                for (int target : entry.getValue()) {
                    assertTrue(target >= 0 && target < 3, "a push target is a partition id: " + target);
                }
            }
        }
        // vertex 5 has out-edges to 4 and 0, which live on chunks 2 and 0: the worker owning 5 must be
        // told to push to both, otherwise its message would never reach them
        Set<Integer> targets = payloads.get(2).pushPeers().getOrDefault(5, Set.of());
        assertEquals(Set.of(2, 0), targets, "the owner of 5 pushes to the holders of its out-edges");
    }

    @Test
    void theLayoutBuildsTheTwoIndexTricks() {
        GeminiGraph graph = paperGraph();
        GeminiPartitioner.Plan plan = new GeminiPartitioner().chunk(graph, 3);
        Map<Integer, String> urls = Map.of(0, "http://127.0.0.1:19100", 1, "http://127.0.0.1:19101",
                2, "http://127.0.0.1:19102");
        GeminiLauncherLayout.WorkerPayload payload = GeminiLauncherLayout.payloads(graph, plan, urls).get(0);

        WorkerLayout layout = GeminiLauncherLayout.layoutOf(payload);

        // vertex 0 is owned by chunk 0 and has an out-edge to 1, which is owned here: the sparse side
        // holds it, so the bitmap says yes and the edges are reachable
        assertTrue(layout.hasOutEdges(0));
        assertEquals(List.of(1), layout.outEdgesOf(0).stream().map(GeminiGraph.Edge::target).toList());
        assertTrue(layout.hasOutEdges(5), "chunk 0 holds 5's edge to 0: it is a mirror of 5 there");
        assertFalse(layout.hasOutEdges(3), "3's only edge goes to 2, which chunk 1 owns");
        // the dense side lists only vertices that have in-edges sourced here
        assertEquals(List.of(0, 1, 2, 4), List.copyOf(layout.denseDestinations()),
                "1 -> 4 is sourced here, so 4 has a dense entry even though chunk 2 owns it");
        assertEquals(2, layout.outDegree(0), "the global out-degree is what the mode rule counts");
        assertEquals(0, layout.outDegree(5), "a mirror's out-degree is the owner's business");
        WorkerLayout owner = GeminiLauncherLayout.layoutOf(
                GeminiLauncherLayout.payloads(graph, plan, urls).get(2));
        assertEquals(2, owner.outDegree(5), "vertex 5 points at 4 and 0, which its owner counts");
        assertTrue(layout.owns(0) && !layout.owns(4));
        assertEquals(2, layout.ownerOf(4), "a mirror knows which worker owns it");
    }

    @Test
    void aVertexOutsideEveryChunkIsAnError() {
        GeminiPartitioner.Plan plan = new GeminiPartitioner().chunk(paperGraph(), 3);

        assertNotNull(plan.ownerOf(5));
        assertThrows(IllegalArgumentException.class, () -> plan.ownerOf(6));
    }
}
