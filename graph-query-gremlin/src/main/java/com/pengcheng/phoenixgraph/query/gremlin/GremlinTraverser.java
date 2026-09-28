package com.pengcheng.phoenixgraph.query.gremlin;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One traverser of the stateful execution path: the value it carries, the labels it has been given, and
 * (when a query asks for it) the walk that brought it here.
 *
 * <p>The list-shaped pipeline carries a bare value, which is why it is fast and why it cannot answer
 * {@code as}/{@code select}: nothing remembers where a value came from. A query that needs that runs on
 * this second path instead (see TRAVERSER-DESIGN.md), and this is what flows through it. The value is
 * the same object the list pipeline would carry - a vertex, an edge or a plain value - so every step
 * that does not care about state keeps working on it unchanged, through the adapter in
 * {@link GremlinGraphQuery}.
 *
 * @param value  what the traverser stands on
 * @param labels the {@code as(...)} bindings, newest value per label
 * @param path   the walk so far, or {@code null} when nothing asked for it (a query pays for its walk
 *               only when it asks: {@code path()}, or {@code select} over a label bound before a hop)
 */
public record GremlinTraverser(Object value, Map<String, Object> labels, java.util.List<Object> path) {

    public GremlinTraverser {
        labels = labels == null ? Map.of() : Map.copyOf(labels);
        path = path == null ? null : java.util.List.copyOf(path);
    }

    /** A traverser standing on a value with no labels yet. */
    public static GremlinTraverser of(Object value) {
        return new GremlinTraverser(value, Map.of(), null);
    }

    /** The same traverser standing on another value (a hop, a projection), labels and walk kept. */
    public GremlinTraverser withValue(Object next) {
        return new GremlinTraverser(next, labels, path);
    }

    /** The same traverser with one more label bound. */
    public GremlinTraverser withLabel(String label, Object bound) {
        Map<String, Object> next = new LinkedHashMap<>(labels);
        next.put(label, bound);
        return new GremlinTraverser(value, next, path);
    }

    /** The same traverser with the walk extended (a hop appends the edge it took and the vertex it reached). */
    public GremlinTraverser withPathElement(Object element) {
        java.util.List<Object> next = new java.util.ArrayList<>(path == null ? java.util.List.of() : path);
        next.add(element);
        return new GremlinTraverser(value, labels, next);
    }

    /** The same traverser with the walk extended by several elements. */
    public GremlinTraverser withPathElements(java.util.List<Object> elements) {
        java.util.List<Object> next = new java.util.ArrayList<>(path == null ? java.util.List.of() : path);
        next.addAll(elements);
        return new GremlinTraverser(value, labels, next);
    }

    /** The labelled value, or {@code null} when the label was never bound. */
    public Object label(String name) {
        return labels.get(name);
    }
}
