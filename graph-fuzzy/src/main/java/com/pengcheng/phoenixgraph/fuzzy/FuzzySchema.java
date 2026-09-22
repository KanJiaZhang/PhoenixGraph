package com.pengcheng.phoenixgraph.fuzzy;

import com.pengcheng.phoenixgraph.schema.GraphSchema;

/**
 * The names fuzzy linking uses in a graph schema, so every layer spells them the same way.
 *
 * <p>A graph that wants fuzzy linking declares three things: a vertex type holding the patterns
 * (the registry the query step reads), a vertex type holding the links, and one edge class from a
 * link to the vertex it links. The link's degree limits are what make a hub readable: a pattern
 * like "same IP prefix" can group a thousand accounts, and the edge class has to allow it.
 */
public final class FuzzySchema {

    /** The vertex type holding the patterns, one vertex per pattern. */
    public static final String PATTERN_VERTEX = "FuzzyPattern";

    /** The property holding the pattern's number. */
    public static final String PATTERN_ID_PROPERTY = "patternId";

    /** The vertex type holding the computed links. */
    public static final String LINK_VERTEX = "FuzzyLink";

    /** The property of a link holding its key. */
    public static final String KEY_PROPERTY = "key";

    /** The property of a link holding the pattern it came from. */
    public static final String PATTERN_PROPERTY = "pattern";

    /** The edge class from a link to a linked vertex. */
    public static final String LINK_EDGE = "FuzzyLinkedTo";

    /** The degree limit the link edge is expected to allow. */
    public static final int DEGREE_LIMIT = 1000;

    private FuzzySchema() {
    }

    /** The id of the vertex holding a pattern. */
    public static String patternVertexId(int patternId) {
        if (patternId < 0) {
            throw new FuzzyPatternException("A pattern id must not be negative, got " + patternId);
        }
        return "pattern_" + patternId;
    }

    /** The id of the vertex holding a link of a pattern. */
    public static String linkVertexId(int patternId, String key) {
        return "fl_" + patternId + "_" + FuzzyKey.of(key);
    }

    /**
     * The id of the edge from a link to one of the vertices it links.
     *
     * <p>The id is the pair, so writing the same account twice leaves one edge rather than two -
     * linking has to be idempotent, because it runs on every write of an account.
     */
    public static String linkEdgeId(String linkVertexId, Object linkedVertexId) {
        return linkVertexId + "|" + linkedVertexId;
    }

    /**
     * Whether a schema declares what fuzzy linking needs: the link vertex type and the edge class
     * from a link to the vertex it links.
     *
     * <p>The <em>patterns</em> are not part of the schema: they live in the pattern file an analyst
     * edits ({@code fuzzy-patterns.json}), so a graph does not have to carry a pattern vertex to
     * support linking - which is why {@code FuzzyPattern} was removed from the demo schema. The
     * constant stays for the graphs (and fixtures) that still keep one.
     */
    public static boolean isSupported(GraphSchema schema) {
        return schema != null
                && schema.vertexSchema(LINK_VERTEX) != null
                && schema.edgeSchema(LINK_EDGE) != null;
    }
}
