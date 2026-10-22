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
    void theRelationEdgeDeclaresFourPropertiesAndTheMentionEdgeOne() {
        GraphSchema schema = AgentIngestService.schemaDraft("test-graph", "test").toSchema(1);
        assertEquals(4, edge(schema, "Relates").properties().size());
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

    private static AgentIndex sampleIndex() {
        return new AgentIndex(
                "test-graph", "hashing-64", 64, "now",
                List.of(new AgentIndex.Entity("关羽", "Entity", "Person", "关羽", "card",
                        new float[]{1f, 0f}, List.of("chunk-1"), List.of("doc §1"))),
                List.of(new AgentIndex.Relation("关羽", "killed", "颜良", "killed", "chunk-1",
                        "doc §1", "关羽斩杀颜良")),
                List.of(new AgentIndex.Chunk("chunk-1", "doc", "title", 0, "关羽斩杀颜良")),
                Map.of());
    }
}
