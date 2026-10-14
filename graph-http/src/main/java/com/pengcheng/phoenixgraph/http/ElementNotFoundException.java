package com.pengcheng.phoenixgraph.http;

/**
 * The element an operation names is not in the graph -&gt; HTTP 404.
 *
 * <p>Deliberately not an {@link IllegalArgumentException}: both are client mistakes, but they are different
 * mistakes and a caller acts on them differently. A malformed request must be fixed and sent again (400); a
 * request naming a vertex that is not there may mean the caller looked at stale data, or that the id is a
 * typo, or that the write that was supposed to create it failed - and the answer is "that vertex does not
 * exist", which is what a 404 says. Mapping both to 400 would send an integrator hunting for a syntax error
 * that is not there.
 */
public class ElementNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ElementNotFoundException(String message) {
        super(message);
    }
}
