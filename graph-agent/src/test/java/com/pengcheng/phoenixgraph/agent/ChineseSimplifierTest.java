package com.pengcheng.phoenixgraph.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** The safe canonicalization tier: traditional and simplified spellings are the same entity. */
class ChineseSimplifierTest {

    @Test
    void traditionalNamesMapToTheirSimplifiedForm() {
        assertEquals("关羽", ChineseSimplifier.toSimplified("關羽"));
        assertEquals("张飞", ChineseSimplifier.toSimplified("張飛"));
        assertEquals("诸葛亮", ChineseSimplifier.toSimplified("諸葛亮"));
        assertEquals("孙权", ChineseSimplifier.toSimplified("孫權"));
    }

    @Test
    void simplifiedTextIsUntouched() {
        assertEquals("关羽", ChineseSimplifier.toSimplified("关羽"));
        assertEquals("曹操", ChineseSimplifier.toSimplified("曹操"));
    }

    @Test
    void charactersOutsideTheTableAreKept() {
        // 曹 and 操 are not traditional forms in the curated table, so they must stay put
        assertEquals("曹操", ChineseSimplifier.toSimplified("曹操"));
    }

    @Test
    void differentNamesStayDifferent() {
        assertNotEquals(ChineseSimplifier.toSimplified("關羽"), ChineseSimplifier.toSimplified("關張"));
    }
}
