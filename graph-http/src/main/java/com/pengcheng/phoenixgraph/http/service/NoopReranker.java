package com.pengcheng.phoenixgraph.http.service;

import com.pengcheng.phoenixgraph.agent.AgentQuery;
import com.pengcheng.phoenixgraph.agent.Reranker;
import java.util.List;

/**
 * The default {@link Reranker}: keep the hybrid order. {@link RerankerFactory} uses this when no
 * provider is configured; the tools construct their {@link AgentQuery} with whatever reranker the
 * factory returns.
 */
public class NoopReranker implements Reranker {

    @Override
    public List<AgentQuery.Bundle.Hit> rerank(String query, List<AgentQuery.Bundle.Hit> hits, int k) {
        return hits.stream().limit(Math.max(1, k)).toList();
    }
}
