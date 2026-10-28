package com.pengcheng.phoenixgraph.http.service;

import com.pengcheng.phoenixgraph.agent.AgentIndex;
import com.pengcheng.phoenixgraph.http.dto.BatchRequest;
import com.pengcheng.phoenixgraph.http.dto.EdgeRequest;
import com.pengcheng.phoenixgraph.http.dto.SchemaDraft;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ingestion writes edge properties by schema index. The index is assigned by the schema draft, so a
 * constant that drifts from the draft is a write that fails at runtime - which is exactly what happened
 * when the relation edge used 3..6 against a schema that declares 1..4. This test keeps the two in step
 * without needing a store, a registry or a running service.
 */
class AgentIngestServiceTest {

    @Test
    void everyEdgePropertyIndexTheBatchUsesIsDeclaredByTheSchemaDraft() {
        BatchRequest batch = AgentIngestService.toBatch(sampleIndex());
        GraphSchema schema = AgentIngestService.schemaDraft("test-graph", "test").toSchema(1);

        for (EdgeRequest edge : batch.edges()) {
            EdgeSchema edgeSchema = schema.edgeSchemas().stream()
                    .filter(candidate -> candidate.name().equals(edge.edgeSchemaName()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no edge schema " + edge.edgeSchemaName()));
            Set<Integer> declared = edgeSchema.properties().stream()
                    .map(property -> property.id())
                    .collect(Collectors.toSet());
            Set<Integer> used = edge.properties().stream()
                    .map(property -> property.index())
                    .collect(Collectors.toSet());
            assertEquals(declared, used,
                    edge.edgeSchemaName() + " writes " + used + " but declares " + declared);
        }
    }

    @Test
    void theRelationEdgeDeclaresSixPropertiesAndTheMentionEdgeOne() {
        GraphSchema schema = AgentIngestService.schemaDraft("test-graph", "test").toSchema(1);
        // relation, rule, sentence, chunkId and the validity window the time dimension added
        assertEquals(6, edge(schema, "Relates").properties().size());
        assertEquals(1, edge(schema, "Mentions").properties().size());
    }

    private static EdgeSchema edge(GraphSchema schema, String name) {
        return schema.edgeSchemas().stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no edge schema " + name));
    }

    @Test
    void anEntityReadInTwoDocumentsKeepsBothProvenanceLists() {
        AgentIndex previous = new AgentIndex("g", "hashing-64", 64, "before",
                List.of(new AgentIndex.Entity("关羽", "Entity", "Person", "关羽", "card",
                        new float[]{1f}, List.of("doc-a-1"), List.of("doc A §1"))),
                List.of(), List.of(), Map.of());
        AgentIndex current = new AgentIndex("g", "hashing-64", 64, "after",
                List.of(new AgentIndex.Entity("关羽", "Entity", "Person", "关羽", "card",
                        new float[]{2f}, List.of("doc-b-1"), List.of("doc B §1"))),
                List.of(), List.of(), Map.of());

        AgentIndex merged = AgentIngestService.mergeChunks(previous, current);
        assertEquals(List.of("doc-a-1", "doc-b-1"), merged.entities().get(0).chunkIds(),
                "the same entity keeps both documents' chunks instead of the second overwriting the first");
    }

    @Test
    void aMergeRewritesEveryEdgeFromDropToKeepAndMergesChunks() {
        List<AgentIngestService.EdgeView> edges = List.of(
                new AgentIngestService.EdgeView("Relates", "rel-1", "關羽", "颜良",
                        Map.of("relation", "killed", "sentence", "關羽斩杀颜良")),
                new AgentIngestService.EdgeView("Relates", "rel-2", "文丑", "關羽",
                        Map.of("relation", "fought", "sentence", "文丑与關羽交战")),
                new AgentIngestService.EdgeView("Mentions", "mention-c1-關羽", "c1", "關羽",
                        Map.of("sentence", "關羽斩杀颜良")));

        AgentIngestService.MergePlan plan = AgentIngestService.mergePlan("关羽", "關羽", edges,
                List.of("c1"), List.of("c2"));

        assertEquals(3, plan.writes().size(), plan.toString());
        assertEquals(List.of("c1", "c2"), plan.chunkIds());
        // an OUT edge keeps its source; only the endpoint that was drop moves to keep
        assertEquals("关羽", plan.writes().get(0).source());
        assertEquals("颜良", plan.writes().get(0).target());
        // an IN edge keeps its source too, and the target moves to keep
        assertEquals("文丑", plan.writes().get(1).source());
        assertEquals("关羽", plan.writes().get(1).target());
        // a mention edge is chunk -> entity, so the entity end moves to keep
        assertEquals("c1", plan.writes().get(2).source());
        assertEquals("关羽", plan.writes().get(2).target());
    }

    @Test
    void aMergeFoldsDropIntoKeepInTheIndex() {
        AgentIndex index = new AgentIndex("g", "hashing-64", 64, "now",
                List.of(
                        new AgentIndex.Entity("关羽", "Entity", "Person", "关羽", "card",
                                new float[] {1f}, List.of("c1"), List.of("d1 §1")),
                        new AgentIndex.Entity("關羽", "Entity", "Person", "關羽", "card",
                                new float[] {2f}, List.of("c2"), List.of("d2 §1"))),
                List.of(new AgentIndex.Relation("關羽", "killed", "颜良", "killed", "c2", "d2 §1", "關羽斩杀颜良")),
                List.of(), Map.of());

        AgentIndex merged = AgentIngestService.mergeIndex(index, "关羽", "關羽");

        assertEquals(1, merged.entities().size(), merged.entities().toString());
        assertEquals(List.of("c1", "c2"), merged.entities().get(0).chunkIds());
        assertTrue(merged.entities().get(0).aliases().contains("關羽"), merged.entities().get(0).toString());
        assertEquals("关羽", merged.relations().get(0).head());
        assertEquals("颜良", merged.relations().get(0).tail());
    }

    @Test
    void relationEdgeIdsAreAFunctionOfTheTripleSoReingestingIsIdempotent() {
        // The same triple in two ingestions has to produce one edge, not two. `rel-<n>` made the id a function of
        // the position in the document, so a second run wrote a fresh set of edges that collided with the first
        // run's different triples - the graph kept both readings of the same corpus.
        String id = AgentIngestService.relationEdgeId("关羽", "killed", "颜良");
        assertEquals(id, AgentIngestService.relationEdgeId("关羽", "killed", "颜良"));
        assertTrue(id.startsWith("rel-"), id);
        assertTrue(!id.equals(AgentIngestService.relationEdgeId("颜良", "killed", "关羽")), "direction is part of the identity");
        assertTrue(!id.equals(AgentIngestService.relationEdgeId("关羽", "fought", "颜良")), "the relation is part of it too");
        // Names may contain any character, so the id cannot be a concatenation that a separator could merge
        assertTrue(!AgentIngestService.relationEdgeId("a-b", "c", "d")
                .equals(AgentIngestService.relationEdgeId("a", "b-c", "d")), "a dash inside a name cannot collide");
    }

    private static AgentIndex sampleIndex() {
        return new AgentIndex(
                "test-graph", "hashing-64", 64, "now",
                List.of(new AgentIndex.Entity("关羽", "Entity", "Person", "关羽", "card",
                        new float[]{1f, 0f}, List.of("chunk-1"), List.of("doc §1"), List.of(),
                        1_700_000_000_000L, 1_800_000_000_000L)),
                List.of(new AgentIndex.Relation("关羽", "killed", "颜良", "killed", "chunk-1",
                        "doc §1", "关羽斩杀颜良", 1_700_000_000_000L, 1_800_000_000_000L)),
                List.of(new AgentIndex.Chunk("chunk-1", "doc", "title", 0, "关羽斩杀颜良")),
                Map.of());
    }
}
