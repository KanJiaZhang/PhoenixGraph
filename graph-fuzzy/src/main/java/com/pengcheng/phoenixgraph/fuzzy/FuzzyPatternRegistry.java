package com.pengcheng.phoenixgraph.fuzzy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Where the fuzzy patterns of a graph come from.
 *
 * <p>The query step and the pattern editor both ask this, so a pattern an analyst adds is visible to
 * the next query without a restart. Implementations read the patterns from wherever the graph keeps
 * them (a vertex type, a file, a service) and are free to cache them, as long as
 * {@link #reload()} makes a new pattern visible.
 */
public interface FuzzyPatternRegistry {

    /** The enabled patterns, in id order. */
    List<FuzzyPattern> patterns();

    /** One pattern by id, when it exists and is enabled. */
    default Optional<FuzzyPattern> pattern(int id) {
        for (FuzzyPattern pattern : patterns()) {
            if (pattern.id() == id) {
                return Optional.of(pattern);
            }
        }
        return Optional.empty();
    }

    /**
     * The pattern by id, or an exception that names what the caller can do about it.
     *
     * <p>A traversal asks for a pattern on every execution, so the message lists what exists - it
     * ends up in the UI's error box.
     */
    default FuzzyPattern require(int id) {
        return pattern(id).orElseThrow(() -> new FuzzyPatternException("Unknown fuzzy pattern " + id
                + ". Known patterns: " + (patterns().isEmpty() ? "none"
                        : String.join(", ", patterns().stream().map(p -> p.id() + " (" + p.name() + ")").toList()))));
    }

    /** Forgets anything cached, so patterns written since the last call are seen. */
    default void reload() {
    }

    /** A registry over a fixed list (tests, a single query, an offline job). */
    static FuzzyPatternRegistry of(List<FuzzyPattern> patterns) {
        Map<Integer, FuzzyPattern> byId = new LinkedHashMap<>();
        for (FuzzyPattern pattern : patterns) {
            byId.put(pattern.id(), pattern);
        }
        List<FuzzyPattern> enabled = new ArrayList<>();
        byId.values().stream().filter(FuzzyPattern::enabled).forEach(enabled::add);
        enabled.sort((a, b) -> Integer.compare(a.id(), b.id()));
        return () -> List.copyOf(enabled);
    }

    /** An empty registry: no pattern exists yet. */
    static FuzzyPatternRegistry empty() {
        return List::of;
    }
}
