package com.pengcheng.phoenixgraph.integration;

import com.aerospike.client.AerospikeClient;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Writes the big-vertex demo into the vertex set the UI serves as {@code payment-graph-as-edge}, so
 * the queries of {@link AerospikeBigVertexDemoQueries} can be run in the browser against real data.
 *
 * <p>Opt in with {@code -Daerospike.bigVertex.demo=true} (or {@code AEROSPIKE_BIG_VERTEX_DEMO=true}):
 * the demo rewrites the near-line day and the inline tail every time it runs, which is what a UI user
 * wants but not what a plain {@code ./mvnw test} should do. Nothing is truncated - the demo adds its
 * own hubs and counterparties next to what is already in the set.
 *
 * <pre>
 * ./mvnw -pl graph-integration-test -am test -Dtest=AerospikeBigVertexDemoTest \
 *        -Daerospike.bigVertex.demo=true -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
class AerospikeBigVertexDemoTest {

    @Test
    void writeTheUiDemo() {
        assumeTrue(wanted(), "set -Daerospike.bigVertex.demo=true (or AEROSPIKE_BIG_VERTEX_DEMO=true) "
                + "to write the big-vertex demo into the UI's graph");
        AerospikeClient client;
        try {
            client = new AerospikeClient(env("AEROSPIKE_HOST", "localhost"),
                    Integer.parseInt(env("AEROSPIKE_PORT", "3000")));
        } catch (RuntimeException e) {
            assumeTrue(false, "Aerospike not available");
            return;
        }
        String namespace = env("AEROSPIKE_NAMESPACE", "test");
        String vertexSet = env("AEROSPIKE_BIG_VERTEX_SET", AerospikeBigVertexDemoLoader.VERTEX_SET);
        try (client) {
            GraphSchema schema = AerospikeEdgeLiveLoadTest.AerospikeEdgeIntegrationTestAccess.loadSchema();
            if (reset()) {
                System.out.printf("reset: truncated %d set(s) of %s%n",
                        AerospikeBigVertexDemoLoader.truncate(client, namespace, vertexSet, schema).size(),
                        vertexSet);
            }
            long started = System.currentTimeMillis();
            AerospikeBigVertexDemoLoader.LoadResult demo =
                    AerospikeBigVertexDemoLoader.load(client, namespace, vertexSet, schema);
            System.out.printf("big-vertex demo written into %s/%s in %d ms:%n  %s%n",
                    namespace, vertexSet, System.currentTimeMillis() - started,
                    demo.edgesPerVertex());
            System.out.printf("  graph shown by the UI: %s%n", AerospikeBigVertexDemoLoader.GRAPH);
            System.out.printf("  near-line partitions a day: %d (from a declared monthly volume of %d)%n",
                    demo.nearlinePartitions(), demo.declaredMonthlyVolume());
            for (String line : AerospikeBigVertexDemoQueries.describe(demo)) {
                System.out.println("  " + line);
            }
            assertTrue(demo.edgesPerVertex().size() >= 5, "five demo vertices were written");
            assertTrue(demo.nearlinePartitions() == AerospikeBigVertexDemoLoader.expectedPartitions());
            assertTrue(AerospikeBigVertexDemoQueries.forUi(demo).size() >= 6,
                    "a query per read case, plus the boundary ones");
        }
    }

    private static boolean wanted() {
        return "true".equalsIgnoreCase(System.getProperty("aerospike.bigVertex.demo"))
                || "true".equalsIgnoreCase(System.getenv("AEROSPIKE_BIG_VERTEX_DEMO"));
    }

    /** {@code -Daerospike.bigVertex.reset=true} truncates the demo's sets first (drops older runs). */
    private static boolean reset() {
        return "true".equalsIgnoreCase(System.getProperty("aerospike.bigVertex.reset"));
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    /** Keeps the query list in one place so the demo test and the docs cannot drift apart. */
    static final class AerospikeBigVertexDemoQueries {

        private AerospikeBigVertexDemoQueries() {
        }

        /** The queries to paste into the UI, one per read case of the spec. */
        static List<String> forUi(AerospikeBigVertexDemoLoader.LoadResult demo) {
            String hub = AerospikeBigVertexDemoLoader.HUB_ALL;
            return List.of(
                    // 1. a big vertex refuses a read without a window
                    "g.V('" + hub + "','Account').inE('Payment')",
                    // 2. a window inside the online span: the inline tail
                    "g.V('" + hub + "','Account').createdBetween('-10m', '*').inE('Payment')"
                            + ".values('id','status')",
                    // 3. the last day: near-line and online merged
                    "g.V('" + hub + "','Account').createdBetween('-23h', '*').inE('Payment')"
                            + ".values('id')",
                    // 4. a cold window: the aggregated tables alone
                    "g.V('" + hub + "','Account').createdBetween('-360d', '-330d').inE('Payment')"
                            + ".limit(20).values('id','status')",
                    // 5./6. everything: cold, near-line and online merged, newest copy wins
                    "g.V('" + hub + "','Account').createdBetween('-400d', '*').inE('Payment')"
                            + ".count()",
                    "g.V('" + hub + "','Account').createdBetween('-400d', '*').inE('Payment')"
                            + ".limit(10).values('id','status')",
                    // a big vertex that has nothing cold yet
                    "g.V('" + AerospikeBigVertexDemoLoader.HUB_NEW + "','Account')"
                            + ".createdBetween('-23h', '*').inE('Payment').values('id')",
                    // a big, quiet vertex: everything is older than the near-line window
                    "g.V('" + AerospikeBigVertexDemoLoader.HUB_COLD + "','Account')"
                            + ".createdBetween('-400d', '*').inE('Payment').count()",
                    // the same tiers on the outgoing side (a big payer)
                    "g.V('" + AerospikeBigVertexDemoLoader.HUB_OUT + "','Account')"
                            + ".createdBetween('-400d', '*').outE('Payment').limit(10).values('id')",
                    // under the cap: no window needed
                    "g.V('" + AerospikeBigVertexDemoLoader.BELOW_CAP + "','Account').inE('Payment')"
                            + ".values('id')",
                    // the near-line day is cut into partitions from the volume
                    "g.V('" + hub + "','Account').createdBetween('-23h', '*').inE('Payment')"
                            + ".groupCount('status')");
        }

        /** What the demo wrote, as printed lines. */
        static List<String> describe(AerospikeBigVertexDemoLoader.LoadResult demo) {
            return List.of(
                    "hubs: " + AerospikeBigVertexDemoLoader.HUB_ALL + " (in, all tiers), "
                            + AerospikeBigVertexDemoLoader.HUB_NEW + " (in, near-line+online), "
                            + AerospikeBigVertexDemoLoader.HUB_COLD + " (in, cold only), "
                            + AerospikeBigVertexDemoLoader.HUB_OUT + " (out, all tiers), "
                            + AerospikeBigVertexDemoLoader.BELOW_CAP + " (under the cap)",
                    "windows: cold [" + demo.coldFrom() + ", " + demo.coldTo() + "], nearline ["
                            + demo.nearlineFrom() + ", " + demo.nearlineTo() + "], online ["
                            + demo.onlineFrom() + ", now]",
                    "opening the UI (http://localhost:8080) on " + AerospikeBigVertexDemoLoader.GRAPH
                            + " and running these queries: " + forUi(demo).size() + " queries");
        }
    }
}
