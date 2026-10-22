package com.pengcheng.phoenixgraph.agent;

import java.util.List;

/**
 * A final ordering step for the recall stage. The default is {@link #none()}: the hybrid score already
 * ranks by name, description terms and vector. A deployment with a cross-encoder (Cohere Rerank, a local
 * BGE reranker, a hosted search API) implements this interface and passes it to
 * {@link AgentQuery#AgentQuery(AgentQuery.Engine, AgentIndex, Embedding, String, Reranker)}; the hits it
 * receives already carry the candidate entities and their citations, and it returns at most {@code k}.
 */
@FunctionalInterface
public interface Reranker {

    List<AgentQuery.Bundle.Hit> rerank(String query, List<AgentQuery.Bundle.Hit> hits, int k);

    /** Keep the hybrid order. */
    static Reranker none() {
        return (query, hits, k) -> hits.stream().limit(Math.max(1, k)).toList();
    }
}
