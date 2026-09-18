package com.pengcheng.phoenixgraph.http.dto;

/**
 * A context a pattern is run against, and what to run it on.
 *
 * <p>The same payload serves the three questions the fuzzy endpoints answer: "what would this
 * expression produce for these details" ({@code expression} + {@code context}), "what does pattern 0
 * produce for this account" ({@code patternId} + {@code vertexName} + {@code vertexId}), and "link
 * this account" (the same, but written). A field that is not needed stays empty.
 *
 * @param graph       the graph the context belongs to
 * @param patternId   a pattern already in the graph, when the caller wants its key
 * @param expression  an expression to try out, when the caller is writing one
 * @param name        a name for the expression being tried out
 * @param description what the expression being tried out looks for
 * @param context     the details, as a JSON object ({@code {"email":"a@b.com","ip":"10.20.31.7"}})
 * @param vertexName  a vertex type whose properties complete the context
 * @param vertexId    the vertex to read those properties from (and to link, on the link endpoint)
 */
public record FuzzyPreviewRequest(String graph, Integer patternId, String expression, String name,
                                  String description, String context, String vertexName, Object vertexId) {

    public FuzzyPreviewRequest {
        context = context == null ? "" : context;
    }
}
