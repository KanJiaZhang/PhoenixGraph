package com.pengcheng.phoenixgraph.http.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pengcheng.phoenixgraph.http.ElementNotFoundException;
import com.pengcheng.phoenixgraph.http.dto.BatchRequest;
import com.pengcheng.phoenixgraph.http.dto.EdgeRequest;
import com.pengcheng.phoenixgraph.http.dto.GraphInfo;
import com.pengcheng.phoenixgraph.http.dto.PropertyUpdateRequest;
import com.pengcheng.phoenixgraph.http.dto.VertexPropertyRequest;
import com.pengcheng.phoenixgraph.http.dto.VertexRequest;
import com.pengcheng.phoenixgraph.schema.EdgePropertySchema;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.PropertyConstraints;
import com.pengcheng.phoenixgraph.schema.PropertyType;
import com.pengcheng.phoenixgraph.schema.VertexPropertySchema;
import com.pengcheng.phoenixgraph.schema.VertexPropertySchema;
import com.pengcheng.phoenixgraph.schema.VertexSchema;
import com.pengcheng.phoenixgraph.storage.WriteOutcome;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import com.pengcheng.phoenixgraph.storage.aerospike.EdgeUpsert;
import com.pengcheng.phoenixgraph.storage.aerospike.StoredVertex;
import com.pengcheng.phoenixgraph.storage.aerospike.VertexUpsert;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GraphServiceTest {

    private final AerospikeVertexStore vertexStore = mock(AerospikeVertexStore.class);
    private final AerospikeEdgeStore edgeStore = mock(AerospikeEdgeStore.class);
    private final GraphService service = new GraphService(vertexStore, edgeStore, schema(), new ObjectMapper());

    /** A store where the two endpoints of the test edges exist, which is what the write path requires. */
    private void endpointsExist(String... ids) {
        for (String id : ids) {
            when(vertexStore.readVertex(id)).thenReturn(new StoredVertex(id, Map.of(), 0L, 0L, "Account"));
        }
    }

    @Test
    void aVertexWriteIndexesItsVectorProperty() {
        VectorIndexManager vectorIndexes = Mockito.mock(VectorIndexManager.class);
        GraphService indexing = new GraphService(vertexStore, edgeStore, schema(), new ObjectMapper(), vectorIndexes);
        when(vertexStore.upsertVertex(any(), anyMap(), anyLong(), anyLong(), any())).thenReturn(WriteOutcome.SUCCESS);

        WriteOutcome outcome = indexing.upsertVertex(new VertexRequest("v1", "Account",
                List.of(new VertexPropertyRequest(1, "alice", 100L)), 50L, 100L));

        assertEquals(WriteOutcome.SUCCESS, outcome);
        // the index of every isVector property of the vertex type is updated after the write
        Mockito.verify(vectorIndexes).indexVertex("payment-graph", "Account", "v1");
        // and the record is stamped with the vertex type it was written as
        Mockito.verify(vertexStore).upsertVertex(eq("v1"), anyMap(), eq(50L), eq(100L), eq("Account"));
    }

    // --- what a wrong write request gets (see the M3-wide input contract work) ---

    @Test
    void aVertexWithoutAnIdIsAClientErrorNotANullPointerException() {
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> service.upsertVertex(new VertexRequest(null, "Account", List.of(), 1L, 1L)));

        assertEquals("id must not be null", rejected.getMessage());
    }

    @Test
    void aBlankVertexIdIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> service.upsertVertex(new VertexRequest("  ", "Account", List.of(), 1L, 1L)));
    }

    @Test
    void aNullPropertyEntryIsRefusedWithAMessage() {
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> service.toVertexUpsert(new VertexRequest("v1", "Account",
                        java.util.Arrays.asList((VertexPropertyRequest) null), 1L, 1L)));

        assertEquals("properties must not contain null entries", rejected.getMessage());
    }

    @Test
    void aPropertyIndexTheSchemaDoesNotDeclareIsRefused() {
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> service.toVertexUpsert(new VertexRequest("v1", "Account",
                        List.of(new VertexPropertyRequest(99, "x", 1L)), 1L, 1L)));

        assertTrue(rejected.getMessage().contains("Unknown property index 99"), rejected.getMessage());
        assertTrue(rejected.getMessage().contains("1 (firstName)"),
                "the message lists what is declared: " + rejected.getMessage());
    }

    @Test
    void aPropertyValueOfTheWrongTypeIsRefused() {
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> service.toVertexUpsert(new VertexRequest("v1", "Account",
                        List.of(new VertexPropertyRequest(1, Map.of("nested", 1), 1L)), 1L, 1L)));

        assertTrue(rejected.getMessage().contains("is not a STRING"), rejected.getMessage());
    }

    @Test
    void aPropertyValueOverTheSizeLimitIsRefusedWithoutEchoingIt() {
        GraphService limited = new GraphService(vertexStore, edgeStore, schema(), new ObjectMapper(), null,
                new WriteValidation(true, 1024));
        String huge = "A".repeat(4096);

        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> limited.toVertexUpsert(new VertexRequest("v1", "Account",
                        List.of(new VertexPropertyRequest(1, huge, 1L)), 1L, 1L)));

        assertTrue(rejected.getMessage().contains("over the limit of 1024"), rejected.getMessage());
        assertTrue(rejected.getMessage().length() < 400,
                "the rejected value is not echoed back: " + rejected.getMessage().length() + " characters");
    }

    @Test
    void anEdgeToAVertexThatDoesNotExistIsRefused() {
        endpointsExist("v1");

        ElementNotFoundException rejected = assertThrows(ElementNotFoundException.class,
                () -> service.upsertEdge(new EdgeRequest("e1", "Payment", "v1", "ghost", List.of(), 1L, 1L)));

        assertTrue(rejected.getMessage().contains("destination vertex 'ghost' does not exist"),
                rejected.getMessage());
        assertTrue(rejected.getMessage().contains("require-existing-endpoints"),
                "the message names the setting to change: " + rejected.getMessage());
    }

    @Test
    void anEdgeBetweenTwoVerticesTheSameBatchWritesIsAccepted() {
        when(vertexStore.upsertVertices(anyList())).thenReturn(List.of(WriteOutcome.SUCCESS, WriteOutcome.SUCCESS));
        when(edgeStore.upsertEdges(anyList())).thenReturn(List.of(WriteOutcome.SUCCESS));

        var results = service.processBatch(new BatchRequest(
                List.of(new VertexRequest("v1", "Account", List.of(), 1L, 1L),
                        new VertexRequest("v2", "Account", List.of(), 1L, 1L)),
                List.of(new EdgeRequest("e1", "Payment", "v1", "v2", List.of(), 1L, 1L)),
                null));

        assertEquals(3, results.size());
        assertEquals("SUCCESS", results.get(2).status());
        Mockito.verify(vertexStore, Mockito.never()).readVertex(any());
    }

    @Test
    void aDeploymentThatImportsEdgesFirstCanTurnTheEndpointCheckOff() {
        GraphService permissive = new GraphService(vertexStore, edgeStore, schema(), new ObjectMapper(), null,
                new WriteValidation(false, WriteValidation.DEFAULT_MAX_PROPERTY_VALUE_BYTES));
        when(edgeStore.upsertEdge(any())).thenReturn(WriteOutcome.SUCCESS);

        assertEquals(WriteOutcome.SUCCESS,
                permissive.upsertEdge(new EdgeRequest("e1", "Payment", "ghost", "ghost2", List.of(), 1L, 1L)));
    }

    @Test
    void anEdgeWithoutAnEndpointIsAClientError() {
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> service.upsertEdge(new EdgeRequest("e1", "Payment", "v1", null, List.of(), 1L, 1L)));

        assertEquals("destination must not be null", rejected.getMessage());
    }

    @Test
    void aPropertyUpdateOfAMissingVertexIsNotFound() {
        ElementNotFoundException rejected = assertThrows(ElementNotFoundException.class,
                () -> service.updateVertexProperty("ghost", 1, new PropertyUpdateRequest("x", 1L)));

        assertTrue(rejected.getMessage().contains("No vertex 'ghost'"), rejected.getMessage());
    }

    @Test
    void aPropertyUpdateInsideTheVertexsOwnSchemaIsAccepted() {
        when(vertexStore.readVertex("v1")).thenReturn(new StoredVertex("v1", Map.of(), 0L, 0L, "Account"));
        when(vertexStore.upsertVertex(any(), anyMap(), anyLong(), anyLong())).thenReturn(WriteOutcome.SUCCESS);

        assertEquals(WriteOutcome.SUCCESS,
                service.updateVertexProperty("v1", 1, new PropertyUpdateRequest("bob", 1L)));
    }

    @Test
    void aPropertyUpdateOfAnIndexTheVertexTypeDoesNotDeclareIsRefused() {
        when(vertexStore.readVertex("v1")).thenReturn(new StoredVertex("v1", Map.of(), 0L, 0L, "Account"));

        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> service.updateVertexProperty("v1", 99, new PropertyUpdateRequest("x", 1L)));

        assertTrue(rejected.getMessage().contains("Unknown property index 99"), rejected.getMessage());
    }

    @Test
    void deletingAVertexThatIsNotThereIsNotFoundRatherThanASilentSuccess() {
        ElementNotFoundException rejected = assertThrows(ElementNotFoundException.class,
                () -> service.deleteVertex("ghost"));

        assertTrue(rejected.getMessage().contains("No vertex 'ghost' to delete"), rejected.getMessage());
    }

    @Test
    void deletingAPropertyOfAMissingVertexIsNotFound() {
        assertThrows(ElementNotFoundException.class, () -> service.deleteVertexProperty("ghost", 1));
    }

    @Test
    void deletingAnEdgeWithoutItsEndpointsIsAClientError() {
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> service.deleteEdge("e1", "Payment", "v1", null));

        assertEquals("destination must not be null", rejected.getMessage());
    }

    @Test
    void aFailedVertexWriteIsNotIndexed() {
        VectorIndexManager vectorIndexes = Mockito.mock(VectorIndexManager.class);
        GraphService indexing = new GraphService(vertexStore, edgeStore, schema(), new ObjectMapper(), vectorIndexes);
        when(vertexStore.upsertVertex(any(), anyMap(), anyLong(), anyLong())).thenReturn(WriteOutcome.FAILED);

        indexing.upsertVertex(new VertexRequest("v1", "Account",
                List.of(new VertexPropertyRequest(1, "alice", 100L)), 50L, 100L));

        Mockito.verifyNoInteractions(vectorIndexes);
    }

    @Test
    void processBatchMapsInlineVerticesAndEdges() {
        when(vertexStore.upsertVertices(anyList())).thenReturn(List.of(WriteOutcome.SUCCESS, WriteOutcome.SUCCESS));
        when(edgeStore.upsertEdges(anyList())).thenReturn(List.of(WriteOutcome.SUCCESS));

        BatchRequest request = new BatchRequest(
                List.of(new VertexRequest("v1", "Account", List.of(new VertexPropertyRequest(1, "alice", 100L)), 50L, 100L),
                        new VertexRequest("v2", "Account", List.of(), 50L, 100L)),
                List.of(new EdgeRequest("e1", "Payment", "v1", "v2", List.of(), 100L, 100L)),
                null);

        var results = service.processBatch(request);

        assertEquals(3, results.size());
        assertEquals("v1", results.get(0).id());
        assertEquals("SUCCESS", results.get(0).status());
        assertEquals("v2", results.get(1).id());
        assertEquals("e1", results.get(2).id());
        assertEquals("SUCCESS", results.get(2).status());
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
        when(vertexStore.upsertVertices(anyList())).thenReturn(List.of(WriteOutcome.SUCCESS, WriteOutcome.SUCCESS));

        var results = service.processBatch(new BatchRequest(null, null, file.toString()));

        assertEquals(1, results.size());
        assertEquals("v9", results.get(0).id());
        assertEquals("SUCCESS", results.get(0).status());
    }

    @Test
    void mapsEdgeSchemaByName() {
        endpointsExist("a", "b");

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
        // the Account schema declares no display property -> empty list, not a missing key
        assertEquals(List.of(), info.displayProperties().get("Account"));
    }

    @Test
    void graphInfoListsDisplayPropertiesPerVertexType() {
        VertexSchema account = new VertexSchema(1, "Account", "An account")
                .property(new VertexPropertySchema(1, "firstName", "first name", PropertyType.ScalarType.STRING)
                        .display(true))
                .property(new VertexPropertySchema(2, "lastName", "last name", PropertyType.ScalarType.STRING)
                        .display(true))
                .property(new VertexPropertySchema(3, "accountType", "type", PropertyType.ScalarType.STRING));
        GraphSchema graph = new GraphSchema(1, "payment-graph", null).vertex(account);

        assertEquals(List.of("firstName", "lastName"),
                GraphInfo.of(graph).displayProperties().get("Account"));
    }

    @Test
    void rejectsUnknownEdgeSchema() {
        assertThrows(IllegalArgumentException.class,
                () -> service.toEdgeUpsert(new EdgeRequest("e1", "Missing", "a", "b", List.of(), 1L, 2L)));
    }

    @Test
    void mapsVertexProperties() {
        VertexUpsert upsert = service.toVertexUpsert(
                new VertexRequest("v1", "Account", List.of(new VertexPropertyRequest(1, "alice", 123L)), 10L, 20L));

        assertEquals("v1", upsert.vertexId());
        assertEquals("Account", upsert.vertexSchema().name());
        assertEquals("alice", upsert.properties().get(1).value());
        assertEquals(123L, upsert.properties().get(1).lastModifiedTime());
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
        VertexSchema account = new VertexSchema(1, "Account", "An account")
                .property(new VertexPropertySchema(1, "firstName", "First name", PropertyType.ScalarType.STRING));
        EdgeSchema payment = new EdgeSchema(1, "Payment", "A payment",
                new EdgePropertySchema(1, "source", "source vertex", PropertyType.ScalarType.LONG),
                new EdgePropertySchema(2, "target", "target vertex", PropertyType.ScalarType.LONG));
        return new GraphSchema(1, "payment-graph", null).vertex(account).edge(payment);
    }
}
