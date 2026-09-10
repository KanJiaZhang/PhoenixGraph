package com.pengcheng.phoenixgraph.schema;

/**
 * Supplies a {@link GraphSchemaManager} instance.
 *
 * <p>The provider pattern lets other modules obtain a graph schema manager
 * without depending on a concrete implementation. The default provider returns a
 * {@link FileSystemGraphSchemaManager}.
 */
@FunctionalInterface
public interface GraphSchemaManagerProvider {

    GraphSchemaManager provide();
}
