package com.pengcheng.phoenixgraph.http.service;

import com.pengcheng.phoenixgraph.fuzzy.FileFuzzyPatterns;
import com.pengcheng.phoenixgraph.fuzzy.FuzzyPattern;
import com.pengcheng.phoenixgraph.fuzzy.FuzzyPatternRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The fuzzy patterns of every graph this instance serves, kept in one file.
 *
 * <p>Patterns are configuration an analyst writes while the graph is running, so they live next to
 * the schema files rather than in the graph: the file is re-read when it changes, which means a
 * pattern added through the builder page is used by the next query, and one edited by hand is picked
 * up too. Each graph has its own list in the same document.
 *
 * <p>The manager hands out one {@link FileFuzzyPatterns} per graph for the life of the process, so
 * the query engine's registry and the editor's view see the same patterns.
 */
@Component
public class FuzzyPatternStore {

    private final Path file;
    private final Map<String, FileFuzzyPatterns> byGraph = new ConcurrentHashMap<>();

    public FuzzyPatternStore(@Value("${phoenixgraph.fuzzy.patterns-file:fuzzy-patterns.json}") String file) {
        this.file = Path.of(file);
    }

    /** The file the patterns are kept in. */
    public Path file() {
        return file;
    }

    /** The patterns of a graph as a registry (what the query engine reads). */
    public FuzzyPatternRegistry registryOf(String graph) {
        return of(graph);
    }

    /** The patterns of a graph, disabled ones included (what the editor lists). */
    public List<FuzzyPattern> patterns(String graph) {
        return of(graph).allPatterns();
    }

    /** Adds or replaces one pattern. */
    public List<FuzzyPattern> save(String graph, FuzzyPattern pattern) {
        return of(graph).save(pattern);
    }

    /** Removes a pattern, returning what is left. */
    public List<FuzzyPattern> remove(String graph, int patternId) {
        return of(graph).remove(patternId);
    }

    /** Forgets what is cached, so the file is read again. */
    public void reload(String graph) {
        of(graph).reload();
    }

    private FileFuzzyPatterns of(String graph) {
        if (graph == null || graph.isBlank()) {
            throw new IllegalArgumentException("graph must not be blank");
        }
        return byGraph.computeIfAbsent(graph, name -> new FileFuzzyPatterns(file, name));
    }

    /** The patterns of every graph in the file, for a caller that reports the whole state. */
    public Map<String, List<FuzzyPattern>> all() {
        Map<String, List<FuzzyPattern>> out = new LinkedHashMap<>(new com.pengcheng.phoenixgraph.fuzzy.FuzzyPatterns()
                .readAll(readDocument()));
        for (String graph : byGraph.keySet()) {
            out.put(graph, byGraph.get(graph).allPatterns());
        }
        return out;
    }

    private String readDocument() {
        try {
            return java.nio.file.Files.exists(file)
                    ? java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8) : "";
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("Cannot read " + file, e);
        }
    }
}
