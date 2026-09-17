package com.pengcheng.phoenixgraph.query.cypher;

import com.pengcheng.phoenixgraph.query.QueryException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The values a query's {@code $name} references resolve to.
 *
 * <p>Being able to pass a template plus parameters is what keeps a UI or an API
 * client from building query strings by hand: {@code MATCH (a:Account {id: $id})}
 * with {@code {"id": "135"}} is parsed once and stays safe for any id.
 */
public record CypherParameters(Map<String, Object> values) {

    public CypherParameters {
        values = values == null ? Map.of() : new LinkedHashMap<>(values);
    }

    /** No parameters at all. */
    public static CypherParameters none() {
        return new CypherParameters(Map.of());
    }

    public static CypherParameters of(Map<String, Object> values) {
        return values == null || values.isEmpty() ? none() : new CypherParameters(values);
    }

    /** The value of a parameter. */
    public Object get(String name) {
        return values.get(name);
    }

    /** The value of a parameter, failing with a helpful message when it is missing. */
    public Object require(String name) {
        Objects.requireNonNull(name, "name");
        if (!values.containsKey(name)) {
            throw new QueryException("Missing value for the query parameter $" + name
                    + " (provided: " + values.keySet() + ")");
        }
        return values.get(name);
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }
}
