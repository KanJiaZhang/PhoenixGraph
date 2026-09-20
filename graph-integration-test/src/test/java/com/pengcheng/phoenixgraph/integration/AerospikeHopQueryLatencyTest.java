package com.pengcheng.phoenixgraph.integration;

import com.pengcheng.phoenixgraph.metrics.LatencyStats;
import com.pengcheng.phoenixgraph.metrics.Metrics;
import com.pengcheng.phoenixgraph.query.gremlin.GremlinGraphQuery;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Times the same twenty traversals the accuracy check verifies, and reports what each one cost.
 *
 * <p>Every query runs {@value #WARMUP} times to warm the paths (the compiled plan, the client's
 * connections, the driver's buffers) and then {@value #RUNS} times, and for each run the harness records
 * the latency and the storage traffic: the vertex and edge items the store returned, how many edges it
 * had to build ({@code decoded}) and how many a pushed-down condition discarded before building them
 * ({@code filtered}), and the key-value round trips the reads turned into. Latency says how fast a query
 * is, the traffic says why - which is what the bottleneck notes are based on.
 *
 * <p>Two variants of the second two-hop query are measured as well (the same hop with {@code batch(1)},
 * and the engine with more parallelism) so the effect of those two knobs is visible next to the default.
 *
 * <p>The answer of every measured query is compared with the reference once, so no measurement can be of
 * a query that stopped answering the right thing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AerospikeHopQueryLatencyTest {

    private static final int WARMUP = 5;
    private static final int RUNS = 20;
    private static final String TWO_HOP = "2.6";

    private AerospikeHopSuite suite;
    private final Map<String, Measurement> measurements = new LinkedHashMap<>();
    private final List<String> wrong = new ArrayList<>();

    @BeforeAll
    void openTheDemoGraph() {
        suite = AerospikeHopSuite.open("payment-graph-latency");
    }

    @AfterAll
    void report() {
        if (suite == null) {
            return;
        }
        System.out.println(report(suite, measurements, wrong));
        suite.close();
    }

    @Test
    void twentyTraversalsAreTimedAndReported() {
        for (AerospikeHopSuite.Case testCase : suite.cases()) {
            measure(testCase, suite.gremlin(), testCase.id());
        }

        // the same query with the two knobs a caller can turn: batch(1) removes the batching of the
        // second hop, and more parallelism splits the per-hop reads into more concurrent batches
        AerospikeHopSuite.Case base = suite.twoHopCases().stream()
                .filter(testCase -> testCase.id().equals(TWO_HOP)).findFirst().orElseThrow();
        AerospikeHopSuite.Case unbatched = new AerospikeHopSuite.Case(base.id(), base.label() + " [batch(1)]",
                base.query().replace(".out('Payment').outE('Payment')",
                        ".out('Payment').batch(1).outE('Payment')"),
                base.endsAt(), base.shape(), base.limit(), base.expected());
        measure(unbatched, suite.gremlin(), TWO_HOP + " (batch(1))");
        measure(base, suite.engineWithParallelism(8), TWO_HOP + " (parallelism(8))");

        // a control for the pushed-down condition: nothing in the demo is an invoice, so a store that
        // evaluates the condition while it walks the bin builds no edge at all. The demo's hub payments
        // are all SUCCESS, which is why case 1.5 cannot show this - a condition that matches everything
        // saves nothing.
        AerospikeHopSuite.Case nothingMatches = new AerospikeHopSuite.Case("1.5c",
                "V(135).outE(Payment).has(type,'invoice') - a condition that matches nothing",
                "g.V('" + AerospikeHopSuite.HUB + "','Account').outE('Payment').has('type','invoice')",
                AerospikeHopSuite.EndsAt.EDGE, AerospikeHopSuite.Shape.IDS, 0, List::of);
        measure(nothingMatches, suite.gremlin(), "1.5c (no match)");
        Measurement control = measurements.get("1.5c (no match)");
        assertTrue(control.traffic().decoded() == 0,
                "a condition the store evaluates builds no edge: " + control.traffic());
        assertTrue(control.traffic().filtered() >= 10,
                "the edges it walked past are reported as filtered: " + control.traffic());

        assertTrue(wrong.isEmpty(), "every measured query answers what the records say: " + wrong);
        assertTrue(measurements.size() >= 23, "twenty queries and three extra measurements were run");
        for (Measurement measurement : measurements.values()) {
            assertTrue(measurement.latency().percentileMillis(0.95) < 500,
                    measurement.id() + " stays under half a second: "
                            + measurement.latency().percentileMillis(0.95));
        }
    }

    /** Runs one case {@value #RUNS} times after warm-up, checking its answer once. */
    private void measure(AerospikeHopSuite.Case testCase, GremlinGraphQuery engine, String label) {
        Object expected = testCase.expected().get();
        LatencyStats latency = new LatencyStats();
        Counters traffic = new Counters(0, 0, 0, 0, 0, 0);
        for (int i = 0; i < WARMUP; i++) {
            engine.execute(testCase.query());
        }
        long size = 0;
        for (int i = 0; i < RUNS; i++) {
            long v0 = counter(Metrics.items(Metrics.VERTEX_READ));
            long e0 = counter(Metrics.items(Metrics.EDGE_READ));
            long d0 = counter(Metrics.EDGE_DECODED);
            long f0 = counter(Metrics.EDGE_FILTERED);
            long k0 = counter(Metrics.KV_READ_CALL);
            long ki0 = counter(Metrics.KV_READ_ITEMS);
            long started = System.nanoTime();
            List<Object> answer = engine.execute(testCase.query());
            latency.record(System.nanoTime() - started);
            traffic = traffic.add(counter(Metrics.items(Metrics.VERTEX_READ)) - v0,
                    counter(Metrics.items(Metrics.EDGE_READ)) - e0,
                    counter(Metrics.EDGE_DECODED) - d0,
                    counter(Metrics.EDGE_FILTERED) - f0,
                    counter(Metrics.KV_READ_CALL) - k0,
                    counter(Metrics.KV_READ_ITEMS) - ki0);
            size = answer.size();
            if (i == 0) {
                String difference = AerospikeHopSuite.difference(testCase, expected, answer);
                if (difference != null) {
                    wrong.add(label + ": " + difference);
                }
            }
        }
        measurements.put(label, new Measurement(label, testCase, latency, traffic.perRun(RUNS), size));
    }

    private long counter(String name) {
        return suite.metrics().value(name);
    }

    /** Latency and storage traffic of one query, averaged over the timed runs. */
    private record Measurement(String id, AerospikeHopSuite.Case testCase, LatencyStats latency,
                               Counters traffic, long size) {
    }

    /** Items and round trips of the timed runs, divided by the number of runs. */
    private record Counters(long vertexItems, long edgeItems, long decoded, long filtered, long kvCalls,
                            long kvItems) {

        Counters add(long vertexItems, long edgeItems, long decoded, long filtered, long kvCalls, long kvItems) {
            return new Counters(this.vertexItems + vertexItems, this.edgeItems + edgeItems,
                    this.decoded + decoded, this.filtered + filtered, this.kvCalls + kvCalls,
                    this.kvItems + kvItems);
        }

        Counters perRun(int runs) {
            return new Counters(Math.round(vertexItems / (double) runs), Math.round(edgeItems / (double) runs),
                    Math.round(decoded / (double) runs), Math.round(filtered / (double) runs),
                    Math.round(kvCalls / (double) runs), Math.round(kvItems / (double) runs));
        }
    }

    // ------------------------------------------------------------------ the report

    /** The two tables, the group summary and the note that says how to read them. */
    private static String report(AerospikeHopSuite suite, Map<String, Measurement> measurements,
                                 List<String> wrong) {
        StringBuilder out = new StringBuilder("\n");
        out.append("Aerospike payment demo (").append(suite.setId()).append("): ")
                .append(measurements.size()).append(" traversals, ")
                .append(WARMUP).append(" warm-up + ").append(RUNS).append(" timed runs each\n\n");
        out.append("Latency (ms)\n");
        out.append(String.format("%-22s %-5s %-7s %-7s %7s %7s %7s %7s %7s%n",
                "case", "hops", "ends", "size", "mean", "p50", "p95", "p99", "max"));
        out.append("-".repeat(90)).append('\n');
        for (Measurement measurement : measurements.values()) {
            out.append(String.format("%-22s %-5d %-7s %-7d %7.2f %7.2f %7.2f %7.2f %7.2f%n",
                    measurement.id(), suite.hops(measurement.testCase()),
                    measurement.testCase().endsAt() == AerospikeHopSuite.EndsAt.VERTEX ? "vertex" : "edge",
                    measurement.size(), measurement.latency().meanMillis(),
                    measurement.latency().percentileMillis(0.5), measurement.latency().percentileMillis(0.95),
                    measurement.latency().percentileMillis(0.99), measurement.latency().maxMillis()));
        }

        out.append("\nStorage traffic per run\n");
        out.append(String.format("%-22s %9s %9s %9s %9s %9s %9s%n",
                "case", "v-items", "e-items", "decoded", "filtered", "kv-calls", "kv-items"));
        out.append("-".repeat(82)).append('\n');
        for (Measurement measurement : measurements.values()) {
            Counters traffic = measurement.traffic();
            out.append(String.format("%-22s %9d %9d %9d %9d %9d %9d%n", measurement.id(),
                    traffic.vertexItems(), traffic.edgeItems(), traffic.decoded(), traffic.filtered(),
                    traffic.kvCalls(), traffic.kvItems()));
        }

        out.append("\nWhat each query is about\n");
        for (Measurement measurement : measurements.values()) {
            out.append(String.format("  %-22s %s%n", measurement.id(), measurement.testCase().label()));
        }

        out.append("\nGroup summary\n");
        for (String group : List.of("one hop", "two hops", "all")) {
            List<Double> p50 = new ArrayList<>();
            List<Double> mean = new ArrayList<>();
            List<Long> calls = new ArrayList<>();
            for (Measurement measurement : measurements.values()) {
                int hops = suite.hops(measurement.testCase());
                if (group.equals("one hop") && hops != 1 || group.equals("two hops") && hops != 2) {
                    continue;
                }
                p50.add(measurement.latency().percentileMillis(0.5));
                mean.add(measurement.latency().meanMillis());
                calls.add(measurement.traffic().kvCalls());
            }
            p50.sort(null);
            mean.sort(null);
            calls.sort(null);
            out.append(String.format("  %-9s n=%-3d p50 median %6.2f ms   mean median %6.2f ms   kv-calls median %4d%n",
                    group, p50.size(), p50.get(p50.size() / 2), mean.get(mean.size() / 2),
                    calls.get(calls.size() / 2)));
        }

        if (!wrong.isEmpty()) {
            out.append("\nWRONG ANSWERS: ").append(wrong).append('\n');
        }
        out.append("""

                How to read this
                ----------------
                `kv-calls` is the number of storage round trips a query made, so it is what latency tracks:
                a query that reads one record per hop costs a handful, one that reads a record per reached
                vertex costs as many as it has sources. `decoded` is the number of edges the store had to
                build - a bin holds every edge of a class (the hub has 50 payments), and a query that only
                counts, keeps the newest few or throws most edges away by a condition should build far
                fewer. `filtered` is what a pushed-down condition discarded before building it.
                """);
        return out.toString();
    }
}
