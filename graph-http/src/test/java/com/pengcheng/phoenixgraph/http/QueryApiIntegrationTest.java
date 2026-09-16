package com.pengcheng.phoenixgraph.http;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.AerospikeException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pengcheng.phoenixgraph.http.controller.BatchController;
import com.pengcheng.phoenixgraph.http.controller.QueryController;
import com.pengcheng.phoenixgraph.http.service.GraphService;
import com.pengcheng.phoenixgraph.http.service.QueryService;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchemaManagers;
import com.pengcheng.phoenixgraph.schema.VertexSchema;
import com.pengcheng.phoenixgraph.schema.json.SchemaJsonCodec;
import com.pengcheng.phoenixgraph.storage.WriteOutcome;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import com.pengcheng.phoenixgraph.storage.aerospike.EdgePropertyValue;
import com.pengcheng.phoenixgraph.storage.aerospike.EdgeUpsert;
import com.pengcheng.phoenixgraph.storage.aerospike.VertexPropertyValue;
import com.pengcheng.phoenixgraph.storage.aerospike.VertexUpsert;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end API test against a real Aerospike node: batch-writes two accounts
 * and ten payment edges through {@code POST /api/v1/batch}, then runs one- and
 * two-hop Gremlin queries through {@code POST /api/v1/query} (including
 * {@code ${param}} substitution). Skipped when no Aerospike is reachable.
 */
class QueryApiIntegrationTest {

