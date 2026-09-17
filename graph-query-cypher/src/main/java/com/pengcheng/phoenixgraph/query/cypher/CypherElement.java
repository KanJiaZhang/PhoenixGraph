package com.pengcheng.phoenixgraph.query.cypher;

import java.util.List;
import java.util.Map;

/**
 * An element bound to a variable while a pattern is matched: a vertex or a
 * relationship, with the properties that were read for it.
 *
 * <p>{@code id} is always available (also through the {@code id} property, which
 * makes {@code MATCH (a:Account {id:'135'})} and {@code WHERE id(a) = '135'} mean the
 * same thing). Relationship elements also carry their endpoints, which is what lets
 * {@code RETURN r} describe an edge.
 */
public record CypherElement(Kind kind, String label, Object id, Map<String, Object> properties,
                            Object sourceId, Object targetId) {

    /** Whether the element is a vertex or a relationship. */
    public enum Kind {
        VERTEX,
        RELATIONSHIP
    }

    public CypherElement {
        properties = properties == null ? Map.of() : properties;
    }

    public static CypherElement vertex(String label, Object id, Map<String, Object> properties) {
        return new CypherElement(Kind.VERTEX, label, id, properties, null, null);
    }

    public static CypherElement relationship(String type, Object id, Object sourceId, Object targetId,
                                             Map<String, Object> properties) {
        return new CypherElement(Kind.RELATIONSHIP, type, id, properties, sourceId, targetId);
    }

    public boolean isVertex() {
        return kind == Kind.VERTEX;
    }

    /** Value of a property, treating {@code id} as always present. */
    public Object property(String key) {
        if (key == null) {
            return null;
        }
        if (key.equalsIgnoreCase("id") || key.equals("_id")) {
            return id;
        }
        return properties.get(key);
    }

    /** The vertex label or the relationship type. */
    public String typeName() {
        return label;
    }

    /** Flat description used when an element is projected on its own. */
    public Map<String, Object> describe() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("id", id);
        if (kind == Kind.VERTEX) {
            out.put("label", label);
        } else {
            out.put("type", label);
            out.put("source", sourceId);
            out.put("target", targetId);
        }
        out.put("properties", properties);
        return out;
    }

    /** All property keys (including {@code id}), for {@code RETURN *} style output. */
    public List<String> propertyKeys() {
        List<String> keys = new java.util.ArrayList<>();
        keys.add("id");
        keys.addAll(properties.keySet());
        return keys;
    }
}
