package com.pengcheng.phoenixgraph.core;

import java.util.Collection;

/**
 * Base abstraction for a schema-driven graph element (vertex or edge).
 */
public interface Element {

    /** Creation/last-update metadata. */
    MetaProperty meta();

    /** The schema-defined properties of this element. */
    Collection<Property> properties();

    /** Looks up a property by name, or {@code null} if not present. */
    Property property(String name);

    /** Looks up a property by index id, or {@code null} if not present. */
    Property property(int id);
}
