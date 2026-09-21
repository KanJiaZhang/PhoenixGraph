package com.pengcheng.phoenixgraph.integration;

import com.aerospike.client.AerospikeClient;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.storage.WriteOutcome;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import com.pengcheng.phoenixgraph.storage.aerospike.EdgePropertyValue;
import com.pengcheng.phoenixgraph.storage.aerospike.EdgeUpsert;
import com.pengcheng.phoenixgraph.storage.aerospike.StoredEdgeValue;
import com.pengcheng.phoenixgraph.storage.aerospike.StoredVertex;
import com.pengcheng.phoenixgraph.storage.aerospike.VertexPropertyValue;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A property's {@code lastUpdatedTime} is its version: the stored value must end up being the one
 * with the newest timestamp, no matter in which order the writes arrive.
 *
 * <p>This is the rule the whole write path rests on - a replayed or delayed write must not roll a
 * value back - and it is checked here against the real store, on both a vertex property and an edge
 * property, in both arrival orders:
 *
 * <pre>
 *   newer first, then older  -> the older one is refused, the stored value stays the newer one
 *   older first, then newer  -> the newer one replaces it
 *   an equal timestamp       -> not newer, so the stored value stays as well
 * </pre>
 *
 * <p>The test writes into its own vertex set and truncates it first, so it neither depends on nor
 * disturbs any other data.
 */
class LastWriteWinsIntegrationTest {

    /** Own vertex set so the test never depends on (or disturbs) another test's data. */
    private static final String DEFAULT_SET = "last_write_wins_probe";

    private static final long OLD = 1_000_000L;
    private static final long NEW = 2_000_000L;
    private static final long NEWEST = 3_000_000L;

    @Test
    void aVertexPropertyKeepsTheValueWithTheNewestLastModifiedTime() {
        IntegrationConfig config = IntegrationConfig.fromEnv();
        AerospikeClient client = config.connectOrNull();
        assumeTrue(client != null,
                "Aerospike not available at " + config.host() + ":" + config.port() + ", skipping integration test");
        String set = env("AEROSPIKE_LWW_SET", DEFAULT_SET);
        try (client) {
            AerospikeVertexStore vertexStore = new AerospikeVertexStore(client, config.namespace(), set);
            truncate(client, config.namespace(), set);

            // newer first, then older: the older write is refused and nothing changes
            assertEquals(WriteOutcome.SUCCESS, upsert(vertexStore, "v-new-first", "new", NEW));
            assertEquals(WriteOutcome.CONFLICT, upsert(vertexStore, "v-new-first", "old", OLD));
            assertValue(vertexStore, "v-new-first", "new", NEW);
            // ... while a still newer one is accepted
            assertEquals(WriteOutcome.SUCCESS, upsert(vertexStore, "v-new-first", "newest", NEWEST));
            assertValue(vertexStore, "v-new-first", "newest", NEWEST);

            // older first, then newer: the newer write replaces it
            assertEquals(WriteOutcome.SUCCESS, upsert(vertexStore, "v-old-first", "old", OLD));
            assertEquals(WriteOutcome.SUCCESS, upsert(vertexStore, "v-old-first", "new", NEW));
            assertValue(vertexStore, "v-old-first", "new", NEW);

            // the same timestamp is not newer: the stored value stays
            assertEquals(WriteOutcome.CONFLICT, upsert(vertexStore, "v-old-first", "same-time", NEW));
            assertValue(vertexStore, "v-old-first", "new", NEW);

            // and a property written later than the vertex's own lastUpdatedTime still wins
            vertexStore.upsertVertex("v-old-first", Map.of(2, new VertexPropertyValue(11, OLD)), OLD, OLD);
            assertValue(vertexStore, "v-old-first", "new", NEW);
        }
    }

