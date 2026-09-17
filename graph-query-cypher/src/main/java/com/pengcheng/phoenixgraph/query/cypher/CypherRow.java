package com.pengcheng.phoenixgraph.query.cypher;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One matched row: the pattern variables with the elements they are bound to, in
 * binding order.
 */
public final class CypherRow {

    private final Map<String, CypherElement> bindings = new LinkedHashMap<>();

    CypherRow() {
    }

    private CypherRow(Map<String, CypherElement> bindings) {
        this.bindings.putAll(bindings);
    }

    /** A copy of this row (rows are immutable while matching: every step branches). */
    CypherRow copy() {
        return new CypherRow(bindings);
    }

    CypherRow bind(String variable, CypherElement element) {
        bindings.put(variable, element);
        return this;
    }

    /** The element bound to a variable, or {@code null} when it is not bound. */
    public CypherElement get(String variable) {
        return bindings.get(variable);
    }

    public boolean bound(String variable) {
        return bindings.containsKey(variable);
    }

    /** The variable names of this row, in binding order. */
    public List<String> variables() {
        return List.copyOf(bindings.keySet());
    }

    /** The bindings as an immutable map. */
    public Map<String, CypherElement> bindings() {
        return Map.copyOf(bindings);
    }

    @Override
    public String toString() {
        return bindings.toString();
    }
}
