package com.pengcheng.phoenixgraph.core;

import com.pengcheng.phoenixgraph.schema.EdgeSchema;

/**
 * An edge (relationship) instance conforming to an {@link EdgeSchema}.
 */
public interface Edge extends Element {

    /** The edge schema this instance conforms to. */
    EdgeSchema schema();

    /** The edge id, typed according to {@code schema().idSchema()}. */
    Object edgeId();

    /** The source vertex id. */
    Object source();

    /** The destination vertex id. */
    Object destination();

    /**
     * Updates a property value (lastUpdatedTime = now) and propagates its new
     * lastUpdatedTime to this edge if it is newer.
     */
    Property updateProperty(String name, Object value);

    /** Updates a property value with an explicit last-update timestamp and propagates it. */
    Property updateProperty(String name, Object value, long lastUpdatedTime);
}
