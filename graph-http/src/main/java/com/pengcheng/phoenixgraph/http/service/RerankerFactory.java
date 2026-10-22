package com.pengcheng.phoenixgraph.http.service;

import com.pengcheng.phoenixgraph.agent.HttpReranker;
import com.pengcheng.phoenixgraph.agent.Reranker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;

/**
 * Chooses the reranker: the hybrid order by default, or a Cohere/Jina/OpenAI-compatible {@code /rerank}
 * endpoint when {@code phoenixgraph.agent.reranker.provider=http}.
 */
@Component
public class RerankerFactory {

    private final String provider;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int timeoutSeconds;
    private volatile Reranker reranker;

    public RerankerFactory(
            @Value("${phoenixgraph.agent.reranker.provider:none}") String provider,
            @Value("${phoenixgraph.agent.reranker.base-url:https://api.cohere.com/v1}") String baseUrl,
            @Value("${phoenixgraph.agent.reranker.api-key:}") String apiKey,
            @Value("${phoenixgraph.agent.reranker.model:rerank-v3.5}") String model,
            @Value("${phoenixgraph.agent.reranker.timeout-seconds:10}") int timeoutSeconds) {
        this.provider = provider == null ? "none" : provider.trim();
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null || model.isBlank() ? "rerank-v3.5" : model.trim();
        this.timeoutSeconds = timeoutSeconds <= 0 ? 10 : timeoutSeconds;
    }

    public Reranker current() {
        Reranker local = reranker;
        if (local == null) {
            synchronized (this) {
                local = reranker;
                if (local == null) {
                    local = create();
                    reranker = local;
                }
            }
        }
        return local;
    }

    private Reranker create() {
        if (provider.equalsIgnoreCase("http") || provider.equalsIgnoreCase("cohere")) {
            return new HttpReranker(endpoint(), model, apiKey, Duration.ofSeconds(timeoutSeconds));
        }
        return new NoopReranker();
    }

    private URI endpoint() {
        String url = baseUrl.isBlank() ? "https://api.cohere.com/v1" : baseUrl;
        url = url.replaceAll("/+$", "");
        if (!url.endsWith("/rerank")) {
            url = url + "/rerank";
        }
        return URI.create(url);
    }
}
