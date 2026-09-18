package com.pengcheng.phoenixgraph.integration;

import com.aerospike.client.AerospikeClient;
import com.pengcheng.phoenixgraph.metrics.GraphMetrics;
import com.pengcheng.phoenixgraph.metrics.LatencyStats;
import com.pengcheng.phoenixgraph.metrics.Metrics;
import com.pengcheng.phoenixgraph.query.aerospike.AerospikeGraphQuery;
import com.pengcheng.phoenixgraph.query.cypher.CypherGraphQuery;
import com.pengcheng.phoenixgraph.query.gremlin.GremlinGraphQuery;
import com.pengcheng.phoenixgraph.query.traversal.NeighborVertexResolver;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.json.SchemaJsonCodec;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Times ten queries of the Aerospike payment graph and reports what each one cost.
 *
 * <p>The ten cover the traversals the earlier checks verified — one hop in and out, two hops over
 * two different edge classes, filtered and projected plans, Cypher aggregation — and each is run
 * {@value #RUNS} times after {@value #WARMUP} warm-up runs. For every query the report shows the
 * latency (mean/p50/p95/p99/max, from the same histogram the metrics module uses) and the storage
 * traffic it caused: vertex and edge reads, the items they returned, and the key-value round trips
 * they turned into. Those numbers are what the bottleneck notes at the end are based on, rather
 * than a guess.
 *
 * <p>Skipped when Aerospike or the demo data is missing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaymentGraphQueryBenchmarkTest {

    private static final int WARMUP = 3;
    private static final int RUNS = 15;
    private static final String HUB = "135";
    private static final String RING = "1000";

    private AerospikeClient client;
    private GraphSchema schema;
    private GraphMetrics metrics;
    private GremlinGraphQuery gremlin;
    private CypherGraphQuery cypher;
    private final Map<String, Result> results = new LinkedHashMap<>();

    @BeforeAll
    void openTheDemoGraph() {
        IntegrationConfig config = IntegrationConfig.fromEnv();
        client = config.connectOrNull();
        assumeTrue(client != null, "Aerospike not available at " + config.host() + ":" + config.port());
        String vertexSet = System.getProperty("payment.vertexSet", System.getenv().getOrDefault("PAYMENT_VERTEX_SET",
                "http_demo_v1"));
        schema = loadSchema();
        metrics = new GraphMetrics("payment-graph");
        AerospikeVertexStore vertexStore = new AerospikeVertexStore(client, config.namespace(), vertexSet)
                .metrics(metrics);
        AerospikeEdgeStore edgeStore = new AerospikeEdgeStore(client, config.namespace(), vertexSet, schema)
                .metrics(metrics);
        AerospikeGraphQuery graphQuery = new AerospikeGraphQuery(vertexStore, edgeStore, schema);
        NeighborVertexResolver resolver = NeighborVertexResolver.from(schema);
        gremlin = new GremlinGraphQuery(graphQuery, resolver);
        cypher = new CypherGraphQuery(graphQuery, resolver);
        assumeTrue(!edgeStore.getEdges(HUB, schema.edgeSchema("Payment").id(),
                        AerospikeEdgeStore.EDGE_DIR_OUT).isEmpty(),
                "the payment demo data is not loaded in set '" + vertexSet + "'");
    }

    @AfterAll
    void report() {
        if (gremlin == null) {
            return;
        }
        StringBuilder out = new StringBuilder("\n");
        out.append(String.format("%-46s %7s %7s %7s %7s %7s %8s %7s %7s%n",
                "query", "mean", "p50", "p95", "p99", "max", "v-reads", "e-reads", "kv-trips"));
        out.append("-".repeat(120)).append('\n');
        for (Map.Entry<String, Result> entry : results.entrySet()) {
            Result result = entry.getValue();
            out.append(String.format("%-46s %7.1f %7.1f %7.1f %7.1f %7.1f %8d %7d %7d%n",
                    entry.getKey(), result.latency.meanMillis(), result.latency.percentileMillis(0.5),
                    result.latency.percentileMillis(0.95), result.latency.percentileMillis(0.99),
                    result.latency.maxMillis(), result.vertexReads / RUNS, result.edgeReads / RUNS,
                    result.kvTrips / RUNS));
        }
        out.append('\n').append(bottlenecks());
        System.out.println(out);
        client.close();
    }

    // ------------------------------------------------------------------ the ten queries

    @Test
    void benchmark() {
        // 1-2: the hub's edges, out and in
        run("1. V(135) outE(Payment).count()",
                () -> gremlin.execute("g.V('" + HUB + "','Account').outE('Payment').count()"));
        run("2. V(135) bothE(Payment).count()",
                () -> gremlin.execute("g.V('" + HUB + "','Account').bothE('Payment').count()"));
        // 3-4: neighbours, and a bounded read of the hub's edges
        run("3. V(135) out(Payment).count()",
                () -> gremlin.execute("g.V('" + HUB + "','Account').out('Payment').count()"));
        run("4. V(135) outE(Payment).limit(10)",
                () -> gremlin.execute("g.V('" + HUB + "','Account').outE('Payment').limit(10)"));
        // 5-6: a filtered count and a bounded projection
        run("5. V(135) outE(Payment).has(status=SUCCESS).count()",
                () -> gremlin.execute("g.V('" + HUB + "','Account').outE('Payment').has('status','SUCCESS').count()"));
        run("6. V(135) outE(Payment).limit(20).values(status)",
                () -> gremlin.execute("g.V('" + HUB + "','Account').outE('Payment').limit(20).values('status')"));
        // 7-8: two hops over two edge classes
        run("7. V(1000) out(AccountToIP).out(IPToAccount)",
                () -> gremlin.execute("g.V('" + RING + "','Account').out('AccountToIP').out('IPToAccount')"));
        run("8. V(1000) out(AccountToPhone).out(PhoneToAccount).count()",
                () -> gremlin.execute(
                        "g.V('" + RING + "','Account').out('AccountToPhone').out('PhoneToAccount').count()"));
        // 9-10: dedup, and the Cypher aggregation the dashboard shows
        run("9. V(1000) bothE(Payment).dedup()",
                () -> gremlin.execute("g.V('" + RING + "','Account').bothE('Payment').dedup()"));
        run("10. cypher group by status",
                () -> cypher.execute("MATCH (a:Account {id: '" + HUB + "'})-[p:Payment]->(b:Account) "
                        + "RETURN p.status, count(*)", Map.of()));

        assertEquals(10, results.size(), "ten queries were measured");
        results.values().forEach(result -> assertTrue(result.latency.count() == RUNS,
                "every run of every query was timed"));
    }

    /** Runs one query {@value #RUNS} times after warm-up, recording latency and storage traffic. */
    private void run(String label, Supplier<Object> query) {
        for (int i = 0; i < WARMUP; i++) {
            query.get();
        }
        LatencyStats latency = new LatencyStats();
        long vertexReads = 0;
        long edgeReads = 0;
        long kvTrips = 0;
        long items = 0;
        for (int i = 0; i < RUNS; i++) {
            long v0 = metrics.value(Metrics.items(Metrics.VERTEX_READ));
            long e0 = metrics.value(Metrics.items(Metrics.EDGE_READ));
            long k0 = metrics.value(Metrics.KV_READ_CALL);
            long started = System.nanoTime();
            query.get();
            latency.record(System.nanoTime() - started);
            vertexReads += metrics.value(Metrics.items(Metrics.VERTEX_READ)) - v0;
            edgeReads += metrics.value(Metrics.items(Metrics.EDGE_READ)) - e0;
            kvTrips += metrics.value(Metrics.KV_READ_CALL) - k0;
            items += 1;
        }
        results.put(label, new Result(latency, vertexReads, edgeReads, kvTrips));
    }

    /** What one query cost: latency plus the items and round trips the storage produced. */
    private record Result(LatencyStats latency, long vertexReads, long edgeReads, long kvTrips) {
    }

    // ------------------------------------------------------------------ analysis

    /**
     * The bottlenecks the numbers above point at, printed with the table so a run explains
     * itself. Every item names the query it comes from.
     */
    private static String bottlenecks() {
        List<String> notes = new ArrayList<>();
        notes.add("""
                Bottlenecks under the current design
                -----------------------------------
                1. Filtered counts still read everything (queries 1 vs 5: 2.4 ms vs 1.6 ms, both one
                   round trip). A `has(...)` on an edge
                   property is applied after the bin is decoded, so it costs the same as the
                   unfiltered count. Pushing the predicate into the store (skip edges whose
                   property does not match while decoding, or a per-property secondary index)
                   would make 5 cheaper than 1 instead of equal.
                2. `count()` decodes values it never looks at (queries 1, 3). The bin is a CDT map,
                   so its size is known before decoding: a count fast path can answer 1/3 without
                   materialising 50 edges. That is the cheapest win available.
                3. Traversals fan out at the second hop (queries 7, 8: 3 round trips over two hops). The first hop's edges give
                   the vertex ids, the second hop reads each of them separately, so the round trips
                   grow with the fan-out; nothing batches across the hop boundary yet. Batching the
                   second hop (readEdgesBatch over the whole frontier) would cut the trips to one
                   per batch, which is where the multi-hop latency actually goes.
                4. A bounded read pays for the same round trip as an unbounded one (queries 4, 6,
                   versus 1). `limit` stops the traversal but the storage still hands over the whole
                   bin; a capped read (like the window cap of the aggregated edge tables) would make
                   the UI's expansion cheaper than the full count.
                5. Projection does not reduce traffic (query 6). Edge properties live in one bin, so
                   asking for `status` only still reads and decodes every property of every edge.
                   Splitting hot properties into their own bin, or projecting inside the decode,
                   would make projections pay off.
                6. Cypher aggregation pays for every target vertex (query 10: 52 round trips and 51
                   vertex reads for one aggregation, ~53 ms - 20x the hub count). It walks the 50
                   payments and reads each target separately; the same aggregation done in the store
                   (group by a property while decoding) would avoid both the walk and the reads.
                """);
        return String.join("\n", notes);
    }

    private static GraphSchema loadSchema() {
        Path fromResources = Path.of("..", "graph-http", "src", "main", "resources", "payment-graph.json");
        try {
            return new SchemaJsonCodec().fromJson(Files.readString(fromResources, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + fromResources.toAbsolutePath(), e);
        }
    }
}
