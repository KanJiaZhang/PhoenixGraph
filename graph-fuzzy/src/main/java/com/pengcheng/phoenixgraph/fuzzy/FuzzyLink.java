package com.pengcheng.phoenixgraph.fuzzy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One link a pattern produced: the vertex to write and the key it stands for.
 *
 * <p>It is the whole write side of fuzzy linking, computed without touching storage: the caller
 * writes the link vertex and one edge from it to the linked vertex. Two accounts that produce the
 * same {@code linkVertexId} end up on the same node, which is what "linked" means here - nobody
 * chose it, a pattern did.
 *
 * @param patternId    the pattern that produced it
 * @param patternText  the expression, kept on the link so a node can explain itself
 * @param key          the link key (already normalised)
 * @param fragments    what each part of the pattern contributed (the key is their concatenation)
 * @param linkVertexId the vertex id to write
 */
public record FuzzyLink(int patternId, String patternText, String key, List<String> fragments,
                        String linkVertexId) {

    public FuzzyLink {
        fragments = fragments == null ? List.of() : List.copyOf(fragments);
    }

    /** Whether the key came from every part of the pattern (an {@code opt(...)} part shows as *). */
    public boolean complete() {
        return fragments.stream().noneMatch("*"::equals);
    }
}
