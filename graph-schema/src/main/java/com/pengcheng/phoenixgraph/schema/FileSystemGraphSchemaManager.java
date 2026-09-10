package com.pengcheng.phoenixgraph.schema;

import com.pengcheng.phoenixgraph.schema.json.SchemaJsonCodec;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default {@link GraphSchemaManager} implementation.
 *
 * <p>Loads schema definitions as JSON files from the running directory (see
 * {@link FileSchemaSource}). The {@link SchemaSource} abstraction is injectable,
 * leaving room for a future database-backed source.
 */
public class FileSystemGraphSchemaManager implements GraphSchemaManager {

    private final SchemaSource source;
    private final SchemaJsonCodec codec;
    private final Map<String, GraphSchema> schemas = new ConcurrentHashMap<>();

    /** Loads {@code <name>.json} files from the current working directory. */
    public FileSystemGraphSchemaManager() {
        this(new FileSchemaSource());
    }

    /** Loads {@code <name>.json} files from the given directory. */
    public FileSystemGraphSchemaManager(Path directory) {
        this(new FileSchemaSource(directory));
    }

    public FileSystemGraphSchemaManager(SchemaSource source) {
        this.source = Objects.requireNonNull(source, "source");
        this.codec = new SchemaJsonCodec();
    }

    @Override
    public GraphSchema load(String name) {
        return schemas.computeIfAbsent(name, this::loadFromSource);
    }

    private GraphSchema loadFromSource(String name) {
        String json = source.load(name);
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("No schema definition found for name: " + name);
        }
        return codec.fromJson(json);
    }

    @Override
    public void register(GraphSchema schema) {
        schemas.put(schema.name(), schema);
    }

    @Override
    public GraphSchema get(String name) {
        return schemas.get(name);
    }

    @Override
    public Collection<GraphSchema> all() {
        return List.copyOf(schemas.values());
    }
}
