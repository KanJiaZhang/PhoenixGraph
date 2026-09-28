package com.pengcheng.phoenixgraph.query.gremlin;

import java.util.Map;

/**
 * A property flowing through a traversal: what {@code properties()} turns an element into, and what
 * {@code key()} and {@code value()} answer.
 *
 * <p>TinkerPop has the same traverser, and it exists here for the same reason: a query that wants the
 * keys and the values of an element separately ({@code properties().key()}, or a filter over the value
 * while keeping the key) has nothing else to hold onto. It implements {@link GremlinElement} so every
 * step that reads an element keeps working: its label and id are the property key, its property map is
 * the one entry it stands for, and the meta timestamps come from the element it belongs to.
 *
 * <p>Only the properties the traversal read are there: a hop projects what the query needs, and
 * {@code properties()} is on the list of steps that ask for all of them, so a query that ends in
 * {@code properties()} (or filters over it) sees the element's whole property map.
 */
public record GremlinProperty(String key, Object value, GremlinElement owner) implements GremlinElement {

    public GremlinProperty {
        if (key == null) {
            throw new IllegalArgumentException("A property needs a key");
        }
    }

    @Override
    public String label() {
        return key;
    }

    @Override
    public Object id() {
        return key;
    }

    @Override
    public Map<String, Object> properties() {
        return Map.of(key, value == null ? "" : value);
    }

    @Override
    public long createdTime() {
        return owner == null ? 0L : owner.createdTime();
    }

    @Override
    public long lastUpdatedTime() {
        return owner == null ? 0L : owner.lastUpdatedTime();
    }
}
