package com.pengcheng.phoenixgraph.core;

import com.pengcheng.phoenixgraph.schema.PropertySchema;

/**
 * A concrete property instance holding a typed value.
 */
public interface Property {

    /** The schema this property conforms to. */
    PropertySchema schema();

    /** The property index, equal to {@code schema().id()}. */
    int id();

    /** The property value, typed according to {@code schema().type()}. */
    Object value();

    /** Creation/last-update metadata. */
    MetaProperty meta();
}
