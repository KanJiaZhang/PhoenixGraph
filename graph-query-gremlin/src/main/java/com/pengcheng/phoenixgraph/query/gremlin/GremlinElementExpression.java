package com.pengcheng.phoenixgraph.query.gremlin;

/**
 * The Groovy element accessors a Gremlin client writes inside a {@code filter}/{@code map}
 * closure, and the two pseudo property names they read.
 *
 * <pre>{@code
 * it.get().value('accountStatus')   -> the property value
 * it.get().label()                  -> the element's label (schema name)
 * it.get().id()                     -> the element's id
 * it.get() / it                     -> the element itself (what a variable is bound to)
 * }</pre>
 *
 * <p>{@link #accessorAt(String, int)} recognises one of them at a position inside an expression,
 * which is what {@link GremlinExpression} tokenises; {@link #read(GremlinElement, String)} answers
 * the pseudo keys from the element itself rather than from its properties.
 */
final class GremlinElementExpression {

    /** The pseudo key standing for the element's label (schema name). */
    static final String LABEL_KEY = "@label";

    /** The pseudo key standing for the element's id. */
    static final String ID_KEY = "@id";

    /** The pseudo key standing for the element itself (what {@code it.get()} reads). */
    static final String ELEMENT_KEY = "@it";

    /** The pseudo key standing for the element's creation timestamp ({@code values('createdTime')}). */
    static final String CREATED_KEY = "@createdTime";

    /** The pseudo key standing for the element's last-update timestamp. */
    static final String UPDATED_KEY = "@lastUpdatedTime";

    /** The pseudo keys standing for an edge's two endpoints ({@code values('source')}). */
    static final String SOURCE_KEY = "@source";

    static final String TARGET_KEY = "@target";

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
    // it.get() and the bare it: the element itself, the value a closure binds to a variable
    private static final java.util.regex.Pattern ELEMENT = java.util.regex.Pattern.compile(
            "it\\s*\\.\\s*get\\s*\\(\\s*\\)");
    private static final java.util.regex.Pattern BARE_IT = java.util.regex.Pattern.compile(
            "it(?![A-Za-z0-9_])");

    private GremlinElementExpression() {
    }

    /** The accessor that starts at {@code index}, or {@code null} when there is none. */
    static Accessor accessorAt(String text, int index) {
        java.util.regex.Matcher matcher = VALUE.matcher(text);
        matcher.region(index, text.length());
        if (matcher.lookingAt()) {
            return new Accessor(resolveKey(matcher.group(2)), matcher.end());
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
        matcher = ELEMENT.matcher(text);
        matcher.region(index, text.length());
        if (matcher.lookingAt()) {
            return new Accessor(ELEMENT_KEY, matcher.end());
        }
        matcher = BARE_IT.matcher(text);
        matcher.region(index, text.length());
        if (matcher.lookingAt()) {
            return new Accessor(ELEMENT_KEY, matcher.end());
        }
        return null;
    }

    /** Whether a key is one of the element accessors rather than a stored property. */
    static boolean isPseudo(String key) {
        return LABEL_KEY.equals(key) || ID_KEY.equals(key) || ELEMENT_KEY.equals(key)
                || CREATED_KEY.equals(key) || UPDATED_KEY.equals(key)
                || SOURCE_KEY.equals(key) || TARGET_KEY.equals(key);
    }

    /**
     * The Gremlin spellings of the element accessors: {@code value('id')}/{@code value('$id')} mean
     * {@code id()} and {@code value('label')}/{@code value('$label')} mean {@code label()}, so they
     * resolve to the pseudo keys instead of looking for a property of that name.
     *
     * <p>The meta properties and an edge's endpoints are nameable the same way: a schema declares
     * {@code createdTime} and {@code lastUpdatedTime} as meta properties (not as regular ones), and
     * {@code source}/{@code target} as the endpoints of a directed edge, so {@code values('source')}
     * reads what the traversal knows rather than failing with "unknown property".
     */
    static String resolveKey(String key) {
        return switch (key.toLowerCase(java.util.Locale.ROOT)) {            case "id", "$id" -> ID_KEY;
            case "label", "$label" -> LABEL_KEY;
            case "createdtime", "$createdtime" -> CREATED_KEY;
            case "lastupdatedtime", "$lastupdatedtime" -> UPDATED_KEY;
            case "source", "$source" -> SOURCE_KEY;
            case "target", "$target" -> TARGET_KEY;
            default -> key;
        };
    }

    /**
     * The value of a (possibly pseudo) key for one element, which is what the {@code values(...)} step
     * answers.
     *
     * <p>A stored property wins; when the element has no such property, the element's own accessors
     * answer instead - {@code values('id')}, {@code values('createdTime')}, {@code values('source')} - so
     * a schema that declares those names as meta properties or as an edge's endpoints does not read as
     * "unknown property".
     */
    static Object value(GremlinElement element, String key) {
        Object property = element.properties().get(key);
        if (property != null) {
            return property;
        }
        String resolved = resolveKey(key);
        return isPseudo(resolved) ? read(element, resolved) : null;
    }

    /** Whether a key names one of the element accessors rather than a stored property. */
    static boolean isPseudoName(String key) {
        return isPseudo(resolveKey(key));
    }

    /** Reads a (possibly pseudo) key off an element: a property value, its label, its id or itself. */
    static Object read(GremlinElement element, String key) {
        if (LABEL_KEY.equals(key)) {
            return element.label();
        }
        if (ID_KEY.equals(key)) {
            return element.id();
        }
        if (ELEMENT_KEY.equals(key)) {
            return element;
        }
        if (CREATED_KEY.equals(key)) {
            return element.createdTime();
        }
        if (UPDATED_KEY.equals(key)) {
            return element.lastUpdatedTime();
        }
        if (SOURCE_KEY.equals(key)) {
            return element instanceof GremlinEdge edge ? edge.sourceEndpoint() : null;
        }
        if (TARGET_KEY.equals(key)) {
            return element instanceof GremlinEdge edge ? edge.targetEndpoint() : null;
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
        if (ELEMENT_KEY.equals(key)) {
            return null; // and no element either
        }
        return properties.get(key);
    }
}
