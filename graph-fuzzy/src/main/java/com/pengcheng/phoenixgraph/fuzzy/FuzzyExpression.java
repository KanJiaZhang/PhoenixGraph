package com.pengcheng.phoenixgraph.fuzzy;

import java.util.ArrayList;
import java.util.List;

/**
 * A parsed fuzzy pattern: parts joined by {@code +}, where a part is either a function call over
 * the context or a literal separator:
 *
 * <pre>
 * emailDomain(email)+'_'+countryCode(phone)+'_'+ip2(ip)+'_'+regex(username, '^[a-z]+')
 * </pre>
 *
 * <p>Evaluating it yields one fragment per part and the concatenation is the {@link FuzzyKey} of the
 * link node. A function returns {@code null} when its value is missing or has an unexpected shape,
 * and a {@code null} fragment makes the whole pattern <b>not apply</b> - an account without an email
 * is not linked by a pattern that needs the email domain, because a key built around an empty
 * fragment would merge accounts that have nothing in common. {@code opt(...)} marks a part as
 * optional.
 *
 * <p>Grammar: {@code part ('+' part)*}, a part is {@code name(arg, ...)} or a quoted literal, and an
 * argument is a field name, a quoted literal, a number, or another call - so functions nest
 * ({@code lower(emailDomain(email))}).
 */
public final class FuzzyExpression {

    /** A part of the pattern. */
    private sealed interface Part {

        /** A function call with its arguments ({@code emailDomain(email)}). */
        record Call(String function, List<Node> args) implements Part {
        }

        /** A literal separator ({@code '_'}). */
        record Separator(String text) implements Part {
        }
    }

    /** An argument of a call. */
    private sealed interface Node {

        /** A field of the context. */
        record Field(String name) implements Node {
        }

        /** A literal written in the pattern. */
        record Literal(String text) implements Node {
        }

        /** Another function applied to its own arguments. */
        record Call(String function, java.util.List<Node> args) implements Node {
        }

        /** A list of arguments, written {@code [Login, Payment, Dispute]}: a context function reads them. */
        record List(java.util.List<Node> items) implements Node {
        }
    }

    private final String text;
    private final List<Part> parts;
    private final List<String> fields;

    private FuzzyExpression(String text, List<Part> parts, List<String> fields) {
        this.text = text;
        this.parts = parts;
        this.fields = fields;
    }

