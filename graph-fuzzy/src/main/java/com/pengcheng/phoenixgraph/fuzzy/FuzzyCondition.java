package com.pengcheng.phoenixgraph.fuzzy;

import java.util.ArrayList;
import java.util.List;

/**
 * A condition a sequence pattern carries: a test over the fields of the events its steps matched.
 *
 * <pre>
 * [Login.type == 'Mobile']
 * [Dispute.ip == Login.ip || Dispute.cookie == Payment.cookie || Dispute.DisputeReason == 'NotMyCard']
 * </pre>
 *
 * <p>A sequence is the shape of a session; a condition says which sessions of that shape count. The two
 * sides of a comparison are a field path ({@code Dispute.ip}) or a literal ({@code 'Mobile'}), and a
 * field path is read from the events the steps matched: {@code Login.ip} is the address of the Login the
 * first step matched, whichever event of the list that turned out to be. The name in the path is the
 * event's own name ({@code Login}) or the step that matched it ({@code L0}).
 *
 * <p>Operators: {@code ==}, {@code !=}, {@code &&}, {@code ||}, and parentheses; {@code &&} binds tighter
 * than {@code ||}. A comparison whose field is not there is <b>false</b> - a pattern does not fire on
 * evidence that is missing, rather than reading "unknown" as a match. Values are compared as text, so a
 * card BIN keeps its leading zero.
 */
public final class FuzzyCondition {

    private final String text;
    private final Node root;

    private FuzzyCondition(String text, Node root) {
        this.text = text;
        this.root = root;
    }

    /** Parses a condition; the message says what was expected and where. */
    public static FuzzyCondition parse(String text) {
        if (text == null || text.isBlank()) {
            throw new FuzzyPatternException("A fuzzy condition must not be blank");
        }
        String trimmed = text.trim();
        Parser parser = new Parser(trimmed);
        Node root = parser.parseOr();
        parser.expectEnd();
        return new FuzzyCondition(trimmed, root);
    }

    /** Whether the parser accepts the condition. */
    public static boolean isValid(String text) {
        try {
            parse(text);
            return true;
        } catch (FuzzyPatternException e) {
            return false;
        }
    }

    /** The condition as written. */
    public String text() {
        return text;
    }

    /** Whether the condition holds for a context (built from the events the steps matched). */
    public boolean test(FuzzyContext context) {
        return root.test(context);
    }

    /** The field names the condition reads, in the order they appear. */
    public List<String> fields() {
        List<String> out = new ArrayList<>();
        collectFields(root, out);
        return List.copyOf(out);
    }

    private static void collectFields(Node node, List<String> out) {
        if (node instanceof Compare compare) {
            addIfField(compare.left(), out);
            addIfField(compare.right(), out);
        } else if (node instanceof Truthy truthy) {
            addIfField(truthy.operand(), out);
        } else if (node instanceof And and) {
            and.terms().forEach(term -> collectFields(term, out));
        } else if (node instanceof Or or) {
            or.terms().forEach(term -> collectFields(term, out));
        }
    }

    private static void addIfField(Operand operand, List<String> out) {
        if (operand instanceof Operand.Field field && !out.contains(field.name())) {
            out.add(field.name());
        }
    }

    /** One side of a comparison. */
    private sealed interface Operand {

        /** A literal written in the condition ({@code 'Mobile'}). */
        record Literal(String text) implements Operand {
        }

        /** A field of the matched events ({@code Dispute.ip}). */
        record Field(String name) implements Operand {
        }
    }

    /** A node of the condition tree. */
    private sealed interface Node {
        boolean test(FuzzyContext context);
    }

    private record Compare(Operand left, String operator, Operand right) implements Node {

        @Override
        public boolean test(FuzzyContext context) {
            String l = value(left, context);
            String r = value(right, context);
            if (l == null || r == null) {
                return false; // missing evidence is not a match, whichever way the operator points
            }
            boolean equal = l.equals(r);
            return operator.equals("==") == equal;
        }
    }

    private record Truthy(Operand operand) implements Node {

        @Override
        public boolean test(FuzzyContext context) {
            String value = value(operand, context);
            return value != null && !value.isBlank() && !"false".equalsIgnoreCase(value);
        }
    }

    private record And(List<Node> terms) implements Node {

