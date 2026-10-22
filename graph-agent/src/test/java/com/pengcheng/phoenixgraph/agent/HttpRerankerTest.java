package com.pengcheng.phoenixgraph.agent;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpRerankerTest {

    @Test
    void theProviderOrderIsAppliedToTheHits() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> request = new AtomicReference<>();
        server.createContext("/rerank", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] answer = "{\"results\":[{\"index\":1,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.1}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.start();
        try {
            HttpReranker reranker = new HttpReranker(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/rerank"),
                    "test-model", "secret", Duration.ofSeconds(5));
            List<AgentQuery.Bundle.Hit> hits = List.of(
                    new AgentQuery.Bundle.Hit("a", "Person", "A", 0.8, "hybrid", List.of("cite")),
                    new AgentQuery.Bundle.Hit("b", "Person", "B", 0.7, "hybrid", List.of("cite")));

            List<AgentQuery.Bundle.Hit> reranked = reranker.rerank("question", hits, 2);

            assertEquals(List.of("b", "a"), reranked.stream().map(AgentQuery.Bundle.Hit::id).toList());
            assertEquals("Bearer secret", authorization.get());
            assertTrue(request.get().contains("\"top_n\":2"), request.get());
        } finally {
            server.stop(0);
        }
    }
}
