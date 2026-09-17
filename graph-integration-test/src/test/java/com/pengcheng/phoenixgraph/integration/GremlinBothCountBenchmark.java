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

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Measures {@code g.V(888,'Account').batch(40).both('Payment').both('Payment').count()}
 * over the enriched layered Payment graph (1-hop=100, 2-hop=300).
 */
class GremlinBothCountBenchmark {

    private static final int RUNS = 20;
    private static final int WARMUP = 5;

    @Test
    void measuresBothBothCount() {
        IntegrationConfig config = IntegrationConfig.fromEnv();
        AerospikeClient client = config.connectOrNull();
        assumeTrue(client != null,
                "Aerospike not available at " + config.host() + ":" + config.port() + ", skipping benchmark");
        try (client) {
            GraphSchema schema = GremlinMultiHopBenchmark.richSchema();
            AerospikeVertexStore vertexStore = new AerospikeVertexStore(client, config.namespace(), GremlinMultiHopBenchmark.SET);
            AerospikeEdgeStore edgeStore = new AerospikeEdgeStore(client, config.namespace(), GremlinMultiHopBenchmark.SET, schema);
            GremlinMultiHopBenchmark.seed(vertexStore, edgeStore, schema);

            GremlinGraphQuery gremlin = new GremlinGraphQuery(
                    new AerospikeGraphQuery(vertexStore, edgeStore, schema),
                    NeighborVertexResolver.from(schema));

            String query = "g.V(888, 'Account').batch(40).both('Payment').both('Payment').count()";

            for (int i = 0; i < WARMUP; i++) {
                gremlin.execute(query);
            }
            long min = Long.MAX_VALUE;
            long max = Long.MIN_VALUE;
            long sum = 0;
            int resultSize = 0;
            for (int i = 0; i < RUNS; i++) {
                long start = System.nanoTime();
                List<Object> result = gremlin.execute(query);
                long elapsed = System.nanoTime() - start;
                resultSize = result.size();
                min = Math.min(min, elapsed);
                max = Math.max(max, elapsed);
                sum += elapsed;
            }

            System.out.println();
            System.out.println("=== Gremlin both().both().count() benchmark ===");
            System.out.printf("  %s%n", query);
            System.out.printf("  results=%d | avg=%.3f ms | min=%.3f ms | max=%.3f ms%n",
                    resultSize, sum / (double) RUNS / 1_000_000.0, min / 1_000_000.0, max / 1_000_000.0);
            System.out.println();
        }
    }
}
