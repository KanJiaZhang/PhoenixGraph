package com.pengcheng.phoenixgraph.http.config;

import com.aerospike.client.AerospikeClient;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchemaManagers;
import com.pengcheng.phoenixgraph.schema.json.SchemaJsonCodec;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Wires the Aerospike storage stores as Spring beans and loads the configured
 * graph schema (from the working directory first, then from the classpath).
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
        String name = properties.schema();
        if (name != null && !name.isBlank()) {
            try {
                return GraphSchemaManagers.manager().load(name);
            } catch (RuntimeException missingOnDisk) {
                GraphSchema fromClasspath = loadFromClasspath(name);
                if (fromClasspath != null) {
                    GraphSchemaManagers.manager().register(fromClasspath);
                    return fromClasspath;
                }
            }
        }
        // Empty schema: edge degree limits default to unlimited and edge schema
        // lookups fail with a clear error at write time.
        return new GraphSchema(0, "unconfigured", null);
    }

    @Bean
    public AerospikeEdgeStore edgeStore(AerospikeClient client, StorageProperties properties, GraphSchema graphSchema) {
        return new AerospikeEdgeStore(client, properties.namespace(), properties.vertexSet(), graphSchema);
    }

    private GraphSchema loadFromClasspath(String name) {
        String resource = "/" + name + ".json";
        try (InputStream in = StorageConfig.class.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            return new SchemaJsonCodec().fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
    }
}
