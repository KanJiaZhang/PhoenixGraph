package com.pengcheng.phoenixgraph.http.service;

import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchemaManagers;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;

import java.util.List;
import java.util.Objects;

/**
 * Resolves a graph name to the schema plus the stores bound to its vertex set.
 *
 * <p>An instance can serve several graphs living in different Aerospike sets
 * (see {@code phoenixgraph.storage.graphs}); {@link #schemas()} drives the UI's
 * graph dropdown.
 */
public interface GraphResolver {

    /** The binding for the given graph name; throws when the graph is unknown. */
    GraphBinding require(String graphName);

    /** Every graph served by this instance, in configuration order. */
    List<GraphSchema> schemas();

    /**
     * A resolver over a single pair of stores that looks schemas up by name in
     * {@link GraphSchemaManagers} (the shape used by tests and one-graph setups).
     */
    static GraphResolver ambient(AerospikeVertexStore vertexStore, AerospikeEdgeStore edgeStore) {
        Objects.requireNonNull(vertexStore, "vertexStore");
        Objects.requireNonNull(edgeStore, "edgeStore");
        return new GraphResolver() {
            @Override
            public GraphBinding require(String graphName) {
                if (graphName == null || graphName.isBlank()) {
                    throw new IllegalArgumentException("graphName must not be blank");
                }
                return new GraphBinding(GraphSchemaManagers.manager().load(graphName), vertexStore, edgeStore);
            }

            @Override
            public List<GraphSchema> schemas() {
                return List.copyOf(GraphSchemaManagers.manager().all());
            }
        };
    }
}
