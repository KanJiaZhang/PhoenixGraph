package com.pengcheng.phoenixgraph.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * An OpenAI-compatible embeddings endpoint ({@code POST <base>/embeddings}).
 *
 * <p>The artifact records {@link #name()} - {@code http:<model>:<dimensions>} - so a graph ingested with
 * one model is queried with the same model. A provider failure is not hidden behind the hashing fallback:
 * mixing two vector spaces would produce plausible-looking garbage, which is worse than a clear error.
 */
public final class HttpEmbedding implements Embedding {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final URI endpoint;
    private final String model;
    private final int dimensions;
    private final String apiKey;
    private final Duration timeout;
    private final HttpClient client;

    public HttpEmbedding(URI endpoint, String model, int dimensions, String apiKey, Duration timeout) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.model = model == null || model.isBlank() ? "text-embedding-3-small" : model;
        if (dimensions <= 0) {
            throw new IllegalArgumentException("An embedding needs a positive dimension count");
        }
        this.dimensions = dimensions;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        this.client = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public String name() {
        return "http:" + model + ":" + dimensions;
    }

    @Override
    public float[] embed(String text) {
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("model", model);
            body.put("input", text == null ? "" : text);
            HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body), StandardCharsets.UTF_8));
            if (!apiKey.isBlank()) {
                request.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> response = client.send(request.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Embedding provider answered HTTP " + response.statusCode()
                        + ": " + clip(response.body()));
            }
            JsonNode data = MAPPER.readTree(response.body()).path("data");
            if (!data.isArray() || data.isEmpty()) {
                throw new IllegalStateException("Embedding provider returned no data: " + clip(response.body()));
            }
            JsonNode vectorNode = data.get(0).path("embedding");
            if (!vectorNode.isArray() || vectorNode.isEmpty()) {
                throw new IllegalStateException("Embedding provider returned no embedding: " + clip(response.body()));
            }
            if (vectorNode.size() != dimensions) {
                throw new IllegalStateException("Embedding provider returned " + vectorNode.size()
                        + " dimensions, the graph expects " + dimensions);
            }
            float[] vector = new float[dimensions];
            double squares = 0;
            for (int i = 0; i < dimensions; i++) {
                float value = (float) vectorNode.get(i).asDouble();
                vector[i] = value;
                squares += value * value;
            }
            double norm = Math.sqrt(squares);
            if (norm > 0) {
                for (int i = 0; i < dimensions; i++) {
                    vector[i] /= (float) norm;
                }
            }
            return vector;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the embedding provider answer", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling the embedding provider", e);
        }
    }

    private static String clip(String text) {
        if (text == null) {
            return "";
        }
        String value = text.replaceAll("\\s+", " ").trim();
        return value.length() > 200 ? value.substring(0, 199) + "…" : value;
    }
}
