package com.pengcheng.phoenixgraph.integration;

import com.aerospike.client.AerospikeClient;
import com.pengcheng.phoenixgraph.batchload.BatchLoadResult;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.VertexSchema;
import com.pengcheng.phoenixgraph.storage.WriteOutcome;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import com.pengcheng.phoenixgraph.storage.aerospike.EdgeUpsert;
import com.pengcheng.phoenixgraph.storage.aerospike.StoredEdgeValue;
import com.pengcheng.phoenixgraph.storage.aerospike.StoredVertex;
import com.pengcheng.phoenixgraph.storage.aerospike.VertexPropertyValue;
import com.pengcheng.phoenixgraph.storage.aerospike.VertexUpsert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end integration test against a real Aerospike node. Skipped when no
 * Aerospike is reachable at the configured host/port.
 */
class AerospikeIntegrationTest {

    @Test
    void importsGraphAndVerifies(@TempDir Path dir) throws Exception {
        IntegrationConfig config = IntegrationConfig.fromEnv();
        AerospikeClient client = config.connectOrNull();
        assumeTrue(client != null,
                "Aerospike not available at " + config.host() + ":" + config.port()
                        + ", skipping integration test");
        try (client) {
            AerospikeVertexStore vertexStore = new AerospikeVertexStore(client, config.namespace(), config.vertexSet());
            AerospikeEdgeStore edgeStore = new AerospikeEdgeStore(client, config.namespace(), config.vertexSet());

            new TestDataGenerator().generate(dir);
            DataImporter importer = new DataImporter(vertexStore, edgeStore, TestDataGenerator.schema());

            BatchLoadResult vertices = importer.importVertices(dir.resolve("vertices.csv"));
            BatchLoadResult edges = importer.importEdges(dir.resolve("edges.csv"));

            assertEquals(4, vertices.total());
            assertEquals(4, vertices.succeeded());
            assertEquals(2, edges.total());
            assertEquals(2, edges.succeeded());

            new GraphVerifier(vertexStore, edgeStore).verify();
        }
    }

    @Test
    void batchVertexWriteMergesDuplicatesAndBatchEdgeWriteAggregates() {
        IntegrationConfig config = IntegrationConfig.fromEnv();
        AerospikeClient client = config.connectOrNull();
        assumeTrue(client != null,
                "Aerospike not available at " + config.host() + ":" + config.port()
                        + ", skipping integration test");
        try (client) {
            AerospikeVertexStore vertexStore = new AerospikeVertexStore(client, config.namespace(), config.vertexSet());
            AerospikeEdgeStore edgeStore = new AerospikeEdgeStore(client, config.namespace(), config.vertexSet());
            GraphSchema schema = TestDataGenerator.schema();
            VertexSchema account = schema.vertexSchema("Account");
            EdgeSchema payment = schema.edgeSchema("Payment");
            long t = System.currentTimeMillis();

            // Duplicate vertex id in one batch: newest property lastModifiedTime must win.
            String vid = "merge-" + t;
            List<VertexUpsert> upserts = List.of(
                    new VertexUpsert(vid, account, Map.of(1, new VertexPropertyValue("OLD", t - 1000)), t - 1000, t - 1000),
                    new VertexUpsert(vid, account, Map.of(1, new VertexPropertyValue("NEW", t)), t, t));
            List<WriteOutcome> vertexOutcomes = vertexStore.upsertVertices(upserts);
            assertEquals(1, vertexOutcomes.size());
            assertEquals(WriteOutcome.SUCCESS, vertexOutcomes.get(0));
            StoredVertex stored = vertexStore.readVertex(vid);
            assertEquals("NEW", stored.properties().get(1).value());

            // Multiple out-edges sharing one source are aggregated into the source's out-bin.
            String a = "agg-a-" + t, b = "agg-b-" + t, c = "agg-c-" + t;
            List<EdgeUpsert> edges = List.of(
                    new EdgeUpsert("agg-e1", payment, a, b, Map.of(), t, t),
                    new EdgeUpsert("agg-e2", payment, a, c, Map.of(), t, t));
            List<WriteOutcome> edgeOutcomes = edgeStore.upsertEdges(edges);
            assertEquals(2, edgeOutcomes.size());
            assertEquals(WriteOutcome.SUCCESS, edgeOutcomes.get(0));
            assertEquals(WriteOutcome.SUCCESS, edgeOutcomes.get(1));
            List<StoredEdgeValue> out = edgeStore.getEdges(a, 1, AerospikeEdgeStore.EDGE_DIR_OUT);
            assertEquals(2, out.size());
            assertEquals(1, edgeStore.getEdges(b, 1, AerospikeEdgeStore.EDGE_DIR_IN).size());
            assertEquals(1, edgeStore.getEdges(c, 1, AerospikeEdgeStore.EDGE_DIR_IN).size());
        }
    }
}
