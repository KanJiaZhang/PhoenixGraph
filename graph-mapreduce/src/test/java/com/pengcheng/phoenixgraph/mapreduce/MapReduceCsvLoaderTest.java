package com.pengcheng.phoenixgraph.mapreduce;

import com.pengcheng.phoenixgraph.batchload.BatchLoadResult;
import com.pengcheng.phoenixgraph.schema.EdgePropertySchema;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.PropertyType;
import com.pengcheng.phoenixgraph.schema.VertexSchema;
import com.pengcheng.phoenixgraph.storage.WriteOutcome;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import com.pengcheng.phoenixgraph.storage.aerospike.VertexUpsert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MapReduceCsvLoaderTest {

    private final AerospikeVertexStore vertexStore = mock(AerospikeVertexStore.class);
    private final AerospikeEdgeStore edgeStore = mock(AerospikeEdgeStore.class);
    private final MapReduceCsvLoader loader = new MapReduceCsvLoader(vertexStore, edgeStore, schema());

    @Test
    void mergesDuplicateVerticesAcrossFiles(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.csv"), """
                VertexID,1,2,lastUpdatedTime
                g::Vertex::person:v1,a,,100
                """);
        Files.writeString(dir.resolve("b.csv"), """
                VertexID,1,2,lastUpdatedTime
                g::Vertex::person:v1,,b,200
                """);
        List<List<VertexUpsert>> captured = new ArrayList<>();
        when(vertexStore.upsertVertices(anyList())).thenAnswer(inv -> {
            captured.add(new ArrayList<>(inv.getArgument(0)));
            return List.of(WriteOutcome.SUCCESS);
        });

        BatchLoadResult result = loader.load(List.of(dir.resolve("a.csv"), dir.resolve("b.csv")));

        assertEquals("VERTEX", result.type());
        assertEquals(1, result.total());
        assertEquals(1, captured.size());

        List<VertexUpsert> upserts = captured.get(0);
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
    void loadsEdges(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("edges.csv"), """
                EdgeID,source,destination,3,lastUpdatedTime
                g::Edge::knows:e1,a,b,1.5,100
                """);
        when(edgeStore.upsertEdges(anyList())).thenReturn(List.of(WriteOutcome.SUCCESS));

        BatchLoadResult result = loader.load(List.of(dir.resolve("edges.csv")));

        assertEquals("EDGE", result.type());
        assertEquals(1, result.total());
        verify(edgeStore).upsertEdges(anyList());
    }

    @Test
    void spillsAndWritesInBatches(@TempDir Path dir) throws Exception {
        MapReduceCsvLoader smallLoader = new MapReduceCsvLoader(vertexStore, edgeStore, schema(), 1, 1);
        Files.writeString(dir.resolve("v.csv"), """
                VertexID,1,lastUpdatedTime
                g::Vertex::person:v1,a,100
                g::Vertex::person:v2,b,200
                g::Vertex::person:v3,c,300
                """);
        when(vertexStore.upsertVertices(anyList())).thenReturn(List.of(WriteOutcome.SUCCESS));

        BatchLoadResult result = smallLoader.load(List.of(dir.resolve("v.csv")));

        assertEquals(3, result.total());
        verify(vertexStore, times(3)).upsertVertices(anyList());
    }

    private static GraphSchema schema() {
        VertexSchema person = new VertexSchema(1, "person", null);
        EdgeSchema knows = new EdgeSchema(1, "knows", "friendship",
                new EdgePropertySchema(1, "source", "source vertex", PropertyType.ScalarType.LONG),
                new EdgePropertySchema(2, "target", "target vertex", PropertyType.ScalarType.LONG));
        return new GraphSchema(1, "g", null).vertex(person).edge(knows);
    }
}
