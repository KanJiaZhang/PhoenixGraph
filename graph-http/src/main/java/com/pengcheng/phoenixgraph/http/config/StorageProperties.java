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
                                List<GraphProperties> graphs) {

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
