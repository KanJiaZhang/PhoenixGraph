package com.pengcheng.phoenixgraph.http.config;

import com.aerospike.client.AerospikeClient;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchemaManagers;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Aerospike storage stores as Spring beans.
 */
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {

    @Bean(destroyMethod = "close")
    public AerospikeClient aerospikeClient(StorageProperties properties) {
        return new AerospikeClient(properties.host(), properties.port());
    }

    @Bean
    public AerospikeVertexStore vertexStore(AerospikeClient client, StorageProperties properties) {
        return new AerospikeVertexStore(client, properties.namespace(), properties.vertexSet());
    }

    @Bean
    public GraphSchema graphSchema(StorageProperties properties) {
        return loadSchema(properties.schema());
    }

    @Bean
    public AerospikeEdgeStore edgeStore(AerospikeClient client, StorageProperties properties, GraphSchema graphSchema) {
        return new AerospikeEdgeStore(client, properties.namespace(), properties.vertexSet(), graphSchema);
    }

    private GraphSchema loadSchema(String name) {
        if (name != null && !name.isBlank()) {
            try {
                GraphSchema loaded = GraphSchemaManagers.manager().load(name);
                if (loaded != null) {
                    return loaded;
                }
            } catch (RuntimeException e) {
                // Missing schema file: fall through to the empty schema below.
            }
        }
        // Empty schema: edge degree limits default to unlimited and edge schema
        // lookups fail with a clear error at write time.
        return new GraphSchema(0, "unconfigured", null);
    }
}
