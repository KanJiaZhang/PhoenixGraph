package com.pengcheng.phoenixgraph.integration;

import com.aerospike.client.AerospikeClient;
import com.pengcheng.phoenixgraph.query.aerospike.AerospikeGraphQuery;
import com.pengcheng.phoenixgraph.query.gremlin.GremlinGraphQuery;
import com.pengcheng.phoenixgraph.query.gremlin.NeighborVertexResolver;
import com.pengcheng.phoenixgraph.schema.EdgeDirection;
import com.pengcheng.phoenixgraph.schema.EdgeEndpointSchema;
import com.pengcheng.phoenixgraph.schema.EdgePropertySchema;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.PropertyType;
import com.pengcheng.phoenixgraph.schema.VertexPropertySchema;
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
 * Runs a long, multi-line Gremlin query with a long {@code $logic} block
 * (iteration / filter / aggregation / dedup), exercising the compile-once cache.
 */
class GremlinCompiledLogicBenchmark {

    private static final int RUNS = 20;
    private static final int WARMUP = 5;

    @Test
    void runsLongLogicQuery() {
        IntegrationConfig config = IntegrationConfig.fromEnv();
        AerospikeClient client = config.connectOrNull();
        assumeTrue(client != null,
                "Aerospike not available at " + config.host() + ":" + config.port() + ", skipping benchmark");
        try (client) {
            GraphSchema schema = richSchema();
            AerospikeVertexStore vertexStore = new AerospikeVertexStore(client, config.namespace(), "logic_vertices");
            AerospikeEdgeStore edgeStore = new AerospikeEdgeStore(client, config.namespace(), "logic_vertices", schema);
            seed(vertexStore, edgeStore, schema);

            String logic = """
                    .has('accountStatus', 'ACTIVE')
                    .dedup()
                    .has('accountType', 'savings')
                    .out('AccountToPhone')
                    .has('phoneNumber', '138-8000')
                    .out('PhoneToAccount')
                    .has('accountStatus', 'ACTIVE')
                    .dedup()
                    .out('AccountToIP')
                    .has('ipAddress', '10.20.0.1')
                    .dedup()
                    .limit(10)
                    .dedup()
                    .count()
                    """;

            GremlinGraphQuery gremlin = new GremlinGraphQuery(
                    new AerospikeGraphQuery(vertexStore, edgeStore, schema),
                    NeighborVertexResolver.from(schema))
                    .registerLogic("logic", logic);

            String query = """
                    g.V(888, 'Account')
                      .batch(40)
                      .both('Payment')
                      .both('Payment') { $logic }
                    """;

            // warm-up (first call compiles; later calls reuse the compiled plan)
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
            System.out.println("=== Gremlin long-logic benchmark (compiled once, then cached) ===");
            System.out.println("query (multi-line):");
            System.out.println(query.indent(2));
            System.out.println("logic (multi-line, " + logic.lines().count() + " lines):");
            System.out.println(logic.indent(2));
            System.out.printf("  results=%d | avg=%.3f ms | min=%.3f ms | max=%.3f ms%n",
                    resultSize, sum / (double) RUNS / 1_000_000.0, min / 1_000_000.0, max / 1_000_000.0);
            System.out.println();
        }
    }

    private static GraphSchema richSchema() {
        VertexSchema account = new VertexSchema(1, "Account", null)
                .property(new VertexPropertySchema(1, "accountType", null, PropertyType.ScalarType.STRING))
                .property(new VertexPropertySchema(2, "accountStatus", null,
                        PropertyType.enumOf("ACTIVE", "FROZEN", "CLOSED")));
        VertexSchema ip = new VertexSchema(2, "IP", null)
                .property(new VertexPropertySchema(1, "ipAddress", null, PropertyType.ScalarType.STRING));
        VertexSchema phone = new VertexSchema(3, "Phone", null)
                .property(new VertexPropertySchema(1, "phoneNumber", null, PropertyType.ScalarType.STRING));

        EdgeSchema payment = new EdgeSchema(1, "Payment", null,
                new EdgeEndpointSchema(1, "source", null, PropertyType.ScalarType.LONG, "Account"),
                new EdgeEndpointSchema(2, "target", null, PropertyType.ScalarType.LONG, "Account"),
                EdgeDirection.DIRECTED)
                .property(new EdgePropertySchema(3, "status", null, PropertyType.ScalarType.STRING));
        EdgeSchema accountToPhone = new EdgeSchema(2, "AccountToPhone", null,
                new EdgeEndpointSchema(1, "source", null, PropertyType.ScalarType.LONG, "Account"),
                new EdgeEndpointSchema(2, "target", null, PropertyType.ScalarType.LONG, "Phone"),
                EdgeDirection.DIRECTED)
                .property(new EdgePropertySchema(3, "linkedAt", null, PropertyType.ScalarType.LONG));
        EdgeSchema accountToIp = new EdgeSchema(3, "AccountToIP", null,
                new EdgeEndpointSchema(1, "source", null, PropertyType.ScalarType.LONG, "Account"),
                new EdgeEndpointSchema(2, "target", null, PropertyType.ScalarType.LONG, "IP"),
                EdgeDirection.DIRECTED)
                .property(new EdgePropertySchema(3, "loginTime", null, PropertyType.ScalarType.LONG));
        EdgeSchema phoneToAccount = new EdgeSchema(4, "PhoneToAccount", null,
                new EdgeEndpointSchema(1, "source", null, PropertyType.ScalarType.LONG, "Phone"),
                new EdgeEndpointSchema(2, "target", null, PropertyType.ScalarType.LONG, "Account"),
                EdgeDirection.DIRECTED)
                .property(new EdgePropertySchema(3, "linkedAt", null, PropertyType.ScalarType.LONG));

        return new GraphSchema(1, "logic", null)
                .vertex(account).vertex(ip).vertex(phone)
                .edge(payment).edge(accountToPhone).edge(accountToIp).edge(phoneToAccount);
    }

