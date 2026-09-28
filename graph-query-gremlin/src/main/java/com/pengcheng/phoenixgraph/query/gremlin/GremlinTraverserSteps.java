package com.pengcheng.phoenixgraph.query.gremlin;

import com.pengcheng.phoenixgraph.query.QueryException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The steps that need per-traverser state: {@code as}, {@code select}, {@code project} and
 * {@code where('a', P)}.
 *
 * <p>They run on the stateful path only ({@link GremlinTraverser}); a query that uses one of them is
 * compiled into that path, and every other step keeps its implementation - it is applied to the
 * traversers' values through the adapter, which is what keeps the two paths answering the same thing.
 */
final class GremlinTraverserSteps {

    private GremlinTraverserSteps() {
    }

    /** {@code as(label)}: binds the traverser's value under the label (a later {@code select} reads it). */
    static List<GremlinTraverser> as(List<GremlinTraverser> in, List<String> labels) {
        List<GremlinTraverser> out = new ArrayList<>(in.size());
        for (GremlinTraverser traverser : in) {
            GremlinTraverser bound = traverser;
            for (String label : labels) {
                bound = bound.withLabel(label, traverser.value());
            }
            out.add(bound);
        }
        return out;
    }

    /**
     * {@code select(label...)}: one label replaces the traverser's value with what that label holds,
     * several labels answer with a map of them, and {@code select()} with none answers with every label.
     * A label that was never bound is an error: a {@code null} answer would look like data.
     *
     * <p>A {@code by()} modulator after the step picks the value out of what was selected (a property key
     * reads it off the element).
     */
    static List<GremlinTraverser> select(List<GremlinTraverser> in, List<String> labels, List<String> byKeys) {
        List<GremlinTraverser> out = new ArrayList<>(in.size());
        for (GremlinTraverser traverser : in) {
            if (labels.isEmpty()) {
                out.add(traverser.withValue(new LinkedHashMap<>(traverser.labels())));
            } else if (labels.size() == 1) {
                Object bound = requireLabel(traverser, labels.get(0));
                out.add(traverser.withValue(pick(bound, byKeys.isEmpty() ? null : byKeys.get(0), null)));
            } else {
                Map<String, Object> selected = new LinkedHashMap<>();
                for (int i = 0; i < labels.size(); i++) {
                    Object bound = requireLabel(traverser, labels.get(i));
                    selected.put(labels.get(i),
                            i < byKeys.size() ? pick(bound, byKeys.get(i), null) : bound);
                }
                out.add(traverser.withValue(selected));
            }
        }
        return out;
    }

    /**
     * {@code project('a','b').by(...).by(...)}: one map per traverser whose keys are filled by its
     * {@code by()} modulators - a property key reads it off the element, a child traversal runs on the
     * value. A key with no modulator takes the traverser's own value, as TinkerPop does.
     */
    static List<GremlinTraverser> project(List<GremlinTraverser> in, List<String> keys, List<String> bySpecs,
                                          Function<String, List<Object>> childRunner) {
        if (bySpecs.size() > keys.size()) {
            throw new QueryException("project(" + String.join(", ", keys) + ") has " + bySpecs.size()
                    + " by(...) modulators but only " + keys.size() + " keys");
        }
        List<GremlinTraverser> out = new ArrayList<>(in.size());
        for (GremlinTraverser traverser : in) {
            Map<String, Object> projected = new LinkedHashMap<>();
            for (int i = 0; i < keys.size(); i++) {
                projected.put(keys.get(i), bySpecs.size() > i
                        ? pick(traverser.value(), bySpecs.get(i), childRunner)
                        : traverser.value());
            }
            out.add(traverser.withValue(projected));
        }
        return out;
    }

