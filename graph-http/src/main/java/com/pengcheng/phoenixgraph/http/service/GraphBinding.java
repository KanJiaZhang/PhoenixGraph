package com.pengcheng.phoenixgraph.http.service;

import com.aerospike.client.AerospikeClient;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;

/**
 * A graph this instance can query: its {@link GraphSchema} and the stores bound
 * to the Aerospike vertex set holding its data.
 *
 * <p>The client/namespace are only needed to reach key-value state that is not a
 * vertex or an edge - today the HNSW metadata record (entry points). They may be
 * {@code null} in tests that only exercise the graph itself.
 */
public record GraphBinding(GraphSchema schema, AerospikeVertexStore vertexStore, AerospikeEdgeStore edgeStore,
                           AerospikeClient client, String namespace) {

    public GraphBinding(GraphSchema schema, AerospikeVertexStore vertexStore, AerospikeEdgeStore edgeStore) {
        this(schema, vertexStore, edgeStore, null, null);
    }

    /** The graph name (its schema name). */
    public String name() {
        return schema.name();
    }

    /** Whether vector search (HNSW) can be opened for this binding. */
    public boolean hasKeyValueAccess() {
        return client != null && namespace != null;
    }
}
