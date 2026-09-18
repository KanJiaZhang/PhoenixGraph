package com.pengcheng.phoenixgraph.fuzzy;

import java.util.List;
import java.util.Objects;

/**
 * One named rule for turning a context into a link: an id, a name, and the expression that is
 * evaluated against the context.
 *
 * <p>Patterns are data, not code: an analyst adds one while the graph is running, and the accounts
 * whose contexts produce the same key end up on one link node. The expression is validated when the
 * pattern is created, so a typo is answered with a message instead of failing a query later.
 */
public record FuzzyPattern(int id, String name, String expression, String description, boolean enabled) {

    public FuzzyPattern {
        if (id < 0) {
            throw new FuzzyPatternException("A pattern id must not be negative, got " + id);
        }
        expression = Objects.requireNonNull(expression, "expression").trim();
        if (expression.isEmpty()) {
            throw new FuzzyPatternException("Pattern " + id + " has no expression");
        }
        // parse once, here: a pattern that cannot be read never reaches a query
        FuzzyExpression.parse(expression);
        name = name == null || name.isBlank() ? "pattern " + id : name.trim();
        description = description == null ? "" : description.trim();
    }

    /** A pattern with the given expression, named after its id. */
    public static FuzzyPattern of(int id, String expression) {
        return new FuzzyPattern(id, "pattern " + id, expression, "", true);
    }

    /** The parsed expression (parsed again on demand: patterns are read far less often than used). */
    public FuzzyExpression parsed() {
        return FuzzyExpression.parse(expression);
    }

    /** The vertex id of this pattern in the registry. */
    public String vertexId() {
        return FuzzySchema.patternVertexId(id);
    }

    /**
     * The key this pattern produces for a context, or {@code null} when the pattern does not apply
     * (a part of it has no value in this context).
     */
    public String key(FuzzyContext context) {
        String raw = parsed().key(context);
        return raw == null ? null : FuzzyKey.of(raw);
    }

    /** The link vertex id this pattern produces for a context, or {@code null} when it does not apply. */
    public String linkVertexId(FuzzyContext context) {
        String key = key(context);
        return key == null ? null : FuzzySchema.linkVertexId(id, key);
    }

    /** The steps the editor shows for a context. */
    public List<FuzzyExpression.Step> explain(FuzzyContext context) {
        return parsed().explain(context);
    }

    /** This pattern switched off, for a registry that keeps disabled patterns visible. */
    public FuzzyPattern disabled() {
        return new FuzzyPattern(id, name, expression, description, false);
    }
}
