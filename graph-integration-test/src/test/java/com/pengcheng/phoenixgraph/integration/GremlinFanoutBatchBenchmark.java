package com.pengcheng.phoenixgraph.integration;

import com.aerospike.client.AerospikeClient;
import com.pengcheng.phoenixgraph.query.aerospike.AerospikeGraphQuery;
import com.pengcheng.phoenixgraph.query.gremlin.GremlinGraphQuery;
import com.pengcheng.phoenixgraph.query.gremlin.NeighborVertexResolver;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.VertexSchema;
import com.pengcheng.phoenixgraph.storage.WriteOutcome;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import com.pengcheng.phoenixgraph.storage.aerospike.EdgePropertyValue;
import com.pengcheng.phoenixgraph.storage.aerospike.EdgeUpsert;
import com.pengcheng.phoenixgraph.storage.aerospike.VertexPropertyValue;
import com.pengcheng.phoenixgraph.storage.aerospike.VertexUpsert;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Benchmarks the 2-hop traversal {@code g.V(id, 'Account').out('Payment').<in|out>('PhoneToAccount')}
 * with a high-fan-out account (100 outgoing Payment edges), comparing
 * {@code batch(1)} (serial) against {@code batch(10)} (parallel).
 */
class GremlinFanoutBatchBenchmark {

    private static final int FANOUT = 100;
    private static final int RUNS = 20;
    private static final int WARMUP = 5;

    @Test
    void comparesBatchSize1Vs10() {
        IntegrationConfig config = IntegrationConfig.fromEnv();
        AerospikeClient client = config.connectOrNull();
        assumeTrue(client != null,
                "Aerospike not available at " + config.host() + ":" + config.port() + ", skipping benchmark");
        try (client) {
            GraphSchema schema = QueryDataSeeder.loadSchema();
            AerospikeVertexStore vertexStore = new AerospikeVertexStore(client, config.namespace(), config.vertexSet());
            AerospikeEdgeStore edgeStore = new AerospikeEdgeStore(client, config.namespace(), config.vertexSet(), schema);

            seedHighFanout(vertexStore, edgeStore, schema);

            AerospikeGraphQuery aerospikeQuery = new AerospikeGraphQuery(vertexStore, edgeStore, schema);
            GremlinGraphQuery cached = new GremlinGraphQuery(aerospikeQuery, NeighborVertexResolver.from(schema));

            String query = "g.V(888, 'Account').batch(30).out('Payment').in('PhoneToAccount')";
            System.out.println();
            System.out.println("=== Gremlin compiled-query latency (out-degree of account 888 = " + FANOUT + ") ===");
            measure(cached, "compiled", query);
            System.out.println();
        }
    }

    private static void measure(GremlinGraphQuery gremlin, String label, String query) {
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
        System.out.printf("  %-10s %-60s | results=%3d | avg=%7.3f ms | min=%7.3f ms | max=%7.3f ms%n",
                label, query, resultSize, sum / (double) RUNS / 1_000_000.0, min / 1_000_000.0, max / 1_000_000.0);
    }

    // --- high-fan-out seeding (isolated id ranges: 888, 600..699, 700..799) ---

    private static void seedHighFanout(AerospikeVertexStore vertexStore, AerospikeEdgeStore edgeStore,
                                       GraphSchema schema) {
        VertexSchema accountSchema = schema.vertexSchema("Account");
        VertexSchema phoneSchema = schema.vertexSchema("Phone");
        EdgeSchema paymentSchema = schema.edgeSchema("Payment");
        EdgeSchema phoneToAccountSchema = schema.edgeSchema("PhoneToAccount");

        long t = System.currentTimeMillis();

        List<VertexUpsert> vertices = new ArrayList<>();
        vertices.add(new VertexUpsert("888", accountSchema, accountProps("888"), t, t));
        for (int i = 0; i < FANOUT; i++) {
            String accountId = String.valueOf(600 + i);
            String phoneId = String.valueOf(700 + i);
            vertices.add(new VertexUpsert(accountId, accountSchema, accountProps(accountId), t + i, t + i));
            vertices.add(new VertexUpsert(phoneId, phoneSchema, phoneProps(phoneId), t + i, t + i));
        }
        List<WriteOutcome> vertexOutcomes = vertexStore.upsertVertices(vertices);
        long failed = vertexOutcomes.stream().filter(o -> o == WriteOutcome.FAILED).count();
        if (failed > 0) {
            throw new IllegalStateException("failed to seed benchmark vertices: " + failed);
        }

        List<WriteOutcome> edgeOutcomes = new ArrayList<>();
        for (int i = 0; i < FANOUT; i++) {
            long ts = t + i;
            String targetAccount = String.valueOf(600 + i);
            String phone = String.valueOf(700 + i);
            edgeOutcomes.add(edgeStore.upsertEdge(new EdgeUpsert("fan_p" + i, paymentSchema,
                    "888", targetAccount, paymentProps(i, ts), ts, ts)));
            edgeOutcomes.add(edgeStore.upsertEdge(new EdgeUpsert("fan_pa" + i, phoneToAccountSchema,
                    phone, targetAccount, Map.of(3, new EdgePropertyValue(ts, ts, ts)), ts, ts)));
        }
        long edgeFailed = edgeOutcomes.stream().filter(o -> o == WriteOutcome.FAILED).count();
        if (edgeFailed > 0) {
            throw new IllegalStateException("failed to seed benchmark edges: " + edgeFailed);
        }
    }

    private static Map<Integer, VertexPropertyValue> accountProps(String id) {
        long t = System.currentTimeMillis();
        Map<Integer, VertexPropertyValue> props = new LinkedHashMap<>();
        props.put(1, new VertexPropertyValue("ACTIVE", t));
        props.put(2, new VertexPropertyValue("savings", t));
        props.put(3, new VertexPropertyValue("addr-" + id, t));
        return props;
    }

    private static Map<Integer, VertexPropertyValue> phoneProps(String id) {
        long t = System.currentTimeMillis();
        Map<Integer, VertexPropertyValue> props = new LinkedHashMap<>();
        props.put(1, new VertexPropertyValue("138-" + id, t));
        props.put(2, new VertexPropertyValue("CN", t));
        return props;
    }

    private static Map<Integer, EdgePropertyValue> paymentProps(int i, long t) {
        Map<Integer, EdgePropertyValue> props = new LinkedHashMap<>();
        props.put(3, new EdgePropertyValue("SUCCESS", t, t));
        props.put(4, new EdgePropertyValue("transfer", t, t));
        props.put(5, new EdgePropertyValue(100.0 + i, t, t));
        props.put(6, new EdgePropertyValue("USD", t, t));
        return props;
    }
}
