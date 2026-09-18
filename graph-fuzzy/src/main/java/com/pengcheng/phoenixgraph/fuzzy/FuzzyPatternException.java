package com.pengcheng.phoenixgraph.fuzzy;

/**
 * A fuzzy pattern that cannot be read or run.
 *
 * <p>The message is written for the person who typed the pattern: it says which part of the
 * expression is wrong and what was expected, because the pattern editor shows it verbatim.
 */
public class FuzzyPatternException extends RuntimeException {

    public FuzzyPatternException(String message) {
        super(message);
    }

    public FuzzyPatternException(String message, Throwable cause) {
        super(message, cause);
    }
}
