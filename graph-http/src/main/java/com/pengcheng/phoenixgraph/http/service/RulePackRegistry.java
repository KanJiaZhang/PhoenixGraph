package com.pengcheng.phoenixgraph.http.service;

import com.pengcheng.phoenixgraph.agent.RulePack;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Resolves an extraction rule pack by name. A pack in {@code phoenixgraph.agent.rules-dir} wins over the
 * classpath (so an operator can add a domain without rebuilding), and the built-in {@code sanguo} pack is
 * always available.
 *
 * <p>The name is restricted to {@code [a-z0-9_-]+}: it becomes a file name, and a request must not be able
 * to read an arbitrary path through {@code rulePack}.
 */
@Component
public class RulePackRegistry {

    private final Path directory;

    public RulePackRegistry(@Value("${phoenixgraph.agent.rules-dir:}") String directory) {
        this.directory = directory == null || directory.isBlank() ? null : Path.of(directory);
    }

    /** The pack a request names; blank/{@code sanguo} is the built-in default. */
    public RulePack resolve(String name) {
        if (name == null || name.isBlank() || "sanguo".equalsIgnoreCase(name)) {
            return RulePack.sanguo();
        }
        String safe = name.toLowerCase(Locale.ROOT);
        if (!safe.matches("[a-z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid rule pack name '" + name
                    + "'; use letters, digits, '-' or '_'");
        }
        Path file = directory == null ? null : directory.resolve(safe + ".json");
        if (file != null && Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                return RulePack.load(in);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read the rule pack " + file, e);
            }
        }
        try {
            return RulePack.byName(safe);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(e.getMessage() + "; external packs: " + available(), e);
        }
    }

    /** The packs an operator can name: the built-ins plus the JSON files in the configured directory. */
    public List<String> available() {
        List<String> names = new ArrayList<>(List.of("sanguo", "incident"));
        if (directory != null && Files.isDirectory(directory)) {
            try (Stream<Path> files = Files.list(directory)) {
                files.map(path -> path.getFileName().toString())
                        .filter(file -> file.endsWith(".json"))
                        .map(file -> file.substring(0, file.length() - ".json".length()))
                        .filter(file -> file.matches("[a-z0-9_-]+"))
                        .sorted()
                        .forEach(file -> {
                            if (!names.contains(file)) {
                                names.add(file);
                            }
                        });
            } catch (IOException e) {
                // the directory is optional; a listing failure is reported through the resolve error
            }
        }
        return List.copyOf(names);
    }
}
