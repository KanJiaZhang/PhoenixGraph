package com.pengcheng.phoenixgraph.query.cypher;

import com.pengcheng.phoenixgraph.query.QueryException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * An openCypher expression: the right-hand side of a {@code WHERE} clause, a
 * {@code RETURN} item or an {@code ORDER BY} key.
 *
 * <p>Row-wise expressions evaluate with {@link #evaluate(CypherRow)}. Aggregations
 * ({@code count/sum/avg/min/max/collect}) are evaluated per group instead, by
 * {@link Aggregate}; an expression containing one reports {@link #isAggregating()}
 * so the projection can group rows, and mixing an aggregation into a larger
 * expression is rejected by the parser.
 */
public sealed interface CypherExpression {

    /** The value of this expression for one row. */
    Object evaluate(CypherRow row);

    /** Variables this expression reads. */
    default Set<String> variables() {
        return Set.of();
    }

    /** Whether the expression contains an aggregation (and must be evaluated per group). */
    default boolean isAggregating() {
        return false;
    }

    /** A literal value: number, string, boolean or {@code null}. */
    record Literal(Object value) implements CypherExpression {

        @Override
        public Object evaluate(CypherRow row) {
            return value;
        }

        @Override
        public String toString() {
            return value instanceof String ? "'" + value + "'" : String.valueOf(value);
        }
    }

    /** A query parameter ({@code $name}), resolved before execution. */
    record Parameter(String name) implements CypherExpression {

        @Override
        public Object evaluate(CypherRow row) {
            throw new QueryException("Parameter $" + name + " was not provided");
        }

        @Override
        public String toString() {
            return "$" + name;
        }
    }

    /** A property of a bound variable ({@code a.accountType}). */
    record Property(String variable, String key) implements CypherExpression {

        @Override
        public Object evaluate(CypherRow row) {
            CypherElement element = row.get(variable);
            return element == null ? null : element.property(key);
        }

        @Override
        public Set<String> variables() {
            return Set.of(variable);
        }

        @Override
        public String toString() {
            return variable + "." + key;
        }
    }

    /** A comparison: {@code =}, {@code <>}/{@code !=}, {@code <}, {@code <=}, {@code >}, {@code >=}. */
    record Compare(CypherExpression left, String operator, CypherExpression right) implements CypherExpression {

        @Override
        public Object evaluate(CypherRow row) {
            Object a = left.evaluate(row);
            Object b = right.evaluate(row);
            if (a == null || b == null) {
                // Cypher: a comparison with null is null, i.e. not true
                return !operator.equals("<>") && !operator.equals("!=") ? Boolean.FALSE
                        : (a == null && b == null ? Boolean.FALSE : Boolean.TRUE);
            }
            int cmp = Values.compare(a, b);
            return switch (operator) {
                case "=" -> cmp == 0;
                case "<>", "!=" -> cmp != 0;
                case "<" -> cmp < 0;
                case "<=" -> cmp <= 0;
                case ">" -> cmp > 0;
                case ">=" -> cmp >= 0;
                default -> throw new QueryException("Unsupported comparison: " + operator);
            };
        }

        @Override
        public Set<String> variables() {
            return union(left, right);
        }

        @Override
        public String toString() {
            return left + " " + operator + " " + right;
        }
    }

    /** Conjunction. */
    record And(List<CypherExpression> operands) implements CypherExpression {

        @Override
        public Object evaluate(CypherRow row) {
            for (CypherExpression operand : operands) {
                if (!Values.truthy(operand.evaluate(row))) {
                    return Boolean.FALSE;
                }
            }
            return Boolean.TRUE;
        }

        @Override
        public Set<String> variables() {
            return union(operands);
        }

        @Override
        public boolean isAggregating() {
            return operands.stream().anyMatch(CypherExpression::isAggregating);
        }
    }

    /** Disjunction. */
    record Or(List<CypherExpression> operands) implements CypherExpression {

        @Override
        public Object evaluate(CypherRow row) {
            for (CypherExpression operand : operands) {
                if (Values.truthy(operand.evaluate(row))) {
                    return Boolean.TRUE;
                }
            }
            return Boolean.FALSE;
        }

        @Override
        public Set<String> variables() {
            return union(operands);
        }

        @Override
        public boolean isAggregating() {
            return operands.stream().anyMatch(CypherExpression::isAggregating);
        }
    }

    /** Negation. */
    record Not(CypherExpression operand) implements CypherExpression {

        @Override
        public Object evaluate(CypherRow row) {
            return !Values.truthy(operand.evaluate(row));
        }

        @Override
        public Set<String> variables() {
            return operand.variables();
        }
    }

    /** {@code x IN [a, b, c]}. */
    record In(CypherExpression value, List<CypherExpression> list) implements CypherExpression {

        @Override
        public Object evaluate(CypherRow row) {
            Object probe = value.evaluate(row);
            if (probe == null) {
                return Boolean.FALSE;
            }
            for (CypherExpression candidate : list) {
                Object other = candidate.evaluate(row);
                if (other != null && Values.compare(probe, other) == 0) {
                    return Boolean.TRUE;
                }
            }
            return Boolean.FALSE;
        }

        @Override
        public Set<String> variables() {
            Set<String> variables = new LinkedHashSet<>(value.variables());
            for (CypherExpression candidate : list) {
                variables.addAll(candidate.variables());
            }
            return variables;
        }
    }

    /** {@code x IS NULL} / {@code x IS NOT NULL}. */
    record IsNull(CypherExpression value, boolean negated) implements CypherExpression {

        @Override
        public Object evaluate(CypherRow row) {
            boolean isNull = value.evaluate(row) == null;
            return negated != isNull;
        }

        @Override
        public Set<String> variables() {
            return value.variables();
        }

        @Override
        public String toString() {
            return value + (negated ? " IS NOT NULL" : " IS NULL");
        }
    }

    /** String predicates: {@code CONTAINS}, {@code STARTS WITH}, {@code ENDS WITH}, {@code =~}. */
    record StringOp(CypherExpression value, String operator, CypherExpression pattern)
            implements CypherExpression {

        @Override
        public Object evaluate(CypherRow row) {
            Object subject = value.evaluate(row);
            Object expected = pattern.evaluate(row);
            if (subject == null || expected == null) {
                return Boolean.FALSE;
            }
            String text = String.valueOf(subject);
            String target = String.valueOf(expected);
            return switch (operator) {
                case "CONTAINS" -> text.contains(target);
                case "STARTS WITH" -> text.startsWith(target);
                case "ENDS WITH" -> text.endsWith(target);
                case "=~" -> Pattern.compile(target).matcher(text).matches();
                default -> throw new QueryException("Unsupported string operator: " + operator);
            };
        }

        @Override
        public Set<String> variables() {
            return union(value, pattern);
        }

        @Override
        public String toString() {
            return value + " " + operator + " " + pattern;
        }
    }

    /**
     * A function call: scalar functions ({@code id}, {@code type}, {@code labels},
     * {@code toLower}, {@code toUpper}, {@code trim}, {@code length}, {@code size},
     * {@code coalesce}) or an aggregation ({@code count}, {@code sum}, {@code avg},
     * {@code min}, {@code max}, {@code collect}).
     */
    record Function(String name, List<CypherExpression> arguments, boolean distinct, boolean star)
            implements CypherExpression {

        /** Aggregation names, lower-case. */
        public static final Set<String> AGGREGATIONS = Set.of("count", "sum", "avg", "min", "max", "collect");

        public boolean isAggregation() {
            return AGGREGATIONS.contains(name.toLowerCase(Locale.ROOT));
        }

        @Override
        public boolean isAggregating() {
            return isAggregation();
        }

        @Override
        public Object evaluate(CypherRow row) {
            if (isAggregation()) {
                throw new QueryException("Aggregation " + name + "() cannot be evaluated per row");
            }
            List<Object> values = new ArrayList<>(arguments.size());
            for (CypherExpression argument : arguments) {
                values.add(argument.evaluate(row));
            }
            return Values.scalar(name, values);
        }

        @Override
        public Set<String> variables() {
            Set<String> variables = new LinkedHashSet<>();
            for (CypherExpression argument : arguments) {
                variables.addAll(argument.variables());
            }
            return variables;
        }

        @Override
        public String toString() {
            return name + "(" + (distinct ? "DISTINCT " : "") + (star ? "*"
                    : arguments.stream().map(String::valueOf).reduce((a, b) -> a + ", " + b).orElse("")) + ")";
        }
    }

        /** A variable used on its own (e.g. {@code RETURN a}). */
        record VariableReference(String name) implements CypherExpression {
    
            @Override
            public Object evaluate(CypherRow row) {
                CypherElement element = row.get(name);
                return element == null ? null : element.describe();
            }
    
            @Override
            public java.util.Set<String> variables() {
                return java.util.Set.of(name);
            }
    
            @Override
            public String toString() {
                return name;
            }
        }
    
        /** A list literal: {@code [1, 2, 3]}. */
        record ListLiteral(List<CypherExpression> items) implements CypherExpression {
    
            @Override
            public Object evaluate(CypherRow row) {
                List<Object> values = new ArrayList<>(items.size());
                for (CypherExpression item : items) {
                    values.add(item.evaluate(row));
                }
                return values;
            }
    
            @Override
            public java.util.Set<String> variables() {
                java.util.Set<String> variables = new java.util.LinkedHashSet<>();
                for (CypherExpression item : items) {
                    variables.addAll(item.variables());
                }
                return variables;
            }
        }

    /** Value helpers shared by the expression types. */
    final class Values {

        private Values() {
        }

        /** Whether a value counts as true in a {@code WHERE} clause. */
        public static boolean truthy(Object value) {
            return value instanceof Boolean bool ? bool
                    : value != null && !(value instanceof Number number && number.doubleValue() == 0);
        }

        /** Ordering between two non-null values: numbers numerically, everything else as text. */
        public static int compare(Object a, Object b) {
            if (a instanceof Number left && b instanceof Number right) {
                return Double.compare(left.doubleValue(), right.doubleValue());
            }
            if (a instanceof Number || b instanceof Number) {
                // a numeric id against a textual one ("135" vs 135) still compares numerically
                Double left = asDouble(a);
                Double right = asDouble(b);
                if (left != null && right != null) {
                    return Double.compare(left, right);
                }
            }
            return String.valueOf(a).compareTo(String.valueOf(b));
        }

        private static Double asDouble(Object value) {
            if (value instanceof Number number) {
                return number.doubleValue();
            }
            try {
                return Double.valueOf(String.valueOf(value));
            } catch (NumberFormatException e) {
                return null;
            }
        }

        /** Scalar function evaluation. */
        public static Object scalar(String name, List<Object> arguments) {
            Object first = arguments.isEmpty() ? null : arguments.get(0);
            return switch (name.toLowerCase(Locale.ROOT)) {
                case "id" -> first instanceof CypherElement element ? element.id() : null;
                case "type" -> first instanceof CypherElement element && !element.isVertex()
                        ? element.typeName() : null;
                case "labels" -> first instanceof CypherElement element && element.isVertex()
                        ? List.of(element.typeName()) : List.of();
                case "tolower" -> first == null ? null : String.valueOf(first).toLowerCase(Locale.ROOT);
                case "toupper" -> first == null ? null : String.valueOf(first).toUpperCase(Locale.ROOT);
                case "trim" -> first == null ? null : String.valueOf(first).trim();
                case "length" -> first == null ? null : String.valueOf(first).length();
                case "size" -> size(first);
                case "coalesce" -> arguments.stream().filter(Objects::nonNull).findFirst().orElse(null);
                default -> throw new QueryException("Unknown function: " + name + "()");
            };
        }

        private static Object size(Object value) {
            if (value == null) {
                return null;
            }
            if (value instanceof Collection<?> collection) {
                return collection.size();
            }
            if (value instanceof Map<?, ?> map) {
                return map.size();
            }
            return String.valueOf(value).length();
        }
    }

    /** Aggregation over the rows of one group. */
    final class Aggregate {

        private Aggregate() {
        }

        /** Evaluates an aggregation function over a group of rows. */
        public static Object evaluate(Function function, List<CypherRow> group) {
            String name = function.name().toLowerCase(Locale.ROOT);
            if (function.star()) {
                return (long) group.size();
            }
            if (function.arguments().size() != 1) {
                throw new QueryException(name + "() expects exactly one argument");
            }
            CypherExpression argument = function.arguments().get(0);
            List<Object> values = new ArrayList<>(group.size());
            for (CypherRow row : group) {
                Object value = argument.evaluate(row);
                if (value != null) {
                    values.add(value);
                }
            }
            if (function.distinct()) {
                values = distinct(values);
            }
            return switch (name) {
                case "count" -> (long) values.size();
                case "collect" -> values;
                case "sum" -> values.stream().mapToDouble(CypherExpression.Aggregate::toDouble).sum();
                case "avg" -> values.isEmpty() ? null
                        : values.stream().mapToDouble(CypherExpression.Aggregate::toDouble).average().orElse(0);
                case "min" -> values.stream().min(CypherExpression.Values::compare).orElse(null);
                case "max" -> values.stream().max(CypherExpression.Values::compare).orElse(null);
                default -> throw new QueryException("Unknown aggregation: " + name + "()");
            };
        }

        /** Evaluates any expression over a group: aggregations fold, the rest uses the group's first row. */
        public static Object evaluateAny(CypherExpression expression, List<CypherRow> group) {
            if (expression instanceof Function function && function.isAggregation()) {
                return evaluate(function, group);
            }
            if (expression.isAggregating()) {
                throw new QueryException("An aggregation cannot be combined with other expressions yet: "
                        + expression);
            }
            return group.isEmpty() ? null : expression.evaluate(group.get(0));
        }

        private static double toDouble(Object value) {
            if (value instanceof Number number) {
                return number.doubleValue();
            }
            try {
                return Double.parseDouble(String.valueOf(value));
            } catch (NumberFormatException e) {
                throw new QueryException("Not a number: " + value);
            }
        }

        private static List<Object> distinct(List<Object> values) {
            List<Object> out = new ArrayList<>(values.size());
            for (Object value : values) {
                boolean seen = out.stream().anyMatch(existing -> Values.compare(existing, value) == 0);
                if (!seen) {
                    out.add(value);
                }
            }
            return out;
        }
    }

    private static Set<String> union(CypherExpression left, CypherExpression right) {
        Set<String> variables = new LinkedHashSet<>(left.variables());
        variables.addAll(right.variables());
        return variables;
    }

    private static Set<String> union(List<CypherExpression> expressions) {
        Set<String> variables = new LinkedHashSet<>();
        for (CypherExpression expression : expressions) {
            variables.addAll(expression.variables());
        }
        return variables;
    }
}
