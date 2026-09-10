package com.pengcheng.phoenixgraph.schema;

import java.util.Objects;

/**
 * Static access point for the configured {@link GraphSchemaManagerProvider}.
 *
 * <p>Other modules obtain a {@link GraphSchemaManager} via {@link #manager()} (or
 * {@link #provider()}) without depending on a concrete implementation. The
 * default provider returns a lazily-initialized singleton
 * {@link FileSystemGraphSchemaManager} that reads JSON files from the running
 * directory; call {@link #setProvider(GraphSchemaManagerProvider)} to override it
 * (e.g. with a future database-backed implementation).
 */
public final class GraphSchemaManagers {

    private static volatile GraphSchemaManagerProvider provider =
            GraphSchemaManagers::defaultFileSystemManager;

    private GraphSchemaManagers() {
    }

    /** Lazily-initialized singleton holder for the default manager. */
    private static final class DefaultManagerHolder {
        static final GraphSchemaManager INSTANCE = new FileSystemGraphSchemaManager();
    }

    private static GraphSchemaManager defaultFileSystemManager() {
        return DefaultManagerHolder.INSTANCE;
    }

    /** The currently configured provider. */
    public static GraphSchemaManagerProvider provider() {
        return provider;
    }

    /** Overrides the provider used by {@link #manager()}. */
    public static void setProvider(GraphSchemaManagerProvider newProvider) {
        provider = Objects.requireNonNull(newProvider, "newProvider");
    }

    /** Convenience accessor returning a manager supplied by the current provider. */
    public static GraphSchemaManager manager() {
        return provider.provide();
    }
}
