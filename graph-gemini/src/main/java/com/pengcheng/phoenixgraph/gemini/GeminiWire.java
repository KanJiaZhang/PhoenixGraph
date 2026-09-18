package com.pengcheng.phoenixgraph.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The JSON on the wire between workers and the launcher, and the small HTTP client that carries it.
 *
 * <p>Everything a worker needs to run is written in one document (the chunk, both edge directions,
 * the out-degrees, the mirror owners, the push targets and the algorithm), which is what makes a
 * worker startable on any machine: the launcher builds the document and posts it, and the worker
 * never reads storage.
 */
final class GeminiWire {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** One client for every request a worker makes: one connection pool, one selector thread. */
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private GeminiWire() {
    }

    // ------------------------------------------------------------------ documents

    /** The layout document a worker is loaded with. */
    static ObjectNode writePayload(GeminiLauncherLayout.WorkerPayload payload, String algorithm,
                                   Map<String, Object> params) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("workerId", payload.workerId());
        node.put("begin", payload.begin());
        node.put("end", payload.end());
        node.put("vertexCount", payload.vertexCount());
        node.put("partitions", payload.partitions());
        node.put("algorithm", algorithm);
        node.set("params", MAPPER.valueToTree(params == null ? Map.of() : params));
        ObjectNode peers = node.putObject("peers");
        payload.peers().forEach((id, url) -> peers.put(String.valueOf(id), url));
        node.set("sparse", writeEdges(payload.sparse()));
        node.set("dense", writeEdges(payload.dense()));
        ObjectNode outDegree = node.putObject("outDegree");
        payload.outDegree().forEach((vertex, degree) -> outDegree.put(String.valueOf(vertex), degree));
        ObjectNode mirrorOwner = node.putObject("mirrorOwner");
        payload.mirrorOwner().forEach((vertex, owner) -> mirrorOwner.put(String.valueOf(vertex), owner));
        ObjectNode pushPeers = node.putObject("pushPeers");
        payload.pushPeers().forEach((vertex, targets) -> {
            ArrayNode array = pushPeers.putArray(String.valueOf(vertex));
            targets.forEach(array::add);
        });
        return node;
    }

    /** Reads back what {@link #writePayload} wrote, plus the algorithm it names. */
    static GeminiWorker readWorker(JsonNode node) {
        int workerId = node.path("workerId").asInt();
        int begin = node.path("begin").asInt();
        int end = node.path("end").asInt();
        int vertexCount = node.path("vertexCount").asInt();
        Map<Integer, String> peers = new LinkedHashMap<>();
        node.path("peers").fields().forEachRemaining(entry -> peers.put(Integer.parseInt(entry.getKey()),
                entry.getValue().asText()));
        Map<Integer, Integer> outDegree = new LinkedHashMap<>();
        node.path("outDegree").fields().forEachRemaining(entry ->
                outDegree.put(Integer.parseInt(entry.getKey()), entry.getValue().asInt()));
        Map<Integer, Integer> mirrorOwner = new LinkedHashMap<>();
        node.path("mirrorOwner").fields().forEachRemaining(entry ->
                mirrorOwner.put(Integer.parseInt(entry.getKey()), entry.getValue().asInt()));
        Map<Integer, java.util.Set<Integer>> pushPeers = new LinkedHashMap<>();
        node.path("pushPeers").fields().forEachRemaining(entry -> {
            java.util.Set<Integer> targets = new java.util.LinkedHashSet<>();
            entry.getValue().forEach(target -> targets.add(target.asInt()));
            pushPeers.put(Integer.parseInt(entry.getKey()), targets);
        });
        long totalEdges = node.path("totalEdges").asLong(denseEdgeCount(node));
        WorkerLayout layout = new WorkerLayout(workerId, begin, end, vertexCount,
                readEdges(node.path("sparse")), readEdges(node.path("dense")), peers,
                outDegree, mirrorOwner, pushPeers);
        Map<String, Object> params = new LinkedHashMap<>();
        node.path("params").fields().forEachRemaining(entry -> params.put(entry.getKey(),
                entry.getValue().isNumber() ? entry.getValue().numberValue() : entry.getValue().asText()));
        GeminiComputation computation = GeminiAlgorithms.of(node.path("algorithm").asText(), params);
        GeminiWorker worker = new GeminiWorker(layout, computation, totalEdges, vertexCount);
        worker.initialize();
        return worker;
    }

    private static long denseEdgeCount(JsonNode node) {
        long edges = 0;
        for (JsonNode edge : node.path("sparse")) {
            edges++;
        }
        return edges;
    }

    private static ArrayNode writeEdges(List<GeminiGraph.Edge> edges) {
        ArrayNode array = MAPPER.createArrayNode();
        for (GeminiGraph.Edge edge : edges) {
            ArrayNode entry = array.addArray();
            entry.add(edge.source());
            entry.add(edge.target());
            entry.add(edge.weight());
        }
        return array;
    }

    private static List<GeminiGraph.Edge> readEdges(JsonNode node) {
        List<GeminiGraph.Edge> edges = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode entry : node) {
                if (entry.isArray() && entry.size() >= 2) {
                    edges.add(new GeminiGraph.Edge(entry.get(0).asInt(), entry.get(1).asInt(),
                            entry.size() > 2 ? entry.get(2).asDouble() : 1.0));
                }
            }
        }
        return edges;
    }

    /** The message batch document of one superstep. */
    static ObjectNode writeBatch(int round, int from, Map<Integer, Object> messages) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("round", round);
        node.put("from", from);
        ArrayNode array = node.putArray("messages");
        for (Map.Entry<Integer, Object> entry : messages.entrySet()) {
            ArrayNode message = array.addArray();
            message.add(entry.getKey());
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> map) {
                ObjectNode object = message.addObject();
                for (Map.Entry<?, ?> nested : map.entrySet()) {
                    object.put(String.valueOf(nested.getKey()), ((Number) nested.getValue()).doubleValue());
                }
            } else if (value instanceof Long longValue) {
                // a component label or a depth is a whole number: keeping it integral is what lets the
                // receiving worker cast the message back to the type the algorithm uses
                message.add(longValue);
            } else if (value instanceof Integer intValue) {
                message.add(intValue);
            } else if (value instanceof Number number) {
                message.add(number.doubleValue());
            } else {
                message.add(String.valueOf(value));
            }
        }
        return node;
    }

    /** Whether a batch document was delivered. */
    static boolean delivered(JsonNode response) {
        return response != null && response.has("received");
    }

    // ------------------------------------------------------------------ http

    /** Posts a message batch to a peer worker. */
    static void postBatch(String baseUrl, int round, int from, Map<Integer, Object> messages) {
        post(baseUrl + "/gemini/messages?round=" + round, writeBatch(round, from, messages), false);
    }

    /** Posts a document and returns the parsed answer. */
    static JsonNode post(String url, JsonNode body, boolean required) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body),
                            StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("Worker " + url + " answered " + response.statusCode()
                        + ": " + response.body());
            }
            return response.body().isBlank() ? null : MAPPER.readTree(response.body());
        } catch (IOException e) {
            if (required) {
                throw new IllegalStateException("Cannot reach worker " + url + ": " + e.getMessage(), e);
            }
            throw new IllegalStateException(e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while talking to " + url, e);
        }
    }

    /** Reads a document from a URL. */
    static JsonNode get(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET().build();
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("Worker " + url + " answered " + response.statusCode());
            }
            return MAPPER.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot reach worker " + url + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while talking to " + url, e);
        }
    }

    static ObjectMapper mapper() {
        return MAPPER;
    }
}
