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
        List<Query> bothQueries = bothQueries();
        assertTrue(bothQueries.size() >= 15, "a spread of both-hop traversals: " + bothQueries.size());

        List<Query> all = new ArrayList<>(queries);
        all.addAll(bothQueries);
        List<Query> star = starQueries();
        all.addAll(star);
        for (Query query : all) {
            assertTrue(query.gremlin().contains("Payment"), query.id() + " walks payment edges");
            results.add(measure(query));
        }
        // a mixed pass per spelling: every query once per round, back to back, as a dashboard asks them
        mixedPass(queries, "out/in/both first hop");
        mixedPass(bothQueries, "both on every hop");
        mixedPass(star, "star hub 150, IP/phone second hop");

        assertTrue(wrong.isEmpty(), "every measured traversal answers what the records say: " + wrong);
        assertEquals(all.size(), results.size(), "one measurement per traversal");
        for (Result result : results) {
            assertTrue(result.latency().percentileMillis(0.95) < 3_000,
                    result.query().id() + " stays under three seconds: "
                            + result.latency().percentileMillis(0.95));
        }
    }

    /** Runs a set of traversals back to back for the workload's own latency. */
    private void mixedPass(List<Query> queries, String label) {
        long started = System.nanoTime();
        int executions = 0;
        for (int round = 0; round < MIXED_ROUNDS; round++) {
            for (Query query : queries) {
                suite.gremlin().execute(query.gremlin());
                executions++;
            }
        }
        System.out.printf("%nMIXED PASS (%s): %d executions (%d traversals x %d rounds) in %.0f ms, %.0f us each%n",
                label, executions, queries.size(), MIXED_ROUNDS, (System.nanoTime() - started) / 1e6,
                (System.nanoTime() - started) / 1e3 / executions);
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

        // the plain two-hop walk from seeds of different sizes (one vertex hop each).
        // 150 is the star hub (a hundred payments out); its accounts do not pay, so a payment second hop from
        // it is empty and the star group at the end is where that hub has data.
        for (String seed : List.of(HUB, "150", "1000", "1014", "1025", "1049")) {
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

    /**
     * The same traversals with {@code both} on <b>every</b> hop: each step reaches its neighbours in both
     * directions, so the same plan sees far more sources - the hub's second hop goes from a hundred and
     * fifty reached accounts to three hundred and fifty, a ring account from nine to ninety-two.
     *
     * <p>Everything else is the same: the same seeds, the same step shapes, the same warm-up and run counts,
     * the same reference check (the reference reads both sides), and the report pairs each traversal with its
     * single-direction spelling.
     */
    private List<Query> bothQueries() {
        List<Query> queries = new ArrayList<>();

        for (String seed : List.of(HUB, "150", "1000", "1014", "1025", "1049")) {
            queries.add(bothIds(seed, "both > both", g(seed).both().both(), hop2Both(suite.both(seed, "Payment"))));
        }
        queries.add(bothIds(HUB, "bothE > otherV > both",
                g(HUB).bothE().otherV().both(), hop2Both(suite.both(HUB, "Payment"))));
        queries.add(bothIds("1014", "bothE > otherV > both",
                g("1014").bothE().otherV().both(), hop2Both(suite.both("1014", "Payment"))));
        queries.add(bothIds(HUB, "bothE has(status) > otherV > both",
                g(HUB).bothE().has("status", "SUCCESS").otherV().both(),
                hop2Both(AerospikeHopSuite.matching(suite.both(HUB, "Payment"), "status", "SUCCESS"))));
        queries.add(bothIds(HUB, "both > filter(accountStatus) > both",
                g(HUB).both().filter("accountStatus", "ACTIVE").both(),
                hop2Both(byAccountProperty(suite.both(HUB, "Payment"), "accountStatus", "ACTIVE"))));
        queries.add(bothIds("1014", "both > filter(accountStatus=FROZEN) > both",
                g("1014").both().filter("accountStatus", "FROZEN").both(),
                hop2Both(byAccountProperty(suite.both("1014", "Payment"), "accountStatus", "FROZEN"))));

        queries.add(new Query("B-hub both>both>dedup", HUB, "both > both > dedup",
                g(HUB).both().both() + ".dedup()", Check.IDS, distinct(hop2Both(suite.both(HUB, "Payment"))), null));
        queries.add(new Query("B-hub both>both>limit(20)", HUB, "both > both > limit(20)",
                g(HUB).both().both() + ".limit(20)", Check.SIZE, peers(hop2Both(suite.both(HUB, "Payment"))), 20L));
        queries.add(count("B-hub both>both>count", HUB, "both > both > count",
                g(HUB).both().both() + ".count()", hop2Both(suite.both(HUB, "Payment")).size()));
        queries.add(count("B-hub both>both>dedup>count", HUB, "both > both > dedup > count",
                g(HUB).both().both() + ".dedup().count()",
                distinct(hop2Both(suite.both(HUB, "Payment"))).size()));
        queries.add(count("B-1014 both>both>count", "1014", "both > both > count",
                g("1014").both().both() + ".count()", hop2Both(suite.both("1014", "Payment")).size()));

        queries.add(bothIds(HUB, "both > batch(10) > both",
                g(HUB).both().batch(10).both(), hop2Both(suite.both(HUB, "Payment"))));
        queries.add(bothIds(HUB, "both > barrier(10) > both",
                g(HUB).both().barrier(10).both(), hop2Both(suite.both(HUB, "Payment"))));

        queries.add(size(HUB, "both > both > values(accountStatus)",
                g(HUB).both().both() + ".values('accountStatus')",
                hop2Both(suite.both(HUB, "Payment")).size()));
        queries.add(size(HUB, "both > both > values(firstName)",
                g(HUB).both().both() + ".values('firstName')",
                hop2Both(suite.both(HUB, "Payment")).size()));
        queries.add(size(HUB, "both > both > elementMap",
                g(HUB).both().both() + ".elementMap()", hop2Both(suite.both(HUB, "Payment")).size()));
        return queries;
    }

    /**
     * The star hub 150 with its second hop on the classes it actually has: a hundred payments out to accounts
     * that each hold thirty-nine IP links and ten phone links, so this is the biggest second hop in the demo
     * (three thousand nine hundred edges).
     *
     * <p>The star accounts do not pay anyone, so the interesting measurements for 150 are these: the same step
     * shapes as above, over the IP and phone classes.
     */
    private List<Query> starQueries() {
        String seed = "150";
        List<AerospikeHopSuite.RawEdge> hop1 = suite.out(seed, "Payment");
        List<AerospikeHopSuite.RawEdge> ips = hop2Into(hop1, "AccountToIP");
        List<AerospikeHopSuite.RawEdge> phones = hop2Into(hop1, "AccountToPhone");
        List<Query> queries = new ArrayList<>();

        queries.add(star("out > out(AccountToIP)",
                "g.V('150','Account').out('Payment').out('AccountToIP')",
                Check.IDS, peers(ips), null));
        queries.add(star("both > both(AccountToIP)",
                "g.V('150','Account').both('Payment').both('AccountToIP')",
                Check.IDS, peers(hop2Into(suite.both(seed, "Payment"), "AccountToIP", EdgeSide.BOTH)), null));
        queries.add(star("outE has(status) > otherV > out(AccountToIP)",
                "g.V('150','Account').outE('Payment').has('status','SUCCESS').otherV().out('AccountToIP')",
                Check.IDS, peers(hop2Into(AerospikeHopSuite.matching(hop1, "status", "SUCCESS"),
                        "AccountToIP")), null));
        queries.add(star("out > filter(accountStatus) > out(AccountToIP)",
                "g.V('150','Account').out('Payment').filter(accountStatus.equals('ACTIVE'))"
                        + ".out('AccountToIP')",
                Check.IDS, peers(hop2Into(byAccountProperty(hop1, "accountStatus", "ACTIVE"),
                        "AccountToIP")), null));
        queries.add(star("out > out(AccountToIP) > dedup",
                "g.V('150','Account').out('Payment').out('AccountToIP').dedup()",
                Check.IDS, distinct(ips), null));
        queries.add(star("out > out(AccountToIP) > limit(500)",
                "g.V('150','Account').out('Payment').out('AccountToIP').limit(500)",
                Check.SIZE, peers(ips), 500L));
        queries.add(star("out > out(AccountToIP) > count",
                "g.V('150','Account').out('Payment').out('AccountToIP').count()",
                Check.COUNT, List.of(), (long) ips.size()));
        queries.add(star("out > out(AccountToIP) > dedup > count",
                "g.V('150','Account').out('Payment').out('AccountToIP').dedup().count()",
                Check.COUNT, List.of(), (long) distinct(ips).size()));
        queries.add(star("out > batch(10) > out(AccountToIP)",
                "g.V('150','Account').out('Payment').batch(10).out('AccountToIP')",
                Check.IDS, peers(ips), null));
        queries.add(star("out > barrier(100) > out(AccountToIP)",
                "g.V('150','Account').out('Payment').barrier(100).out('AccountToIP')",
                Check.IDS, peers(ips), null));
        queries.add(star("out > out(AccountToPhone) > count",
                "g.V('150','Account').out('Payment').out('AccountToPhone').count()",
                Check.COUNT, List.of(), (long) phones.size()));
        queries.add(star("out > out(AccountToIP) > values(ipAddress)",
                "g.V('150','Account').out('Payment').out('AccountToIP').values('ipAddress')",
                Check.SIZE, List.of(), (long) ips.size()));
        return queries;
    }

    /** One 150 traversal with the query text, its check and its expectation. */
    private static Query star(String shape, String gremlin, Check check, List<Object> ids, Long expected) {
        return new Query("S-150 " + shape, "150", shape, gremlin, check, ids, expected);
    }

    /** The second hop of a star query, over the edge class the query names. */
    private List<AerospikeHopSuite.RawEdge> hop2Into(List<AerospikeHopSuite.RawEdge> first, String edgeName) {
        return suite.hop2(first, edgeName, EdgeSide.OUT);
    }

    private List<AerospikeHopSuite.RawEdge> hop2Into(List<AerospikeHopSuite.RawEdge> first, String edgeName,
                                                    EdgeSide side) {
        return suite.hop2(first, edgeName, side);
    }

    /** A small builder so the same traversal can be written with one hop or with both. */
    private static Hop g(String seed) {
        return new Hop(seed);
    }

    /** The steps of a two-hop payment traversal, with {@code both} in place of a direction. */
    private record Hop(String seed) {

        HopPayment both() {
            return new HopPayment(seed, ".both('Payment')");
        }

        HopPayment bothE() {
            return new HopPayment(seed, ".bothE('Payment')");
        }
    }

    private record HopPayment(String seed, String text) {

        HopPayment both() {
            return new HopPayment(seed, text + ".both('Payment')");
        }

        HopPayment has(String key, String value) {
            return new HopPayment(seed, text + ".has('" + key + "','" + value + "')");
        }

        HopPayment filter(String key, String value) {
            return new HopPayment(seed, text + ".filter(" + key + ".equals('" + value + "'))");
        }

        HopPayment otherV() {
            return new HopPayment(seed, text + ".otherV()");
        }

        HopPayment batch(int size) {
            return new HopPayment(seed, text + ".batch(" + size + ")");
        }

        HopPayment barrier(int size) {
            return new HopPayment(seed, text + ".barrier(" + size + ")");
        }

        @Override
        public String toString() {
            return "g.V('" + seed + "','Account')" + text;
        }
    }

    private Query bothIds(String seed, String shape, HopPayment traversal, List<AerospikeHopSuite.RawEdge> second) {
        return new Query("B-" + seed + " " + shape, seed, shape, traversal.toString(), Check.IDS,
                peers(second), null);
    }

    /** The second hop on the both side: each of the first hop's neighbours is expanded both ways. */
    private List<AerospikeHopSuite.RawEdge> hop2Both(List<AerospikeHopSuite.RawEdge> first) {
        return suite.hop2(first, "Payment", EdgeSide.BOTH);
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
        List<Result> single = new ArrayList<>();
        List<Result> both = new ArrayList<>();
        for (Result result : results) {
            (result.query().id().startsWith("B-") ? both : single).add(result);
        }
        out.append("Two-hop Payment, ending on vertices (").append(suite.setId()).append("): ")
                .append(single.size()).append(" single-direction traversals and ").append(both.size())
                .append(" with both on every hop, ").append(WARMUP).append(" warm-up + ").append(RUNS)
                .append(" timed runs each\n");

        out.append("\nThe traversals (both on every hop)\n");
        for (Result result : both) {
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

        out.append("\nOne direction versus both, per seed and shape (the same plan, the same reference)\n");
        out.append(String.format("%-40s %22s %22s%n", "seed | shape", "one direction", "both on every hop"));
        out.append(String.format("%-40s %10s %11s %10s %11s%n",
                "", "p50", "kv-calls", "p50", "kv-calls"));
        out.append("-".repeat(86)).append('\n');
        Map<String, Result> byKey = new LinkedHashMap<>();
        for (Result result : results) {
            byKey.putIfAbsent(pairKey(result.query()), result);
        }
        Set<String> printed = new LinkedHashSet<>();
        for (Result result : results) {
            String key = pairKey(result.query());
            if (!printed.add(key)) {
                continue;
            }
            Result other = null;
            for (Result candidate : results) {
                if (!pairKey(candidate.query()).equals(key)) {
                    continue;
                }
                if (candidate.query().id().startsWith("B-") != result.query().id().startsWith("B-")) {
                    other = candidate;
                    break;
                }
            }
            if (other == null) {
                continue;
            }
            Result singleResult = result.query().id().startsWith("B-") ? other : result;
            Result bothResult = result.query().id().startsWith("B-") ? result : other;
            out.append(String.format("%-40s %10.2f %11d %10.2f %11d   %d/%d reached%n", key,
                    singleResult.latency().percentileMillis(0.5), singleResult.traffic().kvCalls(),
                    bothResult.latency().percentileMillis(0.5), bothResult.traffic().kvCalls(),
                    singleResult.answer(), bothResult.answer()));
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

                The second set writes both hops as both('Payment'), which reaches a neighbour in either
                direction: on this graph the hub's second hop goes from a hundred and fifty reached accounts
                to three hundred and fifty, a ring account from nine to ninety-two. The last table puts the
                two spellings of one plan side by side - same seed, same steps, same reference check - so the
                direction is the only difference.

                `answer` is what the traversal returned (a count, the number of vertices, or of values/maps),
                and the traffic columns say what it cost: `e-items` the edge lists read, `v-items` the vertices
                read, and `kv-calls` the round trips - which is what latency tracks.
                """);
        return out.toString();
    }

    /**
     * The key that pairs a traversal with its both-hop spelling: the seed and the step shape, with every
     * direction word replaced by the same placeholder (so {@code out > out > dedup} and
     * {@code both > both > dedup} land on one row).
     */
    private static String pairKey(Query query) {
        StringBuilder key = new StringBuilder();
        for (String token : query.steps().split(" > ")) {
            if (key.length() > 0) {
                key.append(" > ");
            }
            String head = token.split("[ (]", 2)[0];
            key.append(switch (head) {
                case "out", "in", "both" -> "hop";
                case "outE", "inE", "bothE" -> "hopE";
                default -> token;
            });
        }
        return query.seed() + " | " + key;
    }
}
