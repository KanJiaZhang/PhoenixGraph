package com.pengcheng.phoenixgraph.http.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pengcheng.phoenixgraph.http.dto.BatchRequest;
import com.pengcheng.phoenixgraph.http.dto.EdgeRequest;
import com.pengcheng.phoenixgraph.http.dto.GraphInfo;
import com.pengcheng.phoenixgraph.http.dto.VertexPropertyRequest;
import com.pengcheng.phoenixgraph.http.dto.VertexRequest;
import com.pengcheng.phoenixgraph.schema.EdgePropertySchema;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.PropertyConstraints;
import com.pengcheng.phoenixgraph.schema.PropertyType;
import com.pengcheng.phoenixgraph.schema.VertexPropertySchema;
import com.pengcheng.phoenixgraph.schema.VertexSchema;
import com.pengcheng.phoenixgraph.storage.WriteOutcome;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import com.pengcheng.phoenixgraph.storage.aerospike.EdgeUpsert;
import com.pengcheng.phoenixgraph.storage.aerospike.VertexUpsert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GraphServiceTest {

    private final AerospikeVertexStore vertexStore = mock(AerospikeVertexStore.class);
    private final AerospikeEdgeStore edgeStore = mock(AerospikeEdgeStore.class);
    private final GraphService service = new GraphService(vertexStore, edgeStore, schema(), new ObjectMapper());

    @Test
    void processBatchMapsInlineVerticesAndEdges() {
        when(vertexStore.upsertVertices(anyList())).thenReturn(List.of(WriteOutcome.SUCCESS));
        when(edgeStore.upsertEdges(anyList())).thenReturn(List.of(WriteOutcome.SUCCESS));

        BatchRequest request = new BatchRequest(
                List.of(new VertexRequest("v1", "Account", List.of(new VertexPropertyRequest(1, "alice", 100L)), 50L, 100L)),
                List.of(new EdgeRequest("e1", "Payment", "v1", "v2", List.of(), 100L, 100L)),
                null);

        var results = service.processBatch(request);

        assertEquals(2, results.size());
        assertEquals("v1", results.get(0).id());
        assertEquals("SUCCESS", results.get(0).status());
        assertEquals("e1", results.get(1).id());
        assertEquals("SUCCESS", results.get(1).status());
    }

    @Test
    void processBatchLoadsFromFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("batch.json");
        String json = """
                {
                  "vertices": [
                    { "id": "v9", "vertexSchemaName": "Account",
                      "properties": [ { "index": 1, "value": "bob", "lastModifiedTime": 100 } ],
                      "createdTime": 100, "lastUpdatedTime": 100 }
                  ],
                  "edges": []
                }
                """;
        Files.writeString(file, json);
        when(vertexStore.upsertVertices(anyList())).thenReturn(List.of(WriteOutcome.SUCCESS));

        var results = service.processBatch(new BatchRequest(null, null, file.toString()));

        assertEquals(1, results.size());
        assertEquals("v9", results.get(0).id());
        assertEquals("SUCCESS", results.get(0).status());
    }

    @Test
    void mapsEdgeSchemaByName() {
        EdgeUpsert upsert = service.toEdgeUpsert(
                new EdgeRequest("e1", "Payment", "a", "b", List.of(), 1L, 2L));

        assertEquals("e1", upsert.edgeId());
        assertEquals("Payment", upsert.edgeSchema().name());
        assertEquals(1, upsert.edgeSchema().id());
    }

    @Test
    void graphInfoSummarizesVertexAndEdgeTypes() {
        GraphInfo info = GraphInfo.of(schema());

        assertEquals("payment-graph", info.name());
        assertEquals(List.of("Account"), info.vertexTypes());
        assertEquals(List.of("Payment"), info.edgeTypes());
    }

    @Test
    void rejectsUnknownEdgeSchema() {
        assertThrows(IllegalArgumentException.class,
                () -> service.toEdgeUpsert(new EdgeRequest("e1", "Missing", "a", "b", List.of(), 1L, 2L)));
    }

    @Test
    void mapsVertexProperties() {
        VertexUpsert upsert = service.toVertexUpsert(
                new VertexRequest("v1", "Account", List.of(new VertexPropertyRequest(2, "alice", 123L)), 10L, 20L));

        assertEquals("v1", upsert.vertexId());
        assertEquals("Account", upsert.vertexSchema().name());
        assertEquals("alice", upsert.properties().get(2).value());
        assertEquals(123L, upsert.properties().get(2).lastModifiedTime());
    }

    @Test
    void rejectsPropertyValueViolatingConstraints() {
        VertexSchema account = new VertexSchema(1, "Account", null)
                .property(new VertexPropertySchema(1, "age", "Age", PropertyType.ScalarType.INT,
                        PropertyConstraints.range(0, 150)));
        GraphSchema constrained = new GraphSchema(1, "g", null).vertex(account);
        GraphService constrainedService = new GraphService(vertexStore, edgeStore, constrained, new ObjectMapper());

        assertThrows(IllegalArgumentException.class,
                () -> constrainedService.toVertexUpsert(new VertexRequest("v1", "Account",
                        List.of(new VertexPropertyRequest(1, 200, 100L)), 50L, 100L)));
    }

    private static GraphSchema schema() {
        VertexSchema account = new VertexSchema(1, "Account", "An account");
        EdgeSchema payment = new EdgeSchema(1, "Payment", "A payment",
                new EdgePropertySchema(1, "source", "source vertex", PropertyType.ScalarType.LONG),
                new EdgePropertySchema(2, "target", "target vertex", PropertyType.ScalarType.LONG));
        return new GraphSchema(1, "payment-graph", null).vertex(account).edge(payment);
    }
}