    @Test
    void anEdgePropertyKeepsTheValueWithTheNewestLastUpdatedTime() {
        IntegrationConfig config = IntegrationConfig.fromEnv();
        AerospikeClient client = config.connectOrNull();
        assumeTrue(client != null,
                "Aerospike not available at " + config.host() + ":" + config.port() + ", skipping integration test");
        String set = env("AEROSPIKE_LWW_SET", DEFAULT_SET);
        try (client) {
            GraphSchema schema = QueryDataSeeder.loadSchema();
            AerospikeEdgeStore edgeStore = new AerospikeEdgeStore(client, config.namespace(), set, schema);
            EdgeSchema payment = schema.edgeSchema("Payment");
            truncate(client, config.namespace(), set);

            // newer first, then older: the older one is ignored and the stored value stays
            assertEquals(WriteOutcome.SUCCESS, upsertEdge(edgeStore, payment, "e-new-first", "SUCCESS", NEW));
            // an edge write reports SUCCESS whenever the bin was written (the stale property is
            // logged, not turned into CONFLICT the way the vertex path does), so the value is what
            // this asserts
            assertTrue(upsertEdge(edgeStore, payment, "e-new-first", "PENDING", OLD) != WriteOutcome.FAILED,
                    "a stale edge write is not a failure");
            assertEdgeValue(edgeStore, payment, "e-new-first", "SUCCESS", NEW);

            // older first, then newer
            assertEquals(WriteOutcome.SUCCESS, upsertEdge(edgeStore, payment, "e-old-first", "PENDING", OLD));
            assertEquals(WriteOutcome.SUCCESS, upsertEdge(edgeStore, payment, "e-old-first", "SUCCESS", NEW));
            assertEdgeValue(edgeStore, payment, "e-old-first", "SUCCESS", NEW);

            // the same timestamp again: not newer, so the stored value stays
            assertTrue(upsertEdge(edgeStore, payment, "e-old-first", "FAILED", NEW) != WriteOutcome.FAILED,
                    "a same-timestamp edge write is not a failure");
            assertEdgeValue(edgeStore, payment, "e-old-first", "SUCCESS", NEW);

            // the two edges live in the same source record and do not overwrite each other
            assertEquals(2, edgeStore.getEdges("v1", payment.id(), AerospikeEdgeStore.EDGE_DIR_OUT).size());
        }
    }

    // --- helpers ---

    private static WriteOutcome upsert(AerospikeVertexStore store, String vertexId, String value, long time) {
        return store.upsertVertex(vertexId, Map.of(1, new VertexPropertyValue(value, time)), time, time);
    }

    private static void assertValue(AerospikeVertexStore store, String vertexId, String value, long time) {
        StoredVertex stored = store.readVertex(vertexId);
        assertNotNull(stored, "vertex " + vertexId + " should exist");
        VertexPropertyValue property = stored.properties().get(1);
        assertNotNull(property, "property 1 of " + vertexId + " should be stored");
        assertEquals(value, property.value(), "value of " + vertexId);
        assertEquals(time, property.lastModifiedTime(), "lastModifiedTime of " + vertexId);
    }

    private static WriteOutcome upsertEdge(AerospikeEdgeStore store, EdgeSchema payment, String edgeId,
                                           String status, long time) {
        EdgeUpsert edge = new EdgeUpsert(edgeId, payment, "v1", "v2",
                Map.of(3, new EdgePropertyValue(status, time, time)), time, time);
        List<WriteOutcome> outcomes = store.upsertEdges(List.of(edge));
        assertTrue(outcomes.size() == 1, "one edge, one outcome");
        return outcomes.get(0);
    }

    private static void assertEdgeValue(AerospikeEdgeStore store, EdgeSchema payment, String edgeId,
                                        String status, long time) {
        Map<String, StoredEdgeValue> byId = new LinkedHashMap<>();
        for (StoredEdgeValue edge : store.getEdges("v1", payment.id(), AerospikeEdgeStore.EDGE_DIR_OUT)) {
            byId.put(edge.edgeId(), edge);
        }
        StoredEdgeValue stored = byId.get(edgeId);
        assertNotNull(stored, "edge " + edgeId + " should be stored on v1, got " + byId.keySet());
        EdgePropertyValue property = stored.properties().get(3);
        assertNotNull(property, "property 3 of " + edgeId + " should be stored");
        assertEquals(status, property.value(), "status of " + edgeId);
        assertEquals(time, property.lastUpdatedTime(), "lastUpdatedTime of " + edgeId);
    }

    private static void truncate(AerospikeClient client, String namespace, String set) {
        try {
            client.truncate(new com.aerospike.client.policy.InfoPolicy(), namespace, set, null);
            Thread.sleep(300);
        } catch (com.aerospike.client.AerospikeException e) {
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
