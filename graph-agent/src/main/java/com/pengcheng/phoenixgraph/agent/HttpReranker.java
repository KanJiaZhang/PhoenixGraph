package com.pengcheng.phoenixgraph.agent;

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
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A reranker over a Cohere/Jina/OpenAI-compatible {@code /rerank} endpoint.
 *
 * <p>The documents sent to the provider are the hit names and types (the artifact's description is not part
 * of {@link AgentQuery.Bundle.Hit}). That is enough to reorder obvious matches; a provider failure falls
 * back to the hybrid order rather than failing the answer.
 */
public final class HttpReranker implements Reranker {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final System.Logger LOG = System.getLogger(HttpReranker.class.getName());

    private final URI endpoint;
    private final String model;
    private final String apiKey;
    private final Duration timeout;
    private final HttpClient client;

    public HttpReranker(URI endpoint, String model, String apiKey, Duration timeout) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.model = model == null || model.isBlank() ? "rerank-v3.5" : model;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
        this.client = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    @Override
    public List<AgentQuery.Bundle.Hit> rerank(String query, List<AgentQuery.Bundle.Hit> hits, int k) {
        int limit = Math.max(1, k);
        if (hits.isEmpty()) {
            return List.of();
        }
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("model", model);
            body.put("query", query == null ? "" : query);
            body.put("top_n", limit);
            ArrayNode documents = body.putArray("documents");
            for (AgentQuery.Bundle.Hit hit : hits) {
                documents.add(hit.name() + " (" + hit.type() + ")");
            }
            HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body),
                            StandardCharsets.UTF_8));
            if (!apiKey.isBlank()) {
                request.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> response = client.send(request.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                LOG.log(System.Logger.Level.WARNING, "reranker answered HTTP {0}; keeping hybrid order",
                        response.statusCode());
                return hits.stream().limit(limit).toList();
            }
            JsonNode root = MAPPER.readTree(response.body());
            JsonNode results = root.has("results") ? root.get("results") : root.path("data");
            if (!results.isArray()) {
                return hits.stream().limit(limit).toList();
            }
            List<AgentQuery.Bundle.Hit> out = new ArrayList<>();
            Set<Integer> used = new HashSet<>();
            for (JsonNode result : results) {
                int index = result.path("index").asInt(-1);
                if (index >= 0 && index < hits.size() && used.add(index)) {
                    out.add(hits.get(index));
                }
                if (out.size() >= limit) break;
            }
            for (int i = 0; i < hits.size() && out.size() < limit; i += 1) {
                if (used.add(i)) {
                    out.add(hits.get(i));
                }
            }
            return List.copyOf(out);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot read the reranker answer; keeping hybrid order", e);
            return hits.stream().limit(limit).toList();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return hits.stream().limit(limit).toList();
        }
    }
}
