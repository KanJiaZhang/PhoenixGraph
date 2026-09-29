package com.pengcheng.phoenixgraph.schema;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Character n-grams of a text, the tokens an inverted full-text index stores.
 *
 * <p>Chinese has no word boundaries, so a character (an n-gram of width 1) is the
 * natural token: indexing every character of {@code history}/{@code novel} and then
 * intersecting the posting lists of the characters of a query term is what turns
 * {@code TextP.containing('赤壁')} into two point reads plus a tiny verification,
 * instead of scanning every element.
 *
 * <p>The n-grams are code-point based, so surrogate pairs (rare CJK extension
 * characters, emoji) count as one character and are never split in half.
 */
public final class FullTextTokens {

    private FullTextTokens() {
    }

    /**
     * The distinct n-grams of {@code text}, in first-seen order. An n-gram wider than
     * the text yields the whole text as its single token; {@code nGram == 1} yields the
     * distinct characters.
     */
    public static Set<String> ngrams(String text, int nGram) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null || text.isEmpty() || nGram < 1) {
            return out;
        }
        int[] points = text.codePoints().toArray();
        if (points.length < nGram) {
            // the whole text is one token when it is shorter than the n-gram width
            out.add(text);
            return out;
        }
        for (int i = 0; i + nGram <= points.length; i++) {
            out.add(new String(points, i, nGram));
        }
        return out;
    }

    /**
     * The lookup tokens for a query term: the characters the term consists of. The store
     * intersects their posting lists to find candidates; the caller then verifies each
     * candidate against the stored text, because character intersection alone does not
     * guarantee the characters are contiguous.
     */
    public static Set<String> lookupTokens(String term, int nGram) {
        return ngrams(term, nGram);
    }
}