        @Override
        public boolean test(FuzzyContext context) {
            for (Node term : terms) {
                if (!term.test(context)) {
                    return false;
                }
            }
            return true;
        }
    }

    private record Or(List<Node> terms) implements Node {

        @Override
        public boolean test(FuzzyContext context) {
            for (Node term : terms) {
                if (term.test(context)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static String value(Operand operand, FuzzyContext context) {
        if (operand instanceof Operand.Literal literal) {
            return literal.text();
        }
        if (operand instanceof Operand.Field field) {
            return context.value(field.name());
        }
        return null;
    }

    /** Recursive descent over the condition text. */
    private static final class Parser {

        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        Node parseOr() {
            List<Node> terms = new ArrayList<>();
            terms.add(parseAnd());
            while (match("||")) {
                terms.add(parseAnd());
            }
            return terms.size() == 1 ? terms.get(0) : new Or(List.copyOf(terms));
        }

        Node parseAnd() {
            List<Node> terms = new ArrayList<>();
            terms.add(parsePrimary());
            while (match("&&")) {
                terms.add(parsePrimary());
            }
            return terms.size() == 1 ? terms.get(0) : new And(List.copyOf(terms));
        }

        Node parsePrimary() {
            skipSpaces();
            if (match("(")) {
                Node inner = parseOr();
                skipSpaces();
                expect(")");
                return inner;
            }
            Operand left = operand();
            String operator = null;
            if (match("==")) {
                operator = "==";
            } else if (match("!=")) {
                operator = "!=";
            }
            if (operator == null) {
                skipSpaces();
                if (pos < text.length() && text.charAt(pos) == '=') {
                    throw new FuzzyPatternException("A comparison in the condition '" + text
                            + "' uses '==', not '='");
                }
                return new Truthy(left);
            }
            return new Compare(left, operator, operand());
        }

        private Operand operand() {
            skipSpaces();
            if (pos >= text.length()) {
                throw new FuzzyPatternException("The condition '" + text + "' ends where a value was expected");
            }
            char c = text.charAt(pos);
            if (c == '\'' || c == '"') {
                return new Operand.Literal(quoted(c));
            }
            if (Character.isDigit(c) || c == '-' || c == '+') {
                int start = pos;
                pos++;
                while (pos < text.length() && (Character.isDigit(text.charAt(pos)) || text.charAt(pos) == '.')) {
                    pos++;
                }
                return new Operand.Literal(text.substring(start, pos));
            }
            int start = pos;
            while (pos < text.length()) {
                char current = text.charAt(pos);
                if (Character.isLetterOrDigit(current) || current == '_' || current == '.') {
                    pos++;
                } else {
                    break;
                }
            }
            if (start == pos) {
                throw new FuzzyPatternException("Expected a field or a value in the condition '" + text
                        + "', found '" + text.charAt(pos) + "'");
            }
            return new Operand.Field(text.substring(start, pos));
        }

        /** A quoted literal; a backslash escapes the quote itself and a backslash. */
        private String quoted(char quote) {
            pos++; // opening quote
            StringBuilder out = new StringBuilder();
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == '\\' && pos + 1 < text.length()) {
                    char next = text.charAt(pos + 1);
                    if (next == quote || next == '\\') {
                        out.append(next);
                        pos += 2;
                        continue;
                    }
                }
                if (c == quote) {
                    pos++;
                    return out.toString();
                }
                out.append(c);
                pos++;
            }
            throw new FuzzyPatternException("The quoted value in the condition '" + text + "' is not closed");
        }

        private boolean match(String token) {
            skipSpaces();
            if (text.regionMatches(pos, token, 0, token.length())) {
                pos += token.length();
                return true;
            }
            return false;
        }

        private void expect(String token) {
            if (!match(token)) {
                throw new FuzzyPatternException("Expected '" + token + "' in the condition '" + text
                        + "', found '" + (pos < text.length() ? text.charAt(pos) : "end") + "'");
            }
        }

        void expectEnd() {
            skipSpaces();
            if (pos < text.length()) {
                throw new FuzzyPatternException("Unexpected '" + text.substring(pos) + "' at the end of the "
                        + "condition '" + text + "'");
            }
        }

        private void skipSpaces() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }
    }
}
