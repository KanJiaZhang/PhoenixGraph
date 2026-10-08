package com.pengcheng.phoenixgraph.agent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The toolkit's three promises, each checked on a document small enough to read by hand: the extraction
 * finds the facts the document states (and cites the sentence), the quality report's numbers are what it
 * says they are, and the tools answer in the one shape an agent consumes.
 */
class AgentToolkitTest {

    private static final String DOCUMENT = """
            关羽字云长，是刘备的义弟，也是刘备的臣。
            张飞是刘备的义弟。刘备的军师是诸葛亮。
            关羽斩杀颜良，又与文丑交战。
            张飞驻守阆中。
            """;

    private static KnowledgeExtractor.Options options() {
        Map<String, String> dictionary = new LinkedHashMap<>();
        dictionary.put("关羽", "Person");
        dictionary.put("刘备", "Person");
        dictionary.put("张飞", "Person");
        dictionary.put("诸葛亮", "Person");
        dictionary.put("颜良", "Person");
        dictionary.put("文丑", "Person");
        dictionary.put("阆中", "Place");
        return new KnowledgeExtractor.Options(dictionary, Map.of());
    }

    private static KnowledgeExtractor.Result extraction() {
        return KnowledgeExtractor.extract(
                List.of(new Chunker.Document("sg-1", "三国志·关张马黄赵传", DOCUMENT)), options());
    }

    @Test
    void extractionFindsTheFactsAndCitesTheirSentence() {
        KnowledgeExtractor.Result result = extraction();

        assertTrue(result.entityIds().containsAll(List.of("关羽", "刘备", "张飞", "诸葛亮", "颜良", "文丑", "阆中")),
                "every known name is an entity: " + result.entityIds());
        assertTrue(relation(result, "关羽", "minister_of", "刘备"), "是…的臣 reads as minister_of");
        assertTrue(relation(result, "张飞", "sibling_of", "刘备"), "是…的义弟 reads as sibling_of");
        assertTrue(relation(result, "诸葛亮", "strategist_of", "刘备"), "刘备的军师是诸葛亮 reads the other way round");
        assertTrue(relation(result, "关羽", "killed", "颜良"), "斩杀 reads as killed");
        assertTrue(relation(result, "关羽", "fought", "文丑"), "与…交战 reads as fought");
        for (KnowledgeExtractor.Relation relation : result.relations()) {
            assertTrue(relation.sentence().contains(relation.head()) && relation.sentence().contains(relation.tail()),
                    "every relation cites a sentence that contains both of its ends: " + relation);
            assertFalse(relation.chunkId().isBlank(), "and names the chunk it came from");
        }
    }

    @Test
    void theWindowRuleAddsNamesTheDictionaryDidNotHave() {
        // 陈到 is in no dictionary entry; 投奔 opens a slot the rule types itself
        KnowledgeExtractor.Result result = KnowledgeExtractor.extract(
                List.of(new Chunker.Document("sg-2", null, "陈到投奔刘备，后来镇守永安。")),
                KnowledgeExtractor.Options.of(Map.of("刘备", "Person")));

        assertTrue(result.entityIds().contains("陈到"), "the name after the verb became an entity: " + result.entityIds());
        assertEquals("Person", result.entities().stream().filter(e -> e.id().equals("陈到")).findFirst().orElseThrow().type(),
                "typed from the rule's slot");
        assertTrue(relation(result, "陈到", "serves", "刘备"), "投奔 reads as serves");
    }

    @Test
    void theQualityReportMeasuresAccuracyAndCoverage() {
        KnowledgeExtractor.Result result = extraction();
        QualityReport report = QualityReport.of(result, options(),
                List.of("关羽|minister_of|刘备", "关羽|killed|颜良", "张飞|serves|曹操"));

        Map<String, Object> metrics = report.metrics();
        assertEquals(1, metrics.get("documents"));
        assertEquals(result.entities().size(), metrics.get("entities"));
        assertEquals(1.0, metrics.get("provenanceRate"), "every relation is backed by its own sentence");
        assertEquals(1.0, metrics.get("dictionaryCoverage"), "every dictionary name occurs in the document");
        assertEquals(1.0, metrics.get("entityLinkRate"), "every entity ends up in a relation: " + metrics);

        // The gold set is deliberately partial: it lists two facts the document states - both found - and
        // one it does not (张飞 serves 曹操), plus the document states facts the gold set never mentions.
        // Precision and recall are therefore both below one, which is what those two numbers are for.
        Map<?, ?> gold = (Map<?, ?>) metrics.get("gold");
        assertEquals(0.2857, (double) gold.get("precision"), 0.001, "two of the seven relations are in the gold set");
        assertEquals(0.6667, (double) gold.get("recall"), 0.001, "two of the three gold facts found");
        assertEquals(0.4, (double) gold.get("f1"), 0.001);
    }