    @Test
    void batchWriteThenQueryThroughApi() throws Exception {
        AerospikeClient client = connectOrNull();
        assumeTrue(client != null, "Aerospike not available, skipping integration test");
        try (client) {
            String namespace = env("AEROSPIKE_NAMESPACE", "test");
            String set = env("AEROSPIKE_VERTEX_SET", "vertices");
            GraphSchema schema = ensureSchemaRegistered();
            AerospikeVertexStore vertexStore = new AerospikeVertexStore(client, namespace, set);
            AerospikeEdgeStore edgeStore = new AerospikeEdgeStore(client, namespace, set, schema);

            GraphService graphService = new GraphService(vertexStore, edgeStore, schema, new ObjectMapper());
            QueryService queryService = new QueryService(vertexStore, edgeStore);
            MockMvc api = MockMvcBuilders.standaloneSetup(
                    new BatchController(graphService), new QueryController(queryService)).build();

            // 1) Batch write: two Account vertices + ten Payment edges via the API.
            api.perform(post("/api/v1/batch")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(batchPayload()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(12));

            // 2) Query the written data: one hop (out + outE), two hops, and param substitution.
            api.perform(post("/api/v1/query")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(query("g.V('1001','Account').outE('Payment').count()")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0]").value(6));

            api.perform(post("/api/v1/query")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(query("g.V('1001','Account').out('Payment').count()")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0]").value(6));

            api.perform(post("/api/v1/query")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(query("g.V('1001','Account').out('Payment').out('Payment').count()")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0]").value(24));

            api.perform(post("/api/v1/query")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"graphName":"payment-graph",
                                     "query":"g.V('${id}','Account').out('Payment').count()",
                                     "params":{"id":"1001"}}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0]").value(6));

            // Property projection across a hop: the reached neighbour's accountStatus.
            api.perform(post("/api/v1/query")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(query("g.V('1001','Account').out('Payment').values('accountStatus')")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(6))
                    .andExpect(jsonPath("$[0]").value("ACTIVE"));
        }
    }

    @Test
    void layeredBothHopQueryViaApi() throws Exception {
        AerospikeClient client = connectOrNull();
        assumeTrue(client != null, "Aerospike not available, skipping integration test");
        try (client) {
            String namespace = env("AEROSPIKE_NAMESPACE", "test");
            GraphSchema schema = ensureSchemaRegistered();
            AerospikeVertexStore vertexStore = new AerospikeVertexStore(client, namespace, "http_rich_v2");
            AerospikeEdgeStore edgeStore = new AerospikeEdgeStore(client, namespace, "http_rich_v2", schema);
            truncateSet(client, namespace, "http_rich_v2"); // drop stale data from earlier seed versions
            seedLayeredGraph(vertexStore, edgeStore, schema);

            QueryService queryService = new QueryService(vertexStore, edgeStore);
            MockMvc api = MockMvcBuilders.standaloneSetup(new QueryController(queryService)).build();

            // one-hop both: 888 has 100 out-Payment edges and no in-Payment edges
            api.perform(post("/api/v1/query")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(query("g.V(888,'Account').both('Payment').count()")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0]").value(100));

            // two-hop both: each of the 100 neighbours has 3 out + 1 in = 4 -> 400
            api.perform(post("/api/v1/query")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(query("g.V(888,'Account').batch(40).both('Payment').both('Payment').count()")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0]").value(400));

            api.perform(post("/api/v1/query")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(query("g.V(888,'Account').batch(40).both('Payment').both('Payment')")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(400));

            // subgraph: 888 + 100 LEVEL1 + 300 LEVEL2 = 401 vertices, 100 + 300 = 400 edges
            api.perform(post("/api/v1/query")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    { "graphName": "payment-graph",
                                      "query": "g.V(888,'Account').batch(40).both('Payment').both('Payment')",
                                      "params": {},
                                      "returnSubgraph": true }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.vertices.length()").value(401))
                    .andExpect(jsonPath("$.edges.length()").value(400));

            // 888 also links to IPs and Phones: 100 Payment + 5 AccountToIP + 5 AccountToPhone
            api.perform(post("/api/v1/query")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(query("g.V(888,'Account').both().count()")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0]").value(110));

            // List<String> and Map<String,String> properties round-trip
            api.perform(post("/api/v1/query")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(query("g.V(888,'Account').values('accountUsage')")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0][0]").value("Personal"))
                    .andExpect(jsonPath("$[0][1]").value("Business"));

            api.perform(post("/api/v1/query")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(query("g.V(888,'Account').elementMap()")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].accountUsage[0]").value("Personal"))
                    .andExpect(jsonPath("$[0].accountLimited['174']").value("open"));

            // two-hop both() over ALL edge classes must succeed as a subgraph
            // (regression: absent neighbours must not NPE the batched vertex read)
            api.perform(post("/api/v1/query")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    { "graphName": "payment-graph",
                                      "query": "g.V(888,'Account').both().both()",
                                      "params": {},
                                      "returnSubgraph": true }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.vertices.length()")
                            .value(org.hamcrest.Matchers.greaterThan(400)));
        }
    }

    private static void truncateSet(AerospikeClient client, String namespace, String set) {
        try {
            client.truncate(new com.aerospike.client.policy.InfoPolicy(), namespace, set, null);
            Thread.sleep(300); // give the server a moment to apply the truncate
        } catch (AerospikeException e) {
            // truncate can be disabled on a server; seeding will just upsert on top
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void seedLayeredGraph(AerospikeVertexStore vertexStore, AerospikeEdgeStore edgeStore,
                                         GraphSchema schema) {
        VertexSchema account = schema.vertexSchema("Account");
        EdgeSchema payment = schema.edgeSchema("Payment");
        long t = System.currentTimeMillis();

        List<VertexUpsert> vertices = new ArrayList<>();
        vertices.add(new VertexUpsert("888", account, accountProps("888", t), t, t));
        for (int i = 0; i < 100; i++) {
            vertices.add(new VertexUpsert(String.valueOf(1000 + i), account,
                    accountProps(String.valueOf(1000 + i), t), t, t));
        }
        for (int i = 0; i < 300; i++) {
            vertices.add(new VertexUpsert(String.valueOf(2000 + i), account,
                    accountProps(String.valueOf(2000 + i), t), t, t));
        }
        assertNoFailures(vertexStore.upsertVertices(vertices));

        List<EdgeUpsert> edges = new ArrayList<>();
        int seq = 0;
        for (int i = 0; i < 100; i++) {
            edges.add(new EdgeUpsert("rpay" + seq++, payment, "888", String.valueOf(1000 + i),
                    Map.of(3, new EdgePropertyValue("SUCCESS", t, t)), t, t));
        }
        for (int i = 0; i < 100; i++) {
            for (int k = 0; k < 3; k++) {
                edges.add(new EdgeUpsert("rpay" + seq++, payment, String.valueOf(1000 + i),
                        String.valueOf(2000 + 3 * i + k),
                        Map.of(3, new EdgePropertyValue("SUCCESS", t, t)), t, t));
            }
        }
        assertNoFailures(edgeStore.upsertEdges(edges));

        // IP / Phone vertices + AccountToIP / AccountToPhone edges (for the UI legend & expansion)
        VertexSchema ip = schema.vertexSchema("IP");
        VertexSchema phone = schema.vertexSchema("Phone");
        EdgeSchema accountToPhone = schema.edgeSchema("AccountToPhone");
        EdgeSchema accountToIp = schema.edgeSchema("AccountToIP");

        List<VertexUpsert> ipPhones = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            ipPhones.add(new VertexUpsert(String.valueOf(9000 + i), ip, ipProps(String.valueOf(9000 + i), t), t, t));
            ipPhones.add(new VertexUpsert(String.valueOf(7000 + i), phone, phoneProps(String.valueOf(7000 + i), t), t, t));
        }
        assertNoFailures(vertexStore.upsertVertices(ipPhones));

        List<EdgeUpsert> extraEdges = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            extraEdges.add(new EdgeUpsert("ati888_" + i, accountToIp, "888", String.valueOf(9000 + i),
                    Map.of(3, new EdgePropertyValue(t, t, t)), t, t));
            extraEdges.add(new EdgeUpsert("atp888_" + i, accountToPhone, "888", String.valueOf(7000 + i),
                    Map.of(3, new EdgePropertyValue(t, t, t)), t, t));
        }
        for (int k = 0; k < 10; k++) {
            String acc = String.valueOf(1000 + k * 10);
            extraEdges.add(new EdgeUpsert("atiL1_" + k, accountToIp, acc, String.valueOf(9001 + k),
                    Map.of(3, new EdgePropertyValue(t, t, t)), t, t));
            extraEdges.add(new EdgeUpsert("atpL1_" + k, accountToPhone, acc, String.valueOf(7001 + k),
                    Map.of(3, new EdgePropertyValue(t, t, t)), t, t));
        }
        assertNoFailures(edgeStore.upsertEdges(extraEdges));
    }

    private static Map<Integer, VertexPropertyValue> accountProps(String id, long t) {
        Map<Integer, VertexPropertyValue> props = new LinkedHashMap<>();
        props.put(1, new VertexPropertyValue("ACTIVE", t));
        props.put(2, new VertexPropertyValue("savings", t));
        props.put(3, new VertexPropertyValue("addr-" + id, t));
        props.put(4, new VertexPropertyValue(List.of("Personal", "Business"), t));
        props.put(5, new VertexPropertyValue(Map.of("174", "open", "175", "kyc"), t));
        return props;
    }

    private static Map<Integer, VertexPropertyValue> ipProps(String id, long t) {
        Map<Integer, VertexPropertyValue> props = new LinkedHashMap<>();
        props.put(1, new VertexPropertyValue("10.0." + id + ".1", t));
        return props;
    }

    private static Map<Integer, VertexPropertyValue> phoneProps(String id, long t) {
        Map<Integer, VertexPropertyValue> props = new LinkedHashMap<>();
        props.put(1, new VertexPropertyValue("138-" + id, t));
        props.put(2, new VertexPropertyValue("CN", t));
        return props;
    }

    private static void assertNoFailures(List<WriteOutcome> outcomes) {
        if (outcomes.stream().anyMatch(o -> o == WriteOutcome.FAILED)) {
            throw new IllegalStateException("seed write failed");
        }
    }

    private static String query(String query) throws Exception {
        return new ObjectMapper().writeValueAsString(Map.of(
                "graphName", "payment-graph",
                "query", query,
                "params", Map.of()));
    }

    private static String batchPayload() {
        StringBuilder edges = new StringBuilder();
        for (int i = 1; i <= 6; i++) {
            edges.append(edge(i, "1001", "1002"));
        }
        for (int i = 7; i <= 10; i++) {
            edges.append(edge(i, "1002", "1001"));
        }
        return """
                {
                  "vertices": [
                    { "id": "1001", "vertexSchemaName": "Account",
                      "properties": [ { "index": 1, "value": "ACTIVE" },
                                      { "index": 2, "value": "savings" },
                                      { "index": 3, "value": "Shanghai" },
                                      { "index": 4, "value": ["Personal", "Business"] },
                                      { "index": 5, "value": { "174": "open", "175": "kyc" } } ] },
                    { "id": "1002", "vertexSchemaName": "Account",
                      "properties": [ { "index": 1, "value": "ACTIVE" },
                                      { "index": 2, "value": "checking" },
                                      { "index": 3, "value": "Beijing" },
                                      { "index": 4, "value": ["Enterprise"] },
                                      { "index": 5, "value": { "174": "closed", "175": "kyc" } } ] }
                  ],
                  "edges": [
                """ + edges + """
                  ]
                }
                """;
    }

    private static String edge(int n, String source, String destination) {
        String comma = n == 10 ? "" : ",";
        return """
                { "edgeId": "e%d", "edgeSchemaName": "Payment", "source": "%s", "destination": "%s",
                  "properties": [ { "index": 3, "value": "SUCCESS" },
                                  { "index": 5, "value": 100.5 },
                                  { "index": 6, "value": "USD" } ] }%s
                """.formatted(n, source, destination, comma);
    }

    private static GraphSchema ensureSchemaRegistered() {
        String name = "payment-graph";
        GraphSchema existing = GraphSchemaManagers.manager().get(name);
        if (existing != null) {
            return existing;
        }
        GraphSchema schema = loadClasspathSchema(name);
        assertNotNull(schema, "Missing payment-graph.json test resource");
        GraphSchemaManagers.manager().register(schema);
        assertEquals(schema.name(), name);
        return schema;
    }

    private static GraphSchema loadClasspathSchema(String name) {
        try (InputStream in = QueryApiIntegrationTest.class.getResourceAsStream("/" + name + ".json")) {
            if (in == null) {
                return null;
            }
            return new SchemaJsonCodec().fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load " + name + ".json", e);
        }
    }

    private static AerospikeClient connectOrNull() {
        try {
            return new AerospikeClient(env("AEROSPIKE_HOST", "localhost"),
                    Integer.parseInt(env("AEROSPIKE_PORT", "3000")));
        } catch (AerospikeException e) {
            return null;
        }
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
