package com.pengcheng.phoenixgraph.query.gremlin;

import com.pengcheng.phoenixgraph.query.QueryException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A small expression over one graph element: the language the {@code filter} and {@code map}
 * closures are written in, and the one the older property-key grammar is a subset of.
 *
 * <pre>{@code
 * .filter(it.get().value('accountStatus').equals('ACTIVE'))
 * .filter { it.get().label() == 'Account' && it.get().value('accountStatus') != 'CLOSED' }
 * .map    { it.get().value('status') == 'SUCCESS' ? 'S' : 'P' }
 * .map    { it.get().value('firstName') + ' ' + it.get().value('lastName') }
 * }</pre>
 *
 * <p>Grammar (keywords are case-insensitive; whitespace - newlines included - is ignored):
 * <pre>
 * expression := conditional
 * conditional:= or ( '?' expression ':' conditional )?
 * or         := and (('||' | 'or') and)*
 * and        := equality (('&amp;&amp;' | 'and') equality)*
 * equality   := relational (('==' | '=' | '!=') relational | (&lt;word operator&gt; operand))*
 * relational := additive (('&lt;' | '&lt;=' | '&gt;' | '&gt;=') additive)*
 * additive   := unary ('+' unary)*
 * unary      := ('!' | 'not' | '-') unary | postfix
 * postfix    := primary ('.' method '(' expression (, expression)* ')')*
 * primary    := '(' expression ')' | accessor | literal | 'true' | 'false' | 'null' | property
 * accessor   := it.get().value('key') | it.get().label() | it.get().id()
 * </pre>
 *
 * <p>The <em>word operators</em> {@code equals}/{@code eq}/{@code ne}/{@code notEquals}/
 * {@code contains}/{@code startsWith}/{@code endsWith} also work as infix words
 * ({@code relation equals directedBy}), where a bare right-hand token is the value itself - that is
 * the property-key grammar the filter step grew up with, and it keeps working.
 *
 * <p>Comparisons follow {@link GremlinSteps#matches(Object, String)} (numeric-aware, string
 * comparison case-insensitive); a missing property is never equal to anything, so
 * {@code it.get().value('x') != 'y'} holds for an element that has no {@code x}. As a condition, an
 * expression is true when its value is neither null nor {@code false} - so a bare property means
 * "present", exactly as it always did.
 */
public final class GremlinExpression {

    /** The value one expression produces for one element. */
    @FunctionalInterface
    private interface Value {
        Object evaluate(GremlinElement element);
    }

    private final Value value;
    private final Set<String> keys;
    private final boolean elementIdentity;

