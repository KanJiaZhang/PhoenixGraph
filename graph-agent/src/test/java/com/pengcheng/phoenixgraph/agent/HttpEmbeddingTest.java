package com.pengcheng.phoenixgraph.agent;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpEmbeddingTest {

    @Test
    void embedsThroughAnOpenAiCompatibleEndpoint() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> authorization = new AtomicReference<>();
        server.createContext("/v1/embeddings", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(request.contains("\"model\":\"test-model\""), request);
            assertTrue(request.contains("\"input\":\"hello\""), request);
            byte[] answer = "{\"data\":[{\"embedding\":[3,4]}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.start();
        try {
            HttpEmbedding embedding = new HttpEmbedding(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/embeddings"),
                    "test-model", 2, "secret", Duration.ofSeconds(5));
            float[] vector = embedding.embed("hello");
            assertEquals(2, vector.length);
            assertEquals(0.6f, vector[0], 0.0001f);
            assertEquals(0.8f, vector[1], 0.0001f);
            assertEquals("Bearer secret", authorization.get());
            assertEquals("http:test-model:2", embedding.name());
        } finally {
            server.stop(0);
        }
    }
}
