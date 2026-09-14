package com.pengcheng.phoenixgraph.http.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Aerospike storage settings for the HTTP service.
 */
@ConfigurationProperties(prefix = "phoenixgraph.storage")
public record StorageProperties(String host, int port, String namespace, String vertexSet, String schema) {

    public StorageProperties {
        if (host == null || host.isBlank()) {
            host = "localhost";
        }
        if (port == 0) {
            port = 3000;
        }
        if (namespace == null || namespace.isBlank()) {
            namespace = "test";
        }
        if (vertexSet == null || vertexSet.isBlank()) {
            vertexSet = "vertices";
        }
    }
}
