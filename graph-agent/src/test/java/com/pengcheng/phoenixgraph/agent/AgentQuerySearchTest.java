package com.pengcheng.phoenixgraph.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Hybrid recall must not turn a two-character slice of an ASCII name into a name hit. */
class AgentQuerySearchTest {

    @Test
    void aPartialAsciiNameIsNotANameHit() {
        Embedding embedding = Embedding.hashing(64);
        AgentQuery query = new AgentQuery(gremlin -> List.of(), index(embedding), embedding);

        AgentQuery.Bundle result = query.search("why did the checkout-api go out", 2);

        assertEquals("checkout-api", result.entities().get(0).id());
        assertTrue(result.entities().stream().noneMatch(hit -> hit.id().equals("old cache")
                && hit.via().equals("name")), result.entities().toString());
    }

    @Test
    void aRerankerCanReorderTheHits() {
        Embedding embedding = Embedding.hashing(64);
        Reranker reverse = (query, hits, k) -> hits.reversed().stream().limit(k).toList();
        AgentQuery normal = new AgentQuery(gremlin -> List.of(), index(embedding), embedding);
        AgentQuery reranked = new AgentQuery(gremlin -> List.of(), index(embedding), embedding, "Relates", reverse);

        AgentQuery.Bundle first = normal.search("checkout-api", 2);
        AgentQuery.Bundle second = reranked.search("checkout-api", 2);

        assertEquals(first.entities().get(0).id(), second.entities().get(1).id());
        assertEquals(first.entities().get(1).id(), second.entities().get(0).id());
    }

    private static AgentIndex index(Embedding embedding) {
        return new AgentIndex("test", "hashing-64", 64, "now",
                List.of(
                        new AgentIndex.Entity("checkout-api", "Entity", "Service", "checkout-api",
                                "checkout-api Service", embedding.embed("checkout-api Service"),
                                List.of("chunk-1"), List.of("doc §1")),
                        new AgentIndex.Entity("old cache", "Entity", "Component", "old cache",
                                "old cache Component", embedding.embed("old cache Component"),
                                List.of("chunk-1"), List.of("doc §1"))),
                List.of(),
                List.of(new AgentIndex.Chunk("chunk-1", "doc", "Incident", 0,
                        "checkout-api depends on old cache")),
                Map.of());
    }
}
