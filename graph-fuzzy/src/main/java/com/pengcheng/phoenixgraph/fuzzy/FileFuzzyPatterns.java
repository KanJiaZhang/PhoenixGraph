package com.pengcheng.phoenixgraph.fuzzy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The patterns of the running graphs, kept in one file next to the schema files.
 *
 * <p>A pattern added in the builder page is written here and is visible to the next query: the file
 * is re-read when it changes on disk (its timestamp is checked, not its content), so an analyst can
 * also edit it by hand. Every graph has its own list, and a graph without patterns simply has none.
 */
public class FileFuzzyPatterns implements FuzzyPatternRegistry {

    private static final long CHECK_INTERVAL_MILLIS = 1_000;

    private final Path file;
    private final String graph;
    private final FuzzyPatterns codec;
    private List<FuzzyPattern> all = List.of();
    private List<FuzzyPattern> enabled = List.of();
    private long lastRead;
    private long lastModified;

    public FileFuzzyPatterns(Path file, String graph) {
        this(file, graph, new FuzzyPatterns());
    }

    public FileFuzzyPatterns(Path file, String graph, FuzzyPatterns codec) {
        this.file = file.toAbsolutePath();
        this.graph = graph;
        this.codec = codec;
        reload();
    }

    /** The file the patterns are kept in. */
    public Path file() {
        return file;
    }

    /** Every pattern of the graph, disabled ones included (what the editor lists). */
    public List<FuzzyPattern> allPatterns() {
        refresh();
        return all;
    }

    @Override
    public List<FuzzyPattern> patterns() {
        refresh();
        return enabled;
    }

    /**
     * Replaces the patterns of the graph.
     *
     * <p>The whole list is written at once: two analysts editing at the same time end up with one of
     * the two lists, never with half of each.
     */
    public void save(List<FuzzyPattern> patterns) {
        List<FuzzyPattern> sorted = new ArrayList<>(patterns);
        sorted.sort((a, b) -> Integer.compare(a.id(), b.id()));
        String json = readDocument();
        String updated = codec.write(json, graph, sorted);
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, updated, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write the fuzzy patterns to " + file, e);
        }
        setPatterns(sorted);
        lastModified = lastModifiedOf();
        lastRead = System.currentTimeMillis();
    }

    /** Adds or replaces one pattern, leaving the others alone. */
    public List<FuzzyPattern> save(FuzzyPattern pattern) {
        Map<Integer, FuzzyPattern> byId = new LinkedHashMap<>();
        for (FuzzyPattern existing : allPatterns()) {
            byId.put(existing.id(), existing);
        }
        byId.put(pattern.id(), pattern);
        List<FuzzyPattern> merged = new ArrayList<>(byId.values());
        save(merged);
        return merged;
    }

    /** Removes a pattern, returning the ones that are left. */
    public List<FuzzyPattern> remove(int id) {
        List<FuzzyPattern> left = new ArrayList<>();
        for (FuzzyPattern pattern : allPatterns()) {
            if (pattern.id() != id) {
                left.add(pattern);
            }
        }
        save(left);
        return left;
    }

    @Override
    public void reload() {
        setPatterns(readFromDisk());
        lastModified = lastModifiedOf();
        lastRead = System.currentTimeMillis();
    }

    /** The patterns as the file holds them (an empty list when the file does not exist yet). */
    private List<FuzzyPattern> readFromDisk() {
        String json = readDocument();
        if (json.isBlank()) {
            return List.of();
        }
        List<FuzzyPattern> patterns = new ArrayList<>(codec.read(json, graph));
        patterns.sort((a, b) -> Integer.compare(a.id(), b.id()));
        return patterns;
    }

    private String readDocument() {
        if (!Files.exists(file)) {
            return "";
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the fuzzy patterns from " + file, e);
        }
    }

    private void setPatterns(List<FuzzyPattern> patterns) {
        this.all = List.copyOf(patterns);
        List<FuzzyPattern> on = new ArrayList<>();
        for (FuzzyPattern pattern : this.all) {
            if (pattern.enabled()) {
                on.add(pattern);
            }
        }
        this.enabled = List.copyOf(on);
    }

    /** Re-reads the file when it changed, at most once a second. */
    private void refresh() {
        long now = System.currentTimeMillis();
        if (now - lastRead < CHECK_INTERVAL_MILLIS) {
            return;
        }
        lastRead = now;
        long modified = lastModifiedOf();
        if (modified != lastModified) {
            reload();
        }
    }

    private long lastModifiedOf() {
        try {
            return Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : -1L;
        } catch (IOException e) {
            return -1L;
        }
    }
}
