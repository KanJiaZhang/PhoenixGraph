package com.pengcheng.phoenixgraph.query.gremlin;

import com.pengcheng.phoenixgraph.query.QueryException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * <p>A closure body is a small program: statements separated by {@code ;} (or by a newline that is
 * followed by another statement) whose value is the last one. A statement may bind a name -
 * {@code def tmp = it.get()} - and the name then stands for its value in the statements after it,
 * which is what makes {@code tmp.value('status')} read a property off the element.
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
 * <p>The element methods {@code value('key')}, {@code values('key')}, {@code label()}, {@code id()}
 * and {@code key()} work on any receiver that turns out to be an element, so both spellings of an
 * accessor are the same predicate:
 *
 * <pre>{@code
 * def tmp = it.get();
 * tmp.value('status') == 'SUCCESS' ? 'S' : 'P'
 * }</pre>
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

    /** A value that is the same for every element (a literal, a boolean, null). */
    private record Const(Object value) implements Value {
        @Override
        public Object evaluate(GremlinElement element) {
            return value;
        }
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
        Value value = parser.program();
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
        private final Map<String, Value> variables = new LinkedHashMap<>();
        private boolean elementIdentity;

        Parser(String text) {
            this.text = text;
        }

        /**
         * A closure body: statements separated by {@code ;} (or by a newline that is followed by a new
         * statement), whose value is the last one.
         *
         * <pre>{@code
         * def tmp = it.get();
         * tmp.value('status') == 'SUCCESS' ? 'S' : 'P'
         * }</pre>
         */
        Value program() {
            Value last = null;
            while (true) {
                skipSpace();
                if (at >= text.length()) {
                    break;
                }
                Value statement = statement();
                last = statement != null ? statement : expression();
                // the expression parser skips the whitespace after its last token, so the end of the
                // statement is the last non-blank character it read
                int end = at;
                while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
                    end--;
                }
                skipSpace();
                if (at < text.length() && text.charAt(at) == ';') {
                    at++;
                    continue;
                }
                if (at < text.length() && newlineBetween(end, at)
                        && canEndStatement(end) && canStartStatement()) {
                    continue; // a newline can end a statement too, when the next line starts one
                }
                break;
            }
            if (last == null) {
                throw error("expected an expression");
            }
            return last;
        }

        /**
         * A {@code [def] name = expression} statement, or {@code null} when this is not one.
         *
         * <p>Without {@code def} the name has to be a variable an earlier statement bound: the
         * property-key grammar spells equality {@code relation = 'r1'}, and that must stay a
         * comparison rather than become an assignment.
         */
        private Value statement() {
            int save = at;
            boolean declared = eatWord("def");
            skipSpace();
            int start = at;
            if (at < text.length() && isWordStart(text.charAt(at))) {
                while (at < text.length() && isWordChar(text.charAt(at))) {
                    at++;
                }
                String name = text.substring(start, at);
                skipSpace();
                if (at < text.length() && text.charAt(at) == '=' && !text.startsWith("==", at)
                        && (declared || variables.containsKey(name))) {
                    at++; // '='
                    Value value = expression();
                    variables.put(name, value);
                    return value;
                }
            }
            at = save; // not an assignment: parse it as an expression instead
            return null;
        }

        /** Whether the character before a line break could end a statement. */
        private boolean canEndStatement(int end) {
            if (end <= 0) {
                return false;
            }
            char c = text.charAt(end - 1);
            return c == ')' || c == ']' || c == '}' || c == '\'' || c == '"'
                    || Character.isLetterOrDigit(c) || c == '_';
        }

        /** Whether what follows a line break could start a statement (not an operator continuation). */
        private boolean canStartStatement() {
            if (at >= text.length()) {
                return false;
            }
            char c = text.charAt(at);
            return Character.isLetterOrDigit(c) || c == '_' || c == '\'' || c == '"'
                    || c == '(';
        }

        private boolean newlineBetween(int from, int to) {
            return text.substring(from, to).indexOf('\n') >= 0;
        }

        void expectEnd() {
            skipSpace();
            if (at < text.length()) {
                throw error("unexpected '" + text.charAt(at) + "'");
            }
        }

        /** One expression, without the surrounding statement machinery. */
        Value expression() {
            return conditional();
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
            String name = method.toLowerCase(Locale.ROOT);
            if (name.equals("value") || name.equals("values") || name.equals("label")
                    || name.equals("id") || name.equals("key")) {
                return elementMethod(receiver, name, args);
            }
            Value builtin = stringMethod(receiver, name, args, method);
            if (builtin != null) {
                return builtin;
            }
            if (args.size() != 1) {
                throw error("'" + method + "' takes one argument");
            }
            Value arg = args.get(0);
            switch (name) {
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

        /**
         * The string and number methods any value answers: {@code length()}, {@code toUpperCase()},
         * {@code toLowerCase()}, {@code trim()}, {@code toString()}, {@code isEmpty()},
         * {@code abs()}, {@code substring(from[, to])}, {@code replace(from, to)} and
         * {@code indexOf(needle)}.
         *
         * <p>They read the receiver through {@code String.valueOf}, so they work on a property, a
         * literal or the result of another call alike, and a null receiver yields null - the same
         * "a missing value stays missing" rule the comparisons follow. {@code substring} and
         * {@code indexOf} follow the Groovy convention that a negative index counts from the end and
         * an out-of-range one is clamped, so {@code substring(-2)} is the last two characters.
         *
         * @return the method as a value, or {@code null} when the name is not one of them
         */
        private Value stringMethod(Value receiver, String name, List<Value> args, String written) {
            return switch (name) {
                case "length" -> noArgs(receiver, args, written,
                        value -> (long) String.valueOf(value).length());
                case "touppercase" -> noArgs(receiver, args, written,
                        value -> String.valueOf(value).toUpperCase(Locale.ROOT));
                case "tolowercase" -> noArgs(receiver, args, written,
                        value -> String.valueOf(value).toLowerCase(Locale.ROOT));
                case "trim" -> noArgs(receiver, args, written, value -> String.valueOf(value).trim());
                case "tostring" -> noArgs(receiver, args, written, String::valueOf);
                case "isempty" -> noArgs(receiver, args, written,
                        value -> String.valueOf(value).isEmpty());
                case "abs" -> noArgs(receiver, args, written, value -> value instanceof Number number
                        ? (isIntegral(number) ? (Object) Math.abs(number.longValue())
                                : (Object) Math.abs(number.doubleValue()))
                        : null);
                case "substring" -> substring(receiver, args, written);
                case "replace" -> {
                    requireArity(args, 2, written);
                    Value from = args.get(0);
                    Value to = args.get(1);
                    yield element -> {
                        Object value = receiver.evaluate(element);
                        return value == null ? null : String.valueOf(value)
                                .replace(String.valueOf(from.evaluate(element)),
                                        String.valueOf(to.evaluate(element)));
                    };
                }
                case "indexof" -> {
                    requireArity(args, 1, written);
                    Value needle = args.get(0);
                    yield element -> {
                        Object value = receiver.evaluate(element);
                        return value == null ? null : (long) String.valueOf(value)
                                .indexOf(String.valueOf(needle.evaluate(element)));
                    };
                }
                default -> null;
            };
        }

        /** A no-argument method whose value is a function of the receiver's value. */
        private Value noArgs(Value receiver, List<Value> args, String written,
                             java.util.function.Function<Object, Object> op) {
            requireArity(args, 0, written);
            return element -> {
                Object value = receiver.evaluate(element);
                return value == null ? null : op.apply(value);
            };
        }

        /** {@code substring(from)} or {@code substring(from, to)}: negative counts from the end. */
        private Value substring(Value receiver, List<Value> args, String written) {
            if (args.size() != 1 && args.size() != 2) {
                throw error("'" + written + "' takes one or two arguments");
            }
            Value from = args.get(0);
            Value to = args.size() == 2 ? args.get(1) : null;
            return element -> {
                Object value = receiver.evaluate(element);
                if (value == null) {
                    return null;
                }
                String text = String.valueOf(value);
                int start = index(from.evaluate(element), text.length());
                int end = to == null ? text.length() : index(to.evaluate(element), text.length());
                if (start > end) {
                    int swap = start;
                    start = end;
                    end = swap;
                }
                return text.substring(start, end);
            };
        }

        /** A Groovy-style index: negative counts from the end, anything out of range is clamped. */
        private static int index(Object value, int length) {
            if (!(value instanceof Number number)) {
                return 0;
            }
            int index = number.intValue();
            if (index < 0) {
                index += length;
            }
            return Math.max(0, Math.min(index, length));
        }

        private void requireArity(List<Value> args, int expected, String written) {
            if (args.size() != expected) {
                throw error("'" + written + "' takes " + (expected == 0 ? "no arguments"
                        : expected + " argument" + (expected == 1 ? "" : "s")));
            }
        }

        /**
         * An accessor called on a receiver that turns out to be an element, the way a closure that
         * bound one to a variable writes it: {@code def tmp = it.get(); tmp.value('status')}.
         *
         * <p>The key of {@code value('status')} is known while parsing, so it joins the property
         * keys the read has to project - the same way {@code it.get().value('status')} does.
         */
        private Value elementMethod(Value receiver, String name, List<Value> args) {
            boolean takesKey = name.equals("value") || name.equals("values");
            if (takesKey ? args.size() != 1 : !args.isEmpty()) {
                throw error("'" + name + "' takes " + (takesKey ? "one argument" : "no arguments"));
            }
            String written = takesKey && args.get(0) instanceof Const constant
                    && constant.value() instanceof String text ? text : null;
            // value('id')/value('label') are the Gremlin spellings of id()/label(): resolve them to the
            // pseudo keys rather than looking for a property of that name
            String key = written == null ? null
                    : switch (written.toLowerCase(Locale.ROOT)) {
                        case "id" -> GremlinElementExpression.ID_KEY;
                        case "label" -> GremlinElementExpression.LABEL_KEY;
                        default -> written;
                    };
            if (key != null) {
                if (GremlinElementExpression.isPseudo(key)) {
                    elementIdentity = true;
                } else {
                    keys.add(key);
                }
            }
            if (!takesKey) {
                elementIdentity = true; // label()/id() read the element's identity, not a property
            }
            return element -> {
                Object base = receiver.evaluate(element);
                if (!(base instanceof GremlinElement target)) {
                    return null;
                }
                return switch (name) {
                    case "value", "values" -> key == null ? null : GremlinElementExpression.read(target, key);
                    case "label" -> target.label();
                    default -> target.id(); // id(), key()
                };
            };
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
                        return new Const(Boolean.TRUE);
                    case "false":
                        return new Const(Boolean.FALSE);
                    case "null":
                        return new Const(null);
                    default:
                        Value variable = variables.get(word);
                        if (variable != null) {
                            return variable; // a name the closure bound earlier
                        }
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
                    return new Const(value);
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
                    return new Const(Long.parseLong(raw));
                }
                return new Const(Double.parseDouble(raw));
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
                return new Const(Long.parseLong(raw));
            } catch (NumberFormatException ignored) {
                // not a whole number; try a decimal below
            }
            try {
                return new Const(Double.parseDouble(raw));
            } catch (NumberFormatException ignored) {
                return new Const(raw);
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
