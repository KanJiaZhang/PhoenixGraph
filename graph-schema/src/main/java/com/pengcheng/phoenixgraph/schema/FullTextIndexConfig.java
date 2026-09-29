package com.pengcheng.phoenixgraph.schema;

/**
 * The settings of a full-text index declared with {@code isFullText} on a property.
 *
 * <p>The index is an <em>inverted</em> index: the value of the property is split
 * into character n-grams, and each n-gram maps to the list of element ids whose
 * value contains it. A {@code TextP.containing('赤壁')} query then reads the
 * posting list of {@code 赤} and {@code 壁} (with {@code nGram = 1}), intersects
 * them, and only verifies that tiny candidate set against the stored text — which
 * is what turns a table scan into a handful of point reads.
 *
 * <p>{@code nGram} is the width of the indexed tokens: {@code 1} indexes single
 * characters (the natural token for Chinese, which has no word boundaries),
 * {@code 2} indexes bigrams. A larger n-gram makes each posting list shorter but
 * needs more tokens to look up; {@code 1} is the default and the most robust.
 *
 * @param nGram the width of the indexed character n-grams ({@code 1} = single characters)
 */
public record FullTextIndexConfig(int nGram) {

    /** Single-character tokens (the natural token for Chinese text). */
    public static final int DEFAULT_NGRAM = 1;

    /** The largest n-gram width that still makes sense for a text index. */
    public static final int MAX_NGRAM = 8;

    /** The default full-text index settings (single characters). */
    public static final FullTextIndexConfig DEFAULT = new FullTextIndexConfig(DEFAULT_NGRAM);

    public FullTextIndexConfig {
        if (nGram < 1 || nGram > MAX_NGRAM) {
            throw new IllegalArgumentException(
                    "Full-text index nGram must be between 1 and " + MAX_NGRAM + ", got " + nGram);
        }
    }

    /** The default settings (single characters). */
    public static FullTextIndexConfig defaults() {
        return DEFAULT;
    }

    public FullTextIndexConfig withNGram(int newNGram) {
        return new FullTextIndexConfig(newNGram);
    }
}
