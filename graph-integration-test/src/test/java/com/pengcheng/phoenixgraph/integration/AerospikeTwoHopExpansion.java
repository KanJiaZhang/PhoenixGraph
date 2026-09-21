package com.pengcheng.phoenixgraph.integration;

import com.aerospike.client.AerospikeClient;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.VertexSchema;
import com.pengcheng.phoenixgraph.schema.json.SchemaJsonCodec;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import com.pengcheng.phoenixgraph.storage.aerospike.EdgePropertyValue;
import com.pengcheng.phoenixgraph.storage.aerospike.EdgeUpsert;
import com.pengcheng.phoenixgraph.storage.aerospike.VertexPropertyValue;
import com.pengcheng.phoenixgraph.storage.aerospike.VertexUpsert;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The two-hop payment expansion of account 150, sized to order.
 *
 * <p>Account {@value #HUB} pays {@code firstHop} accounts ({@code f1..fK}), and each of those pays
 * {@code secondHop} accounts ({@code s1..sL}) - a bipartite expansion, so the traversal the user
 * asked about
 *
 * <pre>
 * g.V('150','Account').both('Payment').both('Payment')
 * </pre>
 *
 * reaches {@code K * (L + 1)} second-hop edges: every {@code f} has {@code L} outgoing payments plus
 * its one incoming payment from the hub, and {@code both} follows the incoming one back. Choosing
 * {@code K = 50}, {@code L = 99} puts the two-hop reach at exactly 5000 edges, and smaller pairs give
 * the 200 / 500 / 1000 / 2000 rungs the benchmark scales over.
 *
 * <p>Each hop also carries a few IP and Phone links (the hub owns one of each, and every {@code f}
 * account owns one of each), so the subgraph still has the neighbour kinds a real account has -
 * while staying "a few" next to the thousands of payment edges.
 *
 * <p>The loader writes into its own vertex set (it truncates it first), so it neither depends on nor
 * disturbs {@code http_demo_v3}.
 */
public final class AerospikeTwoHopExpansion {

    /** The account the expansion hangs off (the demo's star hub). */
    public static final String HUB = "150";

    /** Where the first-hop accounts start, so they never collide with the demo's 1000-1049. */
    private static final long FIRST_HOP_BASE = 1_000_000L;
    /** Where the second-hop accounts start. */
    private static final long SECOND_HOP_BASE = 2_000_000L;

    private AerospikeTwoHopExpansion() {
    }

    /** What a load wrote. */
    public record LoadResult(String vertexSet, int firstHop, int secondHop, int vertices,
                             int paymentEdges, int ipEdges, int phoneEdges) {

        /** The two-hop reach of {@code both('Payment').both('Payment')}: K * (L + 1). */
        public long bothReach() {
            return (long) firstHop * (secondHop + 1);
        }
    }

    /** The first-hop account id of index {@code i} (0-based). */
    public static long firstHopAccount(int i) {
        return FIRST_HOP_BASE + i;
    }

    /** The second-hop account id of index {@code j} (0-based). */
    public static long secondHopAccount(int j) {
        return SECOND_HOP_BASE + j;
    }

    /**
     * Loads the expansion into its own vertex set.
     *
     * @param firstHop  how many accounts the hub pays (the first hop's fan-out)
     * @param secondHop how many accounts each of those pays (the second hop's fan-out)
     */
    public static LoadResult load(AerospikeClient client, String namespace, String vertexSet,
                                  GraphSchema schema, int firstHop, int secondHop) {
        truncate(client, namespace, vertexSet);

        AerospikeVertexStore vertices = new AerospikeVertexStore(client, namespace, vertexSet);
        AerospikeEdgeStore edges = new AerospikeEdgeStore(client, namespace, vertexSet, schema);

        VertexSchema account = schema.vertexSchema("Account");
        VertexSchema ip = schema.vertexSchema("IP");
        VertexSchema phone = schema.vertexSchema("Phone");

        long now = System.currentTimeMillis();
        List<VertexUpsert> upserts = new ArrayList<>();
        upserts.add(accountVertex(HUB, account, "hub", now));
        for (int i = 0; i < firstHop; i++) {
            upserts.add(accountVertex(String.valueOf(firstHopAccount(i)), account, "f" + i, now));
        }
        for (int j = 0; j < secondHop; j++) {
            upserts.add(accountVertex(String.valueOf(secondHopAccount(j)), account, "s" + j, now));
        }
        upserts.add(ipVertex("ip_hub", ip, now));
        upserts.add(phoneVertex("ph_hub", phone, now));
        for (int i = 0; i < firstHop; i++) {
            upserts.add(ipVertex("ip_f" + i, ip, now));
            upserts.add(phoneVertex("ph_f" + i, phone, now));
        }
        vertices.upsertVertices(upserts);

        EdgeSchema payment = schema.edgeSchema("Payment");
        EdgeSchema accountToIp = schema.edgeSchema("AccountToIP");
        EdgeSchema accountToPhone = schema.edgeSchema("AccountToPhone");

        List<EdgeUpsert> edgeUpserts = new ArrayList<>();
        long edgeId = 1L;
        // first hop: the hub pays every f account
        for (int i = 0; i < firstHop; i++) {
            edgeUpserts.add(paymentEdge(edgeId++, payment, HUB, String.valueOf(firstHopAccount(i)), now));
        }
        // second hop: every f account pays every s account
        for (int i = 0; i < firstHop; i++) {
            for (int j = 0; j < secondHop; j++) {
                edgeUpserts.add(paymentEdge(edgeId++, payment, String.valueOf(firstHopAccount(i)),
                        String.valueOf(secondHopAccount(j)), now));
            }
        }
        // a few IP / Phone links per hop
        edgeUpserts.add(accountToIpEdge(1L, accountToIp, HUB, "ip_hub", now));
        edgeUpserts.add(accountToPhoneEdge(1L, accountToPhone, HUB, "ph_hub", now));
        for (int i = 0; i < firstHop; i++) {
            edgeUpserts.add(accountToIpEdge(2L + i, accountToIp, String.valueOf(firstHopAccount(i)),
                    "ip_f" + i, now));
            edgeUpserts.add(accountToPhoneEdge(2L + i, accountToPhone, String.valueOf(firstHopAccount(i)),
                    "ph_f" + i, now));
        }
        edges.upsertEdges(edgeUpserts);

        return new LoadResult(vertexSet, firstHop, secondHop, upserts.size(),
                firstHop + firstHop * secondHop, firstHop + 1, firstHop + 1);
    }

    private static VertexUpsert accountVertex(String id, VertexSchema schema, String name, long now) {
        Map<Integer, VertexPropertyValue> properties = new LinkedHashMap<>();
        properties.put(schema.property("accountStatus").id(), new VertexPropertyValue("ACTIVE", now));
        properties.put(schema.property("accountType").id(), new VertexPropertyValue("savings", now));
        properties.put(schema.property("firstName").id(), new VertexPropertyValue(name, now));
        properties.put(schema.property("lastName").id(), new VertexPropertyValue("acct", now));
        return new VertexUpsert(id, schema, properties, now, now);
    }

    private static VertexUpsert ipVertex(String id, VertexSchema schema, long now) {
        return new VertexUpsert(id, schema,
                Map.of(schema.property("ipAddress").id(), new VertexPropertyValue(id, now)), now, now);
    }

    private static VertexUpsert phoneVertex(String id, VertexSchema schema, long now) {
        Map<Integer, VertexPropertyValue> properties = new LinkedHashMap<>();
        properties.put(schema.property("cnCode").id(), new VertexPropertyValue("+86", now));
        properties.put(schema.property("phoneNumber").id(), new VertexPropertyValue(id, now));
        return new VertexUpsert(id, schema, properties, now, now);
    }

    private static EdgeUpsert paymentEdge(long edgeId, EdgeSchema schema, String source, String target, long now) {
        Map<Integer, EdgePropertyValue> properties = new LinkedHashMap<>();
        properties.put(schema.property("status").id(), new EdgePropertyValue("SUCCESS", now, now));
        properties.put(schema.property("type").id(), new EdgePropertyValue("transfer", now, now));
        properties.put(schema.property("amount").id(), new EdgePropertyValue(10.0d, now, now));
        properties.put(schema.property("currency").id(), new EdgePropertyValue("USD", now, now));
        return new EdgeUpsert(edgeId, schema, source, target, properties, now, now);
    }

    private static EdgeUpsert accountToIpEdge(long edgeId, EdgeSchema schema, String source, String target,
                                              long now) {
        Map<Integer, EdgePropertyValue> properties =
                Map.of(schema.property("loginTime").id(), new EdgePropertyValue(now, now, now));
        return new EdgeUpsert(edgeId, schema, source, target, properties, now, now);
    }

    private static EdgeUpsert accountToPhoneEdge(long edgeId, EdgeSchema schema, String source, String target,
                                                 long now) {
        Map<Integer, EdgePropertyValue> properties =
                Map.of(schema.property("linkedAt").id(), new EdgePropertyValue(now, now, now));
        return new EdgeUpsert(edgeId, schema, source, target, properties, now, now);
    }

    /** Truncates a set; the writes then start from scratch (a server may forbid truncating). */
    private static void truncate(AerospikeClient client, String namespace, String set) {
        try {
            client.truncate(new com.aerospike.client.policy.InfoPolicy(), namespace, set, null);
            Thread.sleep(200);
        } catch (com.aerospike.client.AerospikeException e) {
            // truncate is optional: the writes overwrite the records they generate anyway
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The payment schema the expansion is written with (the demo's {@code payment-graph.json}). */
    public static GraphSchema loadSchema() {
        Path fromResources = Path.of("..", "graph-http", "src", "main", "resources", "payment-graph.json");
        try {
            return new SchemaJsonCodec().fromJson(Files.readString(fromResources, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + fromResources.toAbsolutePath(), e);
        }
    }
}
