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
 */
public record ScriptResponse(Object result, List<String> output, Subgraph subgraph) {

    public ScriptResponse {
        output = output == null ? List.of() : List.copyOf(output);
    }
}
