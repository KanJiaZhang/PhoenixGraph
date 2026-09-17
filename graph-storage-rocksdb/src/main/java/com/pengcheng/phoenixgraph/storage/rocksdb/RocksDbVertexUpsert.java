package com.pengcheng.phoenixgraph.storage.rocksdb;

import com.pengcheng.phoenixgraph.schema.VertexSchema;

import java.util.Map;
import java.util.Objects;

/**
 * A vertex write: the properties to store with their versions plus the record
 * timestamps.
 *
 * <p>Mirrors the Aerospike {@code VertexUpsert}: inserting an absent vertex creates it,
 * updating an existing one replaces each property whose version is newer than the stored
 * one. Property ids are the schema's property indexes, which is what the stored record
 * is keyed by.
 *
 * @param vertexId        vertex id (the record key's id part)
 * @param vertexSchema    the vertex schema this vertex conforms to
 * @param properties      property index -> value + version
 * @param createdTime     vertex creation timestamp (epoch millis)
 * @param lastUpdatedTime vertex last-update timestamp (epoch millis)
 */
public record RocksDbVertexUpsert(Object vertexId, VertexSchema vertexSchema,
                                  Map<Integer, RocksDbPropertyValue> properties,
                                  long createdTime, long lastUpdatedTime) {

    public RocksDbVertexUpsert {
        Objects.requireNonNull(vertexId, "vertexId");
        Objects.requireNonNull(vertexSchema, "vertexSchema");
        properties = Map.copyOf(Objects.requireNonNull(properties, "properties"));
    }

    /** The vertex schema name (the table this vertex lives in). */
    public String vertexName() {
        return vertexSchema.name();
    }
}