    private static void seed(AerospikeVertexStore vertexStore, AerospikeEdgeStore edgeStore, GraphSchema schema) {
        VertexSchema account = schema.vertexSchema("Account");
        VertexSchema ip = schema.vertexSchema("IP");
        VertexSchema phone = schema.vertexSchema("Phone");
        EdgeSchema payment = schema.edgeSchema("Payment");
        EdgeSchema accountToPhone = schema.edgeSchema("AccountToPhone");
        EdgeSchema accountToIp = schema.edgeSchema("AccountToIP");
        EdgeSchema phoneToAccount = schema.edgeSchema("PhoneToAccount");

        long t = System.currentTimeMillis();
        List<VertexUpsert> vertices = new ArrayList<>();
        vertices.add(new VertexUpsert("888", account, accountProps("888"), t, t));
        for (int i = 0; i < 50; i++) {
            vertices.add(new VertexUpsert(String.valueOf(900 + i), account, accountProps(String.valueOf(900 + i)), t, t));
        }
        for (int i = 0; i < 5; i++) {
            vertices.add(new VertexUpsert(String.valueOf(8000 + i), phone, phoneProps(), t, t));
            vertices.add(new VertexUpsert(String.valueOf(9000 + i), ip, ipProps(), t, t));
        }
        assertNoFailures(vertexStore.upsertVertices(vertices), "vertices");

        List<EdgeUpsert> edges = new ArrayList<>();
        int seq = 0;
        for (int i = 0; i < 50; i++) {
            edges.add(new EdgeUpsert("pay" + seq++, payment, "888", String.valueOf(900 + i),
                    Map.of(3, new EdgePropertyValue("SUCCESS", t, t)), t, t));
        }
        for (int i = 0; i < 5; i++) {
            String ph = String.valueOf(8000 + i);
            edges.add(new EdgeUpsert("atp" + seq++, accountToPhone, "888", ph,
                    Map.of(3, new EdgePropertyValue(t, t, t)), t, t));
            edges.add(new EdgeUpsert("pta" + seq++, phoneToAccount, ph, "888",
                    Map.of(3, new EdgePropertyValue(t, t, t)), t, t));
            edges.add(new EdgeUpsert("ati" + seq++, accountToIp, "888", String.valueOf(9000 + i),
                    Map.of(3, new EdgePropertyValue(t, t, t)), t, t));
        }
        assertNoFailures(edgeStore.upsertEdges(edges), "edges");
    }

    private static Map<Integer, VertexPropertyValue> accountProps(String id) {
        long t = System.currentTimeMillis();
        Map<Integer, VertexPropertyValue> props = new LinkedHashMap<>();
        props.put(1, new VertexPropertyValue("savings", t));
        props.put(2, new VertexPropertyValue("ACTIVE", t));
        return props;
    }

    private static Map<Integer, VertexPropertyValue> phoneProps() {
        long t = System.currentTimeMillis();
        return Map.of(1, new VertexPropertyValue("138-8000", t));
    }

    private static Map<Integer, VertexPropertyValue> ipProps() {
        long t = System.currentTimeMillis();
        return Map.of(1, new VertexPropertyValue("10.20.0.1", t));
    }

    private static void assertNoFailures(List<WriteOutcome> outcomes, String kind) {
        long failed = outcomes.stream().filter(o -> o == WriteOutcome.FAILED).count();
        if (failed > 0) {
            throw new IllegalStateException(kind + " writes failed: " + failed);
        }
    }
}
