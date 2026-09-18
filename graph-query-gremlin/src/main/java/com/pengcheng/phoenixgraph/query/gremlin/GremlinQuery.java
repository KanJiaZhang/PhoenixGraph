package com.pengcheng.phoenixgraph.query.gremlin;

import com.pengcheng.phoenixgraph.query.QueryException;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A parsed Gremlin-style traversal starting from one or more vertices.
 *
 * <p>Supported shape:
 * <pre>{@code
 * g.V(vertexId, vertexName).out(edgeName).has(key, value).elementMap()
 * g.V(id1, id2, ..., vertexName).outE(edgeName).values(key)
 * }</pre>
 * The last argument inside {@code g.V(...)} is always the vertex schema name;
 * every preceding argument is a vertex id. An id argument may itself be a
 * comma-separated list ({@code '123,888'} or {@code '123，888'}). Steps are
 * {@code name(arg1, arg2, ...)}; ids/names/arguments may be bare tokens or
 * quoted with {@code '...'} / {@code "..."}; whitespace is ignored. The parser
 * does not interpret step names — resolution happens at execution time via the
 * {@link GremlinStepRegistry}.
 */
public final class GremlinQuery {

    private static final Pattern HEADER = Pattern.compile("^g\\s*\\.\\s*V\\s*\\(", Pattern.CASE_INSENSITIVE);

    /** One parsed step: its name and argument tokens. */
    public record Step(String name, List<String> args) {
    }

    private final List<Object> vertexIds;
    private final String vertexName;
    private final List<Step> steps;
    private final String logic;

    private GremlinQuery(List<Object> vertexIds, String vertexName, List<Step> steps, String logic) {
        this.vertexIds = vertexIds;
        this.vertexName = vertexName;
        this.steps = steps;
        this.logic = logic;
    }

    /** The starting vertex ids. */
    public List<Object> vertexIds() {
        return vertexIds;
    }

    public String vertexName() {
        return vertexName;
    }

    public List<Step> steps() {
        return steps;
    }

    /** The trailing {@code { ... }} logic block content, or {@code null}. */
    public String logic() {
        return logic;
    }

    /** Parses a Gremlin-style traversal query. */
    public static GremlinQuery parse(String query) {
        if (query == null || query.isBlank()) {
            throw new QueryException("Gremlin query must not be blank");
        }
        String s = query.trim();

        Matcher head = HEADER.matcher(s);
        if (!head.find()) {
            throw new QueryException("Expected 'g.V(vertexId..., vertexName)' prefix, got: " + s);
        }
        int open = head.end() - 1; // index of the '(' after g.V
        int close = matchingParen(s, open);
        String args = s.substring(open + 1, close);
        List<String> parts = splitArgs(args).stream().map(GremlinQuery::unquote).toList();
        if (parts.isEmpty()) {
            // 'g.V()' starts from nothing: a step that produces its own vertices (fuzzyLink) then
            // decides what the traversal walks, so the prefix carries neither an id nor a name
            String emptyRest = s.substring(close + 1).trim();
            String emptyLogic = null;
            int emptyBrace = indexOfOutsideQuotes(emptyRest, '{');
            if (emptyBrace >= 0) {
                emptyLogic = emptyRest.substring(emptyBrace + 1, matchingBrace(emptyRest, emptyBrace)).trim();
                emptyRest = emptyRest.substring(0, emptyBrace).trim();
            }
            return new GremlinQuery(List.of(), null, parseSteps(emptyRest), emptyLogic);
        }
        if (parts.size() < 2) {
            throw new QueryException("Expected 'g.V(vertexId..., vertexName)' with at least an id and a name, got: "
                    + args);
        }
        String vertexName = parts.get(parts.size() - 1);
        if (vertexName.isBlank()) {
            throw new QueryException("vertexName must not be blank in: " + s);
        }
        List<Object> vertexIds = new ArrayList<>();
        for (int i = 0; i < parts.size() - 1; i++) {
            for (String id : splitIds(parts.get(i))) {
                vertexIds.add(id);
            }
        }
        if (vertexIds.isEmpty()) {
            throw new QueryException("vertexId must not be blank in: " + s);
        }

        String rest = s.substring(close + 1).trim();
        String logic = null;
        int brace = indexOfOutsideQuotes(rest, '{');
        if (brace >= 0) {
            int logicClose = matchingBrace(rest, brace);
            logic = rest.substring(brace + 1, logicClose).trim();
            rest = rest.substring(0, brace).trim();
        }
        List<Step> steps = parseSteps(rest);
        return new GremlinQuery(vertexIds, vertexName, steps, logic);
    }

    /** Parses a step chain such as {@code .has(key, value).count()} into steps. */
    public static List<Step> parseSteps(String stepChain) {
        if (stepChain == null || stepChain.isBlank()) {
            return List.of();
        }
        List<Step> steps = new ArrayList<>();
        String rest = stepChain.trim();
        while (!rest.isEmpty()) {
            if (!rest.startsWith(".")) {
                throw new QueryException("Expected '.' before step, got: " + rest);
            }
            rest = rest.substring(1).trim();
            int stepOpen = rest.indexOf('(');
            if (stepOpen < 0) {
                throw new QueryException("Malformed step (missing '('): " + rest);
            }
            String name = rest.substring(0, stepOpen).trim();
            if (name.isEmpty()) {
                throw new QueryException("Step name must not be blank in: " + rest);
            }
            int stepClose = matchingParen(rest, stepOpen);
            List<String> stepArgs = splitArgs(rest.substring(stepOpen + 1, stepClose)).stream()
                    .map(GremlinQuery::unquote)
                    .toList();
            steps.add(new Step(name, stepArgs));
            rest = rest.substring(stepClose + 1).trim();
        }
        return List.copyOf(steps);
    }

    private static int matchingBrace(String s, int open) {
        int depth = 0;
        char quote = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        throw new QueryException("Unbalanced braces in: " + s);
    }

    private static int matchingParen(String s, int open) {
        int depth = 0;
        char quote = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        throw new QueryException("Unbalanced parentheses in: " + s);
    }

    /**
     * The index of the first {@code wanted} character that is not inside a quoted argument,
     * or -1. Arguments may be JSON ({@code geoNear('{"lat":31.2}')}), so punctuation inside
     * quotes must not be read as query syntax.
     */
    private static int indexOfOutsideQuotes(String s, char wanted) {
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == wanted) {
                return i;
            }
        }
        return -1;
    }

    /** Splits comma-separated arguments, ignoring commas inside quotes. */
    private static List<String> splitArgs(String s) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                current.append(c);
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
                current.append(c);
            } else if (c == ',') {
                result.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        String last = current.toString().trim();
        if (!last.isEmpty()) {
            result.add(last);
        }
        return result;
    }

    /** Splits an id argument on ASCII and fullwidth commas. */
    private static List<String> splitIds(String s) {
        List<String> ids = new ArrayList<>();
        for (String part : s.split("[,，]")) {
            String id = part.trim();
            if (!id.isEmpty()) {
                ids.add(id);
            }
        }
        return ids;
    }

    private static String unquote(String s) {
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }
}
