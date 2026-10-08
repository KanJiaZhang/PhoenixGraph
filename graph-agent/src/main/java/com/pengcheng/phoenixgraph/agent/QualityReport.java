package com.pengcheng.phoenixgraph.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How good the knowledge that came out of a document set is.
 *
 * <p>Two families of numbers, and both are computed from the extraction itself rather than asserted:
 *
 * <ul>
 *   <li><b>accuracy</b> - is every claim actually supported by the document it names?
 *       {@code provenanceRate} re-reads each relation's sentence and checks that <em>both</em> of its ends
 *       occur in it (a relation whose sentence does not contain its own entities is a mis-read, and this
 *       catches exactly the failure mode that makes a graph untrustworthy). {@code typeAgreement} says how
 *       much of the typing came from the caller's dictionary instead of from a rule's slot;
 *       {@code precision}/{@code recall}/{@code f1} appear when the caller brings a gold set.</li>
 *   <li><b>coverage</b> - how much of the documents did it actually read? {@code chunkCoverage} is the share
 *       of chunks that produced at least one relation, {@code entityLinkRate} the share of entities that
 *       ended up connected to something, {@code dictionaryCoverage} the share of the supplied vocabulary
 *       the corpus really uses, and {@code goldRecall} (when there is a gold set) the share of the known
 *       facts that were found. A high accuracy with a low coverage means a cautious extractor; the opposite
 *       means one that guesses.</li>
 * </ul>
 *
 * <p>Deliberately no single "score": a knowledge base is fit for a purpose or it is not, and the numbers
 * that decide that are the pair above, per relation type.
 */
