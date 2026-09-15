package com.pengcheng.phoenixgraph.integration;

import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.VertexSchema;
import com.pengcheng.phoenixgraph.schema.json.SchemaJsonCodec;
import com.pengcheng.phoenixgraph.storage.WriteOutcome;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import com.pengcheng.phoenixgraph.storage.aerospike.EdgePropertyValue;
import com.pengcheng.phoenixgraph.storage.aerospike.EdgeUpsert;
import com.pengcheng.phoenixgraph.storage.aerospike.VertexPropertyValue;
import com.pengcheng.phoenixgraph.storage.aerospike.VertexUpsert;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Shared data seeding for the Aerospike integration tests: 100 vertices of each
 * type (Account / IP / Phone) and 200 edges of each type (Payment /
 * PhoneToAccount), loaded from {@code GraphSchema.json}.
 */
final class QueryDataSeeder {

    private static final String[] ACCOUNT_STATUS = {"ACTIVE", "FROZEN", "CLOSED"};
    private static final String[] PAYMENT_STATUS = {"PENDING", "SUCCESS", "FAILED"};

    private QueryDataSeeder() {
    }

    static GraphSchema loadSchema() {
        try (InputStream in = QueryDataSeeder.class.getResourceAsStream("/GraphSchema.json")) {
            assertNotNull(in, "Missing GraphSchema.json test resource");
            return new SchemaJsonCodec().fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load GraphSchema.json", e);
        }
    }

    static void seedVertices(AerospikeVertexStore vertexStore, GraphSchema schema) {
        VertexSchema accountSchema = schema.vertexSchema("Account");
        VertexSchema ipSchema = schema.vertexSchema("IP");
        VertexSchema phoneSchema = schema.vertexSchema("Phone");

        List<VertexUpsert> upserts = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            long t = now(i);
            upserts.add(new VertexUpsert(id(100 + i), accountSchema, accountProperties(i), t, t));
            upserts.add(new VertexUpsert(id(200 + i), ipSchema, ipProperties(i), t, t));
            upserts.add(new VertexUpsert(id(300 + i), phoneSchema, phoneProperties(i), t, t));
        }
        assertNoFailures(vertexStore.upsertVertices(upserts), "vertices");
    }

    static void seedEdges(AerospikeEdgeStore edgeStore, GraphSchema schema) {
        EdgeSchema paymentSchema = schema.edgeSchema("Payment");
        EdgeSchema phoneToAccountSchema = schema.edgeSchema("PhoneToAccount");

        List<WriteOutcome> outcomes = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            long t = now(i);
            if (i < 100) {
                outcomes.add(edgeStore.upsertEdge(new EdgeUpsert("p" + i, paymentSchema,
                        id(100 + i), id(100 + (i + 1) % 100), paymentProperties(i), t, t)));
            } else {
                int a = i - 100;
                outcomes.add(edgeStore.upsertEdge(new EdgeUpsert("p" + i, paymentSchema,
                        id(100 + a), id(100 + (a + 3) % 100), paymentProperties(i), t, t)));
            }
        }
        for (int i = 0; i < 200; i++) {
            long t = now(i);
            int a = i % 100;
            outcomes.add(edgeStore.upsertEdge(new EdgeUpsert("pa" + i, phoneToAccountSchema,
                    id(300 + a), id(100 + a), phoneToAccountProperties(i), t, t)));
        }
        assertNoFailures(outcomes, "edges");
    }

    private static Map<Integer, VertexPropertyValue> accountProperties(int i) {
        Map<Integer, VertexPropertyValue> props = new LinkedHashMap<>();
        props.put(1, new VertexPropertyValue(ACCOUNT_STATUS[i % 3], now(i)));
        props.put(2, new VertexPropertyValue(i % 2 == 0 ? "savings" : "checking", now(i)));
        props.put(3, new VertexPropertyValue("addr-" + (100 + i), now(i)));
        props.put(4, new VertexPropertyValue(accountUsage(i), now(i)));
        props.put(5, new VertexPropertyValue(accountLimited(i), now(i)));
        return props;
    }

    private static List<String> accountUsage(int i) {
        return switch (i % 3) {
            case 0 -> List.of("Personal");
            case 1 -> List.of("Business");
            default -> List.of("Personal", "Business", "Enterprise");
        };
    }

    private static Map<String, String> accountLimited(int i) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("174", i % 2 == 0 ? "open" : "closed");
        map.put("175", "kyc");
        return map;
    }

    private static Map<Integer, VertexPropertyValue> ipProperties(int i) {
        return Map.of(1, new VertexPropertyValue("10.20." + (i / 256) + "." + (i % 256), now(i)));
    }

    private static Map<Integer, VertexPropertyValue> phoneProperties(int i) {
        Map<Integer, VertexPropertyValue> props = new LinkedHashMap<>();
        props.put(1, new VertexPropertyValue("138-" + (1000 + i), now(i)));
        props.put(2, new VertexPropertyValue(i % 2 == 0 ? "CN" : "US", now(i)));
        return props;
    }

    private static Map<Integer, EdgePropertyValue> paymentProperties(int i) {
        Map<Integer, EdgePropertyValue> props = new LinkedHashMap<>();
        long t = now(i);
        props.put(3, new EdgePropertyValue(PAYMENT_STATUS[i % 3], t, t));
        props.put(4, new EdgePropertyValue(i % 2 == 0 ? "transfer" : "purchase", t, t));
        props.put(5, new EdgePropertyValue(100.0 + i, t, t));
        props.put(6, new EdgePropertyValue(i % 2 == 0 ? "USD" : "CNY", t, t));
        return props;
    }

    private static Map<Integer, EdgePropertyValue> phoneToAccountProperties(int i) {
        long t = now(i);
        return Map.of(3, new EdgePropertyValue(t, t, t));
    }

    private static String id(int value) {
        return String.valueOf(value);
    }

    private static long now(int offset) {
        return System.currentTimeMillis() + offset;
    }

    private static void assertNoFailures(List<WriteOutcome> outcomes, String kind) {
        long failed = outcomes.stream().filter(o -> o == WriteOutcome.FAILED).count();
        assertEquals(0, failed, kind + " writes failed");
    }
}
