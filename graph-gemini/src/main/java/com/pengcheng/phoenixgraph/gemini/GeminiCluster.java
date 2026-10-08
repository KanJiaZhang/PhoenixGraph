package com.pengcheng.phoenixgraph.gemini;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Starts a Gemini cluster, loads it with a graph and drives the supersteps.
 *
 * <p>A worker can run in this process (its own HTTP port, so the protocol is the same) or as a child
 * JVM ({@link GeminiWorkerMain}, which is what a YARN container or a helper script would start), and
 * either way the launcher talks to it over HTTP: it posts the layout, then for each superstep asks
 * every worker to exchange and compute. Messages between workers never pass through the launcher -
 * each worker posts its own batches to its peers, as the ring schedule does in the paper.
 *
 * <p>The mode of a superstep is global and decided by the launcher from the workers' reports:
 * {@code activeEdges} is the sum over the workers, and Gemini's rule picks the sparse (push) mode when
 * it is below {@code |E| / 20} and the dense (pull) mode otherwise - unless the algorithm has only one
 * half and named it ({@link GeminiComputation#preferredMode()}). Louvain's community weights take the
 * same route: every worker reports its aggregate, the launcher adds them up and hands the sum
 * back before the next superstep - the paper's {@code reduce}.
 */
public final class GeminiCluster implements AutoCloseable {

    /** How the workers of a cluster are run. */
    public enum Mode {
        /** In this process: one thread per worker, one port each. */
        LOCAL,
        /** As a child JVM per worker (the standalone helper-program path). */
        PROCESS
    }

    /** How a cluster is configured. */
    public record Config(int workers, int firstPort, Mode mode, String host) {

        public Config {
            if (workers < 1) {
                throw new IllegalArgumentException("A cluster needs at least one worker");
            }
            host = host == null || host.isBlank() ? "127.0.0.1" : host;
        }

        /** Four workers in this process, on ports the operating system picks (what a test wants). */
        public static Config local(int workers) {
            return local(workers, 0);
        }

        /** Four workers in this process, on a known range (what a service wants). */
        public static Config local(int workers, int firstPort) {
            return new Config(workers, firstPort, Mode.LOCAL, "127.0.0.1");
        }

        /** Four workers, each in its own JVM - the standalone deployment. */
        public static Config processes(int workers) {
            return new Config(workers, 19200, Mode.PROCESS, "127.0.0.1");
        }

        /** The URL of a worker when the ports are the ones the configuration asked for. */
        public String url(int workerId) {
            return "http://" + host + ":" + (firstPort + workerId);
        }
    }

    /** What a run did, for a caller that reports it or a test that asserts on it. */
    public record Stats(int workers, int supersteps, long activeEdges, long messagesSent,
                        long messagesReceived, List<String> modes, List<Integer> storedEdges,
                        List<Integer> mirrors, double seconds, boolean converged) {

        /** A one-line summary. */
        public String describe() {
            long sparse = modes.stream().filter("sparse"::equals).count();
            return workers + " workers, " + supersteps + " supersteps (" + sparse + " sparse, "
                    + (modes.size() - sparse) + " dense), " + messagesSent + " messages sent, "
                    + String.format("%.0f ms", seconds * 1000);
        }
    }

    /** The result of a run: a value per vertex, by the ids the caller used. */
    public record Result(Map<Object, Object> values, Stats stats) {

        public Result {
            values = Map.copyOf(values);
        }

        /** The value of a vertex, or {@code null} when the run did not produce one. */
        public Object value(Object vertexId) {
            return values.get(vertexId);
        }

        /** A numeric view of a value, for a comparison in a test. */
        public double number(Object vertexId) {
            Object value = values.get(vertexId);
            if (value instanceof Number number) {
                return number.doubleValue();
            }
            throw new IllegalStateException("Vertex " + vertexId + " has no numeric value: " + value);
        }
    }

    private final Config config;
    private final List<GeminiWorkerServer> servers = new ArrayList<>();
    private final List<Process> processes = new ArrayList<>();
    /** The port each worker actually listens on (a configuration of 0 asks for a free one). */
    private final List<Integer> boundPorts = new ArrayList<>();

    public GeminiCluster(Config config) {
        this.config = config;
    }

    /** The URL of one worker, for a caller that wants to look at it directly (a test, a probe). */
    public String urlOf(int workerId) {
        return "http://" + config.host() + ":" + portOf(workerId);
    }

    /** The port a worker listens on (the one the operating system picked when the config asked for 0). */
    public int portOf(int workerId) {
        if (workerId < boundPorts.size()) {
            return boundPorts.get(workerId);
        }
        return config.firstPort() + workerId;
    }

    /** How many workers this cluster has. */
    public int workers() {
        return config.workers();
    }

    /** A cluster of four workers in this process - the configuration the tests and the demo use. */
    public static GeminiCluster local() {
        return new GeminiCluster(Config.local(4));
    }

    /** Starts the workers of the configured mode. */
    public GeminiCluster start() {
        if (config.mode() == Mode.LOCAL) {
            for (int i = 0; i < config.workers(); i++) {
                try {
                    GeminiWorkerServer server = new GeminiWorkerServer(config.firstPort() + i).start();
                    servers.add(server);
                    boundPorts.add(server.port());
                } catch (IOException e) {
                    throw new IllegalStateException("Cannot listen on port " + (config.firstPort() + i), e);
                }
            }
        } else {
            for (int i = 0; i < config.workers(); i++) {
                processes.add(startWorkerProcess(i));
                boundPorts.add(config.firstPort() + i);
            }
            awaitWorkers();
        }
        return this;
    }

    /**
     * Runs an algorithm over a graph and returns a value per vertex.
     *
     * <p>The graph is cut into contiguous chunks, each worker is loaded with its own edges in both
     * directions, and the supersteps run until the algorithm converges (or hits its limit). The
     * values come back translated to the ids the caller used.
     */
    public Result run(GeminiGraph graph, String algorithm, Map<String, Object> params) {
        // an unknown name is answered here, before a single worker is touched
        GeminiAlgorithms.of(algorithm, params);
        if (config.mode() == Mode.LOCAL && servers.isEmpty()) {
            start();
        }
        GeminiPartitioner.Plan plan = new GeminiPartitioner().chunk(graph, config.workers());
        Map<Integer, String> urls = new LinkedHashMap<>();
        for (int i = 0; i < config.workers(); i++) {
            urls.put(i, urlOf(i));
        }
        List<GeminiLauncherLayout.WorkerPayload> payloads = GeminiLauncherLayout.payloads(graph, plan, urls);

        long totalEdges = graph.edges().size();
        Map<String, Object> effective = new LinkedHashMap<>(params == null ? Map.of() : params);
        // the totals only the launcher knows: how many vertices the graph has (a PageRank score is
        // relative to it) and how heavy it is (Louvain's gain is relative to that)
        effective.putIfAbsent("vertices", graph.vertexCount());
        effective.putIfAbsent("edges", graph.edges().size());
        if (GeminiAlgorithms.LOUVAIN.equalsIgnoreCase(algorithm) && !effective.containsKey("totalWeight")) {
            // Louvain's modularity gain is relative to the graph's total edge weight, which only the
            // launcher can know (the adjacency is symmetric, so the weight sum counts every edge twice)
            double weight = 0;
            for (GeminiGraph.Edge edge : graph.edges()) {
                weight += edge.weight();
            }
            effective.put("totalWeight", weight / 2.0);
        }
        for (GeminiLauncherLayout.WorkerPayload payload : payloads) {
            Map<String, Object> withEdges = new LinkedHashMap<>(effective);
            // the mode rule compares against the whole graph, so every worker is told it
            var document = GeminiWire.writePayload(payload, algorithm, withEdges);
            document.put("totalEdges", totalEdges);
            Map<String, Object> answer = load(payload, document);
            if (Boolean.FALSE.equals(answer.get("loaded"))) {
                throw new IllegalStateException("Worker " + payload.workerId() + " refused the layout");
            }
        }

        long started = System.nanoTime();
        List<String> modes = new ArrayList<>();
        int supersteps = 0;
        boolean converged = false;
        // the algorithm is validated here, before any worker is told about it, so a typo is a caller
        // error rather than four workers failing to parse the layout
        GeminiComputation computation = GeminiAlgorithms.of(algorithm, effective);
        for (int round = 0; round < computation.maxSupersteps(); round++) {
            // The workers are told the totals every round, the first one included: an algorithm whose
            // decision depends on them (Louvain's modularity gain) must not take its first decision
            // without them, or it merges with whatever is closest to the lowest id and never undoes it.
            // The round number travels with them: it is the frontier level of the push-only algorithms.
            Map<String, Object> totals = collectShared();
            totals.put("superstep", (double) round);
            for (int i = 0; i < config.workers(); i++) {
                share(i, totals);
            }
            // phase one: every worker reports how many edges its active vertices have, so the launcher
            // can pick one mode for all of them (Gemini's rule, from the whole graph's edge count)
            long activeEdges = 0;
            for (int i = 0; i < config.workers(); i++) {
                activeEdges += probe(i);
            }
            // an algorithm that has only one half says so; otherwise Gemini's rule decides from the work
            boolean sparse = switch (computation.preferredMode()) {
                case SPARSE -> true;
                case DENSE -> false;
                case AUTO -> activeEdges < totalEdges / 20;
            };
            modes.add(sparse ? "sparse" : "dense");
            for (int i = 0; i < config.workers(); i++) {
                exchange(i, round, sparse);
            }
            int activated = 0;
            for (int i = 0; i < config.workers(); i++) {
                activated += ((Number) compute(i, round, sparse).getOrDefault("activated", 0)).intValue();
            }
            supersteps = round + 1;
            if (computation.converged(activated, round)) {
                converged = true;
                break;
            }
        }
        double seconds = (System.nanoTime() - started) / 1e9;

        Map<Object, Object> values = new LinkedHashMap<>();
        long sent = 0;
        long received = 0;
        for (int i = 0; i < config.workers(); i++) {
            for (Map.Entry<Integer, Object> entry : values(i).entrySet()) {
                values.put(graph.id(entry.getKey()), entry.getValue());
            }
            Map<String, Object> status = status(i);
            sent += ((Number) status.getOrDefault("messagesSent", 0)).longValue();
            received += ((Number) status.getOrDefault("messagesReceived", 0)).longValue();
        }
        Stats stats = new Stats(config.workers(), supersteps, totalEdges, sent, received, modes,
                plan.storedEdges(), plan.mirrorCounts(), seconds, converged);
        return new Result(values, stats);
    }

    /**
     * The aggregate every worker reports, added up - the paper's {@code reduce}.
     *
     * <p>A key that names a per-partition total (Louvain's community weights, the sum of squares a
     * power iteration normalises by) starts with {@code sum:} or {@code community:} and the parts are
     * added up. A value that describes the whole run (Louvain's total edge weight) is reported by
     * every worker and must <b>not</b> be added up: four workers reporting the same total would
     * multiply it by four every round, and the modularity gain would lose its resolution term
     * entirely.
     */
    private Map<String, Object> collectShared() {
        Map<String, Object> totals = new LinkedHashMap<>();
        for (int i = 0; i < config.workers(); i++) {
            for (Map.Entry<String, Object> entry : aggregate(i).entrySet()) {
                if (!(entry.getValue() instanceof Number number)) {
                    continue;
                }
                double value = number.doubleValue();
                if (!isSummed(entry.getKey())) {
                    totals.putIfAbsent(entry.getKey(), value);
                    continue;
                }
                Object current = totals.get(entry.getKey());
                totals.put(entry.getKey(), current instanceof Number previous
                        ? previous.doubleValue() + value : value);
            }
        }
        return totals;
    }

    /** Whether an aggregate key names a per-partition total that the parts add up to. */
    private static boolean isSummed(String key) {
        return key.startsWith("sum:") || key.startsWith("community:");
    }

    // ------------------------------------------------------------------ worker access (HTTP or in-process)

    private Map<String, Object> load(GeminiLauncherLayout.WorkerPayload payload,
                                     com.fasterxml.jackson.databind.node.ObjectNode document) {
        // one code path for both modes: an in-process worker is loaded over its own port as well, so
        // what the tests exercise is what a child JVM (or a YARN container) does
        return GeminiClient.load(urlOf(payload.workerId()), document);
    }

    /** The active edge count of one worker, without exchanging anything (the mode is decided from it). */
    private long probe(int workerId) {
        Object active = GeminiClient.activeEdges(urlOf(workerId)).get("activeEdges");
        return active instanceof Number number ? number.longValue() : 0L;
    }

    private Map<String, Object> exchange(int workerId, int round, boolean sparse) {
        return GeminiClient.exchange(urlOf(workerId), round, sparse);
    }

    private Map<String, Object> compute(int workerId, int round, boolean sparse) {
        return GeminiClient.compute(urlOf(workerId), round, sparse);
    }

    private void share(int workerId, Map<String, Object> shared) {
        GeminiClient.share(urlOf(workerId), shared);
    }

    private Map<Integer, Object> values(int workerId) {
        return GeminiClient.values(urlOf(workerId));
    }

    private Map<String, Object> aggregate(int workerId) {
        return GeminiClient.aggregate(urlOf(workerId));
    }

    private Map<String, Object> status(int workerId) {
        return GeminiClient.status(urlOf(workerId));
    }

    // ------------------------------------------------------------------ workers as child processes

    private Process startWorkerProcess(int workerId) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");
        List<String> command = new ArrayList<>(List.of(java, "-cp", classpath,
                GeminiWorkerMain.class.getName(),
                "--worker", String.valueOf(workerId),
                "--port", String.valueOf(config.firstPort() + workerId),
                "--workers", String.valueOf(config.workers()),
                "--host", config.host(),
                "--first-port", String.valueOf(config.firstPort())));
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.to(Path.of("target",
                    "gemini-worker-" + workerId + ".log").toFile()));
            return builder.start();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot start worker " + workerId, e);
        }
    }

    /** Waits until every child worker answers on its port. */
    private void awaitWorkers() {
        long deadline = System.currentTimeMillis() + 60_000;
        Set<Integer> ready = new LinkedHashSet<>();
        while (ready.size() < config.workers() && System.currentTimeMillis() < deadline) {
            for (int i = 0; i < config.workers(); i++) {
                if (ready.contains(i)) {
                    continue;
                }
                try {
                    GeminiClient.status(urlOf(i));
                    ready.add(i);
                } catch (RuntimeException e) {
                    // not up yet
                }
            }
            if (ready.size() < config.workers()) {
                sleep(200);
            }
        }
        if (ready.size() < config.workers()) {
            throw new IllegalStateException("Only " + ready.size() + " of " + config.workers()
                    + " workers came up; see target/gemini-worker-*.log");
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Stops the workers of this cluster (child processes are destroyed). */
    @Override
    public void close() {
        for (GeminiWorkerServer server : servers) {
            server.close();
        }
        servers.clear();
        for (Process process : processes) {
            process.destroy();
        }
        processes.clear();
    }
}
