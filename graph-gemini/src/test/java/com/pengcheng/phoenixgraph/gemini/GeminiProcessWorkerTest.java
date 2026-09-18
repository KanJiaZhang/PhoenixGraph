package com.pengcheng.phoenixgraph.gemini;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The standalone deployment: each worker is its own program, started by the launcher the way a helper
 * script or a YARN container would start it.
 *
 * <p>This is the other half of the design's promise - a worker is a process with a port, so it can run
 * anywhere - and it is worth a test of its own, because the path is different: the launcher spawns
 * {@code GeminiWorkerMain}, waits for the ports to answer, and the rest of the run is identical (the
 * same HTTP protocol, the same supersteps). Two workers are enough to prove it: the point is that the
 * computation crosses a process boundary.
 */
class GeminiProcessWorkerTest {

    @Test
    void twoWorkersAsTheirOwnProgramsComputeTheRing() {
        List<Object> ids = new ArrayList<>();
        for (int v = 0; v < 8; v++) {
            ids.add("v" + v);
        }
        List<GeminiGraph.Edge> edges = new ArrayList<>();
        for (int v = 0; v < 8; v++) {
            edges.add(new GeminiGraph.Edge(v, (v + 1) % 8, 1.0));
            edges.add(new GeminiGraph.Edge((v + 1) % 8, v, 1.0));
        }
        GeminiGraph graph = GeminiGraph.of(ids, edges);

        try (GeminiCluster cluster = new GeminiCluster(GeminiCluster.Config.processes(2)).start()) {
            GeminiCluster.Result cc = cluster.run(graph, GeminiAlgorithms.CONNECTED_COMPONENTS, Map.of());

            assertEquals(2, cc.stats().workers());
            assertEquals(0L, ((Number) cc.value("v0")).longValue(), "the ring is one component");
            for (int v = 0; v < 8; v++) {
                assertEquals(0L, ((Number) cc.value("v" + v)).longValue(),
                        "v" + v + " in the component of the first vertex");
            }
            assertEquals(1L, ((Number) cluster.run(graph, GeminiAlgorithms.SSSP, Map.of("root", 0))
                    .value("v1")).longValue(), "one hop from the root");

            // the child processes answer on their own ports, with their own state
            for (int i = 0; i < 2; i++) {
                Map<String, Object> status = GeminiClient.status(cluster.urlOf(i));
                assertEquals(i, ((Number) status.get("worker")).intValue());
                assertTrue(((Number) status.get("supersteps")).intValue() > 0,
                        "worker " + i + " ran supersteps in its own process: " + status);
            }
        }
    }
}
