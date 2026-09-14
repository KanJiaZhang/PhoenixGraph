package com.pengcheng.phoenixgraph.integration;

import com.aerospike.client.AerospikeClient;
import com.pengcheng.phoenixgraph.batchload.BatchLoadResult;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

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
}
