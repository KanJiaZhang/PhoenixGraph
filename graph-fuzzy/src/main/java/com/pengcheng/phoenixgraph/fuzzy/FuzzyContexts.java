package com.pengcheng.phoenixgraph.fuzzy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reading a context from where it comes: a JSON object a client sent, or the properties of a vertex
 * a traversal is standing on.
 *
 * <p>Both turn into the same {@link FuzzyContext}, so a pattern cannot tell whether it was given the
 * context by a form ("check these details") or found it on an account ("what does this account share
 * with others") - the pattern editor previews the first and a traversal uses the second.
 */
public final class FuzzyContexts {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FuzzyContexts() {
    }

    /**
     * A context from a JSON object, e.g. {@code {"email":"zhang1988@mail.ru","ip":"10.20.31.7"}}.
     *
     * @param json the document; blank or {@code null} gives an empty context
     */
    public static FuzzyContext fromJson(String json) {
        if (json == null || json.isBlank()) {
            return FuzzyContext.empty();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            throw new FuzzyPatternException("The fuzzy context is not valid JSON: " + e.getMessage(), e);
        }
        if (!root.isObject()) {
            throw new FuzzyPatternException("A fuzzy context must be a JSON object of field -> value");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            JsonNode value = entry.getValue();
            values.put(entry.getKey(), value.isNumber() ? value.numberValue()
                    : value.isBoolean() ? value.booleanValue() : value.isNull() ? null : value.asText());
        }
        return FuzzyContext.of(values);
    }

    /** A context from the properties of a vertex (values are read as text by a pattern). */
    public static FuzzyContext fromProperties(Map<String, ?> properties) {
        return FuzzyContext.of(properties);
    }

    /** A context a client sent, with the properties of a vertex filling in what it left out. */
    public static FuzzyContext fromJsonAndProperties(String json, Map<String, ?> properties) {
        return fromProperties(properties).with(fromJson(json).asMap());
    }

    /** A context as the JSON an editor would send back. */
    public static String toJson(FuzzyContext context) {
        try {
            return MAPPER.writeValueAsString(context.asMap());
        } catch (Exception e) {
            throw new FuzzyPatternException("Cannot write the fuzzy context: " + e.getMessage(), e);
        }
    }
}
