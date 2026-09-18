package com.pengcheng.phoenixgraph.http.dto;

/**
 * Creating or replacing a fuzzy pattern.
 *
 * @param graph       the graph the pattern belongs to
 * @param id          the pattern number (a pattern is identified by it)
 * @param name        a human readable name
 * @param expression  the pattern itself, e.g. {@code emailDomain(email)+'_'+ip2(ip)}
 * @param description what it looks for
 * @param enabled     whether queries use it (defaults to true)
 */
public record FuzzyPatternRequest(String graph, Integer id, String name, String expression,
                                  String description, Boolean enabled) {

    public FuzzyPatternRequest {
        if (graph == null || graph.isBlank()) {
            throw new IllegalArgumentException("graph must not be blank");
        }
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("expression must not be blank");
        }
    }
}