    /**
     * {@code where('a', P)}: keeps the traversers whose labelled value satisfies the predicate, with the
     * predicate's own arguments resolved through the labels first - {@code where('a', eq('b'))} compares
     * two labelled values, {@code where('a', eq('ACTIVE'))} compares against the literal (the predicate
     * falls back to the text when no label has that name).
     */
    static List<GremlinTraverser> whereLabel(List<GremlinTraverser> in, String label, String predicateText) {
        List<GremlinTraverser> out = new ArrayList<>(in.size());
        for (GremlinTraverser traverser : in) {
            Object stored = requireLabel(traverser, label);
            // the predicate is read with the bound labels written into it, so where('a', eq('b')) compares
            // two labelled values and where('a', eq('ACTIVE')) still compares against the literal
            GremlinPredicate predicate = GremlinPredicate.parse(resolveLabels(predicateText, traverser));
            if (predicate == null) {
                throw new QueryException("where('" + label + "', ...) expects a predicate, got: "
                        + predicateText);
            }
            if (predicate.test(stored)) {
                out.add(traverser);
            }
        }
        return out;
    }

    /**
     * The predicate text with every bare word that names a bound label replaced by that label's value
     * (text quoted, numbers bare, an element by its id). The predicate's own name is left alone.
     */
    private static String resolveLabels(String predicateText, GremlinTraverser traverser) {
        java.util.Set<String> names = java.util.Set.of("eq", "neq", "gt", "gte", "lt", "lte", "between",
                "inside", "outside", "within", "without", "containing", "startingwith", "endingwith",
                "notcontaining", "notstartingwith", "notendingwith", "not", "and", "or");
        StringBuilder out = new StringBuilder(predicateText.length());
        int i = 0;
        while (i < predicateText.length()) {
            char c = predicateText.charAt(i);
            if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < predicateText.length() && (Character.isLetterOrDigit(predicateText.charAt(i))
                        || predicateText.charAt(i) == '_')) {
                    i++;
                }
                String word = predicateText.substring(start, i);
                Object bound = names.contains(word.toLowerCase(java.util.Locale.ROOT)) ? null
                        : traverser.labels().get(word);
                if (bound == null) {
                    out.append(word);
                    continue;
                }
                // the word may already be inside quotes (`eq('s')`): replace the quoted token as a whole,
                // otherwise the value would end up quoted twice (`eq(''ACTIVE'')`)
                boolean quoted = start > 0 && i < predicateText.length()
                        && (predicateText.charAt(start - 1) == '\'' || predicateText.charAt(start - 1) == '"')
                        && predicateText.charAt(i) == predicateText.charAt(start - 1);
                if (quoted) {
                    out.setLength(out.length() - 1); // drop the opening quote that was just appended
                    i++;
                }
                out.append(literalFor(bound));
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** A labelled value as a predicate argument: numbers bare, text quoted, an element by its id. */
    private static String literalFor(Object bound) {
        Object value = bound instanceof GremlinElement element ? element.id() : bound;
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return "'" + String.valueOf(value).replace("'", "\\'") + "'";
    }

    /** The value a {@code by(...)} spec reads off a value (a property key), as {@code sack()} needs it. */
    static Object read(Object value, String bySpec) {
        return pick(value, bySpec, null);
    }

    /**
     * What a {@code by()} modulator produces for one value: a property of the element, or the first
     * element a child traversal reaches (when a runner was handed in).
     */
    private static Object pick(Object value, String byKey, Function<String, List<Object>> childRunner) {
        if (byKey == null || byKey.isBlank()) {
            return value;
        }
        if (value instanceof GremlinElement element && !byKey.contains("(")) {
            return GremlinElementExpression.value(element, byKey);
        }
        if (childRunner != null) {
            List<Object> reached = childRunner.apply(byKey);
            return reached.isEmpty() ? null : reached.get(0);
        }
        return value;
    }

    private static Object requireLabel(GremlinTraverser traverser, String label) {
        if (!traverser.labels().containsKey(label)) {
            throw new QueryException("select('" + label + "') has no such label: bind it with as('" + label
                    + "') before the step that moved on. Bound here: "
                    + (traverser.labels().isEmpty() ? "nothing" : traverser.labels().keySet()));
        }
        return traverser.labels().get(label);
    }
}
