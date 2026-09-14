package com.pengcheng.phoenixgraph.query.aerospike;

import com.pengcheng.phoenixgraph.query.QueryException;
import com.pengcheng.phoenixgraph.query.storage.EdgeQueryResult;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
import com.pengcheng.phoenixgraph.query.storage.VertexQueryResult;
import com.pengcheng.phoenixgraph.schema.EdgeDirection;
import com.pengcheng.phoenixgraph.schema.EdgePropertySchema;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.PropertyType;
import com.pengcheng.phoenixgraph.schema.VertexPropertySchema;
import com.pengcheng.phoenixgraph.schema.VertexSchema;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import com.pengcheng.phoenixgraph.storage.aerospike.EdgePropertyValue;
import com.pengcheng.phoenixgraph.storage.aerospike.StoredEdgeValue;
import com.pengcheng.phoenixgraph.storage.aerospike.StoredVertex;
import com.pengcheng.phoenixgraph.storage.aerospike.VertexPropertyValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AerospikeGraphQueryTest {

    private final AerospikeVertexStore vertexStore = mock(AerospikeVertexStore.class);
    private final AerospikeEdgeStore edgeStore = mock(AerospikeEdgeStore.class);
    private final AerospikeGraphQuery query = new AerospikeGraphQuery(vertexStore, edgeStore, schema());

    // --- vertex ---

    @Test
    void readVertexWithoutPropertyListReturnsAllDeclaredProperties() {
        StoredVertex stored = new StoredVertex("v1",
                Map.of(1, new VertexPropertyValue("alice", 200L),
                        2, new VertexPropertyValue(30, 200L)),
                100L, 200L);
        when(vertexStore.readVertex(eq("v1"), anyList())).thenReturn(stored);

        VertexQueryResult result = query.readVertex("v1", "person", (String[]) null);

        assertEquals("person", result.vertexName());
        assertEquals("v1", result.vertexId());
        assertEquals(Map.of("name", "alice", "age", 30), result.properties());
        assertEquals(100L, result.createdTime());
        assertEquals(200L, result.lastUpdatedTime());
    }

    @Test
    void readVertexWithPropertyListReturnsOnlyRequestedProperties() {
        StoredVertex stored = new StoredVertex("v1",
                Map.of(1, new VertexPropertyValue("alice", 200L),
                        2, new VertexPropertyValue(30, 200L)),
                100L, 200L);
        when(vertexStore.readVertex(eq("v1"), anyList())).thenReturn(stored);

        VertexQueryResult result = query.readVertex("v1", "person", "age");

        assertEquals(Map.of("age", 30), result.properties());
        assertEquals(100L, result.createdTime());
        assertEquals(200L, result.lastUpdatedTime());
    }

    @Test
    void readVertexOmitsDeclaredButUnstoredProperties() {
        StoredVertex stored = new StoredVertex("v1",
                Map.of(1, new VertexPropertyValue("alice", 200L)), // "age" (index 2) not stored
                100L, 200L);
        when(vertexStore.readVertex(eq("v1"), anyList())).thenReturn(stored);

        VertexQueryResult result = query.readVertex("v1", "person", (String[]) null);

        assertEquals(Map.of("name", "alice"), result.properties());
    }

    @Test
    void readVertexMissingVertexReturnsNull() {
        when(vertexStore.readVertex("nope")).thenReturn(null);

        assertNull(query.readVertex("nope", "person", (String[]) null));
    }

    @Test
    void readVertexUnknownSchemaThrows() {
        assertThrows(QueryException.class, () -> query.readVertex("v1", "unknown", (String[]) null));
    }

    @Test
    void readVertexUnknownPropertyThrows() {
        StoredVertex stored = new StoredVertex("v1",
                Map.of(1, new VertexPropertyValue("alice", 200L)), 100L, 200L);
        when(vertexStore.readVertex(eq("v1"), anyList())).thenReturn(stored);

        assertThrows(QueryException.class, () -> query.readVertex("v1", "person", "nope"));
    }

    // --- edges ---

    @Test
    void readDirectedEdgesReadsBothOutAndInBins() {
        StoredEdgeValue out = knowsEdge("e1", "n1", 0.5, 2020L);
        StoredEdgeValue in = knowsEdge("e2", "n2", 0.25, 2021L);
        when(edgeStore.getEdges("v1", 1, AerospikeEdgeStore.EDGE_DIR_OUT)).thenReturn(List.of(out));
        when(edgeStore.getEdges("v1", 1, AerospikeEdgeStore.EDGE_DIR_IN)).thenReturn(List.of(in));

        List<EdgeQueryResult> results = query.readEdges("v1", "person", "knows", (String[]) null);

        assertEquals(2, results.size());
        assertEquals("e1", results.get(0).edgeId());
        assertEquals(EdgeSide.OUT, results.get(0).side());
        assertEquals("n1", results.get(0).otherVertexId());
        assertEquals("e2", results.get(1).edgeId());
        assertEquals(EdgeSide.IN, results.get(1).side());
        assertEquals("n2", results.get(1).otherVertexId());

        verify(edgeStore).getEdges("v1", 1, AerospikeEdgeStore.EDGE_DIR_OUT);
        verify(edgeStore).getEdges("v1", 1, AerospikeEdgeStore.EDGE_DIR_IN);
    }

    @Test
    void readEdgesWithoutPropertyListReturnsAllDeclaredProperties() {
        StoredEdgeValue out = knowsEdge("e1", "n1", 0.5, 2020L);
        when(edgeStore.getEdges("v1", 1, AerospikeEdgeStore.EDGE_DIR_OUT)).thenReturn(List.of(out));
        when(edgeStore.getEdges("v1", 1, AerospikeEdgeStore.EDGE_DIR_IN)).thenReturn(List.of());

        List<EdgeQueryResult> results = query.readEdges("v1", "person", "knows", (String[]) null);

        assertEquals(1, results.size());
        EdgeQueryResult result = results.get(0);
        assertEquals("knows", result.edgeName());
        assertEquals("n1", result.otherVertexId());
        assertEquals(Map.of("weight", 0.5, "since", 2020L), result.properties());
        assertEquals(100L, result.createdTime());
        assertEquals(200L, result.lastUpdatedTime());
    }

    @Test
    void readEdgesWithPropertyListReturnsOnlyRequestedProperties() {
        StoredEdgeValue out = knowsEdge("e1", "n1", 0.5, 2020L);
        when(edgeStore.getEdges("v1", 1, AerospikeEdgeStore.EDGE_DIR_OUT)).thenReturn(List.of(out));
        when(edgeStore.getEdges("v1", 1, AerospikeEdgeStore.EDGE_DIR_IN)).thenReturn(List.of());

        List<EdgeQueryResult> results = query.readEdges("v1", "person", "knows", "weight");

        assertEquals(Map.of("weight", 0.5), results.get(0).properties());
    }

    @Test
    void readUndirectedEdgesReadsBothBin() {
        when(edgeStore.getEdges("v1", 2, AerospikeEdgeStore.EDGE_DIR_BOTH))
                .thenReturn(List.of(friendEdge("e1", "n1", 1)));

        List<EdgeQueryResult> results =
                query.readEdges("v1", "person", "friend", (String[]) null);

        assertEquals(1, results.size());
        assertEquals(EdgeSide.BOTH, results.get(0).side());
        assertEquals("n1", results.get(0).otherVertexId());
        assertEquals(Map.of("strength", 1), results.get(0).properties());
    }

    @Test
    void readEdgesUnknownVertexSchemaThrows() {
        assertThrows(QueryException.class,
                () -> query.readEdges("v1", "unknown", "knows", (String[]) null));
    }

    @Test
    void readEdgesUnknownEdgeSchemaThrows() {
        assertThrows(QueryException.class,
                () -> query.readEdges("v1", "person", "unknown", (String[]) null));
    }

    @Test
    void readEdgesUnknownPropertyThrows() {
        StoredEdgeValue out = knowsEdge("e1", "n1", 0.5, 2020L);
        when(edgeStore.getEdges("v1", 1, AerospikeEdgeStore.EDGE_DIR_OUT)).thenReturn(List.of(out));
        when(edgeStore.getEdges("v1", 1, AerospikeEdgeStore.EDGE_DIR_IN)).thenReturn(List.of());

        assertThrows(QueryException.class,
                () -> query.readEdges("v1", "person", "knows", "nope"));
    }

    @Test
    void readDirectedOutEdgesOnlyReadsOutBin() {
        StoredEdgeValue out = knowsEdge("e1", "n1", 0.5, 2020L);
        when(edgeStore.getEdges("v1", 1, AerospikeEdgeStore.EDGE_DIR_OUT)).thenReturn(List.of(out));

        List<EdgeQueryResult> results =
                query.readEdges("v1", "person", "knows", EdgeSide.OUT, (String[]) null);

        assertEquals(1, results.size());
        assertEquals("e1", results.get(0).edgeId());
        assertEquals(EdgeSide.OUT, results.get(0).side());
        verify(edgeStore).getEdges("v1", 1, AerospikeEdgeStore.EDGE_DIR_OUT);
        verify(edgeStore, never()).getEdges("v1", 1, AerospikeEdgeStore.EDGE_DIR_IN);
    }

    @Test
    void readDirectedInEdgesOnlyReadsInBin() {
        StoredEdgeValue in = knowsEdge("e2", "n2", 0.25, 2021L);
        when(edgeStore.getEdges("v1", 1, AerospikeEdgeStore.EDGE_DIR_IN)).thenReturn(List.of(in));

        List<EdgeQueryResult> results =
                query.readEdges("v1", "person", "knows", EdgeSide.IN, (String[]) null);

        assertEquals(1, results.size());
        assertEquals("e2", results.get(0).edgeId());
        assertEquals(EdgeSide.IN, results.get(0).side());
        verify(edgeStore).getEdges("v1", 1, AerospikeEdgeStore.EDGE_DIR_IN);
        verify(edgeStore, never()).getEdges("v1", 1, AerospikeEdgeStore.EDGE_DIR_OUT);
    }

    @Test
    void readUndirectedEdgesIgnoresDirectionAndReadsBothBin() {
        when(edgeStore.getEdges("v1", 2, AerospikeEdgeStore.EDGE_DIR_BOTH))
                .thenReturn(List.of(friendEdge("e1", "n1", 1)));

        List<EdgeQueryResult> results =
                query.readEdges("v1", "person", "friend", EdgeSide.OUT, (String[]) null);

        assertEquals(1, results.size());
        assertEquals(EdgeSide.BOTH, results.get(0).side());
        assertEquals(Map.of("strength", 1), results.get(0).properties());
        verify(edgeStore).getEdges("v1", 2, AerospikeEdgeStore.EDGE_DIR_BOTH);
    }

    @Test
    void readEdgesAllowsNullVertexNameForTraversal() {
        StoredEdgeValue out = knowsEdge("e1", "n1", 0.5, 2020L);
        when(edgeStore.getEdges("v1", 1, AerospikeEdgeStore.EDGE_DIR_OUT)).thenReturn(List.of(out));
        when(edgeStore.getEdges("v1", 1, AerospikeEdgeStore.EDGE_DIR_IN)).thenReturn(List.of());

        List<EdgeQueryResult> results =
                query.readEdges("v1", null, "knows", EdgeSide.OUT, (String[]) null);

        assertEquals(1, results.size());
        assertEquals("n1", results.get(0).otherVertexId());
    }

    // --- helpers ---

    private static GraphSchema schema() {
        VertexSchema person = new VertexSchema(1, "person", null)
                .property(new VertexPropertySchema(1, "name", null, PropertyType.ScalarType.STRING))
                .property(new VertexPropertySchema(2, "age", null, PropertyType.ScalarType.INT));

        EdgeSchema knows = new EdgeSchema(1, "knows", null,
                new EdgePropertySchema(1, "source", null, PropertyType.ScalarType.LONG),
                new EdgePropertySchema(2, "target", null, PropertyType.ScalarType.LONG),
                EdgeDirection.DIRECTED)
                .property(new EdgePropertySchema(3, "weight", null, PropertyType.ScalarType.DOUBLE))
                .property(new EdgePropertySchema(4, "since", null, PropertyType.ScalarType.LONG));

        EdgeSchema friend = new EdgeSchema(2, "friend", null,
                new EdgePropertySchema(1, "source", null, PropertyType.ScalarType.LONG),
                new EdgePropertySchema(2, "target", null, PropertyType.ScalarType.LONG),
                EdgeDirection.UNDIRECTED)
                .property(new EdgePropertySchema(3, "strength", null, PropertyType.ScalarType.INT));

        return new GraphSchema(1, "g", null).vertex(person).edge(knows).edge(friend);
    }

    private static StoredEdgeValue knowsEdge(String edgeId, Object otherVertexId, double weight, long since) {
        return new StoredEdgeValue(edgeId, otherVertexId, 200L, 100L, Map.of(
                3, new EdgePropertyValue(weight, 100L, 200L),
                4, new EdgePropertyValue(since, 100L, 200L)));
    }

    private static StoredEdgeValue friendEdge(String edgeId, Object otherVertexId, int strength) {
        return new StoredEdgeValue(edgeId, otherVertexId, 200L, 100L, Map.of(
                3, new EdgePropertyValue(strength, 100L, 200L)));
    }
}
