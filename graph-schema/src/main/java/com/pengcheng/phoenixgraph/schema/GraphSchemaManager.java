package com.pengcheng.phoenixgraph.schema;

import java.util.Collection;

/**
 * Manages multiple {@link GraphSchema} instances.
 *
 * <p>Implementations load schema definitions on demand and cache them. The
 * default implementation ({@link FileSystemGraphSchemaManager}) reads JSON files
 * from the running directory. Other implementations may source JSON content from
 * a database or a remote service via {@link SchemaSource}.
 */
public interface GraphSchemaManager {

    /** Loads the graph schema with the given name, parsing it from the source on first use. */
    GraphSchema load(String name);

    /** Registers a schema instance directly (e.g. a programmatically built one). */
    void register(GraphSchema schema);

    /** Returns the cached schema with the given name, or {@code null} if not loaded. */
    GraphSchema get(String name);

    /** Immutable view of all currently managed schemas. */
    Collection<GraphSchema> all();
}
