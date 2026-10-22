package com.pengcheng.phoenixgraph.batchload;

import com.aerospike.client.AerospikeClient;
import com.pengcheng.phoenixgraph.schema.FileSystemGraphSchemaManager;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;

import java.nio.file.Path;

/**
 * Command-line entry point for CSV batch import.
 *
 * <p>Usage: {@code java BatchLoadMain <csv-file> [schema-dir]}. Aerospike connection settings
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
            System.err.println("Usage: BatchLoadMain <csv-file> [schema-dir]");
            System.exit(1);
            return;
        }

        String host = System.getenv().getOrDefault("AEROSPIKE_HOST", "localhost");
        int port = Integer.parseInt(System.getenv().getOrDefault("AEROSPIKE_PORT", "3000"));
        String namespace = System.getenv().getOrDefault("AEROSPIKE_NAMESPACE", "test");
        String vertexSet = System.getenv().getOrDefault("AEROSPIKE_VERTEX_SET", "vertices");
        String graphSchema = System.getenv().getOrDefault("GRAPH_SCHEMA", "payment-graph");
        int batchSize = Integer.parseInt(System.getenv().getOrDefault("AEROSPIKE_BATCH_SIZE",
                String.valueOf(CsvBatchLoader.DEFAULT_BATCH_SIZE)));
        if (batchSize < 1) {
            throw new IllegalArgumentException("AEROSPIKE_BATCH_SIZE must be >= 1");
        }

        try (AerospikeClient client = new AerospikeClient(host, port)) {
            AerospikeVertexStore vertexStore = new AerospikeVertexStore(client, namespace, vertexSet);
            AerospikeEdgeStore edgeStore = new AerospikeEdgeStore(client, namespace, vertexSet);
            Path schemaDir = args.length > 1 ? Path.of(args[1]) : Path.of(".");
            GraphSchema schema = new FileSystemGraphSchemaManager(schemaDir).load(graphSchema);

            CsvBatchLoader loader = new CsvBatchLoader(vertexStore, edgeStore, schema, batchSize);
            BatchLoadResult result = loader.load(Path.of(args[0]));
            System.out.println("Imported " + result.total() + " " + result.type().toLowerCase() + "s: "
                    + result.succeeded() + " succeeded, " + result.conflicts() + " conflicts, "
                    + result.failed() + " failed");
        }
    }
}
