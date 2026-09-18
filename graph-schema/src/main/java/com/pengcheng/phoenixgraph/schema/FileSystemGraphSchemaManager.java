package com.pengcheng.phoenixgraph.schema;

import com.pengcheng.phoenixgraph.schema.json.SchemaJsonCodec;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Default {@link GraphSchemaManager} implementation.
 *
 * <p>Loads schema definitions as JSON files from the running directory (see
 * {@link FileSchemaSource}). The {@link SchemaSource} abstraction is injectable,
 * leaving room for a future database-backed source.
 */
public class FileSystemGraphSchemaManager implements GraphSchemaManager {

    private static final Logger LOGGER = Logger.getLogger(FileSystemGraphSchemaManager.class.getName());

    private final SchemaSource source;
    private final SchemaJsonCodec codec;
    private final SchemaValidator validator = new SchemaValidator();
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
        GraphSchema schema = codec.fromJson(json);
        validateLoaded(schema);
        checkDuplicate(schema);
        return schema;
    }

    @Override
    public void register(GraphSchema schema) {
        Objects.requireNonNull(schema, "schema");
        validateLoaded(schema);
        checkDuplicate(schema);
        schemas.put(schema.name(), schema);
    }

    @Override
    public void registerOrReplace(GraphSchema schema) {
        Objects.requireNonNull(schema, "schema");
        validateLoaded(schema);
        schemas.put(schema.name(), schema);
    }

    private void checkDuplicate(GraphSchema schema) {
        GraphSchema byName = schemas.get(schema.name());
        if (byName != null && byName != schema) {
            throw new IllegalArgumentException("Graph schema name '" + schema.name() + "' is already registered");
        }
        for (GraphSchema existing : schemas.values()) {
            if (existing != schema && existing.id() == schema.id()) {
                throw new IllegalArgumentException("Graph schema id " + schema.id()
                        + " is already registered as '" + existing.name() + "'");
            }
        }
    }

    private void validateLoaded(GraphSchema schema) {
        SchemaValidationResult result = validator.validateResult(schema, schema.constraints());
        if (!result.isValid()) {
            throw new IllegalArgumentException("Invalid graph schema '" + schema.name() + "': "
                    + String.join("; ", result.errors()));
        }
        for (String warning : result.warnings()) {
            LOGGER.warning("Schema '" + schema.name() + "': " + warning);
        }
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
