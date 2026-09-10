package com.pengcheng.phoenixgraph.core;

/**
 * A single map entry value: the original typed value plus its own created and
 * last-updated timestamps.
 */
public record MapEntryValue(Object value, long createdTime, long lastUpdatedTime) {

    public static MapEntryValue of(Object value) {
        long now = System.currentTimeMillis();
        return new MapEntryValue(value, now, now);
    }

    public MapEntryValue touch() {
        return new MapEntryValue(value, createdTime, System.currentTimeMillis());
    }
}
