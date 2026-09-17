package com.pengcheng.phoenixgraph.http.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A traversal query request.
 *
 * @param graphName      the graph schema name used to resolve vertex/edge/property names
 * @param query          the query string, written in {@code language}
 * @param params         optional parameters: {@code ${paramName}} placeholders are substituted
 *                       textually, and the Cypher engine additionally resolves {@code $name}
 *                       references from this map
 * @param returnSubgraph whether to return the visited subgraph along with (Cypher) or instead of
 *                       (Gremlin) the query result
 * @param language       {@code gremlin} (the default) or {@code cypher}
 */
public record QueryRequest(String graphName, String query, Map<String, String> params,
                           boolean returnSubgraph, String language) {

    /** Absent parameters become an empty map, so callers never see {@code null}. */
    public QueryRequest {
        params = params == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }

    /** A Gremlin request; the language was added later and defaults to it. */
    public QueryRequest(String graphName, String query, Map<String, String> params, boolean returnSubgraph) {
        this(graphName, query, params, returnSubgraph, "gremlin");
    }

    /** The language to execute, defaulting to Gremlin when it is absent. */
    public String languageOrDefault() {
        return language == null || language.isBlank()
                ? "gremlin" : language.trim().toLowerCase(Locale.ROOT);
    }
}
