package com.pengcheng.phoenixgraph.core;

import com.pengcheng.phoenixgraph.schema.VertexSchema;

/**
 * A vertex (node) instance conforming to a {@link VertexSchema}.
 */
public interface Vertex extends Element {

    /** The vertex schema this instance conforms to. */
    VertexSchema schema();

    /** The vertex id, typed according to {@code schema().idSchema()}. */
    Object id();

    /**
     * Updates a property value (lastUpdatedTime = now) and propagates its new
     * lastUpdatedTime to this vertex if it is newer.
     */
    Property updateProperty(String name, Object value);

    /** Updates a property value with an explicit last-update timestamp and propagates it. */
    Property updateProperty(String name, Object value, long lastUpdatedTime);
}
