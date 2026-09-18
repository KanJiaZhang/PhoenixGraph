package com.pengcheng.phoenixgraph.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * The HTTP face of a worker: the port another worker (or the launcher) talks to.
 *
 * <p>Gemini exchanges messages between partition processes; here a message batch is a small JSON
 * document posted to the peer that needs it, and the two halves of a superstep are two endpoints:
 *
 * <ul>
 *   <li>{@code POST /gemini/layout} - the launcher hands over the chunk, the edges in both
 *       directions, the out-degrees, the mirror owners and the push targets, and names the
 *       algorithm. Nothing else is needed to run;</li>
 *   <li>{@code POST /gemini/exchange?round=N&mode=sparse|dense} - the worker produces this round's
 *       batches and posts them to its peers, which buffer them;</li>
 *   <li>{@code POST /gemini/compute?round=N&mode=...} - apply what arrived plus the local work and
 *       report the activations;</li>
 *   <li>{@code GET /gemini/values}, {@code POST /gemini/aggregate}, {@code POST /gemini/share} -
 *       what the driver reads back and hands round;</li>
 *   <li>{@code GET /gemini/status} - what a human (or a test) wants to see.</li>
 * </ul>
 *
 * <p>The server is the JDK's, so a worker is one jar with no framework and can be started by a helper
 * program on any machine - over YARN or by hand - exactly as asked of the design.
 */
