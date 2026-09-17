package com.pengcheng.phoenixgraph.storage.rocksdb;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A vertex read back from RocksDB.
 *
 * @param vertexId        vertex id
 * @param vertexName      vertex schema name
 * @param properties      property index -> value + version (only the requested
 *                        properties, or every stored one when none were requested)
 * @param createdTime     creation timestamp (epoch millis)
 * @param lastUpdatedTime last-update timestamp (epoch millis)
 */
public record RocksDbStoredVertex(Object vertexId, String vertexName,
                                  Map<Integer, RocksDbPropertyValue> properties,
                                  long createdTime, long lastUpdatedTime) {

    public RocksDbStoredVertex {
        Objects.requireNonNull(vertexId, "vertexId");
        Objects.requireNonNull(vertexName, "vertexName");
        properties = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(properties, "properties")));
    }

    /** The projected value of one property, or {@code null} when it is not stored. */
    public Object value(int propertyId) {
        RocksDbPropertyValue property = properties.get(propertyId);
        return property == null ? null : property.value();
    }
}
