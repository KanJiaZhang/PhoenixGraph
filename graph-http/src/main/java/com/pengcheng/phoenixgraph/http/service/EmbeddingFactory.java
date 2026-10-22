package com.pengcheng.phoenixgraph.http.service;

import com.pengcheng.phoenixgraph.agent.Embedding;
import com.pengcheng.phoenixgraph.agent.HttpEmbedding;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chooses the embedder: the deterministic hashing baseline by default, an OpenAI-compatible endpoint when
 * {@code phoenixgraph.agent.embedding.provider=http}.
 *
 * <p>The artifact records the embedder's name, and {@link #forIndex(String)} rebuilds that exact vector
 * space at query time. A graph ingested with a model is therefore queried with the same model, even if the
 * service configuration changed in between; a name the service does not know falls back to the configured
 * embedder, which is what the pre-artifact-method graphs need.
 */
@Component
public class EmbeddingFactory {

    private final String provider;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int dimensions;
    private final int timeoutSeconds;
    private final Map<String, Embedding> byName = new ConcurrentHashMap<>();
    private volatile Embedding current;

    public EmbeddingFactory(
            @Value("${phoenixgraph.agent.embedding.provider:hashing}") String provider,
            @Value("${phoenixgraph.agent.embedding.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${phoenixgraph.agent.embedding.api-key:}") String apiKey,
            @Value("${phoenixgraph.agent.embedding.model:text-embedding-3-small}") String model,
            @Value("${phoenixgraph.agent.embedding.dimensions:768}") int dimensions,
            @Value("${phoenixgraph.agent.embedding.timeout-seconds:30}") int timeoutSeconds) {
        this.provider = provider == null ? "hashing" : provider.trim();
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null || model.isBlank() ? "text-embedding-3-small" : model.trim();
        this.dimensions = dimensions <= 0 ? 768 : dimensions;
        this.timeoutSeconds = timeoutSeconds <= 0 ? 30 : timeoutSeconds;
    }

    /** The embedder new ingestions use. */
    public Embedding current() {
        Embedding local = current;
        if (local == null) {
            synchronized (this) {
                local = current;
                if (local == null) {
                    local = createDefault();
                    current = local;
                }
            }
        }
        return local;
    }

    /** The embedder an artifact names; {@code null}/blank means "the configured one". */
    public Embedding forIndex(String descriptor) {
        if (descriptor == null || descriptor.isBlank()) {
            return current();
        }
        return byName.computeIfAbsent(descriptor, this::create);
    }

    private Embedding createDefault() {
        if (provider.equalsIgnoreCase("http") || provider.equalsIgnoreCase("openai")) {
            return http(model, dimensions);
        }
        return Embedding.hashing(dimensions);
    }

    private Embedding create(String descriptor) {
        if (descriptor.startsWith("hashing-")) {
            try {
                return Embedding.hashing(Integer.parseInt(descriptor.substring("hashing-".length())));
            } catch (NumberFormatException e) {
                return current();
            }
        }
        if (descriptor.startsWith("http:")) {
            String rest = descriptor.substring("http:".length());
            int at = rest.lastIndexOf(':');
            if (at > 0) {
                try {
                    return http(rest.substring(0, at), Integer.parseInt(rest.substring(at + 1)));
                } catch (NumberFormatException e) {
                    return current();
                }
            }
        }
        return current();
    }

    private Embedding http(String modelName, int dimensions) {
        return new HttpEmbedding(endpoint(), modelName, dimensions, apiKey, Duration.ofSeconds(timeoutSeconds));
    }

    private URI endpoint() {
        String url = baseUrl.isBlank() ? "https://api.openai.com/v1" : baseUrl;
        url = url.replaceAll("/+$", "");
        if (!url.endsWith("/embeddings")) {
            url = url + "/embeddings";
        }
        return URI.create(url);
    }
}
