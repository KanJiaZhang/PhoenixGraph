package com.pengcheng.phoenixgraph.storage.rocksdb;

import com.pengcheng.phoenixgraph.schema.EdgeSchema;

import java.util.Map;
import java.util.Objects;

/**
 * An edge write.
 *
 * <p>Mirrors the Aerospike {@code EdgeUpsert}: one edge touches two vertices, so an upsert
 * writes the edge into the outgoing bin of {@code source} and the incoming bin of
 * {@code destination} (an undirected edge uses the single undirected bin of both).
 *
 * @param edgeId          edge id
 * @param edgeSchema      the edge class this edge conforms to
 * @param source          source vertex id
 * @param destination     destination vertex id
 * @param properties      property index -> value + per-property timestamps
 * @param createdTime     edge creation timestamp (epoch millis)
 * @param lastUpdatedTime edge last-update timestamp (epoch millis)
 */
public record RocksDbEdgeUpsert(Object edgeId, EdgeSchema edgeSchema, Object source, Object destination,
                                Map<Integer, RocksDbEdgeProperty> properties,
                                long createdTime, long lastUpdatedTime) {

    public RocksDbEdgeUpsert {
        Objects.requireNonNull(edgeId, "edgeId");
        Objects.requireNonNull(edgeSchema, "edgeSchema");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
        properties = Map.copyOf(Objects.requireNonNull(properties, "properties"));
    }
}
