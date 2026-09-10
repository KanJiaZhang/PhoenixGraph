package com.pengcheng.phoenixgraph.core;

/**
 * Instance-level metadata attached to graph elements and properties.
 *
 * @param createdTime     when the element/property was created (epoch millis)
 * @param lastUpdatedTime when it was last updated (epoch millis)
 */
public record MetaProperty(long createdTime, long lastUpdatedTime) {

    public static MetaProperty now() {
        long now = System.currentTimeMillis();
        return new MetaProperty(now, now);
    }

    public static MetaProperty of(long createdTime) {
        return new MetaProperty(createdTime, System.currentTimeMillis());
    }

    public MetaProperty touch() {
        return new MetaProperty(createdTime, System.currentTimeMillis());
    }

    /**
     * Propagates a child's last-update time to this meta. Returns a copy whose
     * {@code lastUpdatedTime} is the maximum of this meta's and the given
     * timestamp, or {@code this} if already up to date.
     */
    public MetaProperty mergedWith(long childLastUpdatedTime) {
        return childLastUpdatedTime > lastUpdatedTime
                ? new MetaProperty(createdTime, childLastUpdatedTime)
                : this;
    }
}
