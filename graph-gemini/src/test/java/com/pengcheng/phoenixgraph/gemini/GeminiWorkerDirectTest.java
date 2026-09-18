package com.pengcheng.phoenixgraph.gemini;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two halves of a Gemini execution, driven directly instead of over HTTP, and the HTTP layer
 * driven on its own.
 *
 * <p>Keeping them apart is what makes a failure readable: if a value is wrong here, the algorithm or
 * the partitioning is wrong; if the direct run is right and the cluster run is not, the transport is.
 */
class GeminiWorkerDirectTest {

    /** Two vertices, one edge 0 -> 1, both owned by the first of two workers. */
    private static GeminiGraph tiny() {
        return GeminiGraph.of(List.of("a", "b"),
                List.of(new GeminiGraph.Edge(0, 1, 1.0), new GeminiGraph.Edge(1, 0, 1.0)));
    }

    private static List<GeminiWorker> workers(GeminiGraph graph, int count, String algorithm,
                                              Map<String, Object> params) {
        GeminiPartitioner.Plan plan = new GeminiPartitioner().chunk(graph, count);
        Map<Integer, String> urls = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            urls.put(i, "http://127.0.0.1:" + (19500 + i));
        }
        List<GeminiLauncherLayout.WorkerPayload> payloads = GeminiLauncherLayout.payloads(graph, plan, urls);
        List<GeminiWorker> workers = new ArrayList<>();
        for (GeminiLauncherLayout.WorkerPayload payload : payloads) {
            ObjectNode document = GeminiWire.writePayload(payload, algorithm, params == null ? Map.of() : params);
            document.put("totalEdges", graph.edges().size());
            workers.add(GeminiWire.readWorker(document));
        }
        return workers;
    }

    /** One superstep, with the batches handed over in process (what the HTTP server does for a peer). */
    private static int superstep(List<GeminiWorker> workers, int round, Map<String, Object> shared) {
        for (GeminiWorker worker : workers) {
            worker.share(shared);
        }
        long activeEdges = 0;
        for (GeminiWorker worker : workers) {
            activeEdges += worker.activeEdges();
        }
        long totalEdges = workers.get(0).totalEdges();
        boolean sparse = activeEdges < totalEdges / 20;
        for (GeminiWorker worker : workers) {
            for (Map.Entry<Integer, Map<Integer, Object>> batch : worker.exchange(sparse).entrySet()) {
                workers.get(batch.getKey()).receive(round, batch.getValue());
            }
        }
        int activated = 0;
        for (GeminiWorker worker : workers) {
            activated += worker.compute(round, sparse);
        }
        return activated;
    }

    private static Map<String, Object> run(GeminiGraph graph, int workerCount, String algorithm,
                                            Map<String, Object> params) {
        List<GeminiWorker> workers = workers(graph, workerCount, algorithm, params);
        GeminiComputation computation = GeminiAlgorithms.of(algorithm, params);
        Map<String, Object> shared = Map.of();
        for (int round = 0; round < computation.maxSupersteps(); round++) {
            int activated = superstep(workers, round, shared);
            if (computation.converged(activated, round)) {
                break;
            }
            Map<String, Object> collected = new HashMap<>();
            for (GeminiWorker worker : workers) {
                for (Map.Entry<Object, Double> entry : worker.aggregate().entrySet()) {
                    collected.merge(String.valueOf(entry.getKey()), entry.getValue(), (a, b) ->
                            ((Number) a).doubleValue() + ((Number) b).doubleValue());
                }
            }
            shared = collected;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (GeminiWorker worker : workers) {
            worker.values().forEach((vertex, value) -> values.put(String.valueOf(graph.id(vertex)), value));
        }
        return values;
    }

    @Test
    void oneWorkerOnATwoVertexGraph() {
        Map<String, Object> values = run(tiny(), 1, GeminiAlgorithms.PAGERANK, Map.of("iterations", 1));

        // one iteration of sum-based PageRank: each vertex collects what its in-neighbours had
        assertEquals(1.0, ((Number) values.get("a")).doubleValue(), 1e-9);
        assertEquals(1.0, ((Number) values.get("b")).doubleValue(), 1e-9);
    }

    @Test
    void twoWorkersOnATwoVertexGraph() {
        Map<String, Object> values = run(tiny(), 2, GeminiAlgorithms.PAGERANK, Map.of("iterations", 1));

        assertEquals(1.0, ((Number) values.get("a")).doubleValue(), 1e-9);
        assertEquals(1.0, ((Number) values.get("b")).doubleValue(), 1e-9);
    }

    @Test
    void connectedComponentsOnOneWorker() {
        List<Object> ids = List.of("a", "b", "c");
        List<GeminiGraph.Edge> edges = List.of(new GeminiGraph.Edge(0, 1, 1.0), new GeminiGraph.Edge(1, 0, 1.0));

        Map<String, Object> values = run(GeminiGraph.of(ids, edges), 1,
                GeminiAlgorithms.CONNECTED_COMPONENTS, Map.of());

        assertEquals(0L, ((Number) values.get("a")).longValue());
        assertEquals(0L, ((Number) values.get("b")).longValue());
        assertEquals(2L, ((Number) values.get("c")).longValue(), "c is alone");
    }

    @Test
    void connectedComponentsOnTwoWorkers() {
        List<Object> ids = List.of("a", "b", "c");
        List<GeminiGraph.Edge> edges = List.of(new GeminiGraph.Edge(0, 1, 1.0), new GeminiGraph.Edge(1, 0, 1.0));

        Map<String, Object> values = run(GeminiGraph.of(ids, edges), 2,
                GeminiAlgorithms.CONNECTED_COMPONENTS, Map.of());

        assertEquals(0L, ((Number) values.get("a")).longValue());
        assertEquals(0L, ((Number) values.get("b")).longValue());
        assertEquals(2L, ((Number) values.get("c")).longValue());
    }

    @Test
    void aChainIsReachedInHopsOnFourWorkers() {
        List<Object> ids = new ArrayList<>();
        for (int v = 0; v < 8; v++) {
            ids.add("v" + v);
        }
        List<GeminiGraph.Edge> edges = new ArrayList<>();
        for (int v = 0; v < 7; v++) {
            edges.add(new GeminiGraph.Edge(v, v + 1, 1.0));
            edges.add(new GeminiGraph.Edge(v + 1, v, 1.0));
        }

        Map<String, Object> values = run(GeminiGraph.of(ids, edges), 4, GeminiAlgorithms.SSSP,
                Map.of("root", 0));

        for (int v = 0; v < 8; v++) {
            assertEquals((double) v, ((Number) values.get("v" + v)).doubleValue(), 1e-9, "v" + v);
        }
    }

    @Test
    void theHttpLayerLoadsAndRunsASuperstep() throws Exception {
        GeminiGraph graph = tiny();
        List<GeminiWorker> workers = workers(graph, 2, GeminiAlgorithms.CONNECTED_COMPONENTS, Map.of());

        try (GeminiWorkerServer first = new GeminiWorkerServer(19510).start();
             GeminiWorkerServer second = new GeminiWorkerServer(19511).start()) {
            GeminiPartitioner.Plan plan = new GeminiPartitioner().chunk(graph, 2);
            Map<Integer, String> urls = Map.of(0, "http://127.0.0.1:19510", 1, "http://127.0.0.1:19511");
            List<GeminiLauncherLayout.WorkerPayload> payloads = GeminiLauncherLayout.payloads(graph, plan, urls);
            for (GeminiLauncherLayout.WorkerPayload payload : payloads) {
                ObjectNode document = GeminiWire.writePayload(payload, GeminiAlgorithms.CONNECTED_COMPONENTS,
                        Map.of());
                document.put("totalEdges", graph.edges().size());
                Map<String, Object> answer = GeminiClient.load("http://127.0.0.1:" + (19510 + payload.workerId()),
                        document);
                assertEquals(Boolean.TRUE, answer.get("loaded"), "worker " + payload.workerId() + " loaded");
            }
            // a probe, an exchange and a compute, each over HTTP, on both workers
            long activeEdges = 0;
            for (int i = 0; i < 2; i++) {
                activeEdges += ((Number) GeminiClient.activeEdges("http://127.0.0.1:" + (19510 + i))
                        .get("activeEdges")).longValue();
            }
            boolean sparse = activeEdges < graph.edges().size() / 20;
            for (int i = 0; i < 2; i++) {
                Map<String, Object> exchanged = GeminiClient.exchange("http://127.0.0.1:" + (19510 + i), 0, sparse);
                assertNotNull(exchanged.get("delivered"));
                assertTrue(((Number) exchanged.get("batches")).intValue() >= 0);
            }
            for (int i = 0; i < 2; i++) {
                Map<String, Object> computed = GeminiClient.compute("http://127.0.0.1:" + (19510 + i), 0, sparse);
                assertNotNull(computed.get("activated"), "worker " + i + " computed: " + computed);
            }
            Map<Integer, Object> values = GeminiClient.values("http://127.0.0.1:19510");
            assertNotNull(values);
            assertEquals(2, workers.size());
        }
    }

    @Test
    void theHttpLayerReportsAWorkerThatIsNotLoaded() throws Exception {
        try (GeminiWorkerServer server = new GeminiWorkerServer(19512).start()) {
            assertNotNull(server);
            IllegalStateException error = org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalStateException.class,
                    () -> GeminiClient.exchange("http://127.0.0.1:19512", 0, true));
            assertTrue(error.getMessage().contains("no layout"),
                    "the worker says what is missing: " + error.getMessage());
        }
    }
}
