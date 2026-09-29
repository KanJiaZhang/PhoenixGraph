package com.pengcheng.phoenixgraph.schema;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The character n-grams the inverted full-text index stores. */
class FullTextTokensTest {

    @Test
    void splitsIntoSingleCharacters() {
        assertEquals(java.util.Set.of("赤", "壁"), FullTextTokens.ngrams("赤壁", 1));
        assertEquals(java.util.Set.of("三", "国", "演", "义"), FullTextTokens.ngrams("三国演义", 1));
    }

    @Test
    void deduplicatesRepeatedCharacters() {
        // 「曹操操」只有两个不同的字，重复的「操」只保留一个 n-gram，posting list 才不重复写
        assertEquals(2, FullTextTokens.ngrams("曹操操", 1).size());
    }

    @Test
    void bigramsOverlap() {
        assertEquals(java.util.Set.of("三国", "国演", "演义"), FullTextTokens.ngrams("三国演义", 2));
    }

    @Test
    void shortTextIsOneToken() {
        assertEquals(java.util.Set.of("字"), FullTextTokens.ngrams("字", 2));
    }

    @Test
    void lookupTokensAreTheTermCharacters() {
        assertEquals(java.util.Set.of("赤", "壁"), FullTextTokens.lookupTokens("赤壁", 1));
    }

    @Test
    void emptyAndNullYieldNothing() {
        assertTrue(FullTextTokens.ngrams(null, 1).isEmpty());
        assertTrue(FullTextTokens.ngrams("", 1).isEmpty());
        assertTrue(FullTextTokens.ngrams("赤壁", 0).isEmpty());
    }
}
