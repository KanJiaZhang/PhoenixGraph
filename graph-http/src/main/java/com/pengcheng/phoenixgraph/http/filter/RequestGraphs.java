package com.pengcheng.phoenixgraph.http.filter;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which graph a request is about - the one thing two callers-aside filters need to know before the request
 * reaches a controller.
 *
 * <p>Both the request gate (per-graph limits) and the API key filter (per-graph permissions) have to answer
 * this before Spring has parsed anything, so it is resolved from the path when the path names a graph and
 * from the (buffered) body when it does not. It lives here, once, because two answers to "which graph is
 * this?" would eventually disagree - and a disagreement between "who may touch it" and "how fast may they"
 * is a security bug, not a cosmetic one.
 */
public final class RequestGraphs {

    /** Path segments whose next segment is *not* a graph name (they are verbs of the collection). */
    private static final Set<String> NOT_A_GRAPH = Set.of("flush", "reset", "prometheus", "validate",
            "storage-types", "tools", "ingest", "graphs", "batch", "query", "vertices", "edges",
            "fuzzy", "schema", "agent", "metrics", "vector-index", "audit", "health", "v1", "api");

    private static final Pattern GRAPH_NAME = Pattern.compile("\"graphName\"\\s*:\\s*\"([^\"]{1,120})\"");
    private static final Pattern GRAPH = Pattern.compile("\"graph\"\\s*:\\s*\"([^\"]{1,120})\"");

    private RequestGraphs() {
    }

    /** The graph named in the path, or null. */
    public static String ofPath(String uri) {
        String[] parts = uri.split("/");
        for (int i = 0; i < parts.length - 1; i += 1) {
            if (parts[i].equals("graphs") || parts[i].equals("metrics") || parts[i].equals("schema")
                    || parts[i].equals("agent")) {
                String candidate = parts[i + 1];
                if (!candidate.isBlank() && !NOT_A_GRAPH.contains(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /** The graph named in a JSON body ({@code graphName} first, then {@code graph}), or null. */
    public static String ofBody(String body) {
        if (body == null || body.isEmpty()) return null;
        Matcher named = GRAPH_NAME.matcher(body);
        if (named.find()) return named.group(1);
        Matcher other = GRAPH.matcher(body);
        return other.find() ? other.group(1) : null;
    }

    /** The graph this request is about: path first (authoritative), then body. */
    public static String of(HttpServletRequest request, String body) {
        String fromPath = ofPath(request.getRequestURI());
        return fromPath != null ? fromPath : ofBody(body);
    }
}
