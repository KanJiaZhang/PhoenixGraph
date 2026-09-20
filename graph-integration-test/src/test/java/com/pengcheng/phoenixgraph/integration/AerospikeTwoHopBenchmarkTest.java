package com.pengcheng.phoenixgraph.integration;

import com.pengcheng.phoenixgraph.metrics.LatencyStats;
import com.pengcheng.phoenixgraph.metrics.Metrics;
import com.pengcheng.phoenixgraph.query.gremlin.GremlinGraphQuery;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Twenty two-hop traversals of the Aerospike demo graph, each run with three fan-out batch sizes, measured
 * with the latency, the storage traffic and the size of the subgraph it visits.
 *
 * <p>The twenty come in two halves: ten end on <b>edges</b> (the second hop is {@code outE}/{@code otherV}
 * plus an edge hop) and ten end on <b>vertices</b> (the second hop is {@code out}/{@code both}). Each hop
 * picks its own edge class, several carry a condition - on the first hop's vertices, on the second hop's
 * edges - and one caps its second hop, which the store applies per source vertex. The seeds are chosen for
 * size: the payment hub (50 payments), the star hub (100 accounts, 39 IPs and 10 phones each, so the second
 * hop reaches thousands of edges), three ring accounts, an IP and a phone.
 *
 * <p>Every query ends in a count, and each is paired with a denominator (usually the first hop's count), so
 * the report shows a <b>ratio</b> as well: edges reached per source, distinct targets per reached account,
 * and so on. The ratio is a property of the graph, the latency is a property of the plan and the batch size.
 *
 * <p>Each answer is compared with the reference reader of {@link AerospikeHopSuite} (which composes the same
 * hops and conditions straight from the stored records), so a measurement can never be of a query that
 * stopped answering the right thing. The subgraph is collected once per query outside the timing loop, which
 * is why its size is a constant of the query rather than part of what is measured.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AerospikeTwoHopBenchmarkTest {

    /** The fan-out batch sizes every query is run with (the {@code batch(n)} step's value). */
    private static final int[] BATCHES = {1, 25, 100};
    private static final int WARMUP = 2;
    private static final int RUNS = 8;
    /** The batch the subgraph of a query is collected with (its size does not depend on it). */
    private static final int SUBGRAPH_BATCH = 25;

    /** Which side of the second hop the answer is on. */
    private enum EndsAt {
        EDGE, VERTEX
    }

    /** What the traversal answers: a number, or the elements a cap lets through. */
    private enum Kind {
        COUNT, ELEMENTS
    }

    /** One traversal: its text ({@code %BATCH%} is where the batch step goes), its denominator, its answer. */
    private record Case(String id, String label, String query, String denominator, EndsAt endsAt, Kind kind,
                        String shape, Supplier<Object> expected) {
    }

    private AerospikeHopSuite suite;
    private final List<Measurement> measurements = new ArrayList<>();
    private final List<String> wrong = new ArrayList<>();
    private final Map<String, int[]> subgraphSizes = new LinkedHashMap<>();

    @BeforeAll
    void openTheDemoGraph() {
        suite = AerospikeHopSuite.open("payment-graph-two-hop");
    }

    @AfterAll
    void report() {
        if (suite == null) {
            return;
        }
        System.out.println(report(suite, measurements, subgraphSizes, wrong, BATCHES));
        suite.close();
    }

    @Test
    void twentyTwoHopTraversalsRunWithThreeBatchSizes() {
        List<Case> cases = cases();
        assertEquals(20, cases.size(), "twenty traversals");
        assertEquals(10, cases.stream().filter(c -> c.endsAt() == EndsAt.EDGE).count(),
                "ten of them end on edges");
        assertEquals(10, cases.stream().filter(c -> c.endsAt() == EndsAt.VERTEX).count(),
                "ten of them end on vertices");

        List<Case> all = new ArrayList<>(cases);
        all.addAll(elementCases());
        for (Case testCase : all) {
            subgraphSizes.put(testCase.id(), subgraphSize(testCase));
            for (int batch : BATCHES) {
                measure(testCase, batch);
            }
        }

        assertTrue(wrong.isEmpty(), "every measured query answers what the records say: " + wrong);
        assertEquals(24 * BATCHES.length, measurements.size(), "three batches per traversal");
        for (Measurement measurement : measurements) {
            assertTrue(measurement.latency().percentileMillis(0.95) < 3_000,
                    measurement.testCase().id() + " batch(" + measurement.batch() + ") stays under three seconds: "
                            + measurement.latency().percentileMillis(0.95));
        }
    }

    /** Collects the subgraph once (outside the timing) so the report can say how big each query is. */
    private int[] subgraphSize(Case testCase) {
        GremlinGraphQuery.Result result = suite.gremlin()
                .executeWithSubgraph(testCase.query().replace("%BATCH%", ".batch(" + SUBGRAPH_BATCH + ")"));
        return new int[] {result.subgraph().vertices().size(), result.subgraph().edges().size()};
    }

    /** Runs one traversal {@value #RUNS} times after warm-up, checking its answer once. */
    private void measure(Case testCase, int batch) {
        String query = testCase.query().replace("%BATCH%", ".batch(" + batch + ")");
        String denominator = testCase.denominator().replace("%BATCH%", ".batch(" + batch + ")");
        Object expected = testCase.expected().get();
        for (int i = 0; i < WARMUP; i++) {
            suite.gremlin().execute(query);
            suite.gremlin().execute(denominator);
        }
        LatencyStats latency = new LatencyStats();
        Counters traffic = new Counters(0, 0, 0, 0, 0, 0);
        long answer = 0;
        long base = 0;
        for (int i = 0; i < RUNS; i++) {
            long v0 = suite.metrics().value(Metrics.items(Metrics.VERTEX_READ));
            long e0 = suite.metrics().value(Metrics.items(Metrics.EDGE_READ));
            long d0 = suite.metrics().value(Metrics.EDGE_DECODED);
            long f0 = suite.metrics().value(Metrics.EDGE_FILTERED);
            long k0 = suite.metrics().value(Metrics.KV_READ_CALL);
            long ki0 = suite.metrics().value(Metrics.KV_READ_ITEMS);
            long started = System.nanoTime();
            List<Object> result = suite.gremlin().execute(query);
            latency.record(System.nanoTime() - started);
            traffic = traffic.add(suite.metrics().value(Metrics.items(Metrics.VERTEX_READ)) - v0,
                    suite.metrics().value(Metrics.items(Metrics.EDGE_READ)) - e0,
                    suite.metrics().value(Metrics.EDGE_DECODED) - d0,
                    suite.metrics().value(Metrics.EDGE_FILTERED) - f0,
                    suite.metrics().value(Metrics.KV_READ_CALL) - k0,
                    suite.metrics().value(Metrics.KV_READ_ITEMS) - ki0);
            answer = testCase.kind() == Kind.ELEMENTS ? result.size() : number(result);
            base = number(suite.gremlin().execute(denominator));
            if (i == 0) {
                String difference = compare(testCase, expected, answer);
                if (difference != null) {
                    wrong.add(testCase.id() + " batch(" + batch + "): " + difference);
                }
            }
        }
        measurements.add(new Measurement(testCase, batch, latency, traffic.perRun(RUNS), answer, base));
    }

    private static long number(List<Object> answer) {
        return answer.isEmpty() || !(answer.get(0) instanceof Number number) ? -1 : number.longValue();
    }

    private static String compare(Case testCase, Object expected, long actual) {
        long wanted = ((Number) expected).longValue();
        return wanted == actual ? null : "expected " + wanted + ", got " + actual;
    }

    // ------------------------------------------------------------------ the twenty traversals

    private List<Case> cases() {
        List<Case> cases = new ArrayList<>();

        // --- ten that end on edges -------------------------------------------------------------
        cases.add(new Case("E1", "hub's accounts' payments", batched("g.V('135','Account').out('Payment')",
                        ".outE('Payment').count()"),
                counted("g.V('135','Account').out('Payment')"), EndsAt.EDGE, Kind.COUNT, "Payment > Payment",
                () -> (long) hop2(suite.out(AerospikeHopSuite.HUB, "Payment"), "Payment").size()));
        cases.add(new Case("E2", "hub's ACTIVE accounts' payments",
                batched(filteredFirstHop("135", "Payment", "accountStatus", "ACTIVE"), ".outE('Payment').count()"),
                counted(filteredFirstHop("135", "Payment", "accountStatus", "ACTIVE")), EndsAt.EDGE, Kind.COUNT,
                "Payment > filter(accountStatus) > Payment",
                () -> (long) hop2(byVertexProperty(suite.out(AerospikeHopSuite.HUB, "Payment"),
                        "accountStatus", "ACTIVE"), "Payment").size()));
        cases.add(new Case("E3", "star hub's accounts' IP links",
                batched("g.V('150','Account').out('Payment')", ".outE('AccountToIP').count()"),
                counted("g.V('150','Account').out('Payment')"), EndsAt.EDGE, Kind.COUNT, "Payment > AccountToIP",
                () -> (long) hop2(suite.out("150", "Payment"), "AccountToIP").size()));
        cases.add(new Case("E4", "star hub's accounts' phone links",
                batched("g.V('150','Account').out('Payment')", ".outE('AccountToPhone').count()"),
                counted("g.V('150','Account').out('Payment')"), EndsAt.EDGE, Kind.COUNT, "Payment > AccountToPhone",
                () -> (long) hop2(suite.out("150", "Payment"), "AccountToPhone").size()));
        cases.add(new Case("E5", "star hub's SUCCESS payments' phone links",
                batched("g.V('150','Account').outE('Payment').has('status','SUCCESS')",
                        ".otherV().outE('AccountToPhone').count()"),
                counted("g.V('150','Account').outE('Payment').has('status','SUCCESS')"), EndsAt.EDGE, Kind.COUNT,
                "Payment(SUCCESS) > otherV > AccountToPhone",
                () -> (long) hop2(AerospikeHopSuite.matching(suite.out("150", "Payment"), "status", "SUCCESS"),
                        "AccountToPhone").size()));
        cases.add(new Case("E6", "ring account's payees' SUCCESS payments",
                batched("g.V('1014','Account').out('Payment')", ".outE('Payment').has('status','SUCCESS').count()"),
                counted("g.V('1014','Account').out('Payment')"), EndsAt.EDGE, Kind.COUNT,
                "Payment > Payment(SUCCESS)",
                () -> (long) AerospikeHopSuite.matching(
                        hop2(suite.out("1014", "Payment"), "Payment"), "status", "SUCCESS").size()));
        cases.add(new Case("E7", "ring account's payers' phone links",
                batched("g.V('1014','Account').outE('Payment')", ".otherV().outE('AccountToPhone').count()"),
                counted("g.V('1014','Account').outE('Payment')"), EndsAt.EDGE, Kind.COUNT,
                "Payment > otherV > AccountToPhone",
                () -> (long) hop2(suite.out("1014", "Payment"), "AccountToPhone")
                        .size()));
        cases.add(new Case("E8", "IP's accounts' payments",
                batched("g.V('10.20.31.15','IP').out('IPToAccount')", ".outE('Payment').count()"),
                counted("g.V('10.20.31.15','IP').out('IPToAccount')"), EndsAt.EDGE, Kind.COUNT, "IPToAccount > Payment",
                () -> (long) hop2(suite.out("10.20.31.15", "IPToAccount"), "Payment").size()));
        cases.add(new Case("E9", "ring account's payment neighbourhood's IP links",
                batched("g.V('1025','Account').both('Payment')", ".outE('AccountToIP').count()"),
                counted("g.V('1025','Account').both('Payment')"), EndsAt.EDGE, Kind.COUNT, "both(Payment) > AccountToIP",
                () -> (long) hop2(suite.both("1025", "Payment"), "AccountToIP").size()));
        cases.add(new Case("E10", "star hub's 5 newest accounts' IP links (cap on the first hop)",
                batched("g.V('150','Account').out('Payment').limit(5)", ".outE('AccountToIP').count()"),
                counted("g.V('150','Account').out('Payment').limit(5)"), EndsAt.EDGE, Kind.COUNT,
                "Payment limit(5) > AccountToIP",
                () -> {
                    // the limit is global (Gremlin semantics): the five newest payments of the hub are the
                    // five sources the second hop runs from
                    List<AerospikeHopSuite.RawEdge> newest = new ArrayList<>(suite.out("150", "Payment"));
                    newest.sort((a, b) -> Long.compare(b.lastUpdatedTime(), a.lastUpdatedTime()));
                    return (long) hop2(newest.subList(0, Math.min(5, newest.size())), "AccountToIP").size();
                }));

        // --- ten that end on vertices ----------------------------------------------------------
        cases.add(new Case("V1", "hub's accounts' payments, as vertices",
                batched("g.V('135','Account').out('Payment')", ".out('Payment').count()"),
                counted("g.V('135','Account').out('Payment')"), EndsAt.VERTEX, Kind.COUNT, "Payment > Payment",
                () -> (long) hop2(suite.out(AerospikeHopSuite.HUB, "Payment"), "Payment").size()));
        cases.add(new Case("V2", "hub's accounts' IPs",
                batched("g.V('135','Account').out('Payment')", ".out('AccountToIP').count()"),
                counted("g.V('135','Account').out('Payment')"), EndsAt.VERTEX, Kind.COUNT, "Payment > AccountToIP",
                () -> (long) hop2(suite.out(AerospikeHopSuite.HUB, "Payment"), "AccountToIP").size()));
        cases.add(new Case("V3", "star hub's accounts' IPs",
                batched("g.V('150','Account').out('Payment')", ".out('AccountToIP').count()"),
                counted("g.V('150','Account').out('Payment')"), EndsAt.VERTEX, Kind.COUNT, "Payment > AccountToIP",
                () -> (long) hop2(suite.out("150", "Payment"), "AccountToIP").size()));
        cases.add(new Case("V4", "star hub's accounts' phones",
                batched("g.V('150','Account').out('Payment')", ".out('AccountToPhone').count()"),
                counted("g.V('150','Account').out('Payment')"), EndsAt.VERTEX, Kind.COUNT, "Payment > AccountToPhone",
                () -> (long) hop2(suite.out("150", "Payment"), "AccountToPhone").size()));
        cases.add(new Case("V5", "star hub's ACTIVE accounts' phones",
                batched(filteredFirstHop("150", "Payment", "accountStatus", "ACTIVE"), ".out('AccountToPhone').count()"),
                counted(filteredFirstHop("150", "Payment", "accountStatus", "ACTIVE")), EndsAt.VERTEX, Kind.COUNT,
                "Payment > filter(accountStatus) > AccountToPhone",
                () -> (long) hop2(byVertexProperty(suite.out("150", "Payment"), "accountStatus", "ACTIVE"),
                        "AccountToPhone").size()));
        cases.add(new Case("V6", "ring account's payees' IPs",
                batched("g.V('1014','Account').out('Payment')", ".out('AccountToIP').count()"),
                counted("g.V('1014','Account').out('Payment')"), EndsAt.VERTEX, Kind.COUNT, "Payment > AccountToIP",
                () -> (long) hop2(suite.out("1014", "Payment"), "AccountToIP").size()));
        cases.add(new Case("V7", "ring account's payers' IPs",
                batched("g.V('1014','Account').outE('Payment')", ".otherV().out('AccountToIP').count()"),
                counted("g.V('1014','Account').outE('Payment')"), EndsAt.VERTEX, Kind.COUNT,
                "Payment > otherV > AccountToIP",
                () -> (long) hop2(suite.out("1014", "Payment"), "AccountToIP")
                        .size()));
        cases.add(new Case("V8", "phone's accounts' payments",
                batched("g.V('+86-138-10014','Phone').out('PhoneToAccount')", ".out('Payment').count()"),
                counted("g.V('+86-138-10014','Phone').out('PhoneToAccount')"), EndsAt.VERTEX, Kind.COUNT,
                "PhoneToAccount > Payment",
                () -> (long) hop2(suite.out("+86-138-10014", "PhoneToAccount"), "Payment").size()));
        cases.add(new Case("V9", "ring account's payment neighbourhood's IPs",
                batched("g.V('1025','Account').both('Payment')", ".out('AccountToIP').count()"),
                counted("g.V('1025','Account').both('Payment')"), EndsAt.VERTEX, Kind.COUNT, "both(Payment) > AccountToIP",
                () -> (long) hop2(suite.both("1025", "Payment"), "AccountToIP").size()));
        cases.add(new Case("V10", "hub's accounts' payments, distinct accounts",
                batched("g.V('135','Account').out('Payment')", ".out('Payment').dedup().count()"),
                counted("g.V('135','Account').out('Payment').out('Payment')"), EndsAt.VERTEX, Kind.COUNT,
                "Payment > Payment dedup()",
                () -> (long) new LinkedHashSet<>(
                        AerospikeHopSuite.peers(hop2(suite.out(AerospikeHopSuite.HUB, "Payment"), "Payment")))
                        .size()));
        return cases;
    }

    /**
     * Four more traversals that return elements instead of a count.
     *
     * <p>They exist to make the batch size visible: when the second hop is a <i>read</i> (not a count), the
     * engine groups its sources into batches and each batch is one storage round trip, so the batch size is
     * the difference between one trip per source and one per batch. On the counted traversals above the
     * second hop is answered per source from the shape of a bin, which is why their batch rows look alike.
     */
    private List<Case> elementCases() {
        List<Case> cases = new ArrayList<>();
        cases.add(new Case("X1", "star hub's IP links, first 500 (edge)",
                batched("g.V('150','Account').out('Payment')", ".outE('AccountToIP').limit(500)"),
                counted("g.V('150','Account').out('Payment')").replace(".count()", ".outE('AccountToIP').count()"),
                EndsAt.EDGE, Kind.ELEMENTS, "Payment > AccountToIP limit(500)",
                () -> (long) Math.min(500, hop2(suite.out("150", "Payment"), "AccountToIP").size())));
        cases.add(new Case("X2", "star hub's IPs, first 500 (vertex)",
                batched("g.V('150','Account').out('Payment')", ".out('AccountToIP').limit(500)"),
                counted("g.V('150','Account').out('Payment')").replace(".count()", ".out('AccountToIP').count()"),
                EndsAt.VERTEX, Kind.ELEMENTS, "Payment > AccountToIP limit(500)",
                () -> (long) Math.min(500, hop2(suite.out("150", "Payment"), "AccountToIP").size())));
        cases.add(new Case("X3", "hub's accounts' payments, first 120 (edge)",
                batched("g.V('135','Account').out('Payment')", ".outE('Payment').limit(120)"),
                counted("g.V('135','Account').out('Payment')").replace(".count()", ".outE('Payment').count()"),
                EndsAt.EDGE, Kind.ELEMENTS, "Payment > Payment limit(120)",
                () -> (long) Math.min(120, hop2(suite.out(AerospikeHopSuite.HUB, "Payment"), "Payment").size())));
        cases.add(new Case("X4", "hub's accounts' IPs, first 40 (vertex)",
                batched("g.V('135','Account').out('Payment')", ".out('AccountToIP').limit(40)"),
                counted("g.V('135','Account').out('Payment')").replace(".count()", ".out('AccountToIP').count()"),
                EndsAt.VERTEX, Kind.ELEMENTS, "Payment > AccountToIP limit(40)",
                () -> (long) Math.min(40, hop2(suite.out(AerospikeHopSuite.HUB, "Payment"), "AccountToIP").size())));
        return cases;
    }

    /** The query text with the batch step in place of the marker. */
    private static String batched(String prefix, String suffix) {
        return prefix + "%BATCH%" + suffix;
    }

    /** The same traversal, counted (used as the ratio's denominator). */
    private static String counted(String prefix) {
        return prefix + "%BATCH%.count()";
    }

    private static String filteredFirstHop(String seed, String hop, String property, String value) {
        return "g.V('" + seed + "','Account').out('" + hop + "').filter(" + property + ".equals('" + value + "'))";
    }

    /** The edges whose far endpoint has one of the given values for a property (the reference's filter). */
    private List<AerospikeHopSuite.RawEdge> byVertexProperty(List<AerospikeHopSuite.RawEdge> edges,
                                                            String property, String... values) {
        Set<String> wanted = Set.of(values);
        List<AerospikeHopSuite.RawEdge> out = new ArrayList<>();
        for (AerospikeHopSuite.RawEdge edge : edges) {
            Object actual = suite.vertex(edge.peer()).get(property);
            if (actual != null && wanted.contains(String.valueOf(actual))) {
                out.add(edge);
            }
        }
        return out;
    }

    private List<AerospikeHopSuite.RawEdge> hop2(List<AerospikeHopSuite.RawEdge> first, String edgeName) {
        return suite.hop2(first, edgeName);
    }

    /** Latency and storage traffic of one traversal at one batch size, averaged over the timed runs. */
    private record Measurement(Case testCase, int batch, LatencyStats latency, Counters traffic, long answer,
                               long denominator) {

        double ratio() {
            return denominator == 0 ? 0 : (double) answer / denominator;
        }
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

    private static String report(AerospikeHopSuite suite, List<Measurement> measurements,
                                 Map<String, int[]> subgraphSizes, List<String> wrong, int[] batches) {
        StringBuilder out = new StringBuilder("\n");
        out.append("Aerospike two-hop benchmark (").append(suite.setId()).append("): ")
                .append(measurements.size() / batches.length).append(" traversals x ")
                .append(batches.length).append(" batch sizes, ")
                .append(WARMUP).append(" warm-up + ").append(RUNS).append(" timed runs each\n");

        out.append("\nTraversals\n");
        out.append(String.format("%-5s %-6s %-9s %-42s %-9s %-9s %s%n",
                "case", "ends", "subgraph", "shape", "answer", "base", "ratio"));
        out.append("-".repeat(120)).append('\n');
        Set<String> seen = new LinkedHashSet<>();
        for (Measurement measurement : measurements) {
            Case testCase = measurement.testCase();
            if (!seen.add(testCase.id())) {
                continue;
            }
            int[] size = subgraphSizes.getOrDefault(testCase.id(), new int[] {0, 0});
            out.append(String.format("%-5s %-6s %4d v %4d e %-42s %-9d %-9d %.3f%n",
                    testCase.id(), testCase.endsAt() == EndsAt.EDGE ? "edge" : "vertex",
                    size[0], size[1], testCase.shape(), measurement.answer(), measurement.denominator(),
                    measurement.ratio()));
        }

        out.append("\nLatency and traffic per batch size (ms; items and round trips per run)\n");
        out.append(String.format("%-5s %-7s %7s %7s %7s %7s %7s %8s %8s %8s %8s%n",
                "case", "batch", "mean", "p50", "p95", "max", "answer", "v-items", "e-items", "decoded", "kv-calls"));
        out.append("-".repeat(112)).append('\n');
        for (Measurement measurement : measurements) {
            Counters traffic = measurement.traffic();
            out.append(String.format("%-5s %-7d %7.2f %7.2f %7.2f %7.2f %7d %8d %8d %8d %8d%n",
                    measurement.testCase().id(), measurement.batch(), measurement.latency().meanMillis(),
                    measurement.latency().percentileMillis(0.5), measurement.latency().percentileMillis(0.95),
                    measurement.latency().maxMillis(), measurement.answer(), traffic.vertexItems(),
                    traffic.edgeItems(), traffic.decoded(), traffic.kvCalls()));
        }

        out.append("\nSummary\n");
        for (String group : List.of("ends on edges", "ends on vertices", "all")) {
            List<Double> p50 = new ArrayList<>();
            List<Long> calls = new ArrayList<>();
            for (Measurement measurement : measurements) {
                boolean edges = measurement.testCase().endsAt() == EndsAt.EDGE;
                if (group.equals("ends on edges") && !edges || group.equals("ends on vertices") && edges) {
                    continue;
                }
                p50.add(measurement.latency().percentileMillis(0.5));
                calls.add(measurement.traffic().kvCalls());
            }
            p50.sort(null);
            calls.sort(null);
            out.append(String.format("  %-17s n=%-3d p50 median %7.2f ms   kv-calls median %5d%n",
                    group, p50.size(), p50.get(p50.size() / 2), calls.get(calls.size() / 2)));
        }
        for (int batch : batches) {
            List<Double> p50 = new ArrayList<>();
            List<Long> calls = new ArrayList<>();
            List<Long> decoded = new ArrayList<>();
            for (Measurement measurement : measurements) {
                if (measurement.batch() != batch) {
                    continue;
                }
                p50.add(measurement.latency().percentileMillis(0.5));
                calls.add(measurement.traffic().kvCalls());
                decoded.add(measurement.traffic().decoded());
            }
            p50.sort(null);
            calls.sort(null);
            decoded.sort(null);
            out.append(String.format("  %-17s n=%-3d p50 median %7.2f ms   kv-calls median %5d   decoded median %5d%n",
                    "batch(" + batch + ")", p50.size(), p50.get(p50.size() / 2), calls.get(calls.size() / 2),
                    decoded.get(decoded.size() / 2)));
        }
        for (String size : List.of("small (< 100)", "medium (100-1000)", "large (> 1000)")) {
            List<Double> p50 = new ArrayList<>();
            List<Long> calls = new ArrayList<>();
            for (Measurement measurement : measurements) {
                int[] subgraph = subgraphSizes.getOrDefault(measurement.testCase().id(), new int[] {0, 0});
                long elements = subgraph[0] + (long) subgraph[1];
                boolean small = elements < 100;
                boolean medium = elements >= 100 && elements <= 1000;
                boolean large = elements > 1000;
                if (size.startsWith("small") && !small || size.startsWith("medium") && !medium
                        || size.startsWith("large") && !large) {
                    continue;
                }
                p50.add(measurement.latency().percentileMillis(0.5));
                calls.add(measurement.traffic().kvCalls());
            }
            if (p50.isEmpty()) {
                continue;
            }
            p50.sort(null);
            calls.sort(null);
            out.append(String.format("  %-17s n=%-3d p50 median %7.2f ms   kv-calls median %5d%n",
                    size, p50.size(), p50.get(p50.size() / 2), calls.get(calls.size() / 2)));
        }

        if (!wrong.isEmpty()) {
            out.append("\nWRONG ANSWERS: ").append(wrong).append('\n');
        }
        out.append("""

                How to read this
                ----------------
                `subgraph` is what the traversal visited (the canvas a UI would draw): the vertices and edges
                of the answer's induced subgraph, collected once per query outside the timing. `answer` is the
                count the query returns and `base` the first hop's count, so `ratio` is the fan-out - edges
                reached per source, distinct accounts per reached account for the dedup case, and so on.

                `batch(n)` sets the fan-out batch size of the hop that follows it, which is where the second
                hop's reads are split; `kv-calls` therefore tracks the batch size whenever a hop has many
                sources, and `decoded` says how many edges the store had to build for the answer. The three
                batch sizes are the same twenty queries, so their rows differ only in how the reads were
                grouped.
                """);
        return out.toString();
    }
}
