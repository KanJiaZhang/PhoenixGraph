package com.pengcheng.phoenixgraph.query.gremlin;

import com.pengcheng.phoenixgraph.query.QueryException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The argument of a comparison when it is a {@code P} predicate rather than a bare value:
 * {@code eq}, {@code neq}, {@code gt}, {@code gte}, {@code lt}, {@code lte}, {@code between},
 * {@code inside}, {@code outside}, {@code within}, {@code without}, {@code not}, {@code and},
 * {@code or}, and the text predicates ({@code containing}, {@code startingWith}, {@code endingWith}
 * and their negations).
 *
 * <pre>{@code
 * g.V('Account').has('accountStatus', eq('FROZEN'))
 * g.V('Account').values('id').is(between(1001, 1010))
 * g.V('Account').has('accountStatus', not(within('CLOSED')))
 * g.V('Entity').has('novel', TextP.containing('水镜'))
 * g.V('Entity').has('name', within('关羽', '张飞'))
 * }</pre>
 *
 * <p>Before this vocabulary was complete, only a few predicates were parsed and the rest fell through
 * to "compare the whole token as a value": {@code is(eq('CLOSED'))} compared {@code CLOSED} with the
 * text {@code eq('CLOSED')} and matched nothing, and {@code has(key, eq(...))} was always empty. A
 * query that found nothing was then indistinguishable from a query the engine never evaluated, so
 * "this graph has no such data" could not be told from "the engine cannot ask that question". A token
 * that <em>looks</em> like a predicate call but names one this engine does not know is now an error.
 *
 * <p>Semantics, chosen to match TinkerPop and the rest of the engine:
 *
 * <ul>
 *   <li><b>Comparison.</b> Numeric when both sides read as numbers, otherwise strings compared by
 *       {@link String#compareTo} (so {@code gt('B')} works on text). {@code eq} ignores case, like
 *       {@link GremlinSteps#matches(Object, String)} — the filter grammar is typed by hand.</li>
 *   <li><b>Ranges.</b> {@code between(a,b)} is {@code a <= x < b}, {@code inside(a,b)} is
 *       {@code a < x < b}, {@code outside(a,b)} is {@code x < a || x > b} — TinkerPop's definitions.</li>
 *   <li><b>Multi-valued properties.</b> A stored {@link Collection} is matched element-wise: a positive
 *       predicate holds when <em>any</em> element matches, a negative one when <em>no</em> element does.</li>
 *   <li><b>Combinators.</b> {@code not}/{@code and}/{@code or} nest predicates ({@code or(gt(10),eq(1))});
 *       a nested argument that is not a predicate is compared by equality, so
 *       {@code and(eq('A'),'B')} reads as {@code eq('A') and eq('B')}.</li>
 * </ul>
 */
final class GremlinPredicate {

    /**
     * {@code TextP.containing('x')}, {@code P.within('a','b')} and the bare {@code containing('x')} all
     * name the same predicate; the optional {@code TextP}/{@code P} prefix is TinkerPop's fluent form.
     */
    private static final Pattern SHAPE = Pattern.compile(
            "^\\s*(?:(?:TextP|P)\\s*\\.\\s*)?([A-Za-z_][A-Za-z0-9_]*)\\s*\\((.*)\\)\\s*$",
            Pattern.DOTALL);

    /** The predicates this engine evaluates, for the error message of an unknown one. */
    static final String SUPPORTED = "eq, neq, gt, gte, lt, lte, between, inside, outside, within,"
            + " without, containing, startingWith, endingWith, notContaining, notStartingWith,"
            + " notEndingWith, not, and, or";

    /** What the predicate does with a stored value. */
    private enum Kind {
        EQUALS,
        NOT_EQUALS,
        GREATER,
        GREATER_EQUAL,
        LESS,
        LESS_EQUAL,
        /** {@code a <= x < b}. */
        BETWEEN,
        /** {@code a < x < b}. */
        INSIDE,
        /** {@code x < a || x > b}. */
        OUTSIDE,
        /** Membership of the argument list. */
        WITHIN,
        /** Substring. */
        CONTAINS,
        /** Prefix. */
        STARTS_WITH,
        /** Suffix. */
        ENDS_WITH,
        /** Negation of one nested predicate. */
        NOT,
        /** All nested predicates. */
        AND,
        /** Any nested predicate. */
        OR
    }

    private final Kind kind;
    private final boolean negated;
    private final List<String> arguments;
    private final List<GremlinPredicate> nested;

    private GremlinPredicate(Kind kind, boolean negated, List<String> arguments,
                             List<GremlinPredicate> nested) {
        this.kind = kind;
        this.negated = negated;
        this.arguments = List.copyOf(arguments);
        this.nested = List.copyOf(nested);
    }

    /**
     * The predicate {@code token} spells, or {@code null} when it is a plain value such as
     * {@code 'ACTIVE'} or {@code 5} and must be compared by equality.
     *
     * @throws QueryException when the token is written as a predicate call but names an unknown one
     */
    static GremlinPredicate parse(String token) {
        if (token == null) {
            return null;
        }
        Matcher matcher = SHAPE.matcher(token);
        if (!matcher.matches()) {
            return null;
        }
        String name = matcher.group(1);
        String body = matcher.group(2);
        return switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "eq" -> new GremlinPredicate(Kind.EQUALS, false, splitArgs(body), List.of());
            case "neq", "not" -> isNot(name) ? not(parseOrValue(body)) : notEquals(body);
            case "gt" -> new GremlinPredicate(Kind.GREATER, false, splitArgs(body), List.of());
            case "gte" -> new GremlinPredicate(Kind.GREATER_EQUAL, false, splitArgs(body), List.of());
            case "lt" -> new GremlinPredicate(Kind.LESS, false, splitArgs(body), List.of());
            case "lte" -> new GremlinPredicate(Kind.LESS_EQUAL, false, splitArgs(body), List.of());
            case "between" -> new GremlinPredicate(Kind.BETWEEN, false, splitArgs(body), List.of());
            case "inside" -> new GremlinPredicate(Kind.INSIDE, false, splitArgs(body), List.of());
            case "outside" -> new GremlinPredicate(Kind.OUTSIDE, false, splitArgs(body), List.of());
            case "within" -> new GremlinPredicate(Kind.WITHIN, false, splitArgs(body), List.of());
            case "without" -> new GremlinPredicate(Kind.WITHIN, true, splitArgs(body), List.of());
            case "contains", "containing" -> new GremlinPredicate(Kind.CONTAINS, false, splitArgs(body),
                    List.of());
            case "notcontaining" -> new GremlinPredicate(Kind.CONTAINS, true, splitArgs(body), List.of());
            case "startswith", "startingwith" -> new GremlinPredicate(Kind.STARTS_WITH, false,
                    splitArgs(body), List.of());
            case "notstartingwith" -> new GremlinPredicate(Kind.STARTS_WITH, true, splitArgs(body),
                    List.of());
            case "endswith", "endingwith" -> new GremlinPredicate(Kind.ENDS_WITH, false, splitArgs(body),
                    List.of());
            case "notendingwith" -> new GremlinPredicate(Kind.ENDS_WITH, true, splitArgs(body), List.of());
            case "and" -> combine(Kind.AND, body);
            case "or" -> combine(Kind.OR, body);
            default -> throw new QueryException("Unknown predicate '" + name + "'. Supported: " + SUPPORTED);
        };
    }

    /** {@code neq}: the negation of an equality test. */
    private static GremlinPredicate notEquals(String body) {
        return new GremlinPredicate(Kind.NOT_EQUALS, false, splitArgs(body), List.of());
    }

    /** Whether the name is {@code not} (a combinator) rather than {@code neq} (an equality test). */
    private static boolean isNot(String name) {
        return name.equalsIgnoreCase("not");
    }

    private static GremlinPredicate not(GremlinPredicate inner) {
        return new GremlinPredicate(Kind.NOT, false, List.of(), List.of(inner));
    }

    private static GremlinPredicate combine(Kind kind, String body) {
        List<GremlinPredicate> parts = new ArrayList<>();
        for (String argument : splitArgs(body)) {
            parts.add(parseOrValue(argument));
        }
        if (parts.isEmpty()) {
            throw new QueryException(kind == Kind.AND ? "and(...) needs at least one predicate"
                    : "or(...) needs at least one predicate");
        }
        return new GremlinPredicate(kind, false, List.of(), parts);
    }

    /** A nested argument: the predicate it spells, or an equality test against it as a value. */
    private static GremlinPredicate parseOrValue(String argument) {
        GremlinPredicate nested = parse(argument);
        return nested != null ? nested
                : new GremlinPredicate(Kind.EQUALS, false, List.of(argument.trim()), List.of());
    }

    /** Whether {@code stored} — a value, or every element of a multi-valued property — matches. */
    boolean test(Object stored) {
        List<Object> values = valuesOf(stored);
        boolean any = values.stream().anyMatch(this::matchesOne);
        return negated != any;
    }

    private boolean matchesOne(Object value) {
        return switch (kind) {
            case NOT -> !nested.get(0).test(value);
            case AND -> nested.stream().allMatch(part -> part.test(value));
            case OR -> nested.stream().anyMatch(part -> part.test(value));
            case EQUALS -> argument(0) != null && equalsValue(value, argument(0));
            case NOT_EQUALS -> argument(0) != null && !equalsValue(value, argument(0));
            case GREATER -> argument(0) != null && compare(value, argument(0)) > 0;
            case GREATER_EQUAL -> argument(0) != null && compare(value, argument(0)) >= 0;
            case LESS -> argument(0) != null && compare(value, argument(0)) < 0;
            case LESS_EQUAL -> argument(0) != null && compare(value, argument(0)) <= 0;
            // TinkerPop's definitions: between is [a, b), inside is (a, b), outside is (-inf, a) U (b, inf)
            case BETWEEN -> argument(0) != null && argument(1) != null
                    && compare(value, argument(0)) >= 0 && compare(value, argument(1)) < 0;
            case INSIDE -> argument(0) != null && argument(1) != null
                    && compare(value, argument(0)) > 0 && compare(value, argument(1)) < 0;
            case OUTSIDE -> argument(0) != null && argument(1) != null
                    && (compare(value, argument(0)) < 0 || compare(value, argument(1)) > 0);
            case WITHIN -> arguments.stream().anyMatch(argument -> equalsValue(value, argument));
            case CONTAINS -> lower(value).contains(argumentLower(0));
            case STARTS_WITH -> lower(value).startsWith(argumentLower(0));
            case ENDS_WITH -> lower(value).endsWith(argumentLower(0));
        };
    }

    private String argument(int index) {
        return index < arguments.size() ? arguments.get(index) : null;
    }

    private String argumentLower(int index) {
        String value = argument(index);
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT);
    }

    private static String lower(Object value) {
        return String.valueOf(value).toLowerCase(java.util.Locale.ROOT);
    }

    /** Equality: numeric when both sides read as numbers, otherwise case-insensitive text. */
    private static boolean equalsValue(Object stored, String argument) {
        if (argument == null) {
            return false;
        }
        if (stored instanceof Boolean bool) {
            return argument.equalsIgnoreCase(String.valueOf(bool));
        }
        String text = String.valueOf(stored);
        try {
            return Double.compare(Double.parseDouble(text), Double.parseDouble(argument)) == 0;
        } catch (NumberFormatException e) {
            return text.equalsIgnoreCase(argument);
        }
    }

    /** Ordering: numeric when both sides read as numbers, otherwise text. */
    private static int compare(Object stored, String argument) {
        String text = String.valueOf(stored);
        try {
            return Double.compare(Double.parseDouble(text), Double.parseDouble(argument));
        } catch (NumberFormatException e) {
            return text.compareTo(argument);
        }
    }

    /** A multi-valued property contributes its elements; anything else is a single value. */
    private static List<Object> valuesOf(Object stored) {
        if (stored instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }
        if (stored instanceof Object[] array) {
            List<Object> values = new ArrayList<>(array.length);
            for (Object element : array) {
                values.add(element);
            }
            return values;
        }
        return List.of(stored);
    }

    /** Splits {@code 'a','b'} on commas that are not inside quotes or brackets, and unquotes each. */
    private static List<String> splitArgs(String text) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                current.append(c);
                if (c == '\\' && i + 1 < text.length()) {
                    current.append(text.charAt(++i));
                } else if (c == quote) {
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
                result.add(unquote(current.toString()));
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        String last = current.toString().trim();
        if (!last.isEmpty()) {
            result.add(unquote(last));
        }
        return result;
    }

    private static String unquote(String token) {
        String text = token.trim();
        if (text.length() >= 2) {
            char first = text.charAt(0);
            if ((first == '\'' || first == '"') && text.charAt(text.length() - 1) == first) {
                return text.substring(1, text.length() - 1);
            }
        }
        return text;
    }

    /** The predicate this token names, or {@code null}; for the error message of a bad shape. */
    static boolean looksLikeCall(String token) {
        return token != null && SHAPE.matcher(token).matches();
    }
}