public record QualityReport(int documents, int chunks, int characters, int entities, int relations,
                            Set<String> relationTypes, Map<String, Integer> relationsByRule,
                            Map<String, Integer> relationsByType, double provenanceRate, double typeAgreement,
                            double entityLinkRate, int isolatedEntities, double chunkCoverage,
                            double dictionaryCoverage, double relationsPerThousandChars, int duplicatesRemoved,
                            Map<String, Double> gold, List<String> warnings) {

    /**
     * Computes the report for an extraction.
     *
     * @param extraction the extraction result
     * @param options    what the extractor was given (its dictionary is what {@code dictionaryCoverage}
     *                   and {@code typeAgreement} are relative to)
     * @param gold       a gold triple set as {@code head|relation|tail} (or {@code head|tail}); empty when
     *                   there is nothing to compare against
     */
    public static QualityReport of(KnowledgeExtractor.Result extraction, KnowledgeExtractor.Options options,
                                   List<String> gold) {
        List<Chunker.Chunk> chunks = extraction.chunks();
        int characters = chunks.stream().mapToInt(chunk -> chunk.text().length()).sum();

        // accuracy: the sentence a relation cites must contain both of its ends
        long supported = extraction.relations().stream().filter(QualityReport::supportedByItsSentence).count();
        double provenanceRate = extraction.relations().isEmpty() ? 0
                : (double) supported / extraction.relations().size();

        // typing: an entity the dictionary knew is curated, one a rule discovered is inferred
        Set<String> dictionary = options.dictionary().keySet();
        long curated = extraction.entities().stream().filter(entity -> dictionary.contains(entity.id())).count();
        long typed = extraction.entities().stream().filter(entity -> !"Mention".equals(entity.type())).count();
        double typeAgreement = extraction.entities().isEmpty() ? 0
                : (double) Math.max(curated, typed) / extraction.entities().size();

        Set<String> linked = new LinkedHashSet<>();
        for (KnowledgeExtractor.Relation relation : extraction.relations()) {
            linked.add(relation.head());
            linked.add(relation.tail());
        }
        int isolated = (int) extraction.entities().stream().filter(entity -> !linked.contains(entity.id())).count();
        double entityLinkRate = extraction.entities().isEmpty() ? 0
                : (double) (extraction.entities().size() - isolated) / extraction.entities().size();

        Set<String> coveredChunks = new LinkedHashSet<>();
        for (KnowledgeExtractor.Relation relation : extraction.relations()) {
            coveredChunks.add(relation.chunkId());
        }
        double chunkCoverage = chunks.isEmpty() ? 0 : (double) coveredChunks.size() / chunks.size();

        long used = dictionary.stream().filter(name -> extraction.entities().stream()
                .anyMatch(entity -> entity.id().equals(name))).count();
        double dictionaryCoverage = dictionary.isEmpty() ? 0 : (double) used / dictionary.size();

        double density = characters == 0 ? 0 : extraction.relations().size() * 1000.0 / characters;

        return new QualityReport(extraction.chunks().stream().map(Chunker.Chunk::documentId).distinct().toList().size(),
                chunks.size(), characters, extraction.entities().size(), extraction.relations().size(),
                new LinkedHashSet<>(extraction.relationsByType().keySet()), extraction.relationsByRule(),
                extraction.relationsByType(), provenanceRate, typeAgreement, entityLinkRate, isolated,
                chunkCoverage, dictionaryCoverage, density, 0, compare(gold, extraction), extraction.warnings());
    }

    /** A report with the duplicate count filled in (the writer knows how many it merged). */
    public QualityReport withDuplicatesRemoved(int duplicates) {
        return new QualityReport(documents, chunks, characters, entities, relations, relationTypes,
                relationsByRule, relationsByType, provenanceRate, typeAgreement, entityLinkRate, isolatedEntities,
                chunkCoverage, dictionaryCoverage, relationsPerThousandChars, duplicates, gold, warnings);
    }

    /** Whether the sentence a relation cites really contains both ends (re-read, not assumed). */
    private static boolean supportedByItsSentence(KnowledgeExtractor.Relation relation) {
        String sentence = relation.sentence();
        return sentence != null && relation.head() != null && relation.tail() != null
                && sentence.contains(relation.head()) && sentence.contains(relation.tail());
    }

    /**
     * Precision, recall and F1 against a gold set, on triples and on the looser (head, tail) pair - the
     * second is what tells an extractor that found the right facts but named the relation differently.
     */
    private static Map<String, Double> compare(List<String> gold, KnowledgeExtractor.Result extraction) {
        Map<String, Double> metrics = new LinkedHashMap<>();
        if (gold == null || gold.isEmpty()) {
            return Map.of();
        }
        Set<String> goldTriples = new LinkedHashSet<>();
        Set<String> goldPairs = new LinkedHashSet<>();
        for (String line : gold) {
            String cleaned = line.strip();
            if (cleaned.isEmpty()) {
                continue;
            }
            String[] parts = cleaned.split("\\s*[|,\\t]\\s*");
            goldTriples.add(String.join("|", parts));
            if (parts.length >= 2) {
                goldPairs.add(parts[0] + "|" + parts[parts.length - 1]);
            }
        }
        Set<String> foundTriples = new LinkedHashSet<>();
        Set<String> foundPairs = new LinkedHashSet<>();
        for (KnowledgeExtractor.Relation relation : extraction.relations()) {
            foundTriples.add(relation.head() + "|" + relation.relation() + "|" + relation.tail());
            foundPairs.add(relation.head() + "|" + relation.tail());
        }
        metrics.put("goldSize", (double) goldTriples.size());
        metrics.put("precision", ratio(hits(goldTriples, foundTriples), foundTriples.size()));
        metrics.put("recall", ratio(hits(goldTriples, foundTriples), goldTriples.size()));
        metrics.put("f1", f1(metrics.get("precision"), metrics.get("recall")));
        metrics.put("pairPrecision", ratio(hits(goldPairs, foundPairs), foundPairs.size()));
        metrics.put("pairRecall", ratio(hits(goldPairs, foundPairs), goldPairs.size()));
        metrics.put("pairF1", f1(metrics.get("pairPrecision"), metrics.get("pairRecall")));
        return metrics;
    }

    private static long hits(Set<String> gold, Set<String> found) {
        return found.stream().filter(gold::contains).count();
    }

    private static double ratio(long numerator, long denominator) {
        return denominator == 0 ? 0 : (double) numerator / denominator;
    }

    private static double f1(Double precision, Double recall) {
        if (precision == null || recall == null || precision + recall == 0) {
            return 0;
        }
        return 2 * precision * recall / (precision + recall);
    }

    /** One flat map for an HTTP answer or a report file: every number the run produced, named. */
    public Map<String, Object> metrics() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("documents", documents);
        out.put("chunks", chunks);
        out.put("characters", characters);
        out.put("entities", entities);
        out.put("relations", relations);
        out.put("relationTypes", new ArrayList<>(relationTypes));
        out.put("relationsByRule", relationsByRule);
        out.put("relationsByType", relationsByType);
        out.put("provenanceRate", round(provenanceRate));
        out.put("typeAgreement", round(typeAgreement));
        out.put("entityLinkRate", round(entityLinkRate));
        out.put("isolatedEntities", isolatedEntities);
        out.put("chunkCoverage", round(chunkCoverage));
        out.put("dictionaryCoverage", round(dictionaryCoverage));
        out.put("relationsPerThousandChars", round(relationsPerThousandChars));
        if (!gold.isEmpty()) {
            Map<String, Object> rounded = new LinkedHashMap<>();
            gold.forEach((key, value) -> rounded.put(key, round(value)));
            out.put("gold", rounded);
        }
        if (!warnings.isEmpty()) {
            out.put("warnings", warnings);
        }
        return out;
    }

    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
