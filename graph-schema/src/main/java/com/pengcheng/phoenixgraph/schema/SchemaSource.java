package com.pengcheng.phoenixgraph.schema;

/**
 * Abstraction for loading the raw JSON definition of a graph schema.
 *
 * <p>The default implementation reads JSON files from the running directory
 * (see {@link FileSchemaSource}); other implementations may load JSON content
 * from a database or a remote service.
 */
public interface SchemaSource {

    /**
     * Returns the raw JSON definition for the named graph schema, or {@code null}
     * if no definition is found.
     */
    String load(String name);
}
