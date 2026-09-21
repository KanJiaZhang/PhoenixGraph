package com.pengcheng.phoenixgraph.query.gremlin;

import com.pengcheng.phoenixgraph.query.QueryException;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Element accessors in the Groovy form a Gremlin client writes, inside the closure of
 * {@code filter { ... }} / {@code map { ... }} or as a plain argument.
 *
 * <pre>{@code
 * .filter(it.get().value('accountStatus').equals('ACTIVE'))
 * .filter { it.get().label() == 'Account' }
 * .map { it.get().value('accountStatus') }
 * }</pre>
 *
 * <p>The engine's own grammar (see {@link GremlinFilterExpression}) compares <em>property keys</em>
 * directly ({@code filter(status.equals('SUCCESS'))}), so this class rewrites the Groovy accessors
 * into that form before the grammar sees them:
 * <ul>
 *   <li>{@code it.get().value('k')} / {@code it.value('k')} → {@code k}</li>
 *   <li>{@code it.get().label()} / {@code it.label()} → {@value #LABEL_KEY}</li>
 *   <li>{@code it.get().id()} / {@code it.id()} → {@value #ID_KEY}</li>
 * </ul>
 * The two pseudo keys are not properties: {@link #read(Object, String)} answers them from the
 * element itself, and callers must keep them out of the property projection they ask storage for
 * ({@link #isPseudo(String)}).
 */
final class GremlinElementExpression {

    /** The pseudo key standing for the element's label (schema name). */
    static final String LABEL_KEY = "@label";

    /** The pseudo key standing for the element's id. */
    static final String ID_KEY = "@id";

    // the optional '.get()' and the optional 's' make it.get().value('k'), it.value('k') and
    // it.values('k') one and the same accessor
    private static final Pattern VALUE = Pattern.compile(
            "it\\s*\\.\\s*(?:get\\s*\\(\\s*\\)\\s*\\.\\s*)?values?\\s*\\(\\s*(['\"])([^'\"]*)\\1\\s*\\)");
    private static final Pattern LABEL = Pattern.compile(
            "it\\s*\\.\\s*(?:get\\s*\\(\\s*\\)\\s*\\.\\s*)?label\\s*\\(\\s*\\)");
    private static final Pattern ID = Pattern.compile(
            "it\\s*\\.\\s*(?:get\\s*\\(\\s*\\)\\s*\\.\\s*)?id\\s*\\(\\s*\\)");

    private GremlinElementExpression() {
    }

    /** Rewrites the Groovy element accessors of an expression into the engine's property-key form. */
    static String normalize(String expression) {
        if (expression == null || expression.indexOf("it") < 0) {
            return expression;
        }
        String out = VALUE.matcher(expression)
                .replaceAll(result -> Matcher.quoteReplacement(result.group(2)));
        out = LABEL.matcher(out).replaceAll(Matcher.quoteReplacement(LABEL_KEY));
        return ID.matcher(out).replaceAll(Matcher.quoteReplacement(ID_KEY));
    }

    /** Whether a key is one of the element accessors rather than a stored property. */
    static boolean isPseudo(String key) {
        return LABEL_KEY.equals(key) || ID_KEY.equals(key);
    }

    /** Reads a (possibly pseudo) key off an element: a property value, its label or its id. */
    static Object read(GremlinElement element, String key) {
        if (LABEL_KEY.equals(key)) {
            return element.label();
        }
        if (ID_KEY.equals(key)) {
            return element.id();
        }
        return element.properties().get(key);
    }

    /**
     * Reads a key off a vertex that is not one of the engine's element types - the HNSW filter
     * evaluates conditions against the raw id/property map of a search hit, so it answers the
     * pseudo keys without building a vertex.
     */
    static Object read(Object id, String key, java.util.Map<String, Object> properties) {
        if (LABEL_KEY.equals(key)) {
            return null; // a raw search hit carries no label
        }
        if (ID_KEY.equals(key)) {
            return id;
        }
        return properties.get(key);
    }

    /**
     * The property names a single accessor reads (empty for the pseudo keys), which is what the
     * projection planner asks storage for.
     */
    static Set<String> propertyKeys(String expression) {
        String normalized = normalize(expression).trim();
        if (isPseudo(normalized)) {
            return Set.of();
        }
        Set<String> keys = new LinkedHashSet<>();
        keys.add(normalized);
        return keys;
    }

    /**
     * The single accessor a {@code map { ... }} closure is: one property, the label or the id.
     * Anything else (a comparison, a concatenation, ...) is rejected with the form to write instead.
     */
    static String pathKey(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new QueryException("map expects a closure such as map { it.get().value('accountStatus') }");
        }
        String normalized = normalize(expression).trim();
        if (normalized.indexOf('(') >= 0 || normalized.indexOf('=') >= 0 || normalized.indexOf('&') >= 0
                || normalized.indexOf('|') >= 0 || normalized.indexOf('!') >= 0 || normalized.indexOf('<') >= 0
                || normalized.indexOf('>') >= 0 || normalized.indexOf(',') >= 0
                || normalized.indexOf(' ') >= 0) {
            throw new QueryException("map expects a single element accessor such as"
                    + " map { it.get().value('accountStatus') }, map { it.get().label() } or"
                    + " map { it.get().id() }, got: " + expression);
        }
        if (normalized.isEmpty()) {
            throw new QueryException("map expects a non-blank expression");
        }
        return normalized;
    }

    /** The property a {@code map { ... }} closure reads (empty for the pseudo keys). */
    static Set<String> pathKeys(String expression) {
        String key = pathKey(expression);
        if (isPseudo(key)) {
            return Set.of();
        }
        Set<String> keys = new LinkedHashSet<>();
        keys.add(key);
        return keys;
    }
}
