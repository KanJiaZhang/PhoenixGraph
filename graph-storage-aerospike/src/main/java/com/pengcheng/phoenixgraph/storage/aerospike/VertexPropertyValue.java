package com.pengcheng.phoenixgraph.storage.aerospike;

/**
 * A vertex property value together with its {@code lastModifiedTime} — the
 * per-property timestamp used by CAS/version checks (mirrors
 * {@code Property.meta().lastUpdatedTime()}).
 */
public record VertexPropertyValue(Object value, long lastModifiedTime) {
}
