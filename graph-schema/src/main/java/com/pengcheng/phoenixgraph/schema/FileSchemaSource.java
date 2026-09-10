package com.pengcheng.phoenixgraph.schema;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Default {@link SchemaSource} that reads {@code <name>.json} files from a
 * directory (the running directory by default).
 */
public class FileSchemaSource implements SchemaSource {

    private final Path directory;

    /** Reads schema files from the current working directory. */
    public FileSchemaSource() {
        this(Path.of("."));
    }

    public FileSchemaSource(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    @Override
    public String load(String name) {
        Path file = directory.resolve(name + ".json");
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read schema file: " + file, e);
        }
    }
}