    @Test
    void theEmbeddingIsDeterministicAndUnitLength() {
        Embedding embedding = Embedding.hashing(64);
        float[] once = embedding.embed("关羽是刘备的臣");
        float[] again = embedding.embed("关羽是刘备的臣");

        assertEquals(64, once.length);
        assertTrue(Math.abs(Embedding.cosine(once, again) - 1.0) < 1e-6, "the same text gives the same vector");
        assertTrue(Embedding.cosine(once, embedding.embed("关羽是刘备的臣")) > Embedding.cosine(once,
                embedding.embed("今天天气很好")), "a related text is closer than an unrelated one");
    }

    @Test
    void theToolsAnswerInTheOneShapeAndReportTheirCoverage() {
        AgentIndex index = index();
        // the engine is stubbed: what the tools add is the assembly (scores, relations, citations, coverage)
        AgentQuery.Engine engine = gremlin -> {
            List<Object> rows = new ArrayList<>();
            if (gremlin.contains("nearest")) {
                rows.add(Map.of("id", "颜良", "label", "Person", "name", "颜良"));
            } else if (gremlin.contains("bothE") || gremlin.contains("repeat")) {
                rows.add(Map.of("id", "颜良", "label", "Person", "name", "颜良"));
                rows.add(Map.of("id", "文丑", "label", "Person", "name", "文丑"));
            }
            return rows;
        };
        AgentQuery tools = new AgentQuery(engine, index);

        AgentQuery.Bundle found = tools.findEntities("关羽斩杀了谁", 3);
        assertEquals("find_entities", found.tool());
        assertEquals("关羽", found.entities().get(0).id(), "the question names it, so it comes first");
        assertEquals("name", found.entities().get(0).via());
        assertFalse(found.citations().isEmpty(), "an answer carries the sentences it came from");
        assertTrue((int) found.coverage().get("candidates") >= 1);

        AgentQuery.Bundle related = tools.related("关羽", null, 1);
        assertEquals(3, related.entities().size(), "the entity asked about, plus the two the stub engine named");
        assertEquals(2, related.coverage().get("neighbours"));
        assertTrue(related.relations().stream().anyMatch(edge -> edge.relation().equals("killed")),
                "the relations between the found entities come from the index, with provenance: " + related.relations());
        // the stub index has two relations among those three entities (killed, fought) - the tools report
        // the count so an agent can tell a two-fact answer from a twenty-fact one
        assertEquals(2, ((Number) related.coverage().get("relations")).intValue());

        AgentQuery.Bundle context = tools.context("关羽和刘备是什么关系", 3, 1);
        assertTrue(context.entities().size() >= 2, "the context holds the seeds and what sits around them");
        assertEquals(context.entities().size(), ((Number) context.coverage().get("entities")).intValue());

        assertEquals(7, tools.tools().size(), "the catalog describes every tool");
        assertTrue(AgentToolCatalog.describe().containsKey("answerShape"));
    }

    /** A small index built the way an ingestion builds one. */
    private static AgentIndex index() {
        Embedding embedding = Embedding.hashing(64);
        List<AgentIndex.Entity> entities = new ArrayList<>();
        for (String name : List.of("关羽", "刘备", "张飞", "诸葛亮", "颜良", "文丑")) {
            List<String> cites = List.of("三国志·关张马黄赵传 §1");
            entities.add(new AgentIndex.Entity(name, "Entity", "Person", name, name + "，三国人物。",
                    embedding.embed(name), List.of("sg-1#0"), cites));
        }
        List<AgentIndex.Relation> relations = List.of(
                new AgentIndex.Relation("关羽", "ministration_of", "刘备", "is-of", "sg-1#0",
                        "三国志·关张马黄赵传 §1", "关羽是刘备的臣。"),
                new AgentIndex.Relation("关羽", "killed", "颜良", "killed", "sg-1#2",
                        "三国志·关张马黄赵传 §3", "关羽斩杀颜良，又与文丑交战。"),
                new AgentIndex.Relation("关羽", "fought", "文丑", "fought", "sg-1#2",
                        "三国志·关张马黄赵传 §3", "关羽斩杀颜良，又与文丑交战。"));
        List<AgentIndex.Chunk> chunks = List.of(new AgentIndex.Chunk("sg-1#0", "sg-1", "三国志·关张马黄赵传", 0,
                "关羽字云长，是刘备的义弟，也是刘备的臣。"));
        return new AgentIndex("unit-test", "hashing-64", 64, "2026-10-08T00:00:00Z", entities, relations, chunks,
                Map.of("provenanceRate", 1.0));
    }

    private static boolean relation(KnowledgeExtractor.Result result, String head, String relation, String tail) {
        return result.relations().stream().anyMatch(found -> found.head().equals(head)
                && found.relation().equals(relation) && found.tail().equals(tail));
    }
}
