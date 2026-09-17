package com.pengcheng.phoenixgraph.integration;

import com.aerospike.client.AerospikeClient;
import com.pengcheng.phoenixgraph.query.aerospike.AerospikeGraphQuery;
import com.pengcheng.phoenixgraph.query.gremlin.GremlinGraphQuery;
import com.pengcheng.phoenixgraph.query.traversal.NeighborVertexResolver;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Rich multi-hop benchmark with an enriched, layered Payment graph and the new
 * Account properties (accountUsage / accountLimited). Account 888 has ~100
 * one-hop and ~300 two-hop Payment neighbours; AccountToPhone (1-5) and
 * AccountToIP (5-80, median 30) keep their degree distributions.
 */
class GremlinMultiHopBenchmark {

    private static final int LEVEL1 = 100;   // one-hop accounts
    private static final int LEVEL2 = 300;   // two-hop accounts
    private static final int RUNS = 20;
    private static final int WARMUP = 5;
    static final String SET = "rich_vertices_v2";

    @Test
    void runsMultiHopQueries() {
        IntegrationConfig config = IntegrationConfig.fromEnv();
        AerospikeClient client = config.connectOrNull();
        assumeTrue(client != null,
                "Aerospike not available at " + config.host() + ":" + config.port() + ", skipping benchmark");
        try (client) {
            GraphSchema schema = richSchema();
            AerospikeVertexStore vertexStore = new AerospikeVertexStore(client, config.namespace(), SET);
            AerospikeEdgeStore edgeStore = new AerospikeEdgeStore(client, config.namespace(), SET, schema);

            seed(vertexStore, edgeStore, schema);

            GremlinGraphQuery gremlin = new GremlinGraphQuery(
                    new AerospikeGraphQuery(vertexStore, edgeStore, schema),
                    NeighborVertexResolver.from(schema))
                    .registerLogic("successCount", ".has('status','SUCCESS').count()");

            String[] queries = {
                    "g.V(888, 'Account').batch(40).out('Payment').out('Payment')",
                    "g.V(888, 'Account').batch(40).out('Payment').in('Payment')",
                    "g.V(888, 'Account').batch(40).out('Payment').out('AccountToPhone')",
                    "g.V(888, 'Account').batch(40).out('Payment').out('AccountToIP')",
                    "g.V(888, 'Account').batch(40).out('Payment').in('PhoneToAccount')",
                    "g.V(888, 'Account').batch(40).out('AccountToPhone').out('PhoneToAccount')",
                    "g.V(888, 'Account').batch(40).both('Payment').both('Payment')",
                    "g.V(888, 'Account').batch(40).out('Payment').both('Payment')",
                    "g.V(888, 'Account').batch(40).both('Payment').out('AccountToPhone')",
                    "g.V(888, 'Account').batch(40).out('Payment').outE('Payment') { $successCount }"
            };

            System.out.println();
            System.out.println("=== Gremlin enriched multi-hop benchmark (888: 1-hop=" + LEVEL1 + ", 2-hop=" + LEVEL2 + ") ===");
            List<Result> results = new ArrayList<>();
            for (String query : queries) {
                results.add(measure(gremlin, query));
            }
            System.out.println();

            List<Result> sorted = new ArrayList<>(results);
            sorted.sort(Comparator.comparingDouble(Result::avgMs).reversed());
            System.out.println("--- slowest queries (for future optimization) ---");
            for (int i = 0; i < sorted.size(); i++) {
                Result r = sorted.get(i);
                System.out.printf("  %2d. avg=%8.3f ms | results=%5d | %s%n", i + 1, r.avgMs(), r.resultSize(), r.query());
            }
            System.out.println();
        }
    }

    private record Result(String query, int resultSize, double avgMs) {
    }

    private static Result measure(GremlinGraphQuery gremlin, String query) {
        for (int i = 0; i < WARMUP; i++) {
            gremlin.execute(query);
        }
        long sum = 0;
        int resultSize = 0;
        for (int i = 0; i < RUNS; i++) {
            long start = System.nanoTime();
            List<Object> result = gremlin.execute(query);
            sum += System.nanoTime() - start;
            resultSize = result.size();
        }
        double avgMs = sum / (double) RUNS / 1_000_000.0;
        System.out.printf("  avg=%8.3f ms | results=%5d | %s%n", avgMs, resultSize, query);
        return new Result(query, resultSize, avgMs);
    }

    // --- schema ---

    static GraphSchema richSchema() {
        VertexSchema account = new VertexSchema(1, "Account", null)
                .property(new VertexPropertySchema(1, "accountType", null, PropertyType.ScalarType.STRING))
                .property(new VertexPropertySchema(2, "accountStatus", null,
                        PropertyType.enumOf("ACTIVE", "FROZEN", "CLOSED")))
                .property(new VertexPropertySchema(3, "accountUsage", null,
                        PropertyType.listOf(PropertyType.ScalarType.STRING)))
                .property(new VertexPropertySchema(4, "accountLimited", null,
                        PropertyType.mapOf(PropertyType.ScalarType.STRING)));
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

        return new GraphSchema(1, "rich", null)
                .vertex(account).vertex(ip).vertex(phone)
                .edge(payment).edge(accountToPhone).edge(accountToIp).edge(phoneToAccount);
    }

    // --- seeding ---

