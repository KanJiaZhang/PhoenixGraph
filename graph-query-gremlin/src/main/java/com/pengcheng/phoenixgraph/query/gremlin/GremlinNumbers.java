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
 * {@code math('_ * 2 + 1')}: the arithmetic a traversal writes down, evaluated per traverser.
 *
 * <p>The expression is the one TinkerPop's {@code math()} names. The reserved variable {@code _} is the
 * traverser's own value; any other name ({@code a}, {@code b}, ...) is a label the query bound with
 * {@code as(name)}. The operators are {@code + - * / % ^} (power) and parentheses group, and the built-in
 * functions are {@link #FUNCTIONS}. Variables are read in the order they first appear, which is the order
 * TinkerPop assigns the {@code by()} modulators in.
 *
 * <p>Numbers are read as {@code double} while the expression is evaluated, and an integral result comes
 * back as a {@code long} - the same rule the script layer uses, so {@code math('_ + 1')} over an id gives a
 * number a later {@code is(gt(...))} can compare.
 *
 * <p>A traverser that is not a number is an error rather than a silent zero: {@code math()} is for values,
 * so a query writes {@code values('amount').math('_ * 2')} and the message says so.
 */
final class GremlinNumbers {

    /** The functions TinkerPop's {@code math()} provides, in the order its documentation lists them. */
    static final Set<String> FUNCTIONS = Set.of("abs", "acos", "asin", "atan", "cbrt", "ceil", "cos",
            "cosh", "exp", "floor", "log", "log10", "log2", "sin", "sinh", "sqrt", "tan", "tanh",
            "signum");

    private GremlinNumbers() {
    }

    /** The value of {@code expression} with {@code _} bound to {@code current}. */
    static Object math(String expression, Object current) {
        Map<String, Double> bindings = new LinkedHashMap<>();
        if (variables(expression).contains("_")) {
            // only an expression that uses _ looks at the traverser; math('1 + 2') answers 3 for any value
            bindings.put("_", numeric(current, expression));
        }
        return math(expression, bindings);
    }

    /** The value of {@code expression} with every variable bound to a number already. */
    static Object math(String expression, Map<String, Double> bindings) {
        double value = new Parser(expression, bindings).parse();
        return value == Math.rint(value) && !Double.isInfinite(value)
                ? (Object) (long) value : (Object) value;
    }

    /**
     * The variable names of an expression, in the order they first appear: {@code _} plus every identifier
     * that is not a function name. Numbers (including exponents such as {@code 1e-3}) are skipped, so the
     * {@code e} of a literal is not read as a variable.
     */
    static List<String> variables(String expression) {
        String text = expression == null ? "" : expression;
        Set<String> names = new LinkedHashSet<>();
        int at = 0;
        while (at < text.length()) {
            char c = text.charAt(at);
            if (Character.isDigit(c) || (c == '.' && at + 1 < text.length()
                    && Character.isDigit(text.charAt(at + 1)))) {
                at = skipNumber(text, at);
            } else if (Character.isLetter(c) || c == '_') {
                int start = at;
                while (at < text.length() && (Character.isLetterOrDigit(text.charAt(at))
                        || text.charAt(at) == '_')) {
                    at++;
                }
                String name = text.substring(start, at);
                if (!FUNCTIONS.contains(name.toLowerCase(Locale.ROOT))) {
                    names.add(name);
                }
            } else {
                at++;
            }
        }
        return new ArrayList<>(names);
    }

    private static int skipNumber(String text, int at) {
        while (at < text.length() && (Character.isDigit(text.charAt(at)) || text.charAt(at) == '.')) {
            at++;
        }
        if (at < text.length() && (text.charAt(at) == 'e' || text.charAt(at) == 'E')) {
            int exponent = at + 1;
            if (exponent < text.length() && (text.charAt(exponent) == '+' || text.charAt(exponent) == '-')) {
                exponent++;
            }
            if (exponent < text.length() && Character.isDigit(text.charAt(exponent))) {
                at = exponent;
                while (at < text.length() && Character.isDigit(text.charAt(at))) {
                    at++;
                }
            }
        }
        return at;
    }

    private static double numeric(Object value, String expression) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof Boolean bool) {
            return bool ? 1d : 0d;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new QueryException("math('" + expression + "') needs a number, got: " + value
                    + " (put values('key') before it)");
        }
    }

    /** A recursive descent parser over the expression text. */
    private static final class Parser {
        private final String text;
        private final Map<String, Double> bindings;
        private int at;

        Parser(String text, Map<String, Double> bindings) {
            this.text = text == null ? "" : text;
            this.bindings = bindings;
        }

        double parse() {
            double value = sum();
            skipSpace();
            if (at < text.length()) {
                throw new QueryException("math('" + text + "') has unexpected text at '" + text.substring(at)
                        + "'");
            }
            return value;
        }

        /** {@code +}/{@code -}. */
        private double sum() {
            double value = product();
            while (true) {
                skipSpace();
                if (eat('+')) {
                    value += product();
                } else if (eat('-')) {
                    value -= product();
                } else {
                    return value;
                }
            }
        }

        /** {@code *}/{@code /}/{@code %}. */
        private double product() {
            double value = unary();
            while (true) {
                skipSpace();
                if (eat('*')) {
                    value *= unary();
                } else if (eat('/')) {
                    value /= unary();
                } else if (eat('%')) {
                    value %= unary();
                } else {
                    return value;
                }
            }
        }

        private double unary() {
            skipSpace();
            if (eat('-')) {
                return -unary();
            }
            if (eat('+')) {
                return unary();
            }
            return power();
        }

        /** {@code ^} (power), right-associative: {@code 2^3^2} is {@code 2^(3^2)}. */
        private double power() {
            double base = atom();
            skipSpace();
            if (eat('^')) {
                return Math.pow(base, power());
            }
            return base;
        }

        private double atom() {
            skipSpace();
            if (eat('(')) {
                double value = sum();
                skipSpace();
                if (!eat(')')) {
                    throw new QueryException("math('" + text + "') is missing a ')'");
                }
                return value;
            }
            if (at < text.length() && (Character.isLetter(text.charAt(at)) || text.charAt(at) == '_')) {
                int start = at;
                while (at < text.length() && (Character.isLetterOrDigit(text.charAt(at))
                        || text.charAt(at) == '_')) {
                    at++;
                }
                String name = text.substring(start, at);
                if (FUNCTIONS.contains(name.toLowerCase(Locale.ROOT))) {
                    return function(name.toLowerCase(Locale.ROOT));
                }
                Double bound = bindings.get(name);
                if (bound == null) {
                    throw new QueryException("math('" + text + "') uses the variable '" + name + "', which"
                            + " is not bound here: bind it with as('" + name + "') before the step, or write"
                            + " _ for the traverser itself");
                }
                return bound;
            }
            int start = at;
            while (at < text.length() && (Character.isDigit(text.charAt(at)) || text.charAt(at) == '.'
                    || text.charAt(at) == 'e' || text.charAt(at) == 'E'
                    || ((text.charAt(at) == '-' || text.charAt(at) == '+')
                        && at > start && (text.charAt(at - 1) == 'e' || text.charAt(at - 1) == 'E')))) {
                at++;
            }
            if (start == at) {
                throw new QueryException("math('" + text + "') cannot read '" + text.substring(Math.min(at,
                        text.length())) + "'");
            }
            try {
                return Double.parseDouble(text.substring(start, at));
            } catch (NumberFormatException e) {
                throw new QueryException("math('" + text + "') cannot read '" + text.substring(start, at)
                        + "' as a number");
            }
        }

        /** A function call: {@code sin(_)} or the TinkerPop spelling {@code sin _}. */
        private double function(String name) {
            skipSpace();
            double argument;
            if (at < text.length() && text.charAt(at) == '(') {
                at++;
                argument = sum();
                skipSpace();
                if (!eat(')')) {
                    throw new QueryException("math('" + text + "') is missing a ')' after " + name + "(");
                }
            } else {
                argument = unary();
            }
            return switch (name) {
                case "abs" -> Math.abs(argument);
                case "acos" -> Math.acos(argument);
                case "asin" -> Math.asin(argument);
                case "atan" -> Math.atan(argument);
                case "cbrt" -> Math.cbrt(argument);
                case "ceil" -> Math.ceil(argument);
                case "cos" -> Math.cos(argument);
                case "cosh" -> Math.cosh(argument);
                case "exp" -> Math.exp(argument);
                case "floor" -> Math.floor(argument);
                case "log" -> Math.log(argument);
                case "log10" -> Math.log10(argument);
                case "log2" -> Math.log(argument) / Math.log(2);
                case "sin" -> Math.sin(argument);
                case "sinh" -> Math.sinh(argument);
                case "sqrt" -> Math.sqrt(argument);
                case "tan" -> Math.tan(argument);
                case "tanh" -> Math.tanh(argument);
                case "signum" -> Math.signum(argument);
                default -> throw new QueryException("math('" + text + "') does not know the function '"
                        + name + "'");
            };
        }

        private boolean eat(char wanted) {
            if (at < text.length() && text.charAt(at) == wanted) {
                at++;
                return true;
            }
            return false;
        }

        private void skipSpace() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
                at++;
            }
        }
    }

    /** The units {@code dateAdd}/{@code dateDiff} accept, in TinkerPop's spelling or the plain word. */
    static List<String> units() {
        return List.of("second", "minute", "hour", "day", "week", "month", "year");
    }
}
