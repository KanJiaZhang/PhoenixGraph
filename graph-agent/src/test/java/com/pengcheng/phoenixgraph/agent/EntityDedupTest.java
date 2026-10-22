package com.pengcheng.phoenixgraph.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Case, whitespace and punctuation are not identity; the sentence is still the evidence. */
class EntityDedupTest {

    private static final Map<String, String> DICTIONARY = Map.of(
            "Acme Corp", "Company",
            "ACME CORP", "Company",
            "Acme Corp.", "Company",
            "outage", "Incident");

    private static final List<Chunker.Document> DOCUMENTS = List.of(new Chunker.Document("d", "D", """
            ACME CORP caused the outage.
            Acme Corp. caused the outage.
            Acme Corp caused the outage.
            """));

    @Test
    void variantsAreMergedAndTheirSentencesStillSupportTheRelations() {
        KnowledgeExtractor.Options options = new KnowledgeExtractor.Options(DICTIONARY, Map.of(),
                RulePack.byName("incident"), true);
        KnowledgeExtractor.Result result = KnowledgeExtractor.extract(DOCUMENTS, options);

        assertEquals(2, result.entities().size(), result.entities().toString());
        assertEquals(2, result.duplicatesRemoved());
        KnowledgeExtractor.Entity company = result.entities().stream()
                .filter(entity -> entity.name().startsWith("Acme") || entity.name().startsWith("ACME"))
                .findFirst().orElseThrow();
        assertEquals(2, company.aliases().size(), company.toString());
        assertTrue(result.relations().stream().allMatch(relation -> relation.head().equals(company.id())),
                result.relations().toString());
        assertEquals(1.0, QualityReport.of(result, options, List.of()).metrics().get("provenanceRate"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> merged = (List<Map<String, Object>>) QualityReport.of(result, options, List.of())
                .metrics().get("mergedEntities");
        assertEquals(1, merged.size(), String.valueOf(merged));
        assertEquals(company.id(), merged.get(0).get("canonical"));

        @SuppressWarnings("unchecked")
        Map<String, Double> gold = (Map<String, Double>) QualityReport.of(result, options,
                List.of("ACME CORP|caused|outage")).metrics().get("gold");
        assertEquals(1.0, gold.get("precision"), 1e-9, gold.toString());
        assertEquals(1.0, gold.get("recall"), 1e-9, gold.toString());
    }

    @Test
    void aliasesAreSearchableInTheArtifact() {
        AgentIndex index = new AgentIndex("g", "hashing-64", 64, "now",
                List.of(new AgentIndex.Entity("Acme Corp.", "Entity", "Company", "Acme Corp.", "",
                        new float[] {1.0f}, List.of(), List.of(), List.of("ACME CORP"))),
                List.of(), List.of(), Map.of());
        assertEquals("Acme Corp.", index.match("ACME CORP").get(0).id());
    }

    @Test
    void dedupeCanBeTurnedOff() {
        KnowledgeExtractor.Options options = new KnowledgeExtractor.Options(DICTIONARY, Map.of(),
                RulePack.byName("incident"), false);
        KnowledgeExtractor.Result result = KnowledgeExtractor.extract(DOCUMENTS, options);

        assertEquals(4, result.entities().size(), result.entities().toString());
        assertEquals(0, result.duplicatesRemoved());
    }
}
