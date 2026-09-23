package com.pengcheng.phoenixgraph.fuzzy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One named rule for turning a context into a link: an id, a name, and the expression that is
 * evaluated against the context.
 *
 * <p>Patterns are data, not code: an analyst adds one while the graph is running, and the accounts
 * whose contexts produce the same key end up on one link node. The expression is validated when the
 * pattern is created, so a typo is answered with a message instead of failing a query later.
 *
 * <p>A pattern can also carry how to try it: an {@code example} context (the values a reader can plug
 * in to see the key) and the {@code demoTtitle}/{@code demoSteps} a demo query continues with. That
 * is what the UI's "run an example" button reads, so a new pattern comes with a working example
 * instead of a placeholder nobody fills in.
 *
 * @param id          the pattern number
 * @param name        a human readable name
 * @param expression  the pattern itself
 * @param description what it looks for
 * @param enabled     whether queries use it
 * @param example     a context that shows what the pattern produces (may be empty)
 * @param demoTitle   what the demo query shows, e.g. "pattern -> ring -> payments -> receiver"
 * @param demoSteps   the steps to append to {@code g.V().fuzzyLink(id, context)} in the demo query
 *                    ({@code .out('FuzzyLinkedTo')} when not given)
 */
public record FuzzyPattern(int id, String name, String expression, String description, boolean enabled,
                           Map<String, Object> example, String demoTitle, String demoSteps) {

    public FuzzyPattern {
        if (id < 0) {
            throw new FuzzyPatternException("A pattern id must not be negative, got " + id);
        }
        expression = Objects.requireNonNull(expression, "expression").trim();
        if (expression.isEmpty()) {
            throw new FuzzyPatternException("Pattern " + id + " has no expression");
        }
        // parse once, here: a pattern that cannot be read never reaches a query. A sequence (events in
        // order, with the time between them) is a pattern of its own kind, and which one it is is read
        // from the expression itself.
        if (FuzzySequence.looksLikeSequence(expression)) {
            FuzzySequence parsed = FuzzySequence.parse(expression);
            if (parsed.declaredPatternId() >= 0 && parsed.declaredPatternId() != id) {
                throw new FuzzyPatternException("The expression names pattern " + parsed.declaredPatternId()
                        + " (fl_" + parsed.declaredPatternId() + "_) but this pattern is " + id);
            }
        } else {
            FuzzyExpression.parse(expression);
        }
        name = name == null || name.isBlank() ? "pattern " + id : name.trim();
        description = description == null ? "" : description.trim();
        example = example == null ? Map.of() : Map.copyOf(example);
        demoTitle = demoTitle == null ? "" : demoTitle.trim();
        demoSteps = demoSteps == null ? "" : demoSteps.trim();
    }

    /** A pattern with just an expression. */
    public FuzzyPattern(int id, String name, String expression, String description, boolean enabled) {
        this(id, name, expression, description, enabled, Map.of(), "", "");
    }

    /** A pattern with the given expression, named after its id. */
    public static FuzzyPattern of(int id, String expression) {
        return new FuzzyPattern(id, "pattern " + id, expression, "", true);
    }

    /** The parsed expression (parsed again on demand: patterns are read far less often than used). */
    public FuzzyExpression parsed() {
        return FuzzyExpression.parse(expression);
    }

    /** Whether this pattern is a sequence of events rather than parts joined by {@code +}. */
    public boolean sequence() {
        return FuzzySequence.looksLikeSequence(expression);
    }

    /** The parsed sequence, or {@code null} when this is a flat pattern. */
    public FuzzySequence parsedSequence() {
        return sequence() ? FuzzySequence.parse(expression) : null;
    }

    /** The fields the key reads, whether the pattern is flat or a sequence. */
    public java.util.List<String> fields() {
        if (!sequence()) {
            return parsed().fields();
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        parsedSequence().fieldsByStep().values().forEach(out::addAll);
        return java.util.List.copyOf(out);
    }

    /** The fragments a context produces, one per value the pattern reads. */
    public java.util.List<String> fragments(FuzzyContext context) {
        return sequence() ? parsedSequence().fragments(context) : parsed().fragments(context);
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
        String raw = sequence() ? parsedSequence().key(context) : parsed().key(context);
        return raw == null ? null : FuzzyKey.of(raw);
    }

    /** The link vertex id this pattern produces for a context, or {@code null} when it does not apply. */
    public String linkVertexId(FuzzyContext context) {
        String key = key(context);
        return key == null ? null : FuzzySchema.linkVertexId(id, key);
    }

    /** The steps a demo query continues with after the step that finds the link node. */
    public String demoStepsOrDefault() {
        return demoSteps.isBlank() ? ".out('" + FuzzySchema.LINK_EDGE + "')" : demoSteps;
    }

    /** The link of this pattern for the pattern's own example context, when it has one. */
    public FuzzyLink exampleLink() {
        if (example.isEmpty()) {
            return null;
        }
        return FuzzyLinks.plan(this, FuzzyContext.of(example)).orElse(null);
    }

    /** The steps the editor shows for a context. */
    public List<FuzzyExpression.Step> explain(FuzzyContext context) {
        return sequence() ? parsedSequence().explain(context) : parsed().explain(context);
    }

    /** This pattern switched off, for a registry that keeps disabled patterns visible. */
    public FuzzyPattern disabled() {
        return new FuzzyPattern(id, name, expression, description, false, example, demoTitle, demoSteps);
    }

    /** This pattern with an example context, so a reader can try it. */
    public FuzzyPattern withExample(Map<String, ?> context) {
        return new FuzzyPattern(id, name, expression, description, enabled,
                context == null ? Map.of() : new LinkedHashMap<>(context), demoTitle, demoSteps);
    }

    /** This pattern with the demo a UI button runs. */
    public FuzzyPattern withDemo(String title, String steps, Map<String, ?> context) {
        return new FuzzyPattern(id, name, expression, description, enabled,
                context == null ? example : new LinkedHashMap<>(context), title, steps);
    }
}
