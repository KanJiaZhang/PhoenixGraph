package com.pengcheng.phoenixgraph.http.service;

import com.pengcheng.phoenixgraph.query.aerospike.AerospikeGraphQuery;
import com.pengcheng.phoenixgraph.query.storage.GraphQuery;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.GraphStorageType;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import com.aerospike.client.AerospikeClient;

import java.util.Objects;

/**
 * A graph this instance can query: its {@link GraphSchema} and everything needed to read it.
 *
 * <p>The read path is storage independent - {@link #graphQuery()} is what the Gremlin and
 * Cypher engines use, whichever backend the schema declares (Aerospike, RocksDB-Inline,
 * RocksDB-Edge). The Aerospike stores are kept alongside because the write endpoints and the
 * vector index are Aerospike features: a binding whose schema declares a RocksDB storage
 * carries {@code null} stores and reports {@link #hasAerospikeStores()} as false.
 *
 * <p>The client/namespace are only needed to reach key-value state that is not a vertex or an
 * edge - today the HNSW metadata record (entry points). They may be {@code null} in tests that
 * only exercise the graph itself.
 *
 * @param schema      the graph schema (which also declares the storage implementation)
 * @param vertexStore the Aerospike vertex store, or {@code null} for a RocksDB graph
 * @param edgeStore   the Aerospike edge store, or {@code null} for a RocksDB graph
 * @param client      the Aerospike client (HNSW metadata), or {@code null}
 * @param namespace   the Aerospike namespace (HNSW metadata), or {@code null}
 * @param graphQuery  the read path of this graph
 */
public record GraphBinding(GraphSchema schema, AerospikeVertexStore vertexStore, AerospikeEdgeStore edgeStore,
                           AerospikeClient client, String namespace, GraphQuery graphQuery) {

    public GraphBinding {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(graphQuery, "graphQuery");
    }

    /** An Aerospike-backed graph (the default storage). */
    public GraphBinding(GraphSchema schema, AerospikeVertexStore vertexStore, AerospikeEdgeStore edgeStore) {
        this(schema, vertexStore, edgeStore, null, null, aerospikeQuery(schema, vertexStore, edgeStore));
    }

    /** An Aerospike-backed graph with key-value access (vector index metadata). */
    public GraphBinding(GraphSchema schema, AerospikeVertexStore vertexStore, AerospikeEdgeStore edgeStore,
                        AerospikeClient client, String namespace) {
        this(schema, vertexStore, edgeStore, client, namespace,
                aerospikeQuery(schema, vertexStore, edgeStore));
    }

    /** A graph read through the given query, without Aerospike stores (a RocksDB graph). */
    public static GraphBinding of(GraphSchema schema, GraphQuery graphQuery) {
        return new GraphBinding(schema, null, null, null, null, graphQuery);
    }

    private static GraphQuery aerospikeQuery(GraphSchema schema, AerospikeVertexStore vertexStore,
                                             AerospikeEdgeStore edgeStore) {
        Objects.requireNonNull(vertexStore, "vertexStore");
        Objects.requireNonNull(edgeStore, "edgeStore");
        return new AerospikeGraphQuery(vertexStore, edgeStore, schema);
    }

    /** The graph name (its schema name). */
    public String name() {
        return schema.name();
    }

    /** The storage implementation serving this graph. */
    public GraphStorageType storageType() {
        return schema.storage().type();
    }

    /** Whether this graph is read from the Aerospike stores (the write endpoints need them). */
    public boolean hasAerospikeStores() {
        return vertexStore != null && edgeStore != null;
    }

    /** Whether vector search (HNSW) can be opened for this binding. */
    public boolean hasKeyValueAccess() {
        return client != null && namespace != null;
    }
}
