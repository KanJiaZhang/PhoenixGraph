package com.pengcheng.phoenixgraph.schema;

/**
 * Validation constraints for a graph schema.
 *
 * <p>Declares the reserved property indexes that vertex and edge schemas must
 * conform to: the element {@code idSchema}, the {@code createdTime} and the
 * {@code lastUpdatedTime} meta-properties.
 *
 * @param idSchemaIndex       required index of the element id property schema
 * @param createdTimeIndex    required index of the {@code createdTime} meta-property
 * @param lastUpdatedTimeIndex required index of the {@code lastUpdatedTime} meta-property
 */
public record SchemaConstraints(int idSchemaIndex, int createdTimeIndex, int lastUpdatedTimeIndex) {

    public static final SchemaConstraints DEFAULTS = new SchemaConstraints(0, -2, -1);

    public static SchemaConstraints defaults() {
        return DEFAULTS;
    }
}
