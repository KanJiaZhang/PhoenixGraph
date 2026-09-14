package com.pengcheng.phoenixgraph.batchload;

import com.aerospike.client.AerospikeClient;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchemaManagers;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;

import java.nio.file.Path;

/**
 * Command-line entry point for CSV batch import.
 *
 * <p>Usage: {@code java BatchLoadMain <csv-file>}. Aerospike connection settings
 * are read from environment variables {@code AEROSPIKE_HOST} (default
 * {@code localhost}), {@code AEROSPIKE_PORT} (default {@code 3000}),
 * {@code AEROSPIKE_NAMESPACE} (default {@code test}) and
 * {@code AEROSPIKE_VERTEX_SET} (default {@code vertices}).
 */
public final class BatchLoadMain {

    private BatchLoadMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: BatchLoadMain <csv-file>");
            System.exit(1);
            return;
        }

        String host = System.getenv().getOrDefault("AEROSPIKE_HOST", "localhost");
        int port = Integer.parseInt(System.getenv().getOrDefault("AEROSPIKE_PORT", "3000"));
        String namespace = System.getenv().getOrDefault("AEROSPIKE_NAMESPACE", "test");
        String vertexSet = System.getenv().getOrDefault("AEROSPIKE_VERTEX_SET", "vertices");
        String graphSchema = System.getenv().getOrDefault("GRAPH_SCHEMA", "payment-graph");

        try (AerospikeClient client = new AerospikeClient(host, port)) {
            AerospikeVertexStore vertexStore = new AerospikeVertexStore(client, namespace, vertexSet);
            AerospikeEdgeStore edgeStore = new AerospikeEdgeStore(client, namespace, vertexSet);
            GraphSchema schema = GraphSchemaManagers.manager().load(graphSchema);

            CsvBatchLoader loader = new CsvBatchLoader(vertexStore, edgeStore, schema);
            BatchLoadResult result = loader.load(Path.of(args[0]));
            System.out.println("Imported " + result.total() + " " + result.type().toLowerCase() + "s: "
                    + result.succeeded() + " succeeded, " + result.conflicts() + " conflicts, "
                    + result.failed() + " failed");
        }
    }
}
