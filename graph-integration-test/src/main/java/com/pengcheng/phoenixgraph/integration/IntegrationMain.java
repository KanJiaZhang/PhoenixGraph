package com.pengcheng.phoenixgraph.integration;

import com.aerospike.client.AerospikeClient;
import com.pengcheng.phoenixgraph.batchload.BatchLoadResult;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Command-line runner for the end-to-end integration flow.
 */
public final class IntegrationMain {

    private IntegrationMain() {
    }

    public static void main(String[] args) throws Exception {
        IntegrationConfig config = IntegrationConfig.fromEnv();
        Path dir = Files.createTempDirectory("phoenixgraph-it");
        AerospikeClient client = config.connectOrNull();
        if (client == null) {
            System.err.println("Aerospike not available at " + config.host() + ":" + config.port());
            System.exit(1);
        }
        try (client) {
            AerospikeVertexStore vertexStore = new AerospikeVertexStore(client, config.namespace(), config.vertexSet());
            AerospikeEdgeStore edgeStore = new AerospikeEdgeStore(client, config.namespace(), config.vertexSet());

            new TestDataGenerator().generate(dir);
            DataImporter importer = new DataImporter(vertexStore, edgeStore, TestDataGenerator.schema());

            BatchLoadResult vertices = importer.importVertices(dir.resolve("vertices.csv"));
            BatchLoadResult edges = importer.importEdges(dir.resolve("edges.csv"));
            System.out.println("Vertices: " + vertices);
            System.out.println("Edges: " + edges);

            new GraphVerifier(vertexStore, edgeStore).verify();
            System.out.println("Verification passed");
        }
    }
}
