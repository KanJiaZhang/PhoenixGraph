package com.pengcheng.phoenixgraph.query.gremlin;

import com.pengcheng.phoenixgraph.query.QueryException;

import java.util.List;

/**
 * {@code math('_ * 2 + 1')}: the arithmetic a traversal writes down, evaluated per traverser.
 *
 * <p>The expression is the one TinkerPop's {@code math()} names: {@code _} is the traverser's own
 * number, the operators are {@code + - * / %}, and parentheses group. Numbers are read as
 * {@code double} while the expression is evaluated, and an integral result comes back as a
 * {@code long} - the same rule the script layer uses, so {@code math('_ + 1')} over an id gives a
 * number a later {@code is(gt(...))} can compare.
 *
 * <p>A traverser that is not a number is an error rather than a silent zero: {@code math()} is for
 * values, so a query writes {@code values('amount').math('_ * 2')} and the message says so.
 */
final class GremlinNumbers {

    private GremlinNumbers() {
    }

    /** The value of {@code expression} with {@code _} bound to {@code current}. */
    static Object math(String expression, Object current) {
        double value = new Parser(expression, numeric(current, expression)).parse();
        return value == Math.rint(value) && !Double.isInfinite(value)
                ? (Object) (long) value : (Object) value;
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
        private final double underscore;
        private int at;

        Parser(String text, double underscore) {
            this.text = text == null ? "" : text;
            this.underscore = underscore;
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
            return atom();
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
            if (at < text.length() && (text.charAt(at) == '_')) {
                at++;
                return underscore;
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
