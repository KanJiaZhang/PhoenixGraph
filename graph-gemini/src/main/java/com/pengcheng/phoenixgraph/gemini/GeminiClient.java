package com.pengcheng.phoenixgraph.gemini;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/** The small HTTP client the launcher and the workers use to talk to a worker. */
final class GeminiClient {

    private GeminiClient() {
    }

    /** Posts a message batch to a peer (the workers use this during a superstep). */
    static void postBatch(String baseUrl, int round, int from, Map<Integer, Object> messages) {
        GeminiWire.postBatch(baseUrl, round, from, messages);
    }

    /** Loads a worker with its layout and algorithm. */
    static Map<String, Object> load(String baseUrl, JsonNode payload) {
        return flatten(GeminiWire.post(baseUrl + "/gemini/layout", payload, true));
    }

    /** Runs the exchange half of a superstep and reports what the worker sent. */
    static Map<String, Object> exchange(String baseUrl, int round, boolean sparse) {
        return flatten(GeminiWire.post(baseUrl + "/gemini/exchange?round=" + round
                + "&mode=" + (sparse ? "sparse" : "dense"), GeminiWire.mapper().createObjectNode(), true));
    }

    /** Runs the compute half of a superstep. */
    static Map<String, Object> compute(String baseUrl, int round, boolean sparse) {
        return flatten(GeminiWire.post(baseUrl + "/gemini/compute?round=" + round
                + "&mode=" + (sparse ? "sparse" : "dense"), GeminiWire.mapper().createObjectNode(), true));
    }

    /** The number of edges the active vertices have, which the mode rule is applied to. */
    static Map<String, Object> activeEdges(String baseUrl) {
        return flatten(GeminiWire.get(baseUrl + "/gemini/active"));
    }

    /** The values of the vertices a worker owns. */
    static Map<Integer, Object> values(String baseUrl) {
        JsonNode node = GeminiWire.get(baseUrl + "/gemini/values");
        Map<Integer, Object> values = new LinkedHashMap<>();
        node.path("values").fields().forEachRemaining(entry ->
                values.put(Integer.parseInt(entry.getKey()), value(entry.getValue())));
        return values;
    }

    /** The per-worker aggregate (Louvain's community weights). */
    static Map<String, Object> aggregate(String baseUrl) {
        JsonNode node = GeminiWire.post(baseUrl + "/gemini/aggregate",
                GeminiWire.mapper().createObjectNode(), true);
        Map<String, Object> out = new LinkedHashMap<>();
        node.path("aggregate").fields().forEachRemaining(entry -> out.put(entry.getKey(),
                entry.getValue().isNumber() ? entry.getValue().numberValue() : entry.getValue().asText()));
        return out;
    }

    /** Hands the driver's shared state to a worker. */
    static void share(String baseUrl, Map<String, Object> shared) {
        GeminiWire.post(baseUrl + "/gemini/share", GeminiWire.mapper().valueToTree(shared), true);
    }

    /** A worker's status. */
    static Map<String, Object> status(String baseUrl) {
        return flatten(GeminiWire.get(baseUrl + "/gemini/status"));
    }

    /** A JSON value as the Java value it stands for (a number stays a number, a boolean a boolean). */
    private static Object plain(JsonNode node) {
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNull()) {
            return null;
        }
        return node.asText();
    }

    private static Object value(JsonNode node) {
        if (node.isFloatingPointNumber() || node.isDouble()) {
            return node.doubleValue();
        }
        return node.asLong();
    }

    private static Map<String, Object> flatten(JsonNode node) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return out;
        }
        node.fields().forEachRemaining(entry -> out.put(entry.getKey(), plain(entry.getValue())));
        return out;
    }
}
