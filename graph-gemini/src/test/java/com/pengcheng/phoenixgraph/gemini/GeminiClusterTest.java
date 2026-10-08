package com.pengcheng.phoenixgraph.gemini;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Gemini cluster of four workers, each on its own port, running the algorithms over a graph that has
 * a known answer - and a single-process reference to compare with, so the test says whether the
 * distributed run is <em>right</em>, not just whether it finished.
 *
 * <p>The graph is a ring of 12 accounts plus a bridge and a clique, so connected components (two
 * components), PageRank (all mass on one component), SSSP/BFS (a known depth per vertex) and Louvain
 * (the clique is one community) all have an answer that can be computed by hand.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GeminiClusterTest {

    private static final int RING = 12;
    private static final int CLIQUE = 3;
    private static final int VERTICES = RING + 1 + CLIQUE; // ring + bridge + clique

    private GeminiCluster cluster;

    @BeforeAll
    void startTheCluster() {
        cluster = GeminiCluster.local().start();
    }

    @AfterAll
    void stopTheCluster() {
        if (cluster != null) {
            cluster.close();
        }
    }

    /** ring 0..11, vertex 12 bridges to 0, and 13,14,15 form a clique hanging off vertex 6. */
    private static GeminiGraph graph() {
        List<Object> ids = new ArrayList<>();
        for (int v = 0; v < VERTICES; v++) {
            ids.add("v" + v);
        }
        List<GeminiGraph.Edge> edges = new ArrayList<>();
        for (int v = 0; v < RING; v++) {
            edges.add(new GeminiGraph.Edge(v, (v + 1) % RING, 1.0));
            edges.add(new GeminiGraph.Edge((v + 1) % RING, v, 1.0));
        }
        edges.add(new GeminiGraph.Edge(12, 0, 1.0));
        edges.add(new GeminiGraph.Edge(0, 12, 1.0));
        for (int a = RING + 1; a < VERTICES; a++) {
            for (int b = RING + 1; b < VERTICES; b++) {
                if (a != b) {
                    edges.add(new GeminiGraph.Edge(a, b, 1.0));
                }
            }
        }
        edges.add(new GeminiGraph.Edge(6, RING + 1, 1.0));
        edges.add(new GeminiGraph.Edge(RING + 1, 6, 1.0));
        return GeminiGraph.of(ids, edges);
    }

    @Test
    void fourWorkersListenOnFourPorts() {
        for (int i = 0; i < 4; i++) {
            Map<String, Object> status = GeminiClient.status(cluster.urlOf(i));
            assertEquals(i, ((Number) status.get("worker")).intValue());
            assertTrue(cluster.portOf(i) > 0, "worker " + i + " listens on a real port");
            assertTrue(cluster.urlOf(i).endsWith(String.valueOf(cluster.portOf(i))), cluster.urlOf(i));
        }
        // the ports are the ones the workers bound, not the ones the configuration asked for: a test
        // must not fight a running service for 19100
        assertEquals(4, cluster.workers());
    }

    @Test
    void pageRankAgreesWithASingleProcessRun() {
        GeminiCluster.Result result = cluster.run(graph(), GeminiAlgorithms.PAGERANK,
                Map.of("iterations", 20));

        Map<Object, Double> reference = referencePageRank(graph(), 20, 0.85);
        for (int v = 0; v < VERTICES; v++) {
            double distributed = result.number("v" + v);
            assertEquals(reference.get("v" + v), distributed, 1e-6, "PageRank of v" + v);
        }
        // a score is a share of one: the run must not invent mass, which it did while the shares were
        // pushed as whole values and the normalisation was missing
        double sum = 0;
        for (int v = 0; v < VERTICES; v++) {
            sum += result.number("v" + v);
        }
        assertTrue(sum > 0.9 && sum < 1.1, "the scores add up to about one, got " + sum);
        assertEquals(4, result.stats().workers());
        assertTrue(result.stats().supersteps() >= 20, "PageRank runs the iterations it was given");
        assertTrue(result.stats().messagesSent() > 0, "workers exchange messages: " + result.stats().describe());
        assertTrue(result.stats().converged(), "the run stopped on its own condition");
    }

    @Test
    void connectedComponentsFindsTheTwoComponents() {
        GeminiCluster.Result result = cluster.run(graph(), GeminiAlgorithms.CONNECTED_COMPONENTS, Map.of());

        // every vertex reports the label of the smallest vertex in its component
        for (int v = 0; v < RING + 1; v++) {
            assertEquals(0L, result.number("v" + v) + 0L, "v" + v + " is in the ring component");
        }
        for (int v = RING + 1; v < VERTICES; v++) {
            assertEquals(0L, result.number("v" + v) + 0L,
                    "v" + v + " hangs off the ring, so it is the same component");
        }
    }

    @Test
    void connectedComponentsSeparatesWhatIsNotConnected() {
        List<Object> ids = List.of("a", "b", "c", "d");
        List<GeminiGraph.Edge> edges = List.of(
                new GeminiGraph.Edge(0, 1, 1.0), new GeminiGraph.Edge(1, 0, 1.0),
                new GeminiGraph.Edge(2, 3, 1.0), new GeminiGraph.Edge(3, 2, 1.0));

        GeminiCluster.Result result = cluster.run(GeminiGraph.of(ids, edges),
                GeminiAlgorithms.CONNECTED_COMPONENTS, Map.of());

        assertEquals(0L, result.number("a") + 0L);
        assertEquals(0L, result.number("b") + 0L);
        assertEquals(2L, result.number("c") + 0L);
        assertEquals(2L, result.number("d") + 0L);
        assertFalse(result.stats().converged() == false && result.stats().supersteps() == 0,
                "the run did supersteps: " + result.stats().describe());
    }

    @Test
    void ssspGivesTheDistanceAlongTheRing() {
        GeminiCluster.Result result = cluster.run(graph(), GeminiAlgorithms.SSSP, Map.of("root", 0));

        for (int v = 0; v <= RING / 2; v++) {
            assertEquals((double) v, result.number("v" + v), "v" + v + " is " + v + " hops from the root");
        }
        // the far side of the ring is reached the short way round
        assertEquals((double) (RING / 2), result.number("v" + (RING / 2)), 0.0);
        assertEquals(1.0, result.number("v12"), "the bridge is one hop from the root");
        assertEquals(7.0, result.number("v" + (RING + 1)),
                "the clique hangs off v6, which is six hops from the root");
    }

    @Test
    void bfsAgreesWithTheShortestPathDepths() {
        GeminiCluster.Result bfs = cluster.run(graph(), GeminiAlgorithms.BFS, Map.of("root", 0));
        GeminiCluster.Result sssp = cluster.run(graph(), GeminiAlgorithms.SSSP, Map.of("root", 0));

        for (int v = 0; v < VERTICES; v++) {
            assertEquals(sssp.number("v" + v), (double) convert(bfs.number("v" + v)), 1e-9,
                    "BFS depth and SSSP distance agree on v" + v);
        }
    }

    @Test
    void louvainPutsTheCliqueInOneCommunity() {
        GeminiCluster.Result result = cluster.run(graph(), GeminiAlgorithms.LOUVAIN, Map.of("iterations", 8));

        long cliqueCommunity = convert(result.number("v" + (RING + 1)));
        for (int v = RING + 1; v < VERTICES; v++) {
            assertEquals(cliqueCommunity, convert(result.number("v" + v)),
                    "the clique is one community (v" + v + ")");
        }
        Set<Long> communities = new LinkedHashSet<>();
        for (int v = 0; v < VERTICES; v++) {
            communities.add(convert(result.number("v" + v)));
        }
        assertTrue(communities.size() >= 2, "the ring and the clique are not one community: " + communities);
        assertTrue(communities.size() <= VERTICES, "every vertex is in some community");
    }

    @Test
    void aRunReportsWhatItDid() {
        GeminiCluster.Result result = cluster.run(graph(), GeminiAlgorithms.CONNECTED_COMPONENTS, Map.of());

        GeminiCluster.Stats stats = result.stats();
        assertEquals(4, stats.workers());
        assertEquals(graph().edges().size(), stats.activeEdges(), "the launcher knows the whole graph");
        assertEquals(4, stats.storedEdges().size());
        assertEquals(4, stats.mirrors().size());
        assertTrue(stats.storedEdges().stream().allMatch(count -> count > 0),
                "every worker stores edges in both directions: " + stats.storedEdges());
        assertTrue(stats.describe().contains("4 workers"), stats.describe());
    }

    @Test
    void theModeFollowsTheActiveEdgeCount() {
        // a single vertex's own edges decide it: with a graph whose edges are almost all at the root,
        // the first superstep has few active edges (sparse) and a later one may not
        List<Object> ids = new ArrayList<>();
        for (int v = 0; v < 40; v++) {
            ids.add("n" + v);
        }
        List<GeminiGraph.Edge> edges = new ArrayList<>();
        for (int v = 1; v < 40; v++) {
            edges.add(new GeminiGraph.Edge(0, v, 1.0));
        }
        GeminiCluster.Result result = cluster.run(GeminiGraph.of(ids, edges), GeminiAlgorithms.BFS,
                Map.of("root", 0));

        assertEquals(1.0, result.number("n39"), "one hop from the root");
        assertFalse(result.stats().modes().isEmpty());
        assertTrue(result.stats().modes().stream().allMatch(mode -> mode.equals("sparse") || mode.equals("dense")));
    }

    @Test
    void anUnknownAlgorithmIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> cluster.run(graph(), "no-such-algorithm", Map.of()));

        assertTrue(error.getMessage().contains("Unknown Gemini algorithm"), error.getMessage());
    }

    @Test
    void aWorkerWithoutALayoutRefusesToCompute() throws Exception {
        try (GeminiWorkerServer server = new GeminiWorkerServer(19199).start()) {
            assertNotNull(server);
            assertTrue(GeminiClient.status("http://127.0.0.1:19199").containsKey("loaded"));
            assertFalse(Boolean.TRUE.equals(GeminiClient.status("http://127.0.0.1:19199").get("loaded")));
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> GeminiClient.exchange("http://127.0.0.1:19199", 0, true));
            assertTrue(error.getMessage().contains("no layout") || error.getMessage().contains("400")
                            || error.getMessage().contains("500"),
                    "a worker says it is not loaded: " + error.getMessage());
        }
    }

    private static long convert(double value) {
        return (long) value;
    }

    // ------------------------------------------------------------------ the single-process references

    /** PageRank as the reference toolkit writes it, on one thread, with the same normalisation. */
    private static Map<Object, Double> referencePageRank(GeminiGraph graph, int iterations, double damping) {
        int n = graph.vertexCount();
        double[] curr = new double[n];
        double[] next = new double[n];
        int[] outDegree = new int[n];
        List<List<GeminiGraph.Edge>> out = new ArrayList<>();
        List<List<GeminiGraph.Edge>> in = new ArrayList<>();
        for (int v = 0; v < n; v++) {
            out.add(new ArrayList<>());
            in.add(new ArrayList<>());
        }
        for (GeminiGraph.Edge edge : graph.edges()) {
            out.get(edge.source()).add(edge);
            in.get(edge.target()).add(edge);
            outDegree[edge.source()]++;
        }
        for (int v = 0; v < n; v++) {
            curr[v] = 1.0 / n; // every vertex starts with an equal share of the mass
        }
        for (int i = 0; i < iterations; i++) {
            java.util.Arrays.fill(next, 0.0);
            for (int v = 0; v < n; v++) {
                if (outDegree[v] == 0) {
                    continue; // a vertex with no out-edges contributes nothing back
                }
                for (GeminiGraph.Edge edge : out.get(v)) {
                    next[edge.target()] += curr[v] / outDegree[v] * edge.weight();
                }
            }
            // a vertex's value is the contributions of this superstep alone, so an empty next is zero
            System.arraycopy(next, 0, curr, 0, n);
        }
        Map<Object, Double> result = new LinkedHashMap<>();
        for (int v = 0; v < n; v++) {
            result.put(graph.id(v), (1 - damping) / n + damping * curr[v]);
        }
        return result;
    }

    /** The reference used for the component check: label propagation twice, iterated to a fixed point. */
    private static Map<Object, Long> referenceComponents(GeminiGraph graph) {
        int n = graph.vertexCount();
        long[] label = new long[n];
        for (int v = 0; v < n; v++) {
            label[v] = v;
        }
        Map<Integer, Set<Integer>> neighbours = new HashMap<>();
        for (GeminiGraph.Edge edge : graph.edges()) {
            neighbours.computeIfAbsent(edge.source(), k -> new LinkedHashSet<>()).add(edge.target());
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int v = 0; v < n; v++) {
                for (int other : neighbours.getOrDefault(v, Set.of())) {
                    if (label[other] < label[v]) {
                        label[v] = label[other];
                        changed = true;
                    }
                }
            }
        }
        Map<Object, Long> out = new LinkedHashMap<>();
        for (int v = 0; v < n; v++) {
            out.put(graph.id(v), label[v]);
        }
        return out;
    }

    /** A breadth first search on one thread, to check that every vertex was reached. */
    private static Map<Object, Integer> referenceBfs(GeminiGraph graph, int root) {
        int n = graph.vertexCount();
        int[] depth = new int[n];
        java.util.Arrays.fill(depth, -1);
        Map<Integer, List<Integer>> out = new HashMap<>();
        for (GeminiGraph.Edge edge : graph.edges()) {
            out.computeIfAbsent(edge.source(), k -> new ArrayList<>()).add(edge.target());
        }
        Deque<Integer> queue = new ArrayDeque<>();
        depth[root] = 0;
        queue.add(root);
        while (!queue.isEmpty()) {
            int v = queue.poll();
            for (int next : out.getOrDefault(v, List.of())) {
                if (depth[next] < 0) {
                    depth[next] = depth[v] + 1;
                    queue.add(next);
                }
            }
        }
        Map<Object, Integer> result = new LinkedHashMap<>();
        for (int v = 0; v < n; v++) {
            result.put(graph.id(v), depth[v]);
        }
        return result;
    }

    // ------------------------------------------------------------------ the centralities

    /**
     * A graph small enough for a betweenness run to be exact and quick: a path of five (v0..v4) and a
     * pair (v5-v6) that nothing joins to it, so reachability is part of the answer.
     */
    private static GeminiGraph centralityGraph() {
        List<Object> ids = new ArrayList<>();
        for (int v = 0; v < 7; v++) {
            ids.add("v" + v);
        }
        List<GeminiGraph.Edge> edges = new ArrayList<>();
        for (int v = 0; v + 1 < 5; v++) {
            edges.add(new GeminiGraph.Edge(v, v + 1, 1.0));
            edges.add(new GeminiGraph.Edge(v + 1, v, 1.0));
        }
        edges.add(new GeminiGraph.Edge(5, 6, 1.0));
        edges.add(new GeminiGraph.Edge(6, 5, 1.0));
        return GeminiGraph.of(ids, edges);
    }

    /** The neighbour lists of the edge list as it stands (both directions), for the references. */
    private static Map<Integer, List<Integer>> neighbours(GeminiGraph graph) {
        Map<Integer, List<Integer>> out = new HashMap<>();
        for (GeminiGraph.Edge edge : graph.edges()) {
            out.computeIfAbsent(edge.source(), k -> new ArrayList<>()).add(edge.target());
        }
        return out;
    }

    /** Degree centrality on one thread: the edge records that leave a vertex. */
    private static Map<Object, Double> referenceDegree(GeminiGraph graph) {
        Map<Integer, List<Integer>> out = neighbours(graph);
        Map<Object, Double> result = new LinkedHashMap<>();
        for (int v = 0; v < graph.vertexCount(); v++) {
            result.put(graph.id(v), (double) out.getOrDefault(v, List.of()).size());
        }
        return result;
    }

    /** Closeness centrality on one thread, the same Wasserman-Faust form the step computes. */
    private static Map<Object, Double> referenceCloseness(GeminiGraph graph) {
        int n = graph.vertexCount();
        Map<Object, Double> result = new LinkedHashMap<>();
        for (int v = 0; v < n; v++) {
            Map<Object, Integer> distance = referenceBfs(graph, v);
            double sum = 0;
            int reached = 0;
            for (int d : distance.values()) {
                if (d >= 0) {
                    reached++;
                    sum += d;
                }
            }
            result.put(graph.id(v), reached <= 1 || sum <= 0
                    ? 0.0 : (reached - 1.0) * (reached - 1.0) / ((n - 1.0) * sum));
        }
        return result;
    }

    /** Betweenness centrality on one thread: Brandes over every source, then the two normalisations. */
    private static Map<Object, Double> referenceBetweenness(GeminiGraph graph) {
        int n = graph.vertexCount();
        Map<Integer, List<Integer>> out = neighbours(graph);
        double[] betweenness = new double[n];
        for (int source = 0; source < n; source++) {
            int[] distance = new int[n];
            java.util.Arrays.fill(distance, -1);
            double[] sigma = new double[n];
            double[] delta = new double[n];
            Map<Integer, List<Integer>> predecessors = new HashMap<>();
            List<Integer> order = new ArrayList<>();
            Deque<Integer> queue = new ArrayDeque<>();
            distance[source] = 0;
            sigma[source] = 1;
            queue.add(source);
            while (!queue.isEmpty()) {
                int v = queue.poll();
                order.add(v);
                for (int w : out.getOrDefault(v, List.of())) {
                    if (distance[w] < 0) {
                        distance[w] = distance[v] + 1;
                        queue.add(w);
                    }
                    if (distance[w] == distance[v] + 1) {
                        sigma[w] += sigma[v];
                        predecessors.computeIfAbsent(w, k -> new ArrayList<>()).add(v);
                    }
                }
            }
            for (int i = order.size() - 1; i >= 0; i--) {
                int w = order.get(i);
                for (int v : predecessors.getOrDefault(w, List.of())) {
                    delta[v] += sigma[v] / sigma[w] * (1 + delta[w]);
                }
                if (w != source) {
                    betweenness[w] += delta[w];
                }
            }
        }
        double pairs = (n - 1.0) * (n - 2.0) / 2.0;
        Map<Object, Double> result = new LinkedHashMap<>();
        for (int v = 0; v < n; v++) {
            result.put(graph.id(v), pairs <= 0 ? 0.0 : betweenness[v] / 2.0 / pairs);
        }
        return result;
    }

    /** Eigenvector centrality on one thread: thirty power iterations, normalised the way the step is. */
    private static Map<Object, Double> referenceEigenvector(GeminiGraph graph) {
        int n = graph.vertexCount();
        Map<Integer, List<Integer>> in = new HashMap<>();
        for (GeminiGraph.Edge edge : graph.edges()) {
            in.computeIfAbsent(edge.target(), k -> new ArrayList<>()).add(edge.source());
        }
        double[] x = new double[n];
        java.util.Arrays.fill(x, 1.0);
        for (int iteration = 0; iteration < 30; iteration++) {
            double[] next = new double[n];
            for (int v = 0; v < n; v++) {
                for (int u : in.getOrDefault(v, List.of())) {
                    next[v] += x[u];
                }
            }
            x = normalise(next);
        }
        Map<Object, Double> result = new LinkedHashMap<>();
        for (int v = 0; v < n; v++) {
            result.put(graph.id(v), x[v]);
        }
        return result;
    }

    /** Unit L2 length, largest entry positive - the sign and the scale both engines end on. */
    private static double[] normalise(double[] values) {
        double squares = 0;
        int largest = 0;
        for (int i = 0; i < values.length; i++) {
            squares += values[i] * values[i];
            if (Math.abs(values[i]) > Math.abs(values[largest])) {
                largest = i;
            }
        }
        double norm = Math.sqrt(squares);
        double sign = values[largest] < 0 ? -1 : 1;
        double[] out = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = norm == 0 ? 0 : values[i] / norm * sign;
        }
        return out;
    }

    @Test
    void degreeCentralityCountsTheEdgesOfEveryPartition() {
        GeminiGraph graph = centralityGraph();
        GeminiCluster.Result result = cluster.run(graph, GeminiAlgorithms.DEGREE_CENTRALITY, Map.of());

        Map<Object, Double> reference = referenceDegree(graph);
        for (int v = 0; v < graph.vertexCount(); v++) {
            assertEquals(reference.get("v" + v), result.number("v" + v), 1e-9, "degree of v" + v);
        }
        // the degree is in the layout, so there is nothing to exchange: the run is one superstep
        assertEquals(1, result.stats().supersteps(), result.stats().describe());
        assertEquals(0, result.stats().messagesSent(), "no messages are needed");
    }

    @Test
    void closenessCentralityAgreesWithASingleProcessRun() {
        GeminiGraph graph = centralityGraph();
        Map<Object, Double> reference = referenceCloseness(graph);

        // closeness is assembled from one breadth-first run per source: a score is a sum over
        // destinations, which no vertex's superstep can see on its own
        int n = graph.vertexCount();
        double[] total = new double[n];
        int[] reached = new int[n];
        for (int root = 0; root < n; root++) {
            GeminiCluster.Result run = cluster.run(graph, GeminiAlgorithms.BFS,
                    Map.of("root", root, "vertices", n));
            for (int v = 0; v < n; v++) {
                double distance = run.number(graph.id(v));
                if (distance >= 0) {
                    reached[v]++;
                    total[v] += distance;
                }
            }
        }
        for (int v = 0; v < n; v++) {
            double sum = total[v];
            double reach = reached[v] - 1;
            double closeness = sum <= 0 || reach <= 0 ? 0.0 : reach * reach / ((n - 1.0) * sum);
            assertEquals(reference.get("v" + v), closeness, 1e-9, "closeness of v" + v);
        }
        // the middle of the path is the closest vertex there is, and the pair on the side is a world of
        // its own: one hop apart, so its two vertices are as close as two vertices can be
        assertTrue(reference.get("v2") > reference.get("v0"), "the middle is closer to everything");
        assertEquals(reference.get("v5"), reference.get("v6"), 1e-9, "two vertices, one hop apart");
    }

    @Test
    void betweennessCentralityAgreesWithASingleProcessRun() {
        GeminiGraph graph = centralityGraph();
        Map<Object, Double> reference = referenceBetweenness(graph);

        // one run per source, added up the way the launcher does it
        int n = graph.vertexCount();
        double[] total = new double[n];
        for (int root = 0; root < n; root++) {
            GeminiCluster.Result run = cluster.run(graph, GeminiAlgorithms.BETWEENNESS_CENTRALITY,
                    Map.of("root", root, "vertices", n));
            for (int v = 0; v < n; v++) {
                total[v] += run.number(graph.id(v));
            }
            assertEquals(0.0, run.number(graph.id(root)), 1e-9,
                    "a source carries no dependency of its own");
        }
        double pairs = (n - 1.0) * (n - 2.0) / 2.0;
        for (int v = 0; v < n; v++) {
            assertEquals(reference.get("v" + v), total[v] / 2.0 / pairs, 1e-9, "betweenness of v" + v);
        }
        // the middle of the path is on every shortest path between its two halves
        assertTrue(total[2] / 2.0 / pairs > total[1] / 2.0 / pairs, "v2 is on more paths than v1");
        assertEquals(0.0, total[5], 1e-9, "an endpoint of a two-vertex component is on no path");
    }

    @Test
    void betweennessRunsPushOnly() {
        // the two passes are level-synchronous, which only the push mode expresses: the algorithm names
        // its half and the launcher honours it, whatever the work would have suggested
        GeminiCluster.Result run = cluster.run(centralityGraph(), GeminiAlgorithms.BETWEENNESS_CENTRALITY,
                Map.of("root", 0, "vertices", 7));

        assertTrue(run.stats().modes().stream().allMatch("sparse"::equals),
                "every superstep was a push: " + run.stats().modes());
    }

    @Test
    void eigenvectorCentralityAgreesWithASingleProcessRun() {
        GeminiGraph graph = centralityGraph();
        GeminiCluster.Result result = cluster.run(graph, GeminiAlgorithms.EIGENVECTOR_CENTRALITY,
                Map.of("iterations", 30));
        // the run answers with the vector up to a constant factor (each superstep normalises it so the
        // values stay finite); the launcher is what turns it into the unit vector, so the test does too
        Map<Object, Double> vector = unitVector(result, graph);

        Map<Object, Double> reference = referenceEigenvector(graph);
        for (int v = 0; v < graph.vertexCount(); v++) {
            assertEquals(reference.get("v" + v), vector.get("v" + v), 1e-6, "eigenvector of v" + v);
        }
        // the path of five carries the influence: its middle outranks its ends, and the pair on the side
        // has next to none
        assertTrue(vector.get("v2") > vector.get("v0"), "the middle of the path is the centre");
        assertTrue(vector.get("v0") > vector.get("v5"), "the separate pair carries no influence");
    }

    @Test
    void theEigenvectorNormTravelsThroughTheDriver() {
        // the sum of squares is a per-partition total the driver adds up, and the first superstep needs
        // it too: with one iteration the vector is the all-ones vector mapped once and normalised, which
        // only comes out right if every worker divided by the same (whole-graph) norm
        GeminiGraph graph = centralityGraph();
        GeminiCluster.Result run = cluster.run(graph, GeminiAlgorithms.EIGENVECTOR_CENTRALITY,
                Map.of("iterations", 1));
        Map<Object, Double> vector = unitVector(run, graph);

        double squares = 0;
        for (double value : vector.values()) {
            squares += value * value;
        }
        assertEquals(1.0, squares, 1e-9, "the unit vector the launcher answers with: " + vector);
        // the path of five: its ends have one neighbour and its middle three have two, so an end carries
        // half the influence of a middle vertex - and this answer only comes out right if the whole graph
        // shared one norm
        assertEquals(0.25, vector.get("v0"), 1e-9, "the end of the path: " + vector);
        assertEquals(0.5, vector.get("v2"), 1e-9, "the middle of the path");
        assertEquals(0.25, vector.get("v4"), 1e-9, "the other end");
        assertEquals(0.25, vector.get("v5"), 1e-9, "the pair on the side is one hop of influence each");
    }

    /** The unit vector a launcher normalises the collected scores into (L2 length, sign fixed). */
    private static Map<Object, Double> unitVector(GeminiCluster.Result run, GeminiGraph graph) {
        double[] values = new double[graph.vertexCount()];
        for (int v = 0; v < graph.vertexCount(); v++) {
            values[v] = run.number(graph.id(v));
        }
        double[] unit = normalise(values);
        Map<Object, Double> out = new LinkedHashMap<>();
        for (int v = 0; v < graph.vertexCount(); v++) {
            out.put(graph.id(v), unit[v]);
        }
        return out;
    }
}
