package com.pengcheng.phoenixgraph.http.dto;

import com.pengcheng.phoenixgraph.query.result.Subgraph;

import java.util.List;

/**
 * The answer of a Groovy-style script: the value it ended with, what {@code print}/{@code println} wrote,
 * and the subgraph its traversals touched.
 *
 * <p>The value is the one a reader wants on screen - the single printed value when the script printed once,
 * the list of printed values when it printed several, otherwise the last statement's value. The output lines
 * are the same values as text, for a client that shows a console-like block.
 *
 * @param result   the script's answer
 * @param output   the printed lines, in order
 * @param subgraph the union of the subgraphs of every traversal the script ran (may be empty)
 * @param compile  what the engine did to prepare the script on this call (may be null)
 */
public record ScriptResponse(Object result, List<String> output, Subgraph subgraph, Compile compile) {

    /**
     * The preparation cost of one script call.
     *
     * <p>A script is parsed once per script text and each traversal it runs is compiled once per traversal
     * text, both cached by the whole text - so a repeated query reports zero for all of these, and only a
     * text the engine has not seen yet pays. Writing a seed id into the script (a dashboard does) makes
     * every text new, the same trade the compiled queries make.
     *
     * @param parseMillis        time spent parsing the script text on this call (0 when it was cached)
     * @param parsedNow          whether this call parsed the script ({@code false} = it came from the cache)
     * @param traversalsCompiled how many of the script's traversals were compiled on this call
     * @param compileMillis      time spent compiling those traversals on this call
     */
    public record Compile(double parseMillis, boolean parsedNow, int traversalsCompiled, double compileMillis) {
    }

    /** A response without the preparation numbers. */
    public ScriptResponse(Object result, List<String> output, Subgraph subgraph) {
        this(result, output, subgraph, null);
    }

    public ScriptResponse {
        output = output == null ? List.of() : List.copyOf(output);
    }
}
