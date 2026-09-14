package com.pengcheng.phoenixgraph.batchload;

import com.pengcheng.phoenixgraph.schema.EdgeDirection;
import com.pengcheng.phoenixgraph.schema.EdgePropertySchema;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.PropertyType;
import com.pengcheng.phoenixgraph.schema.VertexSchema;
import com.pengcheng.phoenixgraph.storage.WriteOutcome;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import com.pengcheng.phoenixgraph.storage.aerospike.EdgeUpsert;
import com.pengcheng.phoenixgraph.storage.aerospike.VertexUpsert;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CsvBatchLoaderTest {

    private final AerospikeVertexStore vertexStore = mock(AerospikeVertexStore.class);
    private final AerospikeEdgeStore edgeStore = mock(AerospikeEdgeStore.class);
    private final CsvBatchLoader loader = new CsvBatchLoader(vertexStore, edgeStore, schema());

    @Test
    void parsesQuotedAndEscapedFields() {
        assertEquals(List.of("a", "b,c", "d\"e"), CsvRowParser.parseLine("a,\"b,c\",\"d\"\"e\""));
    }

    @Test
    void parsesCompositeElementId() {
        CsvBatchLoader.ParsedElementId vertex = CsvBatchLoader.parseElementId("g::Vertex::person:v1");
        assertEquals("g", vertex.graphName());
        assertEquals("Vertex", vertex.kind());
        assertEquals("person", vertex.name());
        assertEquals("v1", vertex.id());

        CsvBatchLoader.ParsedElementId edge = CsvBatchLoader.parseElementId("g::Edge::knows:e1");
        assertEquals("Edge", edge.kind());
        assertEquals("knows", edge.name());
        assertEquals("e1", edge.id());
    }

    @Test
    void loadsVertexCsv() throws Exception {
        when(vertexStore.upsertVertices(anyList())).thenReturn(List.of(WriteOutcome.SUCCESS, WriteOutcome.SUCCESS));

        String csv = """
                VertexID,1,2,lastUpdatedTime
                g::Vertex::person:v1,alice,30,1700000000000
                g::Vertex::person:v2,"bob,smith",,1700000000001
                """;
        BatchLoadResult result = loader.load(new StringReader(csv));

        assertEquals("VERTEX", result.type());
        assertEquals(2, result.total());
        assertEquals(2, result.succeeded());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<VertexUpsert>> captor = ArgumentCaptor.forClass(List.class);
        verify(vertexStore).upsertVertices(captor.capture());
        List<VertexUpsert> upserts = captor.getValue();

        assertEquals("v1", upserts.get(0).vertexId());
        assertEquals("person", upserts.get(0).vertexSchema().name());
        assertEquals("alice", upserts.get(0).properties().get(1).value());
        assertEquals("30", upserts.get(0).properties().get(2).value()); // raw string value
        assertEquals(1700000000000L, upserts.get(0).lastUpdatedTime());

        // v2: quoted value with comma; empty property column is skipped
        assertEquals("bob,smith", upserts.get(1).properties().get(1).value());
        assertFalse(upserts.get(1).properties().containsKey(2));
    }

    @Test
    void loadsEdgeCsv() throws Exception {
        when(edgeStore.upsertEdges(anyList())).thenReturn(List.of(WriteOutcome.SUCCESS));

        String csv = """
                EdgeID,source,destination,3,lastUpdatedTime
                g::Edge::knows:e1,a,b,1.5,1700000000000
                """;
        BatchLoadResult result = loader.load(new StringReader(csv));

        assertEquals("EDGE", result.type());
        assertEquals(1, result.total());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EdgeUpsert>> captor = ArgumentCaptor.forClass(List.class);
        verify(edgeStore).upsertEdges(captor.capture());
        EdgeUpsert edge = captor.getValue().get(0);

        assertEquals("e1", edge.edgeId());
        assertEquals("a", edge.source());
        assertEquals("b", edge.destination());
        assertEquals("knows", edge.edgeSchema().name());
        assertEquals(EdgeDirection.UNDIRECTED, edge.edgeSchema().direction());
        assertEquals("1.5", edge.properties().get(3).value());
    }

    @Test
    void mergesDuplicateVertices() throws Exception {
        when(vertexStore.upsertVertices(anyList())).thenReturn(List.of(WriteOutcome.SUCCESS));

        String csv = """
                VertexID,1,2,lastUpdatedTime
                g::Vertex::person:v1,a,,100
                g::Vertex::person:v1,,b,200
                """;
        BatchLoadResult result = loader.load(new StringReader(csv));

        assertEquals(1, result.total());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<VertexUpsert>> captor = ArgumentCaptor.forClass(List.class);
        verify(vertexStore).upsertVertices(captor.capture());
        List<VertexUpsert> upserts = captor.getValue();
        assertEquals(1, upserts.size());

        VertexUpsert merged = upserts.get(0);
        assertEquals("v1", merged.vertexId());
        assertEquals("person", merged.vertexSchema().name());
        assertEquals("a", merged.properties().get(1).value());
        assertEquals("b", merged.properties().get(2).value());
        assertEquals(100L, merged.createdTime());
        assertEquals(200L, merged.lastUpdatedTime());
    }

    @Test
    void mergesDuplicateEdges() throws Exception {
        when(edgeStore.upsertEdges(anyList())).thenReturn(List.of(WriteOutcome.SUCCESS));

        String csv = """
                EdgeID,source,destination,3,lastUpdatedTime
                g::Edge::knows:e1,a,b,x,100
                g::Edge::knows:e1,a,b,y,200
                """;
        BatchLoadResult result = loader.load(new StringReader(csv));

        assertEquals(1, result.total());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EdgeUpsert>> captor = ArgumentCaptor.forClass(List.class);
        verify(edgeStore).upsertEdges(captor.capture());
        List<EdgeUpsert> edges = captor.getValue();
        assertEquals(1, edges.size());
        assertEquals("knows", edges.get(0).edgeSchema().name());
        assertEquals("y", edges.get(0).properties().get(3).value()); // newer wins
        assertEquals(200L, edges.get(0).lastUpdatedTime());
    }

    @Test
    void streamsInBatches() throws Exception {
        CsvBatchLoader smallBatch = new CsvBatchLoader(vertexStore, edgeStore, schema(), 1);
        when(vertexStore.upsertVertices(anyList())).thenReturn(List.of(WriteOutcome.SUCCESS));

        String csv = """
                VertexID,1,lastUpdatedTime
                g::Vertex::person:v1,a,100
                g::Vertex::person:v2,b,200
                g::Vertex::person:v3,c,300
                """;
        BatchLoadResult result = smallBatch.load(new StringReader(csv));

        assertEquals(3, result.total());
        verify(vertexStore, times(3)).upsertVertices(anyList());
    }

    @Test
    void rejectsUnknownFirstColumn() {
        assertThrows(IllegalArgumentException.class,
                () -> loader.load(new StringReader("Foo,1,lastUpdatedTime\nv1,x,1")));
    }

    @Test
    void rejectsWrongLastColumn() {
        assertThrows(IllegalArgumentException.class,
                () -> loader.load(new StringReader("VertexID,1,foo\ng::Vertex::person:v1,x,1")));
    }

    private static GraphSchema schema() {
        VertexSchema person = new VertexSchema(1, "person", null);
        EdgeSchema knows = new EdgeSchema(1, "knows", "friendship",
                new EdgePropertySchema(1, "source", "source vertex", PropertyType.ScalarType.LONG),
                new EdgePropertySchema(2, "target", "target vertex", PropertyType.ScalarType.LONG),
                EdgeDirection.UNDIRECTED);
        return new GraphSchema(1, "g", null).vertex(person).edge(knows);
    }
}
