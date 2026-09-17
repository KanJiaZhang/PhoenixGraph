package com.pengcheng.phoenixgraph.storage.rocksdb;

/**
 * An edge property value with its own timestamps, the unit the inline edge merge works on.
 *
 * @param value           the property value
 * @param createdTime     when the property was first written (epoch millis)
 * @param lastUpdatedTime when the property was last updated (epoch millis)
 */
public record RocksDbEdgeProperty(Object value, long createdTime, long lastUpdatedTime) {
}
