package com.pengcheng.phoenixgraph.http.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Aerospike storage settings for the HTTP service.
 *
 * <p>{@code schema} / {@code vertexSet} describe the primary graph (used by the
 * write endpoints); {@code graphs} declares additional graphs served by the same
 * instance, so one process can query several graphs that live in different
 * Aerospike sets (the UI lists all of them).
 */
@ConfigurationProperties(prefix = "phoenixgraph.storage")
public record StorageProperties(String host, int port, String namespace, String vertexSet, String schema,
                                String rocksdbRoot, String schemaDir, List<GraphProperties> graphs,
                                List<String> seeds) {

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
        graphs = graphs == null ? List.of() : List.copyOf(graphs);
        seeds = seeds == null ? List.of() : List.copyOf(seeds);
    }

    /**
     * The Aerospike seeds to start from: the configured list, or {@code host:port} when none is given.
     * A seed only bootstraps cluster discovery; once connected the client learns every node.
     */
    public List<String> seedHosts() {
        List<String> configured = new ArrayList<>();
        for (String seed : seeds) {
            if (seed != null && !seed.isBlank()) {
                configured.add(seed.trim());
            }
        }
        return configured.isEmpty() ? List.of(host + ":" + port) : List.copyOf(configured);
    }

    /**
     * Every graph this instance serves, the primary one first. Entries without a
     * schema are ignored, and a name configured twice keeps its first definition.
     */
    public List<GraphProperties> graphDefinitions() {
        List<GraphProperties> definitions = new ArrayList<>();
        if (schema != null && !schema.isBlank()) {
            definitions.add(new GraphProperties(schema, vertexSet));
        }
        for (GraphProperties graph : graphs) {
            if (graph == null || graph.schema() == null || graph.schema().isBlank()) {
                continue;
            }
            if (definitions.stream().anyMatch(d -> d.schema().equals(graph.schema()))) {
                continue;
            }
            definitions.add(new GraphProperties(graph.schema(),
                    graph.vertexSet() == null || graph.vertexSet().isBlank() ? vertexSet : graph.vertexSet()));
        }
        return List.copyOf(definitions);
    }
}
