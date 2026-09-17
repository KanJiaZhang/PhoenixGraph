package com.pengcheng.phoenixgraph.integration;

import com.aerospike.client.AerospikeClient;
import com.pengcheng.phoenixgraph.query.aerospike.AerospikeEdgeGraphQuery;
import com.pengcheng.phoenixgraph.query.aerospike.AerospikeGraphQuery;
import com.pengcheng.phoenixgraph.query.gremlin.GremlinGraphQuery;
import com.pengcheng.phoenixgraph.query.traversal.NeighborVertexResolver;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeChunkStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Loads the real Aerospike-Edge demo - a hub with {@link AerospikeEdgeDemoLoader#HUB_TRANSACTIONS}
 * payments over a year - into a vertex set, so the UI can query it.
 *
 * <pre>
 * ./mvnw -pl graph-integration-test -am test -Dtest=AerospikeEdgeLiveLoadTest \
 *        -Daerospike.asEdge.set=payment_as_edge_v1 -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * <p>The set may also come from {@code AEROSPIKE_AS_EDGE_SET}. The service then serves it as the
 * graph {@code payment-graph-as-edge} ({@code application.yml} maps that graph to
 * {@code payment_as_edge_v1}), and the query of the docs answers:
 *
 * <pre>
 * g.V('9000001','Account').createdBetween('2025-06-01','2025-06-02').inE('Payment').limit(500)
 * </pre>
 */
class AerospikeEdgeLiveLoadTest {

    @Test
    void loadTheDemoAndQueryAWindowOfIt() {
        String set = setting();
        assumeTrue(set != null, "AEROSPIKE_AS_EDGE_SET / -Daerospike.asEdge.set not set, skipping");
        AerospikeClient client;
        try {
            client = new AerospikeClient(env("AEROSPIKE_HOST", "localhost"),
                    Integer.parseInt(env("AEROSPIKE_PORT", "3000")));
        } catch (RuntimeException e) {
            assumeTrue(false, "Aerospike not available");
            return;
        }
        String namespace = env("AEROSPIKE_NAMESPACE", "test");
        try (client) {
            GraphSchema schema = AerospikeEdgeIntegrationTestAccess.loadSchema();
            long start = System.currentTimeMillis();
            AerospikeEdgeDemoLoader.LoadResult result =
                    AerospikeEdgeDemoLoader.load(client, namespace, set, schema);
            System.out.printf("loaded %s into %s: %d vertices, %d hub payments, %d levels,"
                            + " %d chunk records, %d inline updates (%d ms)%n",
                    result.graph(), result.vertexSet(), result.vertices(), result.hubEdges(),
                    result.levels(), result.chunkRecords(), result.inlineUpdates(),
                    System.currentTimeMillis() - start);
            assertEquals(AerospikeEdgeDemoLoader.HUB_TRANSACTIONS, result.hubEdges());
            assertEquals(2, result.levels(), "2000 edges per chunk over 300k edges is two levels");

            // the demo query: one day of the year, at most 500 edges
            AerospikeEdgeChunkStore chunks = new AerospikeEdgeChunkStore(client, namespace, set);
            AerospikeEdgeGraphQuery query = new AerospikeEdgeGraphQuery(
                    new AerospikeGraphQuery(new AerospikeVertexStore(client, namespace, set),
                            new AerospikeEdgeStore(client, namespace, set, schema), schema),
                    chunks, schema);
            GremlinGraphQuery gremlin = new GremlinGraphQuery(query, NeighborVertexResolver.from(schema));
            List<Object> edges = gremlin.execute("g.V('" + AerospikeEdgeDemoLoader.HUB + "','Account')"
                    + ".createdBetween('2025-06-01','2025-06-02').inE('Payment').limit(500)"
                    + ".values('status')");
            System.out.printf("query 2025-06-01..2025-06-02 -> %d edge(s), first: %s%n",
                    edges.size(), edges.isEmpty() ? "-" : edges.get(0));
            assertTrue(edges.size() > 400, "a day holds about " + AerospikeEdgeDemoLoader.edgesPerDay()
                    + " payments, got " + edges.size());
            assertTrue(edges.size() <= 500, "the limit is honoured");

            // the same day over the whole range, to show the window really bounds the read
            List<Object> count = gremlin.execute("g.V('" + AerospikeEdgeDemoLoader.HUB + "','Account')"
                    + ".createdBetween('2025-06-01','2025-06-02').inE('Payment').count()");
            System.out.printf("count of the same window: %s%n", count);
            assertTrue(((Number) count.get(0)).intValue() > 400);
        }
    }

    private static String setting() {
        String property = System.getProperty("aerospike.asEdge.set");
        if (property != null && !property.isBlank()) {
            return property;
        }
        String env = System.getenv("AEROSPIKE_AS_EDGE_SET");
        return env == null || env.isBlank() ? null : env;
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    /** Keeps the schema loading in one place (the integration test owns it). */
    static final class AerospikeEdgeIntegrationTestAccess {

        private AerospikeEdgeIntegrationTestAccess() {
        }

        static GraphSchema loadSchema() {
            for (String candidate : List.of("graph-http/src/main/resources/payment-graph-as-edge.json",
                    "../graph-http/src/main/resources/payment-graph-as-edge.json")) {
                java.nio.file.Path file = java.nio.file.Path.of(candidate);
                if (java.nio.file.Files.isReadable(file)) {
                    try {
                        return new com.pengcheng.phoenixgraph.schema.json.SchemaJsonCodec()
                                .fromJson(java.nio.file.Files.readString(file));
                    } catch (java.io.IOException e) {
                        throw new IllegalStateException("Cannot read " + file, e);
                    }
                }
            }
            throw new IllegalStateException("payment-graph-as-edge.json not found");
        }
    }
}
