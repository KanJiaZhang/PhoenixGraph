package com.pengcheng.phoenixgraph.schema;

/**
 * Cardinality constraint applied to a property key.
 */
public enum Cardinality {
    /** At most one value. */
    SINGLE,
    /** Ordered collection of values. */
    LIST,
    /** Unordered collection of unique values. */
    SET
}
