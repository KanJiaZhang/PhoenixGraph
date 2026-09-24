package com.pengcheng.phoenixgraph.query.gremlin;

import java.util.AbstractList;
import java.util.List;

/**
 * The elements a traversal visited on its way to one answer: what {@code path()} returns.
 *
 * <pre>
 * g.V(1001, 'Account').out('Payment').out('Payment').path()
 *   -> [ Account 1001, Payment p1, Account 1002, Payment p2, Account 1003 ]
 * </pre>
 *
 * <p>It is a {@link List} of the visited objects, vertices and edges in the order they were walked, so a
 * caller can read it like any list ({@code size()}, {@code get(i)}, {@code contains(...)}) and a script can
 * keep a list of paths. {@link #start()} and {@link #end()} name the two ends, which is what joins two paths
 * over a shared vertex.
 */
public final class GremlinPath extends AbstractList<Object> {

    private final List<Object> elements;

    public GremlinPath(List<Object> elements) {
        // a path may hold nulls once path().by(key) maps an element without that key
        this.elements = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(elements));
    }

    @Override
    public Object get(int index) {
        return elements.get(index);
    }

    @Override
    public int size() {
        return elements.size();
    }

    /** The element the path starts at, or {@code null} for an empty path. */
    public Object start() {
        return elements.isEmpty() ? null : elements.get(0);
    }

    /** The element the path ends at, or {@code null} for an empty path. */
    public Object end() {
        return elements.isEmpty() ? null : elements.get(elements.size() - 1);
    }

    /** The visited elements, in order. */
    public List<Object> objects() {
        return elements;
    }

    @Override
    public String toString() {
        return elements.toString();
    }
}
