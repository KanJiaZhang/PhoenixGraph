package com.pengcheng.phoenixgraph.http.service;

import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;

/**
 * A graph this instance can query: its {@link GraphSchema} and the stores bound
 * to the Aerospike vertex set holding its data.
 */
public record GraphBinding(GraphSchema schema, AerospikeVertexStore vertexStore, AerospikeEdgeStore edgeStore) {

    /** The graph name (its schema name). */
    public String name() {
        return schema.name();
    }
}