public final class GeminiWorkerServer implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;
    private final int port;
    private final GeminiWorkerHolder worker = new GeminiWorkerHolder();

    public GeminiWorkerServer(int port) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        // a port of 0 lets the operating system pick one, which is what a test wants: a running
        // service may already hold the default range
        this.port = this.server.getAddress().getPort();
        // an exchange handler blocks while it posts to its peers, and the same worker also serves the
        // messages those peers send back (and the launcher's probes), so the pool has room for both
        this.server.setExecutor(Executors.newFixedThreadPool(
                Math.max(8, 4 * Runtime.getRuntime().availableProcessors())));
        // every handler is wrapped, so a worker that has not been loaded (or an algorithm that throws)
        // answers with the message instead of closing the connection
        this.server.createContext("/gemini/status", exchange -> answer(exchange, worker::status));
        this.server.createContext("/gemini/active", exchange -> answer(exchange,
                () -> Map.of("worker", worker.get().workerId(), "activeEdges", worker.get().activeEdges())));
        this.server.createContext("/gemini/layout", exchange -> answer(exchange, () -> layout(exchange)));
        this.server.createContext("/gemini/exchange", exchange -> answer(exchange, () -> exchangeStep(exchange)));
        this.server.createContext("/gemini/messages", exchange -> answer(exchange, () -> messages(exchange)));
        this.server.createContext("/gemini/compute", exchange -> answer(exchange, () -> compute(exchange)));
        this.server.createContext("/gemini/values", exchange -> answer(exchange, () -> values(exchange)));
        this.server.createContext("/gemini/aggregate", exchange -> answer(exchange, () -> aggregate(exchange)));
        this.server.createContext("/gemini/share", exchange -> answer(exchange, () -> share(exchange)));
        this.server.createContext("/gemini/halt", exchange -> answer(exchange, () -> Map.of("halted", true)));
    }

    /** Starts listening. */
    public GeminiWorkerServer start() {
        server.start();
        return this;
    }

    public int port() {
        return port;
    }

    /** The worker this server exposes, or null before a layout arrived. */
    public GeminiWorker worker() {
        return worker.peek();
    }

    /** Loads the worker from a layout document (the --load path of a standalone worker). */
    public void load(com.fasterxml.jackson.databind.node.ObjectNode document) {
        worker.load(document);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ------------------------------------------------------------------ endpoints

    private Map<String, ?> layout(HttpExchange exchange) throws IOException {
        JsonNode body = MAPPER.readTree(read(exchange));
        worker.load(body);
        return Map.of("loaded", true, "worker", worker.get().workerId(),
                "owned", worker.get().layout().end() - worker.get().layout().begin());
    }

    private Map<String, ?> exchangeStep(HttpExchange exchange) throws IOException {
        boolean sparse = "sparse".equals(query(exchange).get("mode"));
        Map<Integer, Map<Integer, Object>> batches = worker.get().exchange(sparse);
        int delivered = 0;
        List<String> failed = new ArrayList<>();
        for (Map.Entry<Integer, Map<Integer, Object>> batch : batches.entrySet()) {
            if (batch.getKey() == worker.get().workerId()) {
                // the batch for this worker's own vertices is applied here, not sent over HTTP: in
                // the sparse mode it is the update a vertex pushes along its locally stored edges,
                // and dropping it would lose every edge whose endpoints share a worker
                worker.get().receive(round(exchange), batch.getValue());
                delivered += batch.getValue().size();
                continue;
            }
            String url = worker.get().layout().peers().get(batch.getKey());
            if (url == null) {
                continue;
            }
            try {
                GeminiClient.postBatch(url, round(exchange), worker.get().workerId(), batch.getValue());
                delivered += batch.getValue().size();
            } catch (RuntimeException e) {
                failed.add(batch.getKey() + ": " + e.getMessage());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("worker", worker.get().workerId());
        out.put("mode", sparse ? "sparse" : "dense");
        out.put("batches", batches.size());
        out.put("delivered", delivered);
        out.put("failed", failed);
        out.put("activeEdges", worker.get().activeEdges());
        return out;
    }

    private Map<String, ?> messages(HttpExchange exchange) throws IOException {
        JsonNode body = MAPPER.readTree(read(exchange));
        int from = body.path("from").asInt();
        Map<Integer, Object> messages = readMessages(body.path("messages"));
        worker.get().receive(round(exchange), messages);
        return Map.of("received", messages.size(), "from", from);
    }

    private Map<String, ?> compute(HttpExchange exchange) throws IOException {
        boolean sparse = "sparse".equals(query(exchange).get("mode"));
        int activated = worker.get().compute(round(exchange), sparse);
        return Map.of("worker", worker.get().workerId(), "activated", activated,
                "mode", sparse ? "sparse" : "dense");
    }

    private Map<String, ?> values(HttpExchange exchange) throws IOException {
        return Map.of("worker", worker.get().workerId(), "values", worker.get().values());
    }

    private Map<String, ?> aggregate(HttpExchange exchange) throws IOException {
        return Map.of("worker", worker.get().workerId(), "aggregate", worker.get().aggregate());
    }

    private Map<String, ?> share(HttpExchange exchange) throws IOException {
        JsonNode body = MAPPER.readTree(read(exchange));
        Map<String, Object> shared = new LinkedHashMap<>();
        body.fields().forEachRemaining(entry -> shared.put(entry.getKey(),
                entry.getValue().isNumber() ? entry.getValue().numberValue() : entry.getValue().asText()));
        worker.get().share(shared);
        return Map.of("shared", shared.size());
    }

    // ------------------------------------------------------------------ plumbing

    private static final class GeminiWorkerHolder {
        private volatile GeminiWorker instance;

        GeminiWorker get() {
            GeminiWorker current = instance;
            if (current == null) {
                throw new IllegalStateException("This worker has no layout yet: POST /gemini/layout first");
            }
            return current;
        }

        GeminiWorker peek() {
            return instance;
        }

        Map<String, Object> status() {
            return instance == null
                    ? Map.of("loaded", false)
                    : new LinkedHashMap<>(instance.status());
        }

        void load(JsonNode body) {
            instance = GeminiWire.readWorker(body);
        }
    }

    private static Map<Integer, Object> readMessages(JsonNode node) {
        Map<Integer, Object> out = new LinkedHashMap<>();
        if (node.isArray()) {
            for (JsonNode message : node) {
                if (message.isArray() && message.size() == 2) {
                    out.put(message.get(0).asInt(), value(message.get(1)));
                }
            }
        }
        return out;
    }

    private static Object value(JsonNode node) {
        if (node.isObject()) {
            Map<Integer, Double> map = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> map.put(Integer.parseInt(entry.getKey()),
                    entry.getValue().doubleValue()));
            return map;
        }
        return node.isFloatingPointNumber() || node.isDouble() ? node.doubleValue() : node.asLong();
    }

    private static String read(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> out = new LinkedHashMap<>();
        String raw = exchange.getRequestURI().getQuery();
        if (raw == null) {
            return out;
        }
        for (String part : raw.split("&")) {
            int equals = part.indexOf('=');
            if (equals > 0) {
                out.put(part.substring(0, equals), part.substring(equals + 1));
            }
        }
        return out;
    }

    private static int round(HttpExchange exchange) {
        return Integer.parseInt(query(exchange).getOrDefault("round", "0"));
    }

    /** Runs a handler and answers: an error becomes a 500 with its message, never a dropped socket. */
    /** A handler body that may read the request (and so may throw IOException). */
    @FunctionalInterface
    private interface Handler {
        Map<String, ?> handle() throws IOException;
    }

    /** Runs a handler and answers: an error becomes a 500 with its message, never a dropped socket. */
    private static void answer(HttpExchange exchange, Handler handler) {
        try {
            respond(exchange, handler.handle());
        } catch (RuntimeException | IOException e) {
            try {
                respondError(exchange, e);
            } catch (IOException ignored) {
                // the client is gone; nothing else to do
            }
        }
    }

    private static void respondError(HttpExchange exchange, Exception error) throws IOException {
        // the first frames of the trace, so a failure on a worker says where it happened: a
        // distributed run gives no other clue about which side of a message went wrong
        StringBuilder trace = new StringBuilder();
        StackTraceElement[] frames = error.getStackTrace();
        for (int i = 0; i < Math.min(4, frames.length); i++) {
            trace.append(frames[i]).append('\n');
        }
        byte[] bytes = MAPPER.writeValueAsBytes(Map.of("error", String.valueOf(error.getMessage()),
                "exception", error.getClass().getName(), "where", trace.toString()));
        exchange.getResponseHeaders().add("content-type", "application/json");
        exchange.sendResponseHeaders(500, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void respond(HttpExchange exchange, Map<String, ?> body) throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("content-type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