    static void seed(AerospikeVertexStore vertexStore, AerospikeEdgeStore edgeStore, GraphSchema schema) {
        VertexSchema account = schema.vertexSchema("Account");
        VertexSchema ip = schema.vertexSchema("IP");
        VertexSchema phone = schema.vertexSchema("Phone");
        EdgeSchema payment = schema.edgeSchema("Payment");
        EdgeSchema accountToPhone = schema.edgeSchema("AccountToPhone");
        EdgeSchema accountToIp = schema.edgeSchema("AccountToIP");
        EdgeSchema phoneToAccount = schema.edgeSchema("PhoneToAccount");

        Random rnd = new Random(42);
        long t = System.currentTimeMillis();

        List<String> accountIds = new ArrayList<>();
        accountIds.add("888");
        for (int i = 0; i < LEVEL1; i++) {
            accountIds.add(String.valueOf(1000 + i));
        }
        for (int i = 0; i < LEVEL2; i++) {
            accountIds.add(String.valueOf(2000 + i));
        }

        List<VertexUpsert> vertices = new ArrayList<>();
        for (String id : accountIds) {
            vertices.add(new VertexUpsert(id, account, accountProps(id, t), t, t));
        }

        List<EdgeUpsert> edges = new ArrayList<>();
        int phoneSeq = 0;
        int ipSeq = 0;
        int edgeSeq = 0;

        // Payment: layered — 888 -> LEVEL1 (1 hop), LEVEL1 -> LEVEL2 (2 hop)
        for (int i = 0; i < LEVEL1; i++) {
            edges.add(new EdgeUpsert("pay" + edgeSeq++, payment, "888", String.valueOf(1000 + i),
                    Map.of(3, new EdgePropertyValue("SUCCESS", t, t)), t, t));
        }
        for (int i = 0; i < LEVEL1; i++) {
            for (int k = 0; k < 3; k++) {
                edges.add(new EdgeUpsert("pay" + edgeSeq++, payment, String.valueOf(1000 + i),
                        String.valueOf(2000 + 3 * i + k),
                        Map.of(3, new EdgePropertyValue("SUCCESS", t, t)), t, t));
            }
        }

        // AccountToPhone (1-5) and AccountToIP (5-80, median 30) for 888 + LEVEL1
        List<String> hopAccounts = accountIds.subList(0, 1 + LEVEL1);
        for (String acc : hopAccounts) {
            int phoneCount = degree(rnd, 1, 5, 3);
            for (int k = 0; k < phoneCount; k++) {
                String ph = String.valueOf(7000 + phoneSeq++);
                vertices.add(new VertexUpsert(ph, phone, phoneProps(ph, t), t, t));
                edges.add(new EdgeUpsert("atp" + edgeSeq++, accountToPhone, acc, ph,
                        Map.of(3, new EdgePropertyValue(t, t, t)), t, t));
                int target = rnd.nextInt(accountIds.size());
                edges.add(new EdgeUpsert("pta" + edgeSeq++, phoneToAccount, ph, accountIds.get(target),
                        Map.of(3, new EdgePropertyValue(t, t, t)), t, t));
            }
            int ipCount = degree(rnd, 5, 80, 30);
            for (int k = 0; k < ipCount; k++) {
                String ipId = String.valueOf(9000 + ipSeq++);
                vertices.add(new VertexUpsert(ipId, ip, ipProps(ipId, t), t, t));
                edges.add(new EdgeUpsert("ati" + edgeSeq++, accountToIp, acc, ipId,
                        Map.of(3, new EdgePropertyValue(t, t, t)), t, t));
            }
        }

        assertNoFailures(vertexStore.upsertVertices(vertices), "vertices");
        assertNoFailures(edgeStore.upsertEdges(edges), "edges");
        System.out.println("  seeded vertices=" + vertices.size() + " edges=" + edges.size());
    }

    private static int degree(Random r, int min, int max, int median) {
        double pos = (median - min) / (double) (max - min);
        double u = r.nextDouble();
        double v = u < 0.5 ? pos * (u / 0.5) : pos + (1 - pos) * ((u - 0.5) / 0.5);
        return Math.max(min, Math.min(max, min + (int) Math.round(v * (max - min))));
    }

    private static Map<Integer, VertexPropertyValue> accountProps(String id, long t) {
        int hash = Math.floorMod(id.hashCode(), 100);
        Map<Integer, VertexPropertyValue> props = new LinkedHashMap<>();
        props.put(1, new VertexPropertyValue(hash % 2 == 0 ? "savings" : "checking", t));
        props.put(2, new VertexPropertyValue("ACTIVE", t));
        props.put(3, new VertexPropertyValue(accountUsage(hash), t));
        props.put(4, new VertexPropertyValue(accountLimited(hash), t));
        return props;
    }

    private static List<String> accountUsage(int hash) {
        return switch (hash % 3) {
            case 0 -> List.of("Personal");
            case 1 -> List.of("Business");
            default -> List.of("Personal", "Business", "Enterprise");
        };
    }

    private static Map<String, String> accountLimited(int hash) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("174", hash % 2 == 0 ? "open" : "closed");
        map.put("175", "kyc");
        return map;
    }

    private static Map<Integer, VertexPropertyValue> phoneProps(String id, long t) {
        return Map.of(1, new VertexPropertyValue("138-" + id, t));
    }

    private static Map<Integer, VertexPropertyValue> ipProps(String id, long t) {
        return Map.of(1, new VertexPropertyValue("10.20." + (Math.floorMod(id.hashCode(), 256)) + ".1", t));
    }

    private static void assertNoFailures(List<WriteOutcome> outcomes, String kind) {
        long failed = outcomes.stream().filter(o -> o == WriteOutcome.FAILED).count();
        if (failed > 0) {
            throw new IllegalStateException(kind + " writes failed: " + failed);
        }
    }
}