    private GremlinExpression(Value value, Set<String> keys, boolean elementIdentity) {
        this.value = value;
        // declaration order matters: it is the order the property bins are read in
        this.keys = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(keys));
        this.elementIdentity = elementIdentity;
    }

    /** Compiles an expression; throws {@link QueryException} when it is malformed. */
    public static GremlinExpression parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new QueryException("filter/map expects a non-blank expression");
        }
        Parser parser = new Parser(expression);
        Value value = parser.expression();
        parser.expectEnd();
        return new GremlinExpression(value, parser.keys, parser.elementIdentity);
    }

    /** Evaluates the expression against one element. */
    public Object evaluate(GremlinElement element) {
        return value.evaluate(element);
    }

    /** Whether the expression holds for one element (see the truthiness rule above). */
    public boolean test(GremlinElement element) {
        return truthy(value.evaluate(element));
    }

    /** The property names the expression reads, in the order they appear (pseudo keys excluded). */
    public Set<String> propertyKeys() {
        return keys;
    }

    /**
     * Whether the expression reads the element's label or id rather than only its properties.
     *
     * <p>An edge read that pushes conditions into the store decodes properties only, so it can
     * answer these expressions; a caller that hands the condition a synthetic element must keep
     * them as steps instead of folding them away.
     */
    public boolean readsElementIdentity() {
        return elementIdentity;
    }

    // --- semantics ---

    static boolean truthy(Object value) {
        return value != null && !Boolean.FALSE.equals(value);
    }

    private static boolean equal(Object left, Object right) {
        if (left == null || right == null) {
            return left == right;
        }
        if (left instanceof Number a && right instanceof Number b) {
            return a.doubleValue() == b.doubleValue();
        }
        if (right instanceof String token) {
            return GremlinSteps.matches(left, token);
        }
        if (left instanceof String token) {
            return GremlinSteps.matches(right, token);
        }
        return left.equals(right);
    }

    private static int compare(Object left, Object right) {
        if (left instanceof Number a && right instanceof Number b) {
            return Double.compare(a.doubleValue(), b.doubleValue());
        }
        return String.valueOf(left).compareTo(String.valueOf(right));
    }

    private static Object add(Object left, Object right) {
        if (left instanceof Number a && right instanceof Number b) {
            if (isIntegral(a) && isIntegral(b)) {
                return a.longValue() + b.longValue();
            }
            return a.doubleValue() + b.doubleValue();
        }
        return text(left) + text(right);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean isIntegral(Number number) {
        return number instanceof Long || number instanceof Integer || number instanceof Short
                || number instanceof Byte;
    }

    // --- parser ---

    private static final class Parser {

        private static final String[] WORD_OPERATORS = {
                "notequals", "startswith", "endswith", "contains", "equals", "ne", "eq"};

        private final String text;
        private int at;
        private final Set<String> keys = new LinkedHashSet<>();
        private boolean elementIdentity;

        Parser(String text) {
            this.text = text;
        }

        Value expression() {
            return conditional();
        }

        void expectEnd() {
            skipSpace();
            if (at < text.length()) {
                throw error("unexpected '" + text.charAt(at) + "'");
            }
        }

        private Value conditional() {
            Value condition = or();
            skipSpace();
            if (at >= text.length() || text.charAt(at) != '?') {
                return condition;
            }
            at++;
            Value whenTrue = expression();
            skipSpace();
            if (at >= text.length() || text.charAt(at) != ':') {
                throw error("expected ':' in a conditional expression");
            }
            at++;
            Value whenFalse = conditional(); // right-associative, like every other conditional
            return element -> truthy(condition.evaluate(element))
                    ? whenTrue.evaluate(element) : whenFalse.evaluate(element);
        }

        private Value or() {
            Value left = and();
            while (true) {
                if (eat("||") || eatWord("or")) {
                    Value right = and();
                    Value a = left;
                    left = element -> truthy(a.evaluate(element)) || truthy(right.evaluate(element));
                } else {
                    return left;
                }
            }
        }

        private Value and() {
            Value left = equality();
            while (true) {
                if (eat("&&") || eatWord("and")) {
                    Value right = equality();
                    Value a = left;
                    left = element -> truthy(a.evaluate(element)) && truthy(right.evaluate(element));
                } else {
                    return left;
                }
            }
        }

        private Value equality() {
            Value left = relational();
            while (true) {
                if (eat("==")) {
                    Value right = relational();
                    left = comparison(left, right, "==");
                } else if (eat("!=")) {
                    Value right = relational();
                    left = comparison(left, right, "!=");
                } else if (eat("=")) {
                    Value right = relational();
                    left = comparison(left, right, "=");
                } else {
                    String operator = wordOperator();
                    if (operator == null) {
                        return left;
                    }
                    left = comparison(left, wordOperand(), operator);
                }
            }
        }

        private Value relational() {
            Value left = additive();
            while (true) {
                if (eat("<=")) {
                    left = ordering(left, additive(), "<=");
                } else if (eat(">=")) {
                    left = ordering(left, additive(), ">=");
                } else if (eat("<")) {
                    left = ordering(left, additive(), "<");
                } else if (eat(">")) {
                    left = ordering(left, additive(), ">");
                } else {
                    return left;
                }
            }
        }

        private Value additive() {
            Value left = unary();
            while (eat("+")) {
                Value right = unary();
                Value a = left;
                left = element -> add(a.evaluate(element), right.evaluate(element));
            }
            return left;
        }

        private Value unary() {
            if (eat("!") || eatWord("not")) {
                Value operand = unary();
                return element -> !truthy(operand.evaluate(element));
            }
            if (eat("-")) {
                Value operand = unary();
                return element -> {
                    Object value = operand.evaluate(element);
                    if (value instanceof Number number) {
                        return isIntegral(number) ? -number.longValue() : -number.doubleValue();
                    }
                    return null;
                };
            }
            return postfix();
        }

        private Value postfix() {
            Value base = primary();
            while (true) {
                skipSpace();
                if (at >= text.length() || text.charAt(at) != '.') {
                    return base;
                }
                at++; // '.'
                skipSpace();
                int start = at;
                while (at < text.length() && isWordChar(text.charAt(at))) {
                    at++;
                }
                String method = text.substring(start, at);
                skipSpace();
                if (method.isEmpty() || at >= text.length() || text.charAt(at) != '(') {
                    throw error("expected a method call after '.'");
                }
                at++;
                List<Value> args = new ArrayList<>();
                skipSpace();
                if (!(at < text.length() && text.charAt(at) == ')')) {
                    args.add(expression());
                    while (eat(",")) {
                        args.add(expression());
                    }
                }
                skipSpace();
                if (at >= text.length() || text.charAt(at) != ')') {
                    throw error("expected ')'");
                }
                at++;
                base = method(base, method, args);
            }
        }

        private Value method(Value receiver, String method, List<Value> args) {
            if (args.size() != 1) {
                throw error("'" + method + "' takes one argument");
            }
            Value arg = args.get(0);
            switch (method.toLowerCase(Locale.ROOT)) {
                case "equals", "eq", "is" -> {
                    return comparison(receiver, arg, "==");
                }
                case "notequals", "ne" -> {
                    return comparison(receiver, arg, "!=");
                }
                case "equalsignorecase" -> {
                    return element -> {
                        Object value = receiver.evaluate(element);
                        return value != null && String.valueOf(value)
                                .equalsIgnoreCase(String.valueOf(arg.evaluate(element)));
                    };
                }
                case "contains" -> {
                    return comparison(receiver, arg, "contains");
                }
                case "startswith" -> {
                    return comparison(receiver, arg, "startsWith");
                }
                case "endswith" -> {
                    return comparison(receiver, arg, "endsWith");
                }
                default -> throw error("unknown method '" + method + "'");
            }
        }

        private Value comparison(Value left, Value right, String operator) {
            String op = switch (operator.toLowerCase(Locale.ROOT)) {
                case "equals", "eq", "is", "==", "=" -> "=";
                case "notequals", "ne", "!=" -> "!=";
                case "contains" -> "contains";
                case "startswith" -> "startsWith";
                case "endswith" -> "endsWith";
                default -> operator;
            };
            return element -> {
                Object a = left.evaluate(element);
                Object b = right.evaluate(element);
                return switch (op) {
                    case "=" -> equal(a, b);
                    case "!=" -> !equal(a, b);
                    case "contains" -> a != null && String.valueOf(a).contains(String.valueOf(b));
                    case "startsWith" -> a != null && String.valueOf(a).startsWith(String.valueOf(b));
                    case "endsWith" -> a != null && String.valueOf(a).endsWith(String.valueOf(b));
                    default -> false;
                };
            };
        }

        private Value ordering(Value left, Value right, String operator) {
            return element -> {
                int order = compare(left.evaluate(element), right.evaluate(element));
                return switch (operator) {
                    case "<" -> order < 0;
                    case "<=" -> order <= 0;
                    case ">" -> order > 0;
                    default -> order >= 0;
                };
            };
        }

        private Value primary() {
            skipSpace();
            if (at >= text.length()) {
                throw error("expected a value");
            }
            char c = text.charAt(at);
            if (c == '(') {
                at++;
                Value inner = expression();
                skipSpace();
                if (at >= text.length() || text.charAt(at) != ')') {
                    throw error("expected ')'");
                }
                at++;
                return inner;
            }
            if (c == '\'' || c == '"') {
                return literal();
            }
            GremlinElementExpression.Accessor accessor = GremlinElementExpression.accessorAt(text, at);
            if (accessor != null) {
                at = accessor.end();
                String key = accessor.key();
                if (GremlinElementExpression.isPseudo(key)) {
                    elementIdentity = true;
                } else {
                    keys.add(key);
                }
                return element -> GremlinElementExpression.read(element, key);
            }
            if (Character.isDigit(c)) {
                return number();
            }
            if (isWordStart(c)) {
                int start = at;
                while (at < text.length() && isWordChar(text.charAt(at))) {
                    at++;
                }
                String word = text.substring(start, at);
                switch (word.toLowerCase(Locale.ROOT)) {
                    case "true":
                        return element -> Boolean.TRUE;
                    case "false":
                        return element -> Boolean.FALSE;
                    case "null":
                        return element -> null;
                    default:
                        keys.add(word);
                        return element -> element.properties().get(word);
                }
            }
            throw error("unexpected '" + c + "'");
        }

        private Value literal() {
            char quote = text.charAt(at);
            at++;
            StringBuilder literal = new StringBuilder();
            while (at < text.length()) {
                char c = text.charAt(at++);
                if (c == '\\' && at < text.length()) {
                    // escapes are kept as written: a pattern such as '1\d+' must not lose its backslash
                    literal.append(c).append(text.charAt(at++));
                    continue;
                }
                if (c == quote) {
                    String value = literal.toString();
                    return element -> value;
                }
                literal.append(c);
            }
            throw error("unterminated string");
        }

        private Value number() {
            int start = at;
            while (at < text.length() && (Character.isDigit(text.charAt(at)) || text.charAt(at) == '.')) {
                at++;
            }
            if (at < text.length() && (text.charAt(at) == 'e' || text.charAt(at) == 'E')) {
                at++;
                if (at < text.length() && (text.charAt(at) == '+' || text.charAt(at) == '-')) {
                    at++;
                }
                while (at < text.length() && Character.isDigit(text.charAt(at))) {
                    at++;
                }
            }
            String raw = text.substring(start, at);
            try {
                if (raw.indexOf('.') < 0 && raw.indexOf('e') < 0 && raw.indexOf('E') < 0) {
                    long value = Long.parseLong(raw);
                    return element -> value;
                }
                double value = Double.parseDouble(raw);
                return element -> value;
            } catch (NumberFormatException e) {
                throw error("'" + raw + "' is not a number");
            }
        }

        /** The right-hand side of an infix word operator: a bare token is the value itself. */
        private Value wordOperand() {
            skipSpace();
            if (at < text.length() && (text.charAt(at) == '\'' || text.charAt(at) == '"')) {
                return literal();
            }
            int start = at;
            while (at < text.length() && !isDelimiter(text.charAt(at))) {
                at++;
            }
            String raw = text.substring(start, at).trim();
            if (raw.isEmpty()) {
                throw error("expected a value");
            }
            try {
                long value = Long.parseLong(raw);
                return element -> value;
            } catch (NumberFormatException ignored) {
                // not a whole number; try a decimal below
            }
            try {
                double value = Double.parseDouble(raw);
                return element -> value;
            } catch (NumberFormatException ignored) {
                return element -> raw;
            }
        }

        private String wordOperator() {
            for (String operator : WORD_OPERATORS) {
                if (keywordAt(operator)) {
                    at += operator.length();
                    return operator;
                }
            }
            return null;
        }

        // --- token helpers ---

        private void skipSpace() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
                at++;
            }
        }

        private boolean eat(String symbol) {
            skipSpace();
            if (text.startsWith(symbol, at)) {
                at += symbol.length();
                return true;
            }
            return false;
        }

        private boolean eatWord(String word) {
            skipSpace();
            if (keywordAt(word)) {
                at += word.length();
                return true;
            }
            return false;
        }

        /** Whether {@code word} starts here as a whole word (case-insensitive). */
        private boolean keywordAt(String word) {
            if (!text.regionMatches(true, at, word, 0, word.length())) {
                return false;
            }
            int before = at - 1;
            int after = at + word.length();
            boolean beforeBoundary = before < 0 || !isWordChar(text.charAt(before));
            boolean afterBoundary = after >= text.length() || !isWordChar(text.charAt(after));
            return beforeBoundary && afterBoundary;
        }

        private static boolean isWordStart(char c) {
            return Character.isLetter(c) || c == '_';
        }

        private static boolean isWordChar(char c) {
            return Character.isLetterOrDigit(c) || c == '_';
        }

        /** A character that ends a bare word operand: operators, punctuation and whitespace. */
        private static boolean isDelimiter(char c) {
            return Character.isWhitespace(c) || c == ')' || c == '(' || c == '?' || c == ':'
                    || c == ',' || c == '!' || c == '<' || c == '>' || c == '=' || c == '&'
                    || c == '|' || c == '+';
        }

        private QueryException error(String message) {
            return new QueryException(message + " in expression: " + text.trim());
        }
    }
}
