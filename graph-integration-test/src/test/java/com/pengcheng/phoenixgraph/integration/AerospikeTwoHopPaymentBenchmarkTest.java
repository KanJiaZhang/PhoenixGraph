package com.pengcheng.phoenixgraph.integration;

import com.pengcheng.phoenixgraph.metrics.LatencyStats;
import com.pengcheng.phoenixgraph.metrics.Metrics;
import com.pengcheng.phoenixgraph.query.gremlin.GremlinVertex;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Near-line benchmark of two-hop <b>Payment</b> traversals that end on a vertex: every query walks two
 * payment edges ({@code Payment > Payment}) and its last step hands back vertices - reached accounts, their
 * values, or a count of them.
 *
 * <p>What varies is the traversal, not the storage: the same two hops are written with the steps a caller
 * reaches for - {@code out}/{@code in}/{@code both}, an edge hop followed by {@code otherV}, {@code has} on
 * the payment, {@code filter} on the intermediate account, {@code dedup}, {@code limit}, {@code batch} and
 * {@code barrier} between the hops, and {@code values}/{@code elementMap} to look at the reached vertices
 * instead of counting them. Each is a different plan and the report says what each plan cost.
 *
 * <p>Like a production run rather than a single measurement, the seeds are several accounts of different
 * sizes (the payment hub, four ring accounts, and both directions), the per-query numbers are a distribution
 * (mean/p50/p95/p99/max) collected after warm-up, and a final mixed pass runs every query back to back for
 * the workload's own latency. Every answer is checked against {@link AerospikeHopSuite}'s reference reader,
 * so a measured query is never one that stopped being right.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AerospikeTwoHopPaymentBenchmarkTest {

    private static final String HUB = AerospikeHopSuite.HUB;
    private static final int WARMUP = 3;
    private static final int RUNS = 12;
    private static final int MIXED_ROUNDS = 4;

    /** How a traversal's answer is checked against the stored records. */
    private enum Check {
        /** The reached vertex ids, as a multiset. */
        IDS,
        /** The count the traversal returns. */
        COUNT,
        /** The number of items returned (a cap, a projection, a map per vertex). */
        SIZE
    }

    private record Query(String id, String seed, String steps, String gremlin, Check check,
                         List<Object> expectedIds, Long expected) {
    }

    private AerospikeHopSuite suite;
    private final List<Result> results = new ArrayList<>();
    private final List<String> wrong = new ArrayList<>();

    @BeforeAll
    void openTheDemoGraph() {
        suite = AerospikeHopSuite.open("payment-graph-two-hop-payment");
    }

    @AfterAll
    void report() {
        if (suite == null) {
            return;
        }
        System.out.println(report(suite, results, wrong));
        suite.close();
    }

    @Test
    void twoHopPaymentTraversalsEndingOnVerticesAreMeasured() {
        List<Query> queries = queries();
        assertTrue(queries.size() >= 20, "a spread of traversals: " + queries.size());
        for (Query query : queries) {
            assertTrue(query.gremlin().contains("Payment"), query.id() + " walks payment edges");
            results.add(measure(query));
        }

        // the mixed pass: every query once per round, back to back, the way a dashboard asks them
        long started = System.nanoTime();
        int executions = 0;
        for (int round = 0; round < MIXED_ROUNDS; round++) {
            for (Query query : queries) {
                suite.gremlin().execute(query.gremlin());
                executions++;
            }
        }
        long mixedNanos = System.nanoTime() - started;

        assertTrue(wrong.isEmpty(), "every measured traversal answers what the records say: " + wrong);
        assertEquals(queries.size(), results.size(), "one measurement per traversal");
        for (Result result : results) {
            assertTrue(result.latency().percentileMillis(0.95) < 3_000,
                    result.query().id() + " stays under three seconds: "
                            + result.latency().percentileMillis(0.95));
        }
        System.out.printf("%nMIXED PASS: %d executions (%d traversals x %d rounds) in %.0f ms, %.0f us each%n",
                executions, queries.size(), MIXED_ROUNDS, mixedNanos / 1e6, mixedNanos / 1e3 / executions);
    }

    /** Runs one traversal after warm-up, checking its answer once. */
    private Result measure(Query query) {
        for (int i = 0; i < WARMUP; i++) {
            suite.gremlin().execute(query.gremlin());
        }
        LatencyStats latency = new LatencyStats();
        Counters traffic = new Counters(0, 0, 0, 0, 0, 0);
        long answer = 0;
        for (int i = 0; i < RUNS; i++) {
            long v0 = suite.metrics().value(Metrics.items(Metrics.VERTEX_READ));
            long e0 = suite.metrics().value(Metrics.items(Metrics.EDGE_READ));
            long d0 = suite.metrics().value(Metrics.EDGE_DECODED);
            long k0 = suite.metrics().value(Metrics.KV_READ_CALL);
            long ki0 = suite.metrics().value(Metrics.KV_READ_ITEMS);
            long started = System.nanoTime();
            List<Object> result = suite.gremlin().execute(query.gremlin());
            latency.record(System.nanoTime() - started);
            traffic = traffic.add(suite.metrics().value(Metrics.items(Metrics.VERTEX_READ)) - v0,
                    suite.metrics().value(Metrics.items(Metrics.EDGE_READ)) - e0,
                    suite.metrics().value(Metrics.EDGE_DECODED) - d0, 0,
                    suite.metrics().value(Metrics.KV_READ_CALL) - k0,
                    suite.metrics().value(Metrics.KV_READ_ITEMS) - ki0);
            answer = query.check() == Check.COUNT ? number(result) : result.size();
            if (i == 0) {
                String difference = compare(query, result);
                if (difference != null) {
                    wrong.add(query.id() + ": " + difference);
                }
            }
        }
        return new Result(query, latency, traffic.perRun(RUNS), answer);
    }

    private static long number(List<Object> answer) {
        return answer.isEmpty() || !(answer.get(0) instanceof Number number) ? -1 : number.longValue();
    }

    private static String compare(Query query, List<Object> answer) {
        if (query.check() == Check.COUNT) {
            return number(answer) == query.expected() ? null
                    : "expected count " + query.expected() + ", got " + number(answer);
        }
        List<String> actual = ids(answer);
        if (query.check() == Check.SIZE) {
            if (actual.size() != query.expected()) {
                return "expected " + query.expected() + " items, got " + actual.size();
            }
            if (query.expectedIds().isEmpty()) {
                return null;
            }
        }
        List<String> wanted = strings(query.expectedIds());
        if (query.check() == Check.SIZE) {
            Set<String> allowed = new LinkedHashSet<>(wanted);
            for (String id : actual) {
                if (!allowed.contains(id)) {
                    return "id " + id + " is not among the reached accounts";
                }
            }
            return null;
        }
        actual.sort(null);
        wanted.sort(null);
        return wanted.equals(actual) ? null
                : "expected " + wanted.size() + " reached accounts " + preview(wanted)
                        + ", got " + actual.size() + " " + preview(actual);
    }

    private static List<String> ids(List<Object> answer) {
        List<String> out = new ArrayList<>(answer.size());
        for (Object element : answer) {
            out.add(element instanceof GremlinVertex vertex ? String.valueOf(vertex.id())
                    : String.valueOf(element));
        }
        return out;
    }

    private static List<String> strings(List<Object> values) {
        List<String> out = new ArrayList<>(values.size());
        for (Object value : values) {
            out.add(String.valueOf(value));
        }
        return out;
    }

    private static String preview(List<String> ids) {
        return ids.subList(0, Math.min(6, ids.size())) + (ids.size() > 6 ? "..." : "");
    }

    // ------------------------------------------------------------------ the traversals

    private List<Query> queries() {
        List<Query> queries = new ArrayList<>();

        // the plain two-hop walk from seeds of different sizes (one vertex hop each)
        for (String seed : List.of(HUB, "1000", "1014", "1025", "1049")) {
            List<Object> reached = peers(hop2(out(seed)));
            queries.add(new Query("P-" + seed + " out>out", seed, "out > out",
                    "g.V('" + seed + "','Account').out('Payment').out('Payment')",
                    Check.IDS, reached, null));
        }

        // the same hop written as an edge hop plus otherV, and with a condition on either side
        queries.add(ids(HUB, "outE > otherV > out",
                "g.V('" + HUB + "','Account').outE('Payment').otherV().out('Payment')", hop2(out(HUB))));
        queries.add(ids("1014", "outE > otherV > out",
                "g.V('1014','Account').outE('Payment').otherV().out('Payment')", hop2(out("1014"))));
        queries.add(ids(HUB, "outE has(status) > otherV > out",
                "g.V('" + HUB + "','Account').outE('Payment').has('status','SUCCESS').otherV().out('Payment')",
                hop2(AerospikeHopSuite.matching(out(HUB), "status", "SUCCESS"))));
        queries.add(ids(HUB, "out > filter(accountStatus) > out",
                "g.V('" + HUB + "','Account').out('Payment').filter(accountStatus.equals('ACTIVE'))"
                        + ".out('Payment')",
                hop2(byAccountProperty(out(HUB), "accountStatus", "ACTIVE"))));
        queries.add(ids("1014", "out > filter(accountStatus=FROZEN) > out",
                "g.V('1014','Account').out('Payment').filter(accountStatus.equals('FROZEN'))"
                        + ".out('Payment')",
                hop2(byAccountProperty(out("1014"), "accountStatus", "FROZEN"))));

        // the reverse direction, and both directions at once (the second hop follows the first)
        queries.add(ids("1000", "in > in", "g.V('1000','Account').in('Payment').in('Payment')",
                hop2(suite.in("1000", "Payment"), EdgeSide.IN)));
        queries.add(ids("1000", "both > both", "g.V('1000','Account').both('Payment').both('Payment')",
                hop2(suite.both("1000", "Payment"), EdgeSide.BOTH)));

        // the trailing steps: distinct accounts, a cap, a count, and a count of the distinct ones
        queries.add(new Query("P-hub out>out>dedup", HUB, "out > out > dedup",
                "g.V('" + HUB + "','Account').out('Payment').out('Payment').dedup()", Check.IDS,
                distinct(hop2(out(HUB))), null));
        queries.add(new Query("P-hub out>out>limit(20)", HUB, "out > out > limit(20)",
                "g.V('" + HUB + "','Account').out('Payment').out('Payment').limit(20)", Check.SIZE,
                peers(hop2(out(HUB))), 20L));
        queries.add(count("P-hub out>out>count", HUB, "out > out > count",
                "g.V('" + HUB + "','Account').out('Payment').out('Payment').count()", hop2(out(HUB)).size()));
        queries.add(count("P-hub out>out>dedup>count", HUB, "out > out > dedup > count",
                "g.V('" + HUB + "','Account').out('Payment').out('Payment').dedup().count()",
                distinct(hop2(out(HUB))).size()));
        queries.add(count("P-1014 out>out>count", "1014", "out > out > count",
                "g.V('1014','Account').out('Payment').out('Payment').count()", hop2(out("1014")).size()));

        // the two ordering steps a caller can put between the hops
        queries.add(ids(HUB, "out > batch(10) > out",
                "g.V('" + HUB + "','Account').out('Payment').batch(10).out('Payment')", hop2(out(HUB))));
        queries.add(ids(HUB, "out > barrier(10) > out",
                "g.V('" + HUB + "','Account').out('Payment').barrier(10).out('Payment')", hop2(out(HUB))));

        // looking at the reached vertices instead of counting them
        queries.add(size(HUB, "out > out > values(accountStatus)",
                "g.V('" + HUB + "','Account').out('Payment').out('Payment').values('accountStatus')",
                hop2(out(HUB)).size()));
        queries.add(size(HUB, "out > out > values(firstName)",
                "g.V('" + HUB + "','Account').out('Payment').out('Payment').values('firstName')",
                hop2(out(HUB)).size()));
        queries.add(size(HUB, "out > out > elementMap",
                "g.V('" + HUB + "','Account').out('Payment').out('Payment').elementMap()",
                hop2(out(HUB)).size()));
        return queries;
    }

    private Query ids(String seed, String shape, String gremlin, List<AerospikeHopSuite.RawEdge> secondHop) {
        return new Query("P-" + seed + " " + shape, seed, shape, gremlin, Check.IDS, peers(secondHop), null);
    }

    private Query count(String id, String seed, String shape, String gremlin, long expected) {
        return new Query(id, seed, shape, gremlin, Check.COUNT, List.of(), expected);
    }

    private Query size(String seed, String shape, String gremlin, long expected) {
        return new Query("P-" + seed + " " + shape, seed, shape, gremlin, Check.SIZE, List.of(), expected);
    }

    private List<AerospikeHopSuite.RawEdge> out(String seed) {
        return suite.out(seed, "Payment");
    }

    private List<AerospikeHopSuite.RawEdge> hop2(List<AerospikeHopSuite.RawEdge> first) {
        return suite.hop2(first, "Payment", EdgeSide.OUT);
    }

    /** The second hop on a given side: {@code in > in} and {@code both > both} follow the first hop. */
    private List<AerospikeHopSuite.RawEdge> hop2(List<AerospikeHopSuite.RawEdge> first, EdgeSide side) {
        return suite.hop2(first, "Payment", side);
    }

    private List<AerospikeHopSuite.RawEdge> byAccountProperty(List<AerospikeHopSuite.RawEdge> edges,
                                                             String property, String... values) {
        Set<String> wanted = Set.of(values);
        List<AerospikeHopSuite.RawEdge> kept = new ArrayList<>();
        for (AerospikeHopSuite.RawEdge edge : edges) {
            Object actual = suite.vertex(edge.peer()).get(property);
            if (actual != null && wanted.contains(String.valueOf(actual))) {
                kept.add(edge);
            }
        }
        return kept;
    }

    private static List<Object> peers(List<AerospikeHopSuite.RawEdge> edges) {
        return new ArrayList<>(AerospikeHopSuite.peers(edges));
    }

    /** The peers of a list of edges, deduplicated (the reference for an explicit or implicit dedup). */
    private static List<Object> distinct(List<AerospikeHopSuite.RawEdge> edges) {
        return new ArrayList<>(new LinkedHashSet<>(AerospikeHopSuite.peers(edges)));
    }

    /** Latency and traffic of one traversal, averaged over the timed runs. */
    private record Result(Query query, LatencyStats latency, Counters traffic, long answer) {
    }

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

    private static String report(AerospikeHopSuite suite, List<Result> results, List<String> wrong) {
        StringBuilder out = new StringBuilder("\n");
        out.append("Two-hop Payment, ending on vertices (").append(suite.setId()).append("): ")
                .append(results.size()).append(" traversals, ").append(WARMUP).append(" warm-up + ")
                .append(RUNS).append(" timed runs each\n");

        out.append("\nThe traversals\n");
        for (Result result : results) {
            out.append("  ").append(result.query().gremlin()).append('\n');
        }

        out.append("\nLatency (ms) and storage traffic per run\n");
        out.append(String.format("%-36s %-6s %7s %7s %7s %7s %7s %8s %8s %8s%n",
                "query", "answer", "mean", "p50", "p95", "p99", "max", "v-items", "e-items", "kv-calls"));
        out.append("-".repeat(122)).append('\n');
        for (Result result : results) {
            Counters traffic = result.traffic();
            out.append(String.format("%-36s %-6d %7.2f %7.2f %7.2f %7.2f %7.2f %8d %8d %8d%n",
                    result.query().id(), result.answer(), result.latency().meanMillis(),
                    result.latency().percentileMillis(0.5), result.latency().percentileMillis(0.95),
                    result.latency().percentileMillis(0.99), result.latency().maxMillis(),
                    traffic.vertexItems(), traffic.edgeItems(), traffic.kvCalls()));
        }

        out.append("\nBy step shape\n");
        Map<String, List<Double>> byShape = new LinkedHashMap<>();
        for (Result result : results) {
            byShape.computeIfAbsent(result.query().steps(), key -> new ArrayList<>())
                    .add(result.latency().percentileMillis(0.5));
        }
        for (Map.Entry<String, List<Double>> entry : byShape.entrySet()) {
            List<Double> p50 = new ArrayList<>(entry.getValue());
            p50.sort(null);
            out.append(String.format("  %-44s n=%-3d p50 median %7.2f ms%n",
                    entry.getKey(), p50.size(), p50.get(p50.size() / 2)));
        }

        if (!wrong.isEmpty()) {
            out.append("\nWRONG ANSWERS: ").append(wrong).append('\n');
        }
        out.append("""

                How to read this
                ----------------
                Every traversal is two payment hops and its last step hands back vertices, so the plans differ
                only in the steps between and after the hops: out/in/both choose the direction, an edge hop
                plus otherV is the same hop read as edges first, has/filter remove payments or accounts before
                the second hop, dedup makes the reached set distinct, limit caps it, batch/barrier change how
                the second hop's reads are grouped, and values/elementMap look at the reached vertices instead
                of counting them.

                `answer` is what the traversal returned (a count, the number of vertices, or of values/maps),
                and the traffic columns say what it cost: `e-items` the edge lists read, `v-items` the vertices
                read, and `kv-calls` the round trips - which is what latency tracks, on a graph where the hub
                reaches fifty accounts and each of them pays three more.
                """);
        return out.toString();
    }
}
