package com.pengcheng.phoenixgraph.storage.aerospike;

/**
 * An edge property value with its own timestamps.
 *
 * @param value           raw property value
 * @param createdTime     when this property was created (epoch millis)
 * @param lastUpdatedTime when this property was last updated (epoch millis)
 */
public record EdgePropertyValue(Object value, long createdTime, long lastUpdatedTime) {
}
