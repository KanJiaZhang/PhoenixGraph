package com.pengcheng.phoenixgraph.http.config;

import com.aerospike.client.AerospikeClient;
import com.pengcheng.phoenixgraph.http.service.GraphBinding;
import com.pengcheng.phoenixgraph.http.service.GraphRegistry;
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
import java.util.ArrayList;
import java.util.List;

/**
 * Wires the Aerospike storage stores as Spring beans and loads the configured
 * graph schemas (from the working directory first, then from the classpath).
 *
 * <p>The primary graph ({@code phoenixgraph.storage.schema} / {@code .vertex-set})
 * backs the write endpoints; {@code phoenixgraph.storage.graphs} adds further
 * graphs served by the same instance, each with its own vertex set.
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
        List<GraphProperties> definitions = properties.graphDefinitions();
        if (!definitions.isEmpty()) {
            return loadSchema(definitions.get(0).schema());
        }
        // Empty schema: edge degree limits default to unlimited and edge schema
        // lookups fail with a clear error at write time.
        return new GraphSchema(0, "unconfigured", null);
    }

    @Bean
    public AerospikeEdgeStore edgeStore(AerospikeClient client, StorageProperties properties, GraphSchema graphSchema) {
        return new AerospikeEdgeStore(client, properties.namespace(), properties.vertexSet(), graphSchema);
    }

    /**
     * Every graph this instance can query, each bound to its own vertex set. All
     * schemas are also registered with {@link GraphSchemaManagers} so schema-aware
     * storage code can look them up by name.
     */
    @Bean
    public GraphRegistry graphRegistry(AerospikeClient client, StorageProperties properties) {
        List<GraphBinding> bindings = new ArrayList<>();
        for (GraphProperties definition : properties.graphDefinitions()) {
            GraphSchema schema = loadSchema(definition.schema());
            bindings.add(new GraphBinding(schema,
                    new AerospikeVertexStore(client, properties.namespace(), definition.vertexSet()),
                    new AerospikeEdgeStore(client, properties.namespace(), definition.vertexSet(), schema),
                    client, properties.namespace()));
        }
        return new GraphRegistry(bindings);
    }

    /** Loads a schema from the working directory first, then from the classpath. */
    private GraphSchema loadSchema(String name) {
        try {
            GraphSchema registered = GraphSchemaManagers.manager().get(name);
            if (registered != null) {
                return registered;
            }
            return GraphSchemaManagers.manager().load(name);
        } catch (RuntimeException missingOnDisk) {
            GraphSchema fromClasspath = loadFromClasspath(name);
            if (fromClasspath != null) {
                GraphSchemaManagers.manager().register(fromClasspath);
                return fromClasspath;
            }
            throw missingOnDisk;
        }
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
