package com.pengcheng.phoenixgraph.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The extraction vocabulary is data: a business pack reads business prose without a new extractor. */
class RulePackTest {

    private static final Map<String, String> DICTIONARY = Map.ofEntries(
            Map.entry("payment-service", "Service"),
            Map.entry("checkout-api", "Service"),
            Map.entry("Redis", "Component"),
            Map.entry("Deploy 42", "Change"),
            Map.entry("Alice", "Person"),
            Map.entry("Bob", "Person"),
            Map.entry("bad config", "Issue"),
            Map.entry("cache leak", "Issue"),
            Map.entry("latency spike", "Issue"));

    @Test
    void theIncidentPackReadsBusinessRelations() {
        List<Chunker.Document> documents = List.of(new Chunker.Document("incident", "Incident", """
                The payment-service depends on Redis.
                Deploy 42 caused the checkout-api outage.
                A bad config caused Deploy 42.
                Alice fixed the cache leak.
                Bob reported the latency spike.
                """));

        KnowledgeExtractor.Result result = KnowledgeExtractor.extract(documents,
                new KnowledgeExtractor.Options(DICTIONARY, Map.of(), RulePack.byName("incident")));

        assertTrue(has(result, "Deploy 42", "caused", "checkout-api"), result.relations().toString());
        assertTrue(has(result, "bad config", "caused", "Deploy 42"), result.relations().toString());
        assertTrue(has(result, "payment-service", "depends_on", "Redis"), result.relations().toString());
        assertTrue(has(result, "Alice", "fixed", "cache leak"), result.relations().toString());
        assertTrue(has(result, "Bob", "reported", "latency spike"), result.relations().toString());
        assertEquals(1.0, QualityReport.of(result, new KnowledgeExtractor.Options(
                DICTIONARY, Map.of(), RulePack.byName("incident")), List.of()).metrics()
                .get("provenanceRate"));
    }

    @Test
    void anAliasIsCanonicalisedBeforeTheRelationIsRead() {
        KnowledgeExtractor.Result result = KnowledgeExtractor.extract(
                List.of(new Chunker.Document("d", "D", "玄德 caused the outage.")),
                new KnowledgeExtractor.Options(Map.of("刘备", "Person", "outage", "Incident"),
                        Map.of("玄德", "刘备"), RulePack.byName("incident")));
        assertTrue(has(result, "刘备", "caused", "outage"), result.relations().toString());
    }

    @Test
    void anUnknownPackSaysWhatIsAvailable() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> RulePack.byName("does-not-exist"));
        assertTrue(error.getMessage().contains("sanguo"));
        assertTrue(error.getMessage().contains("incident"));
    }

    private static boolean has(KnowledgeExtractor.Result result, String head, String relation, String tail) {
        return result.relations().stream().anyMatch(candidate -> candidate.head().equals(head)
                && candidate.relation().equals(relation) && candidate.tail().equals(tail));
    }
}
