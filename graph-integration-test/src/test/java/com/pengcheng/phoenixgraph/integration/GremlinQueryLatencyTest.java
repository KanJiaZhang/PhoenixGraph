package com.pengcheng.phoenixgraph.integration;

import com.aerospike.client.AerospikeClient;
import com.pengcheng.phoenixgraph.query.aerospike.AerospikeGraphQuery;
import com.pengcheng.phoenixgraph.query.gremlin.GremlinGraphQuery;
import com.pengcheng.phoenixgraph.query.traversal.NeighborVertexResolver;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Measures the latency of the Gremlin traversal
 * {@code g.V(123, 'Account').out('Payment').inE('PhoneToAccount')} against the
 * local Aerospike node. Runs 20 timed iterations after a warm-up and prints the
 * average / min / max.
 */
class GremlinQueryLatencyTest {

    private static final int WARMUP_RUNS = 5;
    private static final int MEASURED_RUNS = 20;

    @Test
    void measuresGremlinQueryLatency() {
        IntegrationConfig config = IntegrationConfig.fromEnv();
        AerospikeClient client = config.connectOrNull();
        assumeTrue(client != null,
                "Aerospike not available at " + config.host() + ":" + config.port() + ", skipping latency test");
        try (client) {
            GraphSchema schema = QueryDataSeeder.loadSchema();
            AerospikeVertexStore vertexStore = new AerospikeVertexStore(client, config.namespace(), config.vertexSet());
            AerospikeEdgeStore edgeStore = new AerospikeEdgeStore(client, config.namespace(), config.vertexSet(), schema);

            QueryDataSeeder.seedVertices(vertexStore, schema);
            QueryDataSeeder.seedEdges(edgeStore, schema);

            GremlinGraphQuery gremlin = new GremlinGraphQuery(
                    new AerospikeGraphQuery(vertexStore, edgeStore, schema),
                    NeighborVertexResolver.from(schema));
            String query = "g.V(123, 'Account').out('Payment').inE('PhoneToAccount')";

            // warm-up (JIT / client connection warm-up)
            for (int i = 0; i < WARMUP_RUNS; i++) {
                gremlin.execute(query);
            }

            long min = Long.MAX_VALUE;
            long max = Long.MIN_VALUE;
            long sum = 0;
            for (int i = 0; i < MEASURED_RUNS; i++) {
                long start = System.nanoTime();
                List<Object> result = gremlin.execute(query);
                long elapsed = System.nanoTime() - start;
                assertFalse(result.isEmpty(), "query unexpectedly returned no results");
                min = Math.min(min, elapsed);
                max = Math.max(max, elapsed);
                sum += elapsed;
            }

            double avgMs = sum / (double) MEASURED_RUNS / 1_000_000.0;
            double minMs = min / 1_000_000.0;
            double maxMs = max / 1_000_000.0;
            System.out.println();
            System.out.println("[Gremlin latency] query = " + query);
            System.out.printf("  runs = %d | avg = %.3f ms | min = %.3f ms | max = %.3f ms%n",
                    MEASURED_RUNS, avgMs, minMs, maxMs);
            System.out.printf("  avg = %.1f us | min = %.1f us | max = %.1f us%n",
                    sum / (double) MEASURED_RUNS / 1_000.0, min / 1_000.0, max / 1_000.0);
            System.out.println();
        }
    }
}
