package com.pengcheng.phoenixgraph.query.gremlin;

/**
 * The Groovy element accessors a Gremlin client writes inside a {@code filter}/{@code map}
 * closure, and the two pseudo property names they read.
 *
 * <pre>{@code
 * it.get().value('accountStatus')   -> the property value
 * it.get().label()                  -> the element's label (schema name)
 * it.get().id()                     -> the element's id
 * }</pre>
 *
 * <p>{@link #accessorAt(String, int)} recognises one of them at a position inside an expression,
 * which is what {@link GremlinExpression} tokenises; {@link #read(GremlinElement, String)} answers
 * the two pseudo keys from the element itself rather than from its properties.
 */
final class GremlinElementExpression {

    /** The pseudo key standing for the element's label (schema name). */
    static final String LABEL_KEY = "@label";

    /** The pseudo key standing for the element's id. */
    static final String ID_KEY = "@id";

    /** An accessor found at a position: the property (or pseudo) key it reads and where it ends. */
    record Accessor(String key, int end) {
    }

    // the optional '.get()' and the optional 's' make it.get().value('k'), it.value('k') and
    // it.values('k') one and the same accessor
    private static final java.util.regex.Pattern VALUE = java.util.regex.Pattern.compile(
            "it\\s*\\.\\s*(?:get\\s*\\(\\s*\\)\\s*\\.\\s*)?values?\\s*\\(\\s*(['\"])([^'\"]*)\\1\\s*\\)");
    private static final java.util.regex.Pattern LABEL = java.util.regex.Pattern.compile(
            "it\\s*\\.\\s*(?:get\\s*\\(\\s*\\)\\s*\\.\\s*)?label\\s*\\(\\s*\\)");
    private static final java.util.regex.Pattern ID = java.util.regex.Pattern.compile(
            "it\\s*\\.\\s*(?:get\\s*\\(\\s*\\)\\s*\\.\\s*)?id\\s*\\(\\s*\\)");

    private GremlinElementExpression() {
    }

    /** The accessor that starts at {@code index}, or {@code null} when there is none. */
    static Accessor accessorAt(String text, int index) {
        java.util.regex.Matcher matcher = VALUE.matcher(text);
        matcher.region(index, text.length());
        if (matcher.lookingAt()) {
            return new Accessor(matcher.group(2), matcher.end());
        }
        matcher = LABEL.matcher(text);
        matcher.region(index, text.length());
        if (matcher.lookingAt()) {
            return new Accessor(LABEL_KEY, matcher.end());
        }
        matcher = ID.matcher(text);
        matcher.region(index, text.length());
        if (matcher.lookingAt()) {
            return new Accessor(ID_KEY, matcher.end());
        }
        return null;
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
}
