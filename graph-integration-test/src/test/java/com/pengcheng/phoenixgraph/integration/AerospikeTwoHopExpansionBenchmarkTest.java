package com.pengcheng.phoenixgraph.integration;

import com.aerospike.client.AerospikeClient;
import com.pengcheng.phoenixgraph.metrics.GraphMetrics;
import com.pengcheng.phoenixgraph.metrics.LatencyStats;
import com.pengcheng.phoenixgraph.metrics.Metrics;
import com.pengcheng.phoenixgraph.query.aerospike.AerospikeGraphQuery;
import com.pengcheng.phoenixgraph.query.gremlin.GremlinGraphQuery;
import com.pengcheng.phoenixgraph.query.traversal.NeighborVertexResolver;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The two-hop {@code Payment} traversal of account 150, measured as its reach grows from 200 to
 * 5000 second-hop edges.
 *
 * <p>The data is {@link AerospikeTwoHopExpansion}: the hub pays {@code K} accounts, each of which
 * pays {@code L}, so {@code both('Payment').both('Payment')} reaches {@code K * (L + 1)} edges
 * (each {@code f} account's both-side read follows its {@code L} outgoing payments plus its one
 * incoming payment from the hub). The five rungs are
 *
 * <pre>
 *   200   K=10  L=19        1000   K=25  L=39        5000   K=50  L=99
 *   500   K=20  L=24        2000   K=40  L=49
 * </pre>
 *
 * <p>Each rung is loaded into its own vertex set and the same traversals are timed:
 * {@code both>both}, its count and distinct-count, {@code out>out} (no back edges) and its count,
 * the edge form {@code both>bothE}, and a capped expansion. The report is one row per traversal per
 * rung with mean / p95 / p99 / max latency and the storage traffic columns, so the scaling of the
 * round trips with the reach - and therefore where the optimisation space is - is visible rather
 * than asserted.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AerospikeTwoHopExpansionBenchmarkTest {

    private static final int WARMUP = 3;
    private static final int RUNS = 10;

    /** The five (K, L) rungs, ordered by reach. */
    private static final int[][] RUNGS = {
            {10, 19},   // 200
            {20, 24},   // 500
            {25, 39},   // 1000
            {40, 49},   // 2000
            {50, 99},   // 5000
    };

    private record Query(String id, String gremlin, long expected) {
    }

    private record Measurement(Query query, LatencyStats latency, Counters traffic, long answer) {
    }

    private record Counters(long vertexItems, long edgeItems, long decoded, long kvCalls, long kvItems) {

        Counters add(long vertexItems, long edgeItems, long decoded, long kvCalls, long kvItems) {
            return new Counters(this.vertexItems + vertexItems, this.edgeItems + edgeItems,
                    this.decoded + decoded, this.kvCalls + kvCalls, this.kvItems + kvItems);
        }

        Counters perRun(int runs) {
            return new Counters(Math.round(vertexItems / (double) runs),
                    Math.round(edgeItems / (double) runs), Math.round(decoded / (double) runs),
                    Math.round(kvCalls / (double) runs), Math.round(kvItems / (double) runs));
        }
    }

    private final List<Measurement> measurements = new ArrayList<>();
    private final List<String> wrong = new ArrayList<>();

    @Test
    void twoHopPaymentReachScalesFromTwoHundredToFiveThousand() {
        IntegrationConfig config = IntegrationConfig.fromEnv();
        AerospikeClient client = config.connectOrNull();
        assumeTrue(client != null, "Aerospike not available at " + config.host() + ":" + config.port());
        String baseSet = System.getProperty("twohop.vertexSet", "payment_twohop_bench");
        GraphSchema schema = AerospikeTwoHopExpansion.loadSchema();
        GraphMetrics metrics = new GraphMetrics("two-hop-expansion");

        try (client) {
            for (int[] rung : RUNGS) {
                int k = rung[0];
                int l = rung[1];
                String set = baseSet + "_" + (k * (l + 1));
                AerospikeTwoHopExpansion.LoadResult loaded =
                        AerospikeTwoHopExpansion.load(client, config.namespace(), set, schema, k, l);

                AerospikeVertexStore vertexStore = new AerospikeVertexStore(client, config.namespace(), set)
                        .metrics(metrics);
                AerospikeEdgeStore edgeStore = new AerospikeEdgeStore(client, config.namespace(), set, schema)
                        .metrics(metrics);
                GremlinGraphQuery gremlin = new GremlinGraphQuery(
                        new AerospikeGraphQuery(vertexStore, edgeStore, schema),
                        NeighborVertexResolver.from(schema));

                for (Query query : queries(k, l)) {
                    measurements.add(measure(gremlin, metrics, query));
                }

                // sanity: the data really has the reach the arithmetic claims
                assertTrue(loaded.bothReach() == k * (l + 1L), "reach arithmetic");
                assertEquals(k + k * l, loaded.paymentEdges(), "one payment edge per traversal hop");
            }
        }
        System.out.println(report());
        assertTrue(wrong.isEmpty(), "every measured traversal answers what the data says: " + wrong);
    }

    @AfterAll
    void afterAll() {
        // the report is printed inside the test so it appears next to the surefire output
    }

    /** The traversals timed on every rung. */
    private List<Query> queries(int k, int l) {
        long both = (long) k * (l + 1);
        long out = (long) k * l;
        long distinct = l + 1L;
        String seed = "g.V('150','Account')";
        List<Query> queries = new ArrayList<>();
        queries.add(new Query("both>both", seed + ".both('Payment').both('Payment')", both));
        queries.add(new Query("both>both>count", seed + ".both('Payment').both('Payment').count()", both));
        queries.add(new Query("both>both>dedup>count",
                seed + ".both('Payment').both('Payment').dedup().count()", distinct));
        queries.add(new Query("out>out", seed + ".out('Payment').out('Payment')", out));
        queries.add(new Query("out>out>count", seed + ".out('Payment').out('Payment').count()", out));
        queries.add(new Query("both>bothE", seed + ".both('Payment').bothE('Payment')", both));
        queries.add(new Query("both>both>limit(100)", seed + ".both('Payment').both('Payment').limit(100)",
                100));
        return queries;
    }

    /** Runs one traversal after warm-up and records its latency and traffic. */
    private Measurement measure(GremlinGraphQuery gremlin, GraphMetrics metrics, Query query) {
        for (int i = 0; i < WARMUP; i++) {
            gremlin.execute(query.gremlin());
        }
        LatencyStats latency = new LatencyStats();
        Counters traffic = new Counters(0, 0, 0, 0, 0);
        long answer = 0;
        for (int i = 0; i < RUNS; i++) {
            long v0 = metrics.value(Metrics.items(Metrics.VERTEX_READ));
            long e0 = metrics.value(Metrics.items(Metrics.EDGE_READ));
            long d0 = metrics.value(Metrics.EDGE_DECODED);
            long k0 = metrics.value(Metrics.KV_READ_CALL);
            long ki0 = metrics.value(Metrics.KV_READ_ITEMS);
            long started = System.nanoTime();
            List<Object> result = gremlin.execute(query.gremlin());
            latency.record(System.nanoTime() - started);
            traffic = traffic.add(metrics.value(Metrics.items(Metrics.VERTEX_READ)) - v0,
                    metrics.value(Metrics.items(Metrics.EDGE_READ)) - e0,
                    metrics.value(Metrics.EDGE_DECODED) - d0,
                    metrics.value(Metrics.KV_READ_CALL) - k0,
                    metrics.value(Metrics.KV_READ_ITEMS) - ki0);
            answer = query.gremlin().endsWith(".count()")
                    ? number(result) : result.size();
        }
        if (answer != query.expected()) {
            wrong.add(query.id() + ": expected " + query.expected() + ", got " + answer);
        }
        return new Measurement(query, latency, traffic.perRun(RUNS), answer);
    }

    private static long number(List<Object> answer) {
        return answer.isEmpty() || !(answer.get(0) instanceof Number number) ? -1 : number.longValue();
    }

    private String report() {
        StringBuilder out = new StringBuilder("\nTwo-hop Payment reach of account 150, scaled\n");
        out.append("each rung: the hub pays K accounts, each pays L; both('Payment').both('Payment')")
                .append(" reaches K*(L+1)\n\n");
        out.append(String.format("%-22s %-10s %7s %7s %7s %7s %8s %8s %8s %8s%n",
                "query", "reach", "mean", "p95", "p99", "max", "v-items", "e-items", "decoded", "kv-calls"));
        out.append("-".repeat(100)).append('\n');
        for (Measurement m : measurements) {
            Counters c = m.traffic();
            out.append(String.format("%-22s %-10d %7.2f %7.2f %7.2f %7.2f %8d %8d %8d %8d%n",
                    m.query().id(), m.query().expected(), m.latency().meanMillis(),
                    m.latency().percentileMillis(0.95), m.latency().percentileMillis(0.99),
                    m.latency().maxMillis(), c.vertexItems(), c.edgeItems(), c.decoded(), c.kvCalls()));
        }
        out.append("""

                How to read this
                ----------------
                `reach` is the second-hop edge count the traversal walks. The traffic columns say what
                the plan cost: `kv-calls` the round trips (what latency tracks on a quiet machine),
                `e-items` the edge lists read, `v-items` the vertices read, `decoded` the edges that
                were actually built. A vertex hop walks neighbours, so it should read headers (decoded
                stays ~0) while the edge form bothE decodes one edge per reached edge.
                """);
        return out.toString();
    }
}
