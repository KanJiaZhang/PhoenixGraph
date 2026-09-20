package com.pengcheng.phoenixgraph.integration;

import com.aerospike.client.AerospikeClient;
import com.pengcheng.phoenixgraph.metrics.GraphMetrics;
import com.pengcheng.phoenixgraph.metrics.Metrics;
import com.pengcheng.phoenixgraph.query.aerospike.AerospikeGraphQuery;
import com.pengcheng.phoenixgraph.query.storage.EdgeQueryResult;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.json.SchemaJsonCodec;
import com.pengcheng.phoenixgraph.storage.TimeRange;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.EdgePropertyValue;
import com.pengcheng.phoenixgraph.storage.aerospike.EdgeUpsert;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;

import com.pengcheng.phoenixgraph.storage.aerospike.VertexUpsert;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The creation-time window travels to the store, which drops an edge outside it while it walks the bin.
 *
 * <p>The demo data cannot show this (every edge of the payment demo carries the same timestamp, so a
 * window either holds all of them or none), which is why this test writes its own three edges - one day
 * apart - into a scratch set. It then reads them twice: once through the storage layer's read record
 * (where {@code decoded} and {@code filtered} show that the edges outside the window were never built)
 * and once through the query layer's windowed read (where the answer itself is checked).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AerospikeEdgeWindowPushdownTest {

    private static final String SET = "payment_window_probe";
    private static final long DAY = 24 * 60 * 60 * 1000L;
    private static final long T0 = 1_700_000_000_000L;

    private AerospikeClient client;
    private String namespace;
    private GraphSchema schema;
    private AerospikeEdgeStore edgeStore;
    private GraphMetrics metrics;
    private AerospikeGraphQuery graphQuery;

    @BeforeAll
    void seedThreeEdgesADayApart() {
        IntegrationConfig config = IntegrationConfig.fromEnv();
        client = config.connectOrNull();
        assumeTrue(client != null, "Aerospike not available at " + config.host() + ":" + config.port());
        namespace = config.namespace();
        schema = loadSchema();
        metrics = new GraphMetrics("payment-window-probe");
        AerospikeVertexStore vertices = new AerospikeVertexStore(client, namespace, SET).metrics(metrics);
        edgeStore = new AerospikeEdgeStore(client, namespace, SET, schema).metrics(metrics);
        graphQuery = new AerospikeGraphQuery(vertices, edgeStore, schema);

        EdgeSchema payment = schema.edgeSchema("Payment");
        // two accounts of the demo's shape, so the schema's properties apply unchanged
        vertices.upsertVertices(List.of(
                new VertexUpsert("990001", schema.vertexSchema("Account"), Map.of(), T0, T0),
                new VertexUpsert("990002", schema.vertexSchema("Account"), Map.of(), T0, T0)));
        for (EdgeUpsert edge : List.of(
                payment("w1", T0),
                payment("w2", T0 + DAY),
                payment("w3", T0 + 2 * DAY))) {
            edgeStore.upsertEdge(edge);
        }
    }

    private EdgeUpsert payment(String edgeId, long createdTime) {
        return new EdgeUpsert(edgeId, schema.edgeSchema("Payment"), "990001", "990002",
                Map.of(3, new EdgePropertyValue("SUCCESS", createdTime, createdTime)), createdTime,
                createdTime);
    }

    @AfterAll
    void close() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void aWindowLeavesTheEdgesOutsideItUnbuilt() {
        long before = metrics.value(Metrics.EDGE_DECODED);
        long filteredBefore = metrics.value(Metrics.EDGE_FILTERED);

        // two of the three edges are inside [T0, T0 + DAY]
        AerospikeEdgeStore.EdgeRead read = AerospikeEdgeStore.EdgeRead.all().withWindow(TimeRange.of(T0, T0 + DAY));
        List<com.pengcheng.phoenixgraph.storage.aerospike.StoredEdgeValue> edges = edgeStore.getEdgesFiltered(
                "990001", schema.edgeSchema("Payment").id(), AerospikeEdgeStore.EDGE_DIR_OUT, read);

        assertEquals(2, edges.size(), "the window keeps the two edges inside it");
        assertEquals(2, metrics.value(Metrics.EDGE_DECODED) - before, "only those two were built");
        assertEquals(1, metrics.value(Metrics.EDGE_FILTERED) - filteredBefore, "the third was walked past");
    }

    @Test
    void theQueryLayerPushesTheSameWindow() {
        AerospikeEdgeStore.EdgeRead read = AerospikeEdgeStore.EdgeRead.all().withWindow(TimeRange.of(T0, T0));
        List<EdgeQueryResult> edges = graphQuery.readEdgesWithin("990001", "Account", "Payment", EdgeSide.OUT,
                TimeRange.of(T0, T0), List.of("status"));

        assertEquals(1, edges.size(), "a one-millisecond window holds the first edge alone");
        assertEquals("w1", edges.get(0).edgeId());
        assertEquals("SUCCESS", edges.get(0).properties().get("status"));
        assertEquals(1, edgeStore.getEdgesFiltered("990001", schema.edgeSchema("Payment").id(),
                AerospikeEdgeStore.EDGE_DIR_OUT, read).size());

        // and an unbounded window is the plain read: all three edges, unchanged
        assertEquals(3, graphQuery.readEdgesWithin("990001", "Account", "Payment", EdgeSide.OUT,
                TimeRange.ALL, List.of()).size());
    }

    private static GraphSchema loadSchema() {
        for (String candidate : List.of("../graph-http/src/main/resources/payment-graph.json",
                "graph-http/src/main/resources/payment-graph.json")) {
            Path file = Path.of(candidate);
            if (Files.isReadable(file)) {
                try {
                    return new SchemaJsonCodec().fromJson(Files.readString(file, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new IllegalStateException("Cannot read " + file, e);
                }
            }
        }
        throw new IllegalStateException("payment-graph.json not found");
    }
}
