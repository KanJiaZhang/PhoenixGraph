package com.pengcheng.phoenixgraph.http.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A traversal query request.
 *
 * @param graphName      the graph schema name used to resolve vertex/edge/property names
 * @param query          the query string, written in {@code language}
 * @param params         optional parameters: {@code ${paramName}} placeholders are substituted
 *                       textually, and the Cypher engine additionally resolves {@code $name}
 *                       references from this map
 * @param returnSubgraph whether the visited subgraph is wanted (Cypher always answers with
 *                       columns and rows, and adds the subgraph when this is set)
 * @param language       {@code gremlin} (the default) or {@code cypher}
 * @param includeResult  whether the answer itself is wanted next to the subgraph: a
 *                       {@code count()} has no graph of its own and a walk has no single
 *                       value, so a UI asks for both
 * @param source         who asked: {@code ui}, {@code http} (default), {@code algorithm} or
 *                       {@code internal}
 * @param script         whether the query is a Groovy-style script (statements, variables,
 *                       {@code print}) rather than one traversal; {@code null} means "decide from the
 *                       text", which is what a client that does not know the difference sends
 */
public record QueryRequest(String graphName, String query, Map<String, String> params,
                           boolean returnSubgraph, String language, Boolean includeResult, String source,
                           Boolean script) {

    /** Absent parameters become an empty map, so callers never see {@code null}. */
    public QueryRequest {
        params = params == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }

    /** A Gremlin request; the language was added later and defaults to it. */
    public QueryRequest(String graphName, String query, Map<String, String> params, boolean returnSubgraph) {
        this(graphName, query, params, returnSubgraph, "gremlin", null, null, null);
    }

    /** A Gremlin request that only says which language to use. */
    public QueryRequest(String graphName, String query, Map<String, String> params, boolean returnSubgraph,
                        String language) {
        this(graphName, query, params, returnSubgraph, language, null, null, null);
    }

    /** A request with the language and the value flag, but no source. */
    public QueryRequest(String graphName, String query, Map<String, String> params, boolean returnSubgraph,
                        String language, Boolean includeResult) {
        this(graphName, query, params, returnSubgraph, language, includeResult, null, null);
    }

    /** The language to execute, defaulting to Gremlin when it is absent. */
    public String languageOrDefault() {
        return language == null || language.isBlank()
                ? "gremlin" : language.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** Who asked: {@code ui}, {@code http} (default), {@code algorithm} or {@code internal}. */
    public String sourceOrDefault() {
        return source == null || source.isBlank() ? "http" : source.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** Whether the answer is wanted as well as the subgraph. */
    public boolean includeResultOrDefault() {
        return Boolean.TRUE.equals(includeResult);
    }
}
