package com.pengcheng.phoenixgraph.http.service;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.AerospikeException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pengcheng.phoenixgraph.fuzzy.FuzzyContext;
import com.pengcheng.phoenixgraph.fuzzy.FuzzyContexts;
import com.pengcheng.phoenixgraph.fuzzy.FuzzyDemo;
import com.pengcheng.phoenixgraph.fuzzy.FuzzyLink;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchemaManagers;
import com.pengcheng.phoenixgraph.schema.VertexSchema;
import com.pengcheng.phoenixgraph.schema.json.SchemaJsonCodec;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import com.pengcheng.phoenixgraph.storage.aerospike.StoredVertex;
import com.pengcheng.phoenixgraph.storage.aerospike.VertexPropertyValue;
import com.pengcheng.phoenixgraph.storage.aerospike.VertexUpsert;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Linking writes the fuzzy link node and the edge to it - and nothing else.
 *
 * <p>The context a caller links with is not a write request for the vertex: it may carry keys the
 * schema does not even have ({@code deviceId}), and its values may differ from the vertex's own. The
 * account must come out of a link exactly as it went in - same properties, same values, same
 * version - because a link is a statement about the vertex, not an update of it.
 *
 * <p>Runs against a real Aerospike node in its own vertex set, and is skipped when there is none.
 */
class FuzzyLinkingServiceIntegrationTest {

    private static final String GRAPH = "payment-graph";
    private static final String ACCOUNT = "link_probe_a1";

    @Test
    void linkingWritesTheLinkNodeAndEdgeAndLeavesTheAccountAsItWas() {
        AerospikeClient client = connectOrNull();
        assumeTrue(client != null, "Aerospike not available, skipping integration test");
        try (client) {
            String namespace = env("AEROSPIKE_NAMESPACE", "test");
            String set = env("AEROSPIKE_LINK_SET", "fuzzy_link_probe");
            GraphSchema schema = ensureSchemaRegistered();
            AerospikeVertexStore vertexStore = new AerospikeVertexStore(client, namespace, set);
            AerospikeEdgeStore edgeStore = new AerospikeEdgeStore(client, namespace, set, schema);
            truncate(client, namespace, set);

            // one account carrying the fragments the demo patterns read
            VertexSchema account = schema.vertexSchema("Account");
            long now = System.currentTimeMillis();
            Map<Integer, VertexPropertyValue> properties = new LinkedHashMap<>();
            properties.put(account.property("firstName").id(), new VertexPropertyValue("zhang", now));
            properties.put(account.property("username").id(), new VertexPropertyValue("zhang1988", now));
            properties.put(account.property("email").id(), new VertexPropertyValue("19880101@mail.ru", now));
            properties.put(account.property("phoneNumber").id(), new VertexPropertyValue("+8613800138001", now));
            properties.put(account.property("ipAddress").id(), new VertexPropertyValue("10.20.31.7", now));
            vertexStore.upsertVertices(List.of(new VertexUpsert(ACCOUNT, account, properties, now, now)));
            StoredVertex before = vertexStore.readVertex(ACCOUNT);
            assertNotNull(before, "the account was written");

            FuzzyPatternStore patterns = mock(FuzzyPatternStore.class);
            when(patterns.registryOf(GRAPH)).thenReturn(FuzzyDemo.demoRegistry());
            GraphRegistry graphs = new GraphRegistry(
                    List.of(new GraphBinding(schema, vertexStore, edgeStore)));
            FuzzyLinkingService linking = new FuzzyLinkingService(
                    new GraphService(vertexStore, edgeStore, schema, new ObjectMapper()), graphs, patterns);

            // a context that carries a key the schema does not have, and the account's own fragments
            FuzzyContext context = FuzzyContexts.fromJson("{\"email\":\"19880101@mail.ru\","
                    + "\"phone\":\"+8613800138001\",\"ip\":\"10.20.31.7\",\"username\":\"zhang1988\","
                    + "\"deviceId\":\"dev-1\",\"extraKey\":\"extra\"}");
            List<FuzzyLink> links = linking.link(GRAPH, ACCOUNT, context);
            assertFalse(links.isEmpty(), "the demo patterns produce a link for this context");

            // the link node and the edge to the account are there
            int linkEdgeId = schema.edgeSchema("FuzzyLinkedTo").id();
            for (FuzzyLink link : links) {
                assertNotNull(vertexStore.readVertex(link.linkVertexId()),
                        "link node " + link.linkVertexId());
                assertFalse(edgeStore.getEdges(link.linkVertexId(), linkEdgeId,
                        AerospikeEdgeStore.EDGE_DIR_OUT).isEmpty(),
                        "edge from " + link.linkVertexId() + " to " + ACCOUNT);
            }

            // ... and the account is exactly as it was: no context key became a property, no value moved
            StoredVertex after = vertexStore.readVertex(ACCOUNT);
            assertEquals(before.properties().keySet(), after.properties().keySet(),
                    "linking added no property to the account");
            for (Map.Entry<Integer, VertexPropertyValue> entry : before.properties().entrySet()) {
                assertEquals(entry.getValue().value(), after.properties().get(entry.getKey()).value(),
                        "property " + entry.getKey() + " keeps its value");
            }
            assertEquals(before.createdTime(), after.createdTime());
            assertEquals(before.lastUpdatedTime(), after.lastUpdatedTime(),
                    "the account's version did not move");
        }
    }

    // --- helpers ---

    private static GraphSchema ensureSchemaRegistered() {
        GraphSchema existing = GraphSchemaManagers.manager().get(GRAPH);
        if (existing != null) {
            return existing;
        }
        try (InputStream in = FuzzyLinkingServiceIntegrationTest.class.getResourceAsStream("/" + GRAPH + ".json")) {
            assertNotNull(in, "Missing " + GRAPH + ".json resource");
            GraphSchema schema = new SchemaJsonCodec()
                    .fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            GraphSchemaManagers.manager().register(schema);
            return schema;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load " + GRAPH + ".json", e);
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

    private static void truncate(AerospikeClient client, String namespace, String set) {
        try {
            client.truncate(new com.aerospike.client.policy.InfoPolicy(), namespace, set, null);
            Thread.sleep(300);
        } catch (AerospikeException e) {
            // truncate may be disabled on a server; the writes then land on top
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
