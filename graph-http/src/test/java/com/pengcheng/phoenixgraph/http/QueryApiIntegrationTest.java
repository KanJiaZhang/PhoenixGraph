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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
            QueryService queryService = QueryService.ambient(vertexStore, edgeStore);
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

            QueryService queryService = QueryService.ambient(vertexStore, edgeStore);
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

    /**
     * A richer demo graph for the visualizer, seeded into {@code http_demo_v2}:
     * account 135 pays 50 accounts, those 50 trade among themselves, and each of
     * them has 2 IP and 2 Phone associations (plus reverse IPToAccount /
     * PhoneToAccount links for full edge-type coverage).
     */
    @Test
    void demoStarGraphViaApi() throws Exception {
        AerospikeClient client = connectOrNull();
        assumeTrue(client != null, "Aerospike not available, skipping integration test");
        try (client) {
            String namespace = env("AEROSPIKE_NAMESPACE", "test");
            String set = "http_demo_v2";
            GraphSchema schema = ensureSchemaRegistered();
            AerospikeVertexStore vertexStore = new AerospikeVertexStore(client, namespace, set);
            AerospikeEdgeStore edgeStore = new AerospikeEdgeStore(client, namespace, set, schema);
            truncateSet(client, namespace, set);
            seedStarGraph(vertexStore, edgeStore, schema);

            // the schema flags the properties the UI uses to label vertices
            assertEquals(List.of("firstName", "lastName", "username"),
                    schema.vertexSchema("Account").displayPropertyNames());
            assertEquals(List.of("ipAddress"), schema.vertexSchema("IP").displayPropertyNames());
            assertEquals(List.of("cnCode", "phoneNumber"),
                    schema.vertexSchema("Phone").displayPropertyNames());

            QueryService queryService = QueryService.ambient(vertexStore, edgeStore);
            MockMvc api = MockMvcBuilders.standaloneSetup(new QueryController(queryService)).build();

            // 135 pays exactly 50 one-hop accounts
            api.perform(post("/api/v1/query")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(query("g.V('135','Account').out('Payment').count()")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0]").value(50));

            // those 50 trade among themselves (ring + 2 cross edges)
            api.perform(post("/api/v1/query")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(query("g.V('1000','Account').out('Payment').count()")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0]").value(3));

            // 2-hop both() over every edge type renders as a subgraph
            api.perform(post("/api/v1/query")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    { "graphName": "payment-graph",
                                      "query": "g.V('135','Account').both().both()",
                                      "params": {},
                                      "returnSubgraph": true }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.vertices.length()")
                            .value(org.hamcrest.Matchers.greaterThan(200)));

            // a queried id that does not exist must not yield a phantom vertex
            api.perform(post("/api/v1/query")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    { "graphName": "payment-graph",
                                      "query": "g.V('999999','Account').both().both()",
                                      "params": {},
                                      "returnSubgraph": true }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.vertices.length()").value(0));

            // hub 150 fans out to ~10k vertices within two hops (every link is stored in both
            // directions, so a walk over all classes sees the neighbourhood twice per hop)
            api.perform(post("/api/v1/query")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(query("g.V('150','Account').both().both().count()")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0]").value(9900));

            api.perform(post("/api/v1/query")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    { "graphName": "payment-graph",
                                      "query": "g.V('150','Account').both().both()",
                                      "params": {},
                                      "returnSubgraph": true }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.vertices.length()").value(5001));
        }
    }

    /**
     * The single-direction demo ({@code http_demo_v3}): the same payments, but every account-IP/Phone link
     * is one directed edge and IPs/Phones are shared across several accounts. This is what makes the
     * {@code both()} edge count equal to the sum of the three named classes, and the UI's IP/Phone edges
     * single-direction.
     */
    @Test
    void demoSharedGraphViaApi() throws Exception {
        AerospikeClient client = connectOrNull();
        assumeTrue(client != null, "Aerospike not available, skipping integration test");
        try (client) {
            String namespace = env("AEROSPIKE_NAMESPACE", "test");
            String set = "http_demo_v3";
            GraphSchema schema = ensureSchemaRegistered();
            AerospikeVertexStore vertexStore = new AerospikeVertexStore(client, namespace, set);
            AerospikeEdgeStore edgeStore = new AerospikeEdgeStore(client, namespace, set, schema);
            truncateSet(client, namespace, set);
            seedSharedGraph(vertexStore, edgeStore, schema);

            QueryService queryService = QueryService.ambient(vertexStore, edgeStore);
            MockMvc api = MockMvcBuilders.standaloneSetup(new QueryController(queryService)).build();

            api.perform(post("/api/v1/query")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(query("g.V('135','Account').out('Payment').count()")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0]").value(76));

            // a multi-account IP links to three accounts (10.99.255.2 -> 2000, 2001, 2002)
            api.perform(post("/api/v1/query")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(query("g.V('10.99.255.2','IP').out('IPToAccount').count()")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0]").value(3));

            // every edge of 2007 is one of the three named classes: both() equals the sum (and the
            // multi-class hop gives the same answer)
            long both = number(api, "g.V('2007','Account').both().count()");
            long named = number(api, "g.V('2007','Account').both('Payment').count()")
                    + number(api, "g.V('2007','Account').both('IPToAccount').count()")
                    + number(api, "g.V('2007','Account').both('PhoneToAccount').count()");
            assertEquals(both, named, "both() is the sum of the three named classes");
            assertEquals(both, number(api,
                    "g.V('2007','Account').both('Payment','IPToAccount','PhoneToAccount').count()"),
                    "the multi-class hop agrees with both()");

            // the Phone display is the country code plus the national number, never the id twice
            String phoneId = "+86-138-69999";
            assertTrue(phoneProps(phoneId, 1L).get(2).value().equals("138-69999"),
                    "phoneNumber stores the national part, so the label does not repeat +86");
        }
    }

    private static long number(MockMvc api, String query) throws Exception {
        MvcResult result = api.perform(post("/api/v1/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(QueryApiIntegrationTest.query(query)))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        return Long.parseLong(body.substring(1, body.length() - 1));
    }

    private static void seedStarGraph(AerospikeVertexStore vertexStore, AerospikeEdgeStore edgeStore,
                                      GraphSchema schema) {
        VertexSchema account = schema.vertexSchema("Account");
        VertexSchema ip = schema.vertexSchema("IP");
        VertexSchema phone = schema.vertexSchema("Phone");
        EdgeSchema payment = schema.edgeSchema("Payment");
        EdgeSchema accountToIp = schema.edgeSchema("AccountToIP");
        EdgeSchema ipToAccount = schema.edgeSchema("IPToAccount");
        EdgeSchema accountToPhone = schema.edgeSchema("AccountToPhone");
        EdgeSchema phoneToAccount = schema.edgeSchema("PhoneToAccount");
        long t = System.currentTimeMillis();

        List<VertexUpsert> vertices = new ArrayList<>();
        vertices.add(new VertexUpsert("135", account, accountProps("135", t), t, t));
        for (int i = 0; i < 50; i++) {
            String acc = String.valueOf(1000 + i);
            vertices.add(new VertexUpsert(acc, account, accountProps(acc, t), t, t));
            for (int k = 0; k < 2; k++) {
                String ipId = ipAddress(i, k);
                String phoneId = phoneNumber(i, k);
                vertices.add(new VertexUpsert(ipId, ip, ipProps(ipId, t), t, t));
                vertices.add(new VertexUpsert(phoneId, phone, phoneProps(phoneId, t), t, t));
            }
        }
        assertNoFailures(upsertVerticesChunked(vertexStore, vertices));

        List<EdgeUpsert> edges = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            String acc = String.valueOf(1000 + i);
            edges.add(new EdgeUpsert("pay_c" + i, payment, "135", acc,
                    Map.of(3, new EdgePropertyValue("SUCCESS", t, t)), t, t));
        }
        for (int i = 0; i < 50; i++) {
            String acc = String.valueOf(1000 + i);
            edges.add(new EdgeUpsert("pay_r" + i, payment, acc,
                    String.valueOf(1000 + (i + 1) % 50), Map.of(3, new EdgePropertyValue("SUCCESS", t, t)), t, t));
            edges.add(new EdgeUpsert("pay_x" + i, payment, acc,
                    String.valueOf(1000 + (i + 7) % 50), Map.of(3, new EdgePropertyValue("PENDING", t, t)), t, t));
            edges.add(new EdgeUpsert("pay_y" + i, payment, acc,
                    String.valueOf(1000 + (i + 13) % 50), Map.of(3, new EdgePropertyValue("SUCCESS", t, t)), t, t));
        }
        for (int i = 0; i < 50; i++) {
            String acc = String.valueOf(1000 + i);
            String ip0 = ipAddress(i, 0);
            String ip1 = ipAddress(i, 1);
            String ph0 = phoneNumber(i, 0);
            String ph1 = phoneNumber(i, 1);
            // both directions of every link, so a traversal that names one of the two classes sees the
            // same neighbourhood as one that walks every class (the UI expands with both())
            for (int k = 0; k < 2; k++) {
                String ipId = k == 0 ? ip0 : ip1;
                String phoneId = k == 0 ? ph0 : ph1;
                edges.add(new EdgeUpsert("ati_" + i + "_" + k, accountToIp, acc, ipId,
                        Map.of(3, new EdgePropertyValue(t, t, t)), t, t));
                edges.add(new EdgeUpsert("ipa_" + i + "_" + k, ipToAccount, ipId, acc,
                        Map.of(3, new EdgePropertyValue(t, t, t)), t, t));
                edges.add(new EdgeUpsert("atp_" + i + "_" + k, accountToPhone, acc, phoneId,
                        Map.of(3, new EdgePropertyValue(t, t, t)), t, t));
                edges.add(new EdgeUpsert("pta_" + i + "_" + k, phoneToAccount, phoneId, acc,
                        Map.of(3, new EdgePropertyValue(t, t, t)), t, t));
            }
        }
        assertNoFailures(upsertEdgesChunked(edgeStore, edges));

        // ---- big fan-out hub: account 150 -> 100 accounts, each with 39 IP + 10 Phone links ----
        // (2-hop both() from 150 reaches ~5000 vertices, for canvas stress testing)
        List<VertexUpsert> big = new ArrayList<>();
        big.add(new VertexUpsert("150", account, accountProps("150", t), t, t));
        for (int i = 0; i < 100; i++) {
            String acc = String.valueOf(2000 + i);
            big.add(new VertexUpsert(acc, account, accountProps(acc, t), t, t));
            for (int k = 0; k < 39; k++) {
                String ipId = bigIpAddress(i, k);
                big.add(new VertexUpsert(ipId, ip, ipProps(ipId, t), t, t));
            }
            for (int k = 0; k < 10; k++) { // AccountToPhone caps out-degree at 10
                String phoneId = bigPhoneNumber(i, k);
                big.add(new VertexUpsert(phoneId, phone, phoneProps(phoneId, t), t, t));
            }
        }
        assertNoFailures(upsertVerticesChunked(vertexStore, big));

        List<EdgeUpsert> bigEdges = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            String acc = String.valueOf(2000 + i);
            bigEdges.add(new EdgeUpsert("big_c" + i, payment, "150", acc,
                    Map.of(3, new EdgePropertyValue("SUCCESS", t, t)), t, t));
            for (int k = 0; k < 39; k++) {
                String ipId = bigIpAddress(i, k);
                bigEdges.add(new EdgeUpsert("big_i" + i + "_" + k, accountToIp, acc, ipId,
                        Map.of(3, new EdgePropertyValue(t, t, t)), t, t));
                bigEdges.add(new EdgeUpsert("big_ip" + i + "_" + k, ipToAccount, ipId, acc,
                        Map.of(3, new EdgePropertyValue(t, t, t)), t, t));
            }
            for (int k = 0; k < 10; k++) {
                String phoneId = bigPhoneNumber(i, k);
                bigEdges.add(new EdgeUpsert("big_p" + i + "_" + k, accountToPhone, acc, phoneId,
                        Map.of(3, new EdgePropertyValue(t, t, t)), t, t));
                bigEdges.add(new EdgeUpsert("big_ph" + i + "_" + k, phoneToAccount, phoneId, acc,
                        Map.of(3, new EdgePropertyValue(t, t, t)), t, t));
            }
        }
        assertNoFailures(upsertEdgesChunked(edgeStore, bigEdges));
    }

    private static List<WriteOutcome> upsertVerticesChunked(AerospikeVertexStore store, List<VertexUpsert> upserts) {
        List<WriteOutcome> all = new ArrayList<>();
        for (int i = 0; i < upserts.size(); i += 500) {
            all.addAll(store.upsertVertices(upserts.subList(i, Math.min(i + 500, upserts.size()))));
        }
        return all;
    }

    private static List<WriteOutcome> upsertEdgesChunked(AerospikeEdgeStore store, List<EdgeUpsert> upserts) {
        List<WriteOutcome> all = new ArrayList<>();
        for (int i = 0; i < upserts.size(); i += 500) {
            all.addAll(store.upsertEdges(upserts.subList(i, Math.min(i + 500, upserts.size()))));
        }
        return all;
    }

    /**
     * The single-direction demo ({@code http_demo_v3}): every account-IP/Phone link is one directed edge
     * ({@code IPToAccount}, {@code PhoneToAccount}), so the UI draws IP -> Account and Phone -> Account
     * instead of two mirrored edges. IPs and Phones are shared across several accounts - an IP links to a
     * deterministic three to eighty accounts - and a few of them link to exactly two or three accounts, so
     * the multi-account cases are easy to spot.
     */
    private static void seedSharedGraph(AerospikeVertexStore vertexStore, AerospikeEdgeStore edgeStore,
                                        GraphSchema schema) {
        VertexSchema account = schema.vertexSchema("Account");
        VertexSchema ip = schema.vertexSchema("IP");
        VertexSchema phone = schema.vertexSchema("Phone");

        EdgeSchema payment = schema.edgeSchema("Payment");
        EdgeSchema ipToAccount = schema.edgeSchema("IPToAccount");
        EdgeSchema phoneToAccount = schema.edgeSchema("PhoneToAccount");
        long t = System.currentTimeMillis();

        List<VertexUpsert> vertices = new ArrayList<>();
        vertices.add(new VertexUpsert("135", account, accountProps("135", t), t, t));
        vertices.add(new VertexUpsert("150", account, accountProps("150", t), t, t));
        for (int i = 0; i < 50; i++) {
            vertices.add(new VertexUpsert(String.valueOf(1000 + i), account,
                    accountProps(String.valueOf(1000 + i), t), t, t));
        }
        for (int i = 0; i < 100; i++) {
            vertices.add(new VertexUpsert(String.valueOf(2000 + i), account,
                    accountProps(String.valueOf(2000 + i), t), t, t));
        }
        // the 150 accounts an IP or phone can link to (ring accounts first, then the star accounts)
        List<String> accounts = new ArrayList<>();
        for (int i = 0; i < 50; i++) accounts.add(String.valueOf(1000 + i));
        for (int i = 0; i < 100; i++) accounts.add(String.valueOf(2000 + i));

        for (int i = 0; i < 60; i++) {
            String ipId = "10.99." + (i / 100) + "." + (i % 100);
            vertices.add(new VertexUpsert(ipId, ip, ipProps(ipId, t), t, t));
        }
        for (int i = 0; i < 24; i++) {
            String phoneId = "+86-138-" + (60000 + i);
            vertices.add(new VertexUpsert(phoneId, phone, phoneProps(phoneId, t), t, t));
        }
        for (String ipId : List.of("10.99.255.1", "10.99.255.2")) {
            vertices.add(new VertexUpsert(ipId, ip, ipProps(ipId, t), t, t));
        }
        for (String phoneId : List.of("+86-138-69999", "+86-138-69998")) {
            vertices.add(new VertexUpsert(phoneId, phone, phoneProps(phoneId, t), t, t));
        }
        assertNoFailures(upsertVerticesChunked(vertexStore, vertices));

        List<EdgeUpsert> edges = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            String acc = String.valueOf(1000 + i);
            edges.add(new EdgeUpsert("pay_c" + i, payment, "135", acc,
                    Map.of(3, new EdgePropertyValue("SUCCESS", t, t)), t, t));
        }
        for (int i = 0; i < 50; i++) {
            String acc = String.valueOf(1000 + i);
            edges.add(new EdgeUpsert("pay_r" + i, payment, acc,
                    String.valueOf(1000 + (i + 1) % 50), Map.of(3, new EdgePropertyValue("SUCCESS", t, t)), t, t));
            edges.add(new EdgeUpsert("pay_x" + i, payment, acc,
                    String.valueOf(1000 + (i + 7) % 50), Map.of(3, new EdgePropertyValue("PENDING", t, t)), t, t));
            edges.add(new EdgeUpsert("pay_y" + i, payment, acc,
                    String.valueOf(1000 + (i + 13) % 50), Map.of(3, new EdgePropertyValue("SUCCESS", t, t)), t, t));
        }
        for (int i = 0; i < 100; i++) {
            edges.add(new EdgeUpsert("big_c" + i, payment, "150", String.valueOf(2000 + i),
                    Map.of(3, new EdgePropertyValue("SUCCESS", t, t)), t, t));
        }
        // shared IPs: each links to a deterministic 3-80 accounts (IP -> Account)
        for (int i = 0; i < 60; i++) {
            String ipId = "10.99." + (i / 100) + "." + (i % 100);
            int links = 3 + ((i * 37) % 78);
            for (int k = 0; k < links; k++) {
                String acc = accounts.get((i * 29 + k * 41) % accounts.size());
                // the id is the pair, so a link that is generated twice is one edge, not two
                edges.add(new EdgeUpsert(ipId + ":" + acc, ipToAccount, ipId, acc,
                        Map.of(3, new EdgePropertyValue(t, t, t)), t, t));
            }
        }
        // shared Phones: each links to a deterministic 2-10 accounts (Phone -> Account)
        for (int i = 0; i < 24; i++) {
            String phoneId = "+86-138-" + (60000 + i);
            int links = 2 + ((i * 23) % 9);
            for (int k = 0; k < links; k++) {
                String acc = accounts.get((i * 17 + k * 31) % accounts.size());
                edges.add(new EdgeUpsert(phoneId + ":" + acc, phoneToAccount, phoneId, acc,
                        Map.of(3, new EdgePropertyValue(t, t, t)), t, t));
            }
        }
        // explicit multi-account examples: 10.99.255.1 -> {1000,1001}, 10.99.255.2 -> {2000,2001,2002}
        edges.add(new EdgeUpsert("10.99.255.1:1000", ipToAccount, "10.99.255.1", "1000",
                Map.of(3, new EdgePropertyValue(t, t, t)), t, t));
        edges.add(new EdgeUpsert("10.99.255.1:1001", ipToAccount, "10.99.255.1", "1001",
                Map.of(3, new EdgePropertyValue(t, t, t)), t, t));
        edges.add(new EdgeUpsert("10.99.255.2:2000", ipToAccount, "10.99.255.2", "2000",
                Map.of(3, new EdgePropertyValue(t, t, t)), t, t));
        edges.add(new EdgeUpsert("10.99.255.2:2001", ipToAccount, "10.99.255.2", "2001",
                Map.of(3, new EdgePropertyValue(t, t, t)), t, t));
        edges.add(new EdgeUpsert("10.99.255.2:2002", ipToAccount, "10.99.255.2", "2002",
                Map.of(3, new EdgePropertyValue(t, t, t)), t, t));
        // +86-138-69999 -> {1001,1002,1003}, +86-138-69998 -> {2003,2004,2005}
        edges.add(new EdgeUpsert("+86-138-69999:1001", phoneToAccount, "+86-138-69999", "1001",
                Map.of(3, new EdgePropertyValue(t, t, t)), t, t));
        edges.add(new EdgeUpsert("+86-138-69999:1002", phoneToAccount, "+86-138-69999", "1002",
                Map.of(3, new EdgePropertyValue(t, t, t)), t, t));
        edges.add(new EdgeUpsert("+86-138-69999:1003", phoneToAccount, "+86-138-69999", "1003",
                Map.of(3, new EdgePropertyValue(t, t, t)), t, t));
        edges.add(new EdgeUpsert("+86-138-69998:2003", phoneToAccount, "+86-138-69998", "2003",
                Map.of(3, new EdgePropertyValue(t, t, t)), t, t));
        edges.add(new EdgeUpsert("+86-138-69998:2004", phoneToAccount, "+86-138-69998", "2004",
                Map.of(3, new EdgePropertyValue(t, t, t)), t, t));
        edges.add(new EdgeUpsert("+86-138-69998:2005", phoneToAccount, "+86-138-69998", "2005",
                Map.of(3, new EdgePropertyValue(t, t, t)), t, t));
        // several payments between the same sender and receiver, the way a real account has them
        // (135 pays 1000..1003 and 1001 pays 1002/1008/1014 more than once; each edge id is unique)
        String[][] repeated = {
                {"135", "1000"}, {"135", "1001"}, {"135", "1002"}, {"135", "1003"},
                {"1001", "1002"}, {"1001", "1008"}, {"1001", "1014"}};
        for (int p = 0; p < repeated.length; p++) {
            String source = repeated[p][0];
            String target = repeated[p][1];
            int extra = 5 + (p % 6); // 5..10 more payments for this pair
            for (int k = 0; k < extra; k++) {
                edges.add(new EdgeUpsert("pay_rep_" + source + "_" + target + "_" + k, payment,
                        source, target,
                        Map.of(3, new EdgePropertyValue(k % 3 == 0 ? "PENDING" : "SUCCESS", t, t)), t, t));
            }
        }
        assertNoFailures(upsertEdgesChunked(edgeStore, edges));
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

    /** Deterministic name pools for the {@code firstName}/{@code lastName} display properties. */
    private static final String[] FIRST_NAMES = {
            "Ada", "Alan", "Grace", "Linus", "Barbara", "Ken", "Margaret", "Dennis", "Radia", "Vint"};
    private static final String[] LAST_NAMES = {
            "Lovelace", "Turing", "Hopper", "Torvalds", "Liskov", "Thompson", "Hamilton", "Ritchie", "Perlman", "Cerf"};

    private static Map<Integer, VertexPropertyValue> accountProps(String id, long t) {
        Map<Integer, VertexPropertyValue> props = new LinkedHashMap<>();
        props.put(1, new VertexPropertyValue("ACTIVE", t));
        props.put(2, new VertexPropertyValue("savings", t));
        props.put(3, new VertexPropertyValue("addr-" + id, t));
        props.put(4, new VertexPropertyValue(List.of("Personal", "Business"), t));
        props.put(5, new VertexPropertyValue(Map.of("174", "open", "175", "kyc"), t));
        props.put(6, new VertexPropertyValue(firstName(id), t)); // display property
        props.put(7, new VertexPropertyValue(lastName(id), t));  // display property
        props.put(13, new VertexPropertyValue(accountVector(id), t)); // the HNSW-indexed profile
        return props;
    }

    /**
     * The 8-dimensional behaviour profile of a demo account, in one of three spending clusters
     * (cash-like, card-like, mixed) with a deterministic jitter per account - so
     * {@code g.V(id,'Account').nearest(k)} returns the accounts of one cluster, and the same load
     * twice gives the same answer. The RocksDB loader generates the same shape (see
     * {@code AccountProfiles}).
     */
    private static List<Double> accountVector(String id) {
        int hash = Math.floorMod(id.hashCode(), 100_000);
        double even = switch (hash % 3) { case 0 -> 0.9; case 1 -> 0.1; default -> 0.5; };
        double odd = switch (hash % 3) { case 0 -> 0.1; case 1 -> 0.9; default -> 0.5; };
        List<Double> vector = new ArrayList<>(8);
        for (int i = 0; i < 8; i++) {
            double jitter = ((hash / (i + 1)) % 100) / 1000.0;
            vector.add(Math.round(((i % 2 == 0 ? even : odd) + jitter) * 1000) / 1000.0);
        }
        return vector;
    }

    /** Deterministic first name derived from the vertex id, so the UI shows readable node labels. */
    static String firstName(String id) {
        return FIRST_NAMES[Math.floorMod(id.hashCode(), FIRST_NAMES.length)];
    }

    /** Deterministic last name derived from the vertex id. */
    static String lastName(String id) {
        return LAST_NAMES[Math.floorMod(id.hashCode() / FIRST_NAMES.length, LAST_NAMES.length)];
    }

    /**
     * The IP address an IP vertex is identified by: the value itself, so nothing has to map an id to
     * the address it stands for (and an IP id can never be mistaken for a phone number).
     */
    private static String ipAddress(int accountIndex, int index) {
        return "10.20." + (31 + index) + "." + (accountIndex + 1);
    }

    /** The phone number a Phone vertex is identified by (also the value itself). */
    private static String phoneNumber(int accountIndex, int index) {
        return "+86-138-" + (index == 0 ? 10000 + accountIndex : 20000 + accountIndex);
    }

    private static String bigIpAddress(int accountIndex, int index) {
        return "10.30." + (accountIndex % 250) + "." + (index + 1);
    }

    private static String bigPhoneNumber(int accountIndex, int index) {
        return "+86-139-" + (100000 + accountIndex * 10 + index);
    }

    private static Map<Integer, VertexPropertyValue> ipProps(String address, long t) {
        Map<Integer, VertexPropertyValue> props = new LinkedHashMap<>();
        props.put(1, new VertexPropertyValue(address, t));   // display property: the address, which is the id
        return props;
    }

    private static Map<Integer, VertexPropertyValue> phoneProps(String number, long t) {
        Map<Integer, VertexPropertyValue> props = new LinkedHashMap<>();
        int dash = number.indexOf('-');
        // cnCode is the country code before the first dash; phoneNumber is the national part after it, so
        // the display label joins "+86" and "138-20043" without repeating the country code the id carries
        props.put(1, new VertexPropertyValue(dash > 0 ? number.substring(0, dash) : "", t)); // cnCode
        props.put(2, new VertexPropertyValue(dash > 0 ? number.substring(dash + 1) : number, t)); // phoneNumber
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
