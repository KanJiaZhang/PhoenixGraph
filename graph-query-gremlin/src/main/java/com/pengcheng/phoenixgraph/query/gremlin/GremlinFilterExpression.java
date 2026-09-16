package com.pengcheng.phoenixgraph.query.gremlin;

import com.pengcheng.phoenixgraph.query.QueryException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A property predicate used by the {@code filter(...)} step, e.g.
 *
 * <pre>{@code
 * .filter(relation.equals('directedBy') || xrelation.equals('directed'))
 * .filter(relation = 'directedBy' && confidence != 'low')
 * .filter(name)                      // property must be present
 * }</pre>
 *
 * <p>Grammar (keywords are case-insensitive):
 * <pre>
 * expression := conjunction (('||' | 'or') conjunction)*        // OR of conjunctions
 * conjunction := predicate (('&amp;&amp;' | 'and') predicate)*    // AND of predicates
 * predicate  := key | key op value | key.method('value')
 * op         := '=' | '==' | '!='</pre>
 *
 * <p>Comparisons are delegated to {@link GremlinSteps#matches(Object, String)},
 * so they are numeric-aware and otherwise compare string representations.
 * Property values inside quotes may contain {@code ||}, {@code &&} or parens.
 */
public final class GremlinFilterExpression {

    /** Comparison applied by a predicate. */
    private enum Op {
        EXISTS, EQ, NE, CONTAINS, STARTS_WITH, ENDS_WITH
    }

    private record Predicate(String key, Op op, String value) {

        boolean test(GremlinElement element) {
            Object stored = element.properties().get(key);
            if (op == Op.EXISTS) {
                return stored != null;
            }
            if (stored == null) {
                return op == Op.NE; // a missing property is never "equal to" / "containing" anything
            }
            String actual = String.valueOf(stored);
            return switch (op) {
                case EQ -> GremlinSteps.matches(stored, value);
                case NE -> !GremlinSteps.matches(stored, value);
                case CONTAINS -> actual.contains(value);
                case STARTS_WITH -> actual.startsWith(value);
                case ENDS_WITH -> actual.endsWith(value);
                default -> false;
            };
        }
    }

    private final List<List<Predicate>> alternatives;

    private GremlinFilterExpression(List<List<Predicate>> alternatives) {
        this.alternatives = alternatives;
    }

    /** Parses a filter expression; throws {@link QueryException} when malformed. */
    public static GremlinFilterExpression parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new QueryException("filter expects a non-blank expression");
        }
        List<List<Predicate>> alternatives = new ArrayList<>();
        for (String orPart : splitTopLevel(expression, "||", "or")) {
            if (orPart.isBlank()) {
                throw new QueryException("filter has an empty alternative in: " + expression);
            }
            List<Predicate> conjunction = new ArrayList<>();
            for (String andPart : splitTopLevel(orPart, "&&", "and")) {
                if (andPart.isBlank()) {
                    throw new QueryException("filter has an empty predicate in: " + expression);
                }
                conjunction.add(parsePredicate(andPart));
            }
            alternatives.add(List.copyOf(conjunction));
        }
        return new GremlinFilterExpression(List.copyOf(alternatives));
    }

    /** Whether the element satisfies any alternative. */
    public boolean test(GremlinElement element) {
        for (List<Predicate> conjunction : alternatives) {
            boolean all = true;
            for (Predicate predicate : conjunction) {
                if (!predicate.test(element)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return true;
            }
        }
        return false;
    }

    /** The property keys referenced by this expression (used for read projection planning). */
    public Set<String> propertyKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (List<Predicate> conjunction : alternatives) {
            for (Predicate predicate : conjunction) {
                keys.add(predicate.key());
            }
        }
        return keys;
    }

    // --- parsing ---

    private static Predicate parsePredicate(String raw) {
        String text = raw.trim();
        // key.equals('value') / key.contains('value') / ...
        if (text.endsWith(")")) {
            int open = text.indexOf('(');
            if (open > 0) {
                String head = text.substring(0, open).trim();
                String body = text.substring(open + 1, text.length() - 1).trim();
                int dot = head.lastIndexOf('.');
                if (dot > 0) {
                    Op op = methodOp(head.substring(dot + 1));
                    if (op != null) {
                        return new Predicate(head.substring(0, dot).trim(), op, unquote(body));
                    }
                }
            }
        }
        // key equals 'value' / key contains 'value' (word form, without parentheses)
        WordMethod word = findWordMethod(text);
        if (word != null) {
            Op op = methodOp(word.method());
            String key = text.substring(0, word.at()).trim();
            if (op != null && !key.isEmpty()) {
                return new Predicate(key, op, unquote(text.substring(word.at() + word.method().length()).trim()));
            }
        }
        // key == 'value' / key != 'value' / key = 'value'  (checked longest-first)
        for (String symbol : new String[] {"==", "!=", "="}) {
            int at = indexOfTopLevel(text, symbol);
            if (at > 0) {
                Op op = symbol.equals("!=") ? Op.NE : Op.EQ;
                return new Predicate(text.substring(0, at).trim(), op,
                        unquote(text.substring(at + symbol.length()).trim()));
            }
        }
        return new Predicate(text, Op.EXISTS, null);
    }

    /** A comparison keyword found at the top level of an expression. */
    private record WordMethod(int at, String method) {
    }

    /** The earliest top-level comparison keyword (e.g. {@code equals}), or {@code null}. */
    private static WordMethod findWordMethod(String text) {
        String[] methods = {"notequals", "startswith", "endswith", "contains", "equals", "ne", "eq"};
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                continue;
            }
            if (c == '(') {
                depth++;
                continue;
            }
            if (c == ')') {
                depth--;
                continue;
            }
            if (depth != 0 || !Character.isLetter(c)) {
                continue;
            }
            for (String method : methods) {
                if (keywordAt(text, i, method)) {
                    return new WordMethod(i, method);
                }
            }
        }
        return null;
    }

    private static Op methodOp(String method) {
        return switch (method.toLowerCase(Locale.ROOT)) {
            case "equals", "eq", "is" -> Op.EQ;
            case "notequals", "ne" -> Op.NE;
            case "contains" -> Op.CONTAINS;
            case "startswith" -> Op.STARTS_WITH;
            case "endswith" -> Op.ENDS_WITH;
            default -> null;
        };
    }

    /** Splits on a top-level symbol ({@code ||}) or keyword ({@code or}), ignoring quotes and parens. */
    private static List<String> splitTopLevel(String s, String symbol, String keyword) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        int start = 0;
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
                continue;
            }
            if (c == '(') {
                depth++;
                continue;
            }
            if (c == ')') {
                depth--;
                continue;
            }
            if (depth != 0) {
                continue;
            }
            if (s.startsWith(symbol, i)) {
                parts.add(s.substring(start, i));
                i += symbol.length() - 1;
                start = i + 1;
            } else if (Character.isLetter(c) && keywordAt(s, i, keyword)) {
                parts.add(s.substring(start, i));
                i += keyword.length() - 1;
                start = i + 1;
            }
        }
        parts.add(s.substring(start));
        return parts;
    }

    /** Whether {@code keyword} starts at {@code at} as a whole word (case-insensitive). */
    private static boolean keywordAt(String s, int at, String keyword) {
        if (!s.regionMatches(true, at, keyword, 0, keyword.length())) {
            return false;
        }
        int before = at - 1;
        int after = at + keyword.length();
        boolean beforeBoundary = before < 0 || !isWordChar(s.charAt(before));
        boolean afterBoundary = after >= s.length() || !isWordChar(s.charAt(after));
        return beforeBoundary && afterBoundary;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** Index of the first top-level occurrence of {@code symbol} outside quotes/parens, or {@code -1}. */
    private static int indexOfTopLevel(String s, String symbol) {
        int depth = 0;
        char quote = 0;
        for (int i = 0; i <= s.length() - symbol.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                continue;
            }
            if (c == '(') {
                depth++;
                continue;
            }
            if (c == ')') {
                depth--;
                continue;
            }
            if (depth == 0 && s.startsWith(symbol, i)) {
                return i;
            }
        }
        return -1;
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
