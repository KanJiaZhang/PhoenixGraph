package com.pengcheng.phoenixgraph.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Map;

class DedupSuggestionsTest {

    private static KnowledgeExtractor.Entity entity(String name) {
        return new KnowledgeExtractor.Entity(name, "Company", name, List.of(), 1);
    }

    @Test
    void prefixAndSpellingCandidatesAreReportedButNotMerged() {
        List<DedupSuggestions.Candidate> candidates = DedupSuggestions.find(List.of(
                entity("Acme"), entity("Acme Corporation"), entity("outage"), entity("outages")), 10);
        assertTrue(candidates.stream().anyMatch(candidate -> candidate.left().equals("Acme")
                && candidate.right().equals("Acme Corporation") && candidate.reason().contains("prefix")),
                candidates.toString());
        assertTrue(candidates.stream().anyMatch(candidate -> candidate.left().equals("outage")
                && candidate.right().equals("outages")), candidates.toString());
    }

    @Test
    void qualityReportCarriesCandidates() {
        KnowledgeExtractor.Options options = new KnowledgeExtractor.Options(
                Map.of("Acme", "Company", "Acme Corporation", "Company", "outage", "Incident"),
                Map.of(), RulePack.byName("incident"), true);
        KnowledgeExtractor.Result result = KnowledgeExtractor.extract(List.of(new Chunker.Document("d", "D",
                "Acme caused the outage." + System.lineSeparator() + "Acme Corporation caused the outage.")), options);
        Object candidates = QualityReport.of(result, options, List.of()).metrics().get("mergeCandidates");
        assertTrue(candidates instanceof List<?> list && !list.isEmpty(), String.valueOf(candidates));
    }

    @Test
    void unrelatedNamesAreNotCandidates() {
        List<DedupSuggestions.Candidate> candidates = DedupSuggestions.find(List.of(
                entity("Alice"), entity("Bob"), entity("Redis")), 10);
        assertEquals(0, candidates.size(), candidates.toString());
    }
}
