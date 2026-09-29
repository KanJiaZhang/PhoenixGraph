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

    private static final Pattern HEADER = Pattern.compile("^g\\s*\\.\\s*([VE]|inject)\\s*\\(",
            Pattern.CASE_INSENSITIVE);

    /** One parsed step: its name and argument tokens. */
    public record Step(String name, List<String> args) {
    }

    /**
     * Where a traversal starts.
     *
     * <p>A traversal either names ids ({@code g.V('135','Account')}), names a whole type
     * ({@code g.V('Account')}, {@code g.E('Payment')} - the form a scan answers) or names nothing at
     * all ({@code g.V()}), which is what a step that produces its own elements starts from.
     */
    public record Source(Kind kind, List<Object> ids, String name) {

        /** How a traversal starts. */
        public enum Kind {
            /** Named vertex ids of one type. */
            VERTICES,
            /** Every vertex of one type. */
            VERTEX_TYPE,
            /** Every edge of one class. */
            EDGE_TYPE,
            /** Named edge ids of one class. */
            EDGES,
            /** No starting point (a step produces them). */
            NONE,
            /** {@code g.V()}: no ids and no type yet - a first step naming a vertex type says which. */
            NO_VERTICES,
            /** {@code g.E()}: no ids and no class yet - a first step naming an edge class says which. */
            NO_EDGES,
            /**
             * {@code g.V(DIST)}: every vertex, scanned in partitions by one mapper each. The flag says
             * <em>where</em> the scan runs; the rows it returns are the same ones {@code g.V()} returns,
             * so a query can be run both ways and compared.
             */
            NO_VERTICES_DIST,
            /** {@code g.V(DIST, 'Account')}: one vertex type, scanned in partitions. */
            VERTEX_TYPE_DIST,
            /** {@code g.E(DIST)}: every edge of every class, scanned in partitions. */
            NO_EDGES_DIST,
            /** {@code g.E(DIST, 'Payment')}: one edge class, scanned in partitions. */
            EDGE_TYPE_DIST
        }

        public Source {
            ids = ids == null ? List.of() : List.copyOf(ids);
        }

        static Source vertices(List<Object> ids, String name) {
            return new Source(Kind.VERTICES, ids, name);
        }

        static Source vertexType(String name) {
            return new Source(Kind.VERTEX_TYPE, List.of(), name);
        }

        static Source edgeType(String name) {
            return new Source(Kind.EDGE_TYPE, List.of(), name);
        }

        static Source edges(List<Object> ids, String name) {
            return new Source(Kind.EDGES, ids, name);
        }

        static Source none() {
            return new Source(Kind.NONE, List.of(), null);
        }

        /** {@code g.V(DIST)}: the whole vertex space, scanned in partitions. */
        static Source distributedVertices() {
            return new Source(Kind.NO_VERTICES_DIST, List.of(), null);
        }

        /** {@code g.V(DIST, 'Account')}: one vertex type, scanned in partitions. */
        static Source vertexTypeDistributed(String name) {
            return new Source(Kind.VERTEX_TYPE_DIST, List.of(), name);
        }

        /** {@code g.E(DIST)}: the whole edge space, scanned in partitions. */
        static Source distributedEdges() {
            return new Source(Kind.NO_EDGES_DIST, List.of(), null);
        }

        /** {@code g.E(DIST, 'Payment')}: one edge class, scanned in partitions. */
        static Source edgeTypeDistributed(String name) {
            return new Source(Kind.EDGE_TYPE_DIST, List.of(), name);
        }

        /** Whether the start asks for a distributed (partitioned) scan. */
        public boolean distributed() {
            return kind == Kind.NO_VERTICES_DIST || kind == Kind.VERTEX_TYPE_DIST
                    || kind == Kind.NO_EDGES_DIST || kind == Kind.EDGE_TYPE_DIST;
        }

        /** Whether the start is a vertex-side scan (distributed or not). */
        public boolean vertices() {
            return kind == Kind.NO_VERTICES || kind == Kind.NO_VERTICES_DIST
                    || kind == Kind.VERTICES || kind == Kind.VERTEX_TYPE
                    || kind == Kind.VERTEX_TYPE_DIST;
        }

        /** Whether the start is an edge-side scan (distributed or not). */
        public boolean edges() {
            return kind == Kind.NO_EDGES || kind == Kind.NO_EDGES_DIST
                    || kind == Kind.EDGES || kind == Kind.EDGE_TYPE || kind == Kind.EDGE_TYPE_DIST;
        }

        static Source noVertices() {
            return new Source(Kind.NO_VERTICES, List.of(), null);
        }

        static Source noEdges() {
            return new Source(Kind.NO_EDGES, List.of(), null);
        }

        /** Whether this source walks a whole type (and therefore needs a store that can scan). */
        public boolean scans() {
            return kind == Kind.VERTEX_TYPE || kind == Kind.EDGE_TYPE
                    || kind == Kind.VERTEX_TYPE_DIST || kind == Kind.EDGE_TYPE_DIST;
        }
    }

    private final Source source;
    private final List<Step> steps;
    private final String logic;

    private GremlinQuery(Source source, List<Step> steps, String logic) {
        this.source = source;
        this.steps = steps;
        this.logic = logic;
    }

    /** Where the traversal starts. */
    public Source source() {
        return source;
    }

    /** The starting vertex ids (empty for a scan or for {@code g.V()}). */
    public List<Object> vertexIds() {
        return source.ids();
    }

    /** The vertex type of a {@code g.V(...)} start, or {@code null} (an edge start names an edge class). */
    public String vertexName() {
        return source.kind() == Source.Kind.VERTICES || source.kind() == Source.Kind.VERTEX_TYPE
                || source.kind() == Source.Kind.VERTEX_TYPE_DIST
                ? source.name() : null;
    }

    /** The edge class of a {@code g.E(...)} start (a whole-class scan or named ids), or {@code null}. */
    public String edgeName() {
        return source.kind() == Source.Kind.EDGE_TYPE || source.kind() == Source.Kind.EDGES
                || source.kind() == Source.Kind.EDGE_TYPE_DIST
                ? source.name() : null;
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
        String s = stripComments(query).trim();
        if (s.isEmpty()) {
            throw new QueryException("Gremlin query must not be blank"); // the query was all comment
        }

        Matcher head = HEADER.matcher(s);
        if (!head.find()) {
            throw new QueryException("Expected 'g.V(...)', 'g.E(edgeName)' or 'g.inject(...)' prefix, got: " + s);
        }
        int open = head.end() - 1; // index of the '(' after the start step
        int close = matchingParen(s, open);
        String args = s.substring(open + 1, close);
        List<String> parts = splitArgs(args).stream().map(GremlinQuery::unquote).toList();
        String rest = s.substring(close + 1).trim();
        String logic = null;
        int brace = indexOfLogicBrace(rest);
        if (brace >= 0) {
            int logicClose = matchingBrace(rest, brace);
            logic = rest.substring(brace + 1, logicClose).trim();
            rest = rest.substring(0, brace).trim();
        }
        List<Step> steps = parseSteps(rest);
        String method = head.group(1);
        if (method.equalsIgnoreCase("E")) {
            return new GremlinQuery(edgeSource(parts, args), steps, logic);
        }
        if (method.equalsIgnoreCase("inject")) {
            // g.inject(v...): the values enter the traversal, which is how a query starts from constants
            List<Step> all = new ArrayList<>();
            all.add(new Step("inject", List.copyOf(parts)));
            all.addAll(steps);
            return new GremlinQuery(Source.none(), all, logic);
        }
        return new GremlinQuery(vertexSource(parts, args), steps, logic);
    }

    /**
     * Removes the comments a Gremlin client writes - {@code //} to the end of the line and
     * {@code /* ... *}{@code /} - as Groovy does.
     *
     * <p>String literals are respected, so an address like {@code 'http://host/path'} and a regular
     * expression inside a closure keep their markers: only a marker outside a string (and outside a
     * quoted key) starts a comment. A comment becomes one space, so the tokens around it stay apart.
     * A commented-out step therefore removes itself - {@code .both() //.both()} is one hop.
     */
    static String stripComments(String query) {
        StringBuilder out = new StringBuilder(query.length());
        char quote = 0;
        for (int i = 0; i < query.length(); i++) {
            char c = query.charAt(i);
            if (quote != 0) {
                out.append(c);
                if (c == '\\' && i + 1 < query.length()) {
                    out.append(query.charAt(++i)); // an escaped character inside the literal
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                out.append(c);
                continue;
            }
            if (c == '/' && i + 1 < query.length() && query.charAt(i + 1) == '/') {
                while (i < query.length() && query.charAt(i) != '\n') {
                    i++;
                }
                out.append(' ');
                if (i < query.length()) {
                    out.append('\n'); // keep the line break so statements stay separated
                }
                continue;
            }
            if (c == '/' && i + 1 < query.length() && query.charAt(i + 1) == '*') {
                int end = query.indexOf("*/", i + 2);
                i = end < 0 ? query.length() - 1 : end + 1;
                out.append(' ');
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * The start of a {@code g.V(...)} traversal: ids plus a type, a type on its own (every vertex of
     * it), or nothing at all.
     */
    private static Source vertexSource(List<String> parts, String args) {
        boolean distributed = !parts.isEmpty() && isDistributionFlag(parts.get(0));
        if (distributed) {
            // the flag says where the scan runs, it is not an id or a type
            parts = List.copyOf(parts.subList(1, parts.size()));
        }
        if (parts.isEmpty()) {
            // 'g.V()' starts from nothing: a step that produces its own vertices (fuzzyLink) then
            // decides what the traversal walks, so the prefix carries neither an id nor a name
            return distributed ? Source.distributedVertices() : Source.noVertices();
        }
        if (parts.size() == 1) {
            // one argument is a vertex type, not an id: without a name there is nothing to read the id
            // against, and 'every vertex of this type' is the question a scan answers
            String vertexName = parts.get(0);
            if (vertexName.isBlank()) {
                throw new QueryException("vertexName must not be blank in: g.V(" + args + ")");
            }
            if (args.trim().endsWith(",")) {
                // 'g.V(1, )': the comma says a name was meant, and one argument is not a name and an id
                throw new QueryException("Expected 'g.V(vertexId..., vertexName)' or 'g.V(vertexName)' -"
                        + " a trailing comma means the vertex name is missing in: g.V(" + args + ")");
            }
            return distributed ? Source.vertexTypeDistributed(vertexName) : Source.vertexType(vertexName);
        }
        if (distributed) {
            throw new QueryException("g.V(DIST, ...) scans a whole type in partitions, so it takes a"
                    + " vertex name and no ids; named ids are read by key in one process. Got: g.V("
                    + args + ")");
        }
        String vertexName = parts.get(parts.size() - 1);
        if (vertexName.isBlank()) {
            throw new QueryException("vertexName must not be blank in: g.V(" + args + ")");
        }
        List<Object> vertexIds = new ArrayList<>();
        for (int i = 0; i < parts.size() - 1; i++) {
            for (String id : splitIds(parts.get(i))) {
                vertexIds.add(id);
            }
        }
        if (vertexIds.isEmpty()) {
            throw new QueryException("vertexId must not be blank in: g.V(" + args + ")");
        }
        return Source.vertices(vertexIds, vertexName);
    }

    /** Whether a start argument is the distribution flag: {@code DIST} (any case). */
    private static boolean isDistributionFlag(String argument) {
        String text = argument == null ? "" : argument.trim();
        return text.equalsIgnoreCase("DIST") || text.equalsIgnoreCase("DISTRIBUTED");
    }

    /**
     * The start of a {@code g.E(...)} traversal: one edge class, which stands for every edge of it.
     *
     * <p>An edge class is a scan for the same reason a vertex type is one: an edge is identified by
     * its endpoints and its creation time, so there is no id a caller could list to mean "all of
     * them". The direction is the one the class stores its edges under.
     */
    private static Source edgeSource(List<String> parts, String args) {
        boolean distributed = !parts.isEmpty() && isDistributionFlag(parts.get(0));
        if (distributed) {
            parts = List.copyOf(parts.subList(1, parts.size()));
        }
        if (parts.isEmpty()) {
            // 'g.E()' starts from nothing, like 'g.V()': a first step naming an edge class says which
            return distributed ? Source.distributedEdges() : Source.noEdges();
        }
        if (parts.size() == 1) {
            String edgeName = parts.get(0);
            if (edgeName.isBlank()) {
                throw new QueryException("edgeName must not be blank in: g.E(" + args + ")");
            }
            if (args.trim().endsWith(",")) {
                // 'g.E('pay_1', )': the comma says an id was meant, and one argument is a class, not an id
                throw new QueryException("Expected 'g.E(edgeId..., edgeName)' or 'g.E(edgeName)' -"
                        + " a trailing comma means the edge class is missing in: g.E(" + args + ")");
            }
            return distributed ? Source.edgeTypeDistributed(edgeName) : Source.edgeType(edgeName);
        }
        if (distributed) {
            throw new QueryException("g.E(DIST, ...) scans a whole class in partitions, so it takes an"
                    + " edge class name and no ids. Got: g.E(" + args + ")");
        }
        String edgeName = parts.get(parts.size() - 1);
        if (edgeName.isBlank()) {
            throw new QueryException("edgeName must not be blank in: g.E(" + args + ")");
        }
        List<Object> edgeIds = new ArrayList<>();
        for (int i = 0; i < parts.size() - 1; i++) {
            for (String id : splitIds(parts.get(i))) {
                edgeIds.add(id);
            }
        }
        if (edgeIds.isEmpty()) {
            throw new QueryException("edgeId must not be blank in: g.E(" + args + ")");
        }
        return Source.edges(edgeIds, edgeName);
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
            // a step is called with parentheses - has('key'), out() - or, the Groovy way, with a
            // closure: filter { ... }, map { ... }. The first of the two after the name opens it.
            int paren = indexOfOutsideQuotes(rest, '(');
            int brace = indexOfOutsideQuotes(rest, '{');
            boolean closure;
            int stepOpen;
            if (paren >= 0 && (brace < 0 || paren < brace)) {
                stepOpen = paren;
                closure = false;
            } else if (brace >= 0) {
                stepOpen = brace;
                closure = true;
            } else {
                throw new QueryException("Malformed step (missing '('): " + rest);
            }
            String name = rest.substring(0, stepOpen).trim();
            if (name.isEmpty()) {
                throw new QueryException("Step name must not be blank in: " + rest);
            }
            int stepClose = closure ? matchingBrace(rest, stepOpen) : matchingParen(rest, stepOpen);
            List<String> stepArgs;
            if (closure) {
                // the body is one argument: it is an expression, not a comma-separated call
                String body = rest.substring(stepOpen + 1, stepClose).trim();
                stepArgs = body.isEmpty() ? List.of() : List.of(body);
            } else {
                stepArgs = splitArgs(rest.substring(stepOpen + 1, stepClose)).stream()
                        .map(GremlinQuery::unquote)
                        .toList();
            }
            steps.add(new Step(name, stepArgs));
            rest = rest.substring(stepClose + 1).trim();
        }
        return List.copyOf(steps);
    }

    /**
     * The index of the {@code {} that opens the trailing logic block, or -1.
     *
     * <p>A logic block follows the traversal's last step call ({@code .out(knows) { $logic }}), and a
     * closure belongs to the step name in front of it ({@code .filter { ... }}). The two are told apart
     * by what precedes the brace: a step call ends with {@code )}, a step name does not.
     */
    private static int indexOfLogicBrace(String s) {
        char quote = 0;
        // a brace inside a step's parentheses or closure belongs to that step
        // (`map { ... if (x) { a } else { b } }`), only one at depth 0 is the trailing logic block
        int depth = 0;
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
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == '{') {
                if (depth == 0 && followsStepCall(s, i)) {
                    return i;
                }
                depth++;
            } else if (c == '}') {
                depth--;
            }
        }
        return -1;
    }

    /** Whether what precedes {@code at} is whitespace, the end of a step call, or the end of a step closure. */
    private static boolean followsStepCall(String s, int at) {
        int i = at - 1;
        while (i >= 0 && Character.isWhitespace(s.charAt(i))) {
            i--;
        }
        return i < 0 || s.charAt(i) == ')' || s.charAt(i) == '}';
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
     * or -1. Arguments may be JSON ({@code circle('{"latitude":31.2,"longitude":121.4}', 1000)}), so punctuation inside
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

    /** Splits comma-separated arguments, ignoring commas inside quotes and nested brackets. */
    private static List<String> splitArgs(String s) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                current.append(c);
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                current.append(c);
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
                current.append(c);
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
                current.append(c);
            } else if (c == ',' && depth == 0) {
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