    /** Parses a pattern; the exception message says which part is wrong and what was expected. */
    public static FuzzyExpression parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new FuzzyPatternException("A fuzzy pattern must not be blank");
        }
        List<Part> parts = new Parser(expression).parse();
        List<String> fields = new ArrayList<>();
        for (Part part : parts) {
            if (part instanceof Part.Call call) {
                for (Node arg : call.args()) {
                    collectFields(arg, fields);
                }
            }
        }
        return new FuzzyExpression(expression.trim(), List.copyOf(parts), List.copyOf(fields));
    }

    /** Whether the parser accepts the pattern. */
    public static boolean isValid(String expression) {
        try {
            parse(expression);
            return true;
        } catch (FuzzyPatternException e) {
            return false;
        }
    }

    /** The pattern as written. */
    public String text() {
        return text;
    }

    /** The context fields the pattern reads, in the order they appear. */
    public List<String> fields() {
        return fields;
    }

    /** How many parts the key is made of. */
    public int partCount() {
        return parts.size();
    }

    /**
     * Evaluates the pattern.
     *
     * @return the fragments joined into the key text, or {@code null} when a part does not apply
     */
    public String key(FuzzyContext context) {
        StringBuilder out = new StringBuilder();
        for (String fragment : fragments(context)) {
            if (fragment == null) {
                return null;
            }
            out.append(fragment);
        }
        return out.toString();
    }

    /**
     * Evaluates every part on its own.
     *
     * @return one fragment per part ({@code null} = the part does not apply)
     */
    public List<String> fragments(FuzzyContext context) {
        List<String> out = new ArrayList<>(parts.size());
        for (Part part : parts) {
            out.add(evaluate(part, context));
        }
        return out;
    }

    /**
     * Evaluates the pattern part by part, the way the pattern editor shows it: what each part
     * produced and what the key became.
     */
    public List<Step> explain(FuzzyContext context) {
        List<Step> steps = new ArrayList<>(parts.size() + 1);
        List<String> fragments = fragments(context);
        for (int i = 0; i < parts.size(); i++) {
            steps.add(new Step(i, describe(parts.get(i)), fragments.get(i)));
        }
        steps.add(new Step(parts.size(), "key", key(context)));
        return steps;
    }

    /** One evaluated part, as the pattern editor shows it. */
    public record Step(int index, String part, String value) {
    }

    private static String evaluate(Part part, FuzzyContext context) {
        if (part instanceof Part.Separator separator) {
            return separator.text();
        }
        if (part instanceof Part.Call call) {
            return evaluateCall(call.function(), call.args(), context);
        }
        return null;
    }

    /** Evaluates a function call: its first argument plus the rest, handed to the library. */
    private static String evaluateCall(String function, List<Node> args, FuzzyContext context) {
        if (args.isEmpty()) {
            throw new FuzzyPatternException("Function '" + function + "' needs a field, e.g. "
                    + function + "(email)");
        }
        if (FuzzyFunctions.isContextFunction(function)) {
            // a context function reads the events themselves, so its arguments are rendered as written
            // rather than evaluated to values: [Login, Payment] is the list of event names
            List<String> rendered = new ArrayList<>(args.size());
            for (Node arg : args) {
                rendered.add(renderArgument(arg, context));
            }
            return FuzzyFunctions.applyContext(function, context, rendered);
        }
        String value = evaluateNode(args.get(0), context);
        List<String> rest = new ArrayList<>(args.size() - 1);
        for (int i = 1; i < args.size(); i++) {
            rest.add(evaluateNode(args.get(i), context));
        }
        return FuzzyFunctions.apply(function, value, rest);
    }

    /**
     * An argument of a context function as text: a field keeps its name ({@code Login} names an event), a
     * list becomes its items joined by commas, and a nested call is evaluated.
     */
    private static String renderArgument(Node node, FuzzyContext context) {
        if (node instanceof Node.Field field) {
            return field.name();
        }
        if (node instanceof Node.Literal literal) {
            return literal.text();
        }
        if (node instanceof Node.List list) {
            List<String> items = new ArrayList<>(list.items().size());
            for (Node item : list.items()) {
                items.add(renderArgument(item, context));
            }
            return String.join(",", items);
        }
        if (node instanceof Node.Call call) {
            return evaluateCall(call.function(), call.args(), context);
        }
        return null;
    }

    private static String evaluateNode(Node node, FuzzyContext context) {
        if (node instanceof Node.Field field) {
            return context.value(field.name());
        }
        if (node instanceof Node.Literal literal) {
            return literal.text();
        }
        if (node instanceof Node.Call call) {
            return evaluateCall(call.function(), call.args(), context);
        }
        return null;
    }

    /** The way a part is written, for the editor's preview. */
    private static String describe(Part part) {
        if (part instanceof Part.Separator separator) {
            return "'" + separator.text() + "'";
        }
        if (!(part instanceof Part.Call call)) {
            return "";
        }
        StringBuilder out = new StringBuilder(call.function()).append('(');
        for (int i = 0; i < call.args().size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(render(call.args().get(i)));
        }
        return out.append(')').toString();
    }

    private static String render(Node node) {
        if (node instanceof Node.Field field) {
            return field.name();
        }
        if (node instanceof Node.Literal literal) {
            return "'" + literal.text() + "'";
        }
        if (node instanceof Node.List list) {
            List<String> items = new ArrayList<>(list.items().size());
            for (Node item : list.items()) {
                items.add(render(item));
            }
            return "[" + String.join(", ", items) + "]";
        }
        return node instanceof Node.Call call ? render(call) : "";
    }

    private static void collectFields(Node node, List<String> out) {
        if (node instanceof Node.Field field) {
            if (!out.contains(field.name())) {
                out.add(field.name());
            }
        } else if (node instanceof Node.Call call) {
            for (Node arg : call.args()) {
                collectFields(arg, out);
            }
        } else if (node instanceof Node.List list) {
            for (Node item : list.items()) {
                collectFields(item, out);
            }
        }
    }

    /** Recursive descent over the pattern text. */
    private static final class Parser {

        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        List<Part> parse() {
            List<Part> parts = new ArrayList<>();
            parts.add(part());
            while (true) {
                skipSpaces();
                if (pos >= text.length()) {
                    return parts;
                }
                if (text.charAt(pos) != '+') {
                    throw new FuzzyPatternException("Expected '+' between the parts of '" + text
                            + "', found '" + text.charAt(pos) + "'");
                }
                pos++;
                skipSpaces();
                if (pos >= text.length()) {
                    throw new FuzzyPatternException("The pattern '" + text + "' ends with a '+'");
                }
                parts.add(part());
            }
        }

        private Part part() {
            skipSpaces();
            char c = peek();
            if (c == '\'' || c == '"') {
                return new Part.Separator(quoted());
            }
            String name = name();
            skipSpaces();
            if (peek() != '(') {
                throw new FuzzyPatternException("'" + name + "' is not a function call; a part is a call"
                        + " such as " + name + "(email) or a quoted separator such as '_'");
            }
            pos++; // '('
            List<Node> args = arguments();
            if (args.isEmpty()) {
                throw new FuzzyPatternException("Function '" + name + "' needs a field, e.g. "
                        + name + "(email)");
            }
            if (!FuzzyFunctions.isFunction(name)) {
                throw new FuzzyPatternException("Unknown fuzzy function '" + name + "'. Known functions: "
                        + String.join(", ", FuzzyFunctions.signatures().stream()
                                .map(FuzzyFunctions.Signature::name).toList()));
            }
            return new Part.Call(name, List.copyOf(args));
        }

        private List<Node> arguments() {
            List<Node> args = new ArrayList<>();
            skipSpaces();
            while (true) {
                args.add(argument());
                skipSpaces();
                char c = peek();
                if (c == ',') {
                    pos++;
                    skipSpaces();
                    continue;
                }
                if (c == ')') {
                    pos++;
                    return args;
                }
                throw new FuzzyPatternException("Expected ',' or ')' in the arguments of '" + text
                        + "', found '" + (pos < text.length() ? text.charAt(pos) : "end") + "'");
            }
        }

        private Node argument() {
            skipSpaces();
            char c = peek();
            if (c == '[') {
                pos++; // '['
                List<Node> items = new ArrayList<>();
                skipSpaces();
                if (peek() == ']') {
                    pos++;
                    return new Node.List(List.copyOf(items));
                }
                while (true) {
                    items.add(argument());
                    skipSpaces();
                    char next = peek();
                    if (next == ',') {
                        pos++;
                        continue;
                    }
                    if (next == ']') {
                        pos++;
                        return new Node.List(List.copyOf(items));
                    }
                    throw new FuzzyPatternException("Expected ',' or ']' in the list of '" + text
                            + "', found '" + next + "'");
                }
            }
            if (c == '\'' || c == '"') {
                return new Node.Literal(quoted());
            }
            if (Character.isDigit(c) || c == '-') {
                int start = pos;
                while (pos < text.length() && (Character.isDigit(text.charAt(pos))
                        || text.charAt(pos) == '-' || text.charAt(pos) == '.')) {
                    pos++;
                }
                return new Node.Literal(text.substring(start, pos));
            }
            String name = name();
            skipSpaces();
            if (peek() != '(') {
                // true/false are literals a context function reads, not fields of the context
                if ("true".equals(name) || "false".equals(name)) {
                    return new Node.Literal(name);
                }
                return new Node.Field(name);
            }
            pos++; // '('
            List<Node> args = arguments();
            if (args.isEmpty()) {
                throw new FuzzyPatternException("Function '" + name + "' needs a field");
            }
            if (!FuzzyFunctions.isFunction(name)) {
                throw new FuzzyPatternException("Unknown fuzzy function '" + name + "'. Known functions: "
                        + String.join(", ", FuzzyFunctions.signatures().stream()
                                .map(FuzzyFunctions.Signature::name).toList()));
            }
            return new Node.Call(name, List.copyOf(args));
        }

        /**
         * A quoted value: a literal separator ({@code '_'}) or a regular expression.
         *
         * <p>A backslash is only an escape for the quote itself and for a backslash; anything else
         * keeps it, because the value is very often a regular expression and {@code '^\d{4}$'}
         * has to reach the engine as it was written - reading {@code \d} as an escaped {@code d}
         * would turn the pattern into {@code ^d} and silently match nothing.
         */
        private String quoted() {
            char quote = text.charAt(pos);
            int start = pos;
            pos++;
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
                    out.append(c);
                    pos++;
                    continue;
                }
                if (c == quote) {
                    pos++;
                    return out.toString();
                }
                out.append(c);
                pos++;
            }
            throw new FuzzyPatternException("The quoted value starting at " + start + " of '" + text
                    + "' is not closed");
        }

        private String name() {
            skipSpaces();
            int start = pos;
            while (pos < text.length()) {
                char c = text.charAt(pos);
                // a dotted path reads inside a nested value (a sequence pattern says Login.IP), so a name
                // may carry dots and the digits of a list index
                if (Character.isLetterOrDigit(c) || c == '_' || c == '.') {
                    pos++;
                } else {
                    break;
                }
            }
            if (start == pos) {
                throw new FuzzyPatternException("Expected a function or a field name in '" + text
                        + "', found '" + (pos < text.length() ? text.charAt(pos) : "end") + "'");
            }
            return text.substring(start, pos);
        }

        private char peek() {
            skipSpaces();
            if (pos >= text.length()) {
                throw new FuzzyPatternException("The pattern '" + text + "' is not complete");
            }
            return text.charAt(pos);
        }

        private void skipSpaces() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }
    }
}
