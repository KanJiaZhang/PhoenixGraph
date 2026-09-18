package com.pengcheng.phoenixgraph.fuzzy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Computes the links a context belongs to, without touching storage.
 *
 * <p>Both sides of the feature meet here. A writer (a loader, an ingest, the account write path)
 * calls {@link #plan(FuzzyPatternRegistry, FuzzyContext)} to learn which link vertices to write and
 * which edges to draw; a reader calls the same thing to learn which link vertex to look for, which
 * is why the two agree by construction - the id of a link is a pure function of the pattern and the
 * context.
 */
public final class FuzzyLinks {

    private FuzzyLinks() {
    }

    /** Every link the enabled patterns produce for a context, in pattern id order. */
    public static List<FuzzyLink> plan(FuzzyPatternRegistry registry, FuzzyContext context) {
        List<FuzzyLink> links = new ArrayList<>();
        for (FuzzyPattern pattern : registry.patterns()) {
            plan(pattern, context).ifPresent(links::add);
        }
        return links;
    }

    /**
     * The link one pattern produces for a context.
     *
     * @return empty when the pattern does not apply - a part of it has no value in this context, and
     *         a key built around a missing fragment would merge accounts that share nothing
     */
    public static Optional<FuzzyLink> plan(FuzzyPattern pattern, FuzzyContext context) {
        if (!pattern.enabled()) {
            return Optional.empty();
        }
        FuzzyExpression expression = pattern.parsed();
        String raw = expression.key(context);
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String key = FuzzyKey.of(raw);
        return Optional.of(new FuzzyLink(pattern.id(), expression.text(), key,
                expression.fragments(context), FuzzySchema.linkVertexId(pattern.id(), key)));
    }

    /** The link vertex id a context produces for a pattern, or {@code null} when it does not apply. */
    public static String linkVertexId(FuzzyPattern pattern, FuzzyContext context) {
        return plan(pattern, context).map(FuzzyLink::linkVertexId).orElse(null);
    }
}
