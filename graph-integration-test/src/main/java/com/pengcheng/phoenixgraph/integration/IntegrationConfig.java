package com.pengcheng.phoenixgraph.integration;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.AerospikeException;

/**
 * Aerospike connection settings for the integration test.
 */
public record IntegrationConfig(String host, int port, String namespace, String vertexSet) {

    public static IntegrationConfig fromEnv() {
        return new IntegrationConfig(
                env("AEROSPIKE_HOST", "localhost"),
                Integer.parseInt(env("AEROSPIKE_PORT", "3000")),
                env("AEROSPIKE_NAMESPACE", "test"),
                env("AEROSPIKE_VERTEX_SET", "vertices"));
    }

    /**
     * Creates a client and connects to the cluster, or returns {@code null}
     * when the server is unreachable (the Aerospike client constructor throws
     * on a failed connection).
     */
    public AerospikeClient connectOrNull() {
        try {
            return new AerospikeClient(host, port);
        } catch (AerospikeException e) {
            return null;
        }
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
