package com.pengcheng.phoenixgraph.fuzzy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes the patterns of every graph as one JSON document:
 *
 * <pre>
 * { "payment-graph": [ { "id": 0, "name": "...", "expression": "...", "enabled": true } ] }
 * </pre>
 *
 * <p>Patterns are configuration, like a schema: they are edited in the builder page, written to a
 * file, and read back by the next query. A document that cannot be read is reported with the line
 * of the offending pattern rather than silently dropped, because a pattern that quietly stopped
 * applying would look like "the ring is gone".
 */
public final class FuzzyPatterns {

    private final ObjectMapper mapper;

    public FuzzyPatterns() {
        this(new ObjectMapper());
    }

    public FuzzyPatterns(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** The patterns of every graph in the document. */
    public Map<String, List<FuzzyPattern>> readAll(String json) {
        Map<String, List<FuzzyPattern>> out = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (Exception e) {
            throw new FuzzyPatternException("The fuzzy pattern document is not valid JSON: "
                    + e.getMessage(), e);
        }
        if (!root.isObject()) {
            throw new FuzzyPatternException("The fuzzy pattern document must be an object of graph -> patterns");
        }
        root.fields().forEachRemaining(entry -> out.put(entry.getKey(), readPatterns(entry.getKey(),
                entry.getValue())));
        return out;
    }

    /** The patterns of one graph in the document. */
    public List<FuzzyPattern> read(String json, String graph) {
        return readAll(json).getOrDefault(graph, List.of());
    }

    /** The whole document with the graph's patterns replaced. */
    public String write(String json, String graph, List<FuzzyPattern> patterns) {
        Map<String, List<FuzzyPattern>> all = readAll(json);
        all.put(graph, List.copyOf(patterns));
        return writeAll(all);
    }

    /** Serialises every graph's patterns. */
    public String writeAll(Map<String, List<FuzzyPattern>> patterns) {
        ObjectNode root = mapper.createObjectNode();
        for (Map.Entry<String, List<FuzzyPattern>> entry : patterns.entrySet()) {
            ArrayNode array = root.putArray(entry.getKey());
            for (FuzzyPattern pattern : entry.getValue()) {
                ObjectNode node = array.addObject();
                node.put("id", pattern.id());
                node.put("name", pattern.name());
                node.put("expression", pattern.expression());
                if (!pattern.description().isEmpty()) {
                    node.put("description", pattern.description());
                }
                node.put("enabled", pattern.enabled());
            }
        }
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            throw new FuzzyPatternException("Cannot write the fuzzy patterns: " + e.getMessage(), e);
        }
    }

    /** The patterns of one entry, validated as they are read. */
    private List<FuzzyPattern> readPatterns(String graph, JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new FuzzyPatternException("The patterns of graph '" + graph + "' must be an array");
        }
        List<FuzzyPattern> out = new ArrayList<>(node.size());
        for (JsonNode element : node) {
            if (!element.isObject()) {
                throw new FuzzyPatternException("Every pattern of graph '" + graph + "' must be an object");
            }
            int id = element.path("id").asInt(-1);
            String expression = element.path("expression").asText("");
            if (id < 0) {
                throw new FuzzyPatternException("A pattern of graph '" + graph + "' has no id");
            }
            try {
                out.add(new FuzzyPattern(id, element.path("name").asText(null), expression,
                        element.path("description").asText(""), !element.has("enabled")
                                || element.path("enabled").asBoolean(true)));
            } catch (FuzzyPatternException e) {
                throw new FuzzyPatternException("Pattern " + id + " of graph '" + graph + "': "
                        + e.getMessage(), e);
            }
        }
        return out;
    }
}
