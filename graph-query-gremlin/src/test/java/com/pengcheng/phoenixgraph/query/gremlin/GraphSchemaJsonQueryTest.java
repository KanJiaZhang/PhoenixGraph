package com.pengcheng.phoenixgraph.query.gremlin;

import com.pengcheng.phoenixgraph.query.storage.EdgeQueryResult;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
import com.pengcheng.phoenixgraph.query.storage.GraphQuery;
import com.pengcheng.phoenixgraph.query.storage.VertexQueryResult;
import com.pengcheng.phoenixgraph.schema.EdgeDirection;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.json.SchemaJsonCodec;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies {@code g.V(123, 'Account').out('Payment').inE('PhoneToAccount')}
 * against the real {@code GraphSchema.json} (which now declares each edge's
 * source/target vertex schema).
 */
class GraphSchemaJsonQueryTest {

    @Test
    void schemaContainsEveryNameUsedByTheQuery() {
        GraphSchema schema = loadSchema();

        assertNotNull(schema.vertexSchema("Account"), "Account vertex schema missing");
        assertNotNull(schema.edgeSchema("Payment"), "Payment edge schema missing");
        assertNotNull(schema.edgeSchema("PhoneToAccount"), "PhoneToAccount edge schema missing");
    }

    @Test
    void schemaDeclaresEdgeEndpointVertexSchemas() {
        GraphSchema schema = loadSchema();

        assertEquals(EdgeDirection.DIRECTED, schema.edgeSchema("Payment").direction());
        assertEquals("Account", schema.edgeSchema("Payment").sourceVertexName());
        assertEquals("Account", schema.edgeSchema("Payment").targetVertexName());

        assertEquals(EdgeDirection.DIRECTED, schema.edgeSchema("PhoneToAccount").direction());
        assertEquals("Phone", schema.edgeSchema("PhoneToAccount").sourceVertexName());
        assertEquals("Account", schema.edgeSchema("PhoneToAccount").targetVertexName());
    }

    @Test
    void resolverDerivedFromSchemaResolvesNeighbourSchemas() {
        NeighborVertexResolver resolver = NeighborVertexResolver.from(loadSchema());

        assertEquals("Account", resolver.neighborVertexName("Payment", EdgeSide.OUT));
        assertEquals("Phone", resolver.neighborVertexName("PhoneToAccount", EdgeSide.IN));
        assertEquals("Account", resolver.neighborVertexName("PhoneToAccount", EdgeSide.OUT));
    }

    @Test
    void queryParsesIntoOutThenInE() {
        GremlinQuery query = GremlinQuery.parse("g.V(123, 'Account').out('Payment').inE('PhoneToAccount')");

        assertEquals(List.of("123"), query.vertexIds());
        assertEquals("Account", query.vertexName());
        assertEquals(List.of(
                new GremlinQuery.Step("out", List.of("Payment")),
                new GremlinQuery.Step("inE", List.of("PhoneToAccount"))), query.steps());
    }

    @Test
    void queryExecutesOutHopThenInEHopUsingSchemaDerivedResolver() {
        GraphSchema schema = loadSchema();
        GraphQuery graphQuery = mock(GraphQuery.class);

        when(graphQuery.readVertex("123", "Account", List.of()))
                .thenReturn(new VertexQueryResult("Account", "123", Map.of(), 1L, 2L));

        // 123 --Payment--> 456 (Payment source/target are both Accounts)
        EdgeQueryResult payment = new EdgeQueryResult("Payment", "p1", EdgeSide.OUT, "456", Map.of(), 3L, 4L);
        when(graphQuery.readEdges("123", "Account", "Payment", EdgeSide.OUT, List.of()))
                .thenReturn(List.of(payment));
        when(graphQuery.readVertex("456", "Account", List.of()))
                .thenReturn(new VertexQueryResult("Account", "456", Map.of(), 5L, 6L));

        // Phone --PhoneToAccount--> Account 456, so it is an IN edge of 456.
        EdgeQueryResult phoneEdge = new EdgeQueryResult("PhoneToAccount", "e2", EdgeSide.IN, "999", Map.of(), 7L, 8L);
        when(graphQuery.readEdges("456", "Account", "PhoneToAccount", EdgeSide.IN, List.of()))
                .thenReturn(List.of(phoneEdge));
        stubReadVerticesFromReadVertex(graphQuery);

        GremlinGraphQuery gremlin = new GremlinGraphQuery(graphQuery, NeighborVertexResolver.from(schema));

        List<Object> result = gremlin.execute("g.V(123, 'Account').out('Payment').inE('PhoneToAccount')");

        assertEquals(1, result.size());
        GremlinEdge edge = (GremlinEdge) result.get(0);
        assertEquals("e2", edge.id());
        assertEquals(EdgeSide.IN, edge.side());
        assertEquals("999", edge.otherVertexId());

        verify(graphQuery).readEdges("123", "Account", "Payment", EdgeSide.OUT, List.of());
        verify(graphQuery).readEdges("456", "Account", "PhoneToAccount", EdgeSide.IN, List.of());
    }

    @Test
    void queryExecutesOutHopThenInEHopAndCounts() {
        GraphSchema schema = loadSchema();
        GraphQuery graphQuery = mock(GraphQuery.class);

        when(graphQuery.readVertex("123", "Account", List.of()))
                .thenReturn(new VertexQueryResult("Account", "123", Map.of(), 1L, 2L));

        // 123 --Payment--> 456
        EdgeQueryResult payment = new EdgeQueryResult("Payment", "p1", EdgeSide.OUT, "456", Map.of(), 3L, 4L);
        when(graphQuery.readEdges("123", "Account", "Payment", EdgeSide.OUT, List.of()))
                .thenReturn(List.of(payment));
        when(graphQuery.readVertex("456", "Account", List.of()))
                .thenReturn(new VertexQueryResult("Account", "456", Map.of(), 5L, 6L));

        // Two phones linked to account 456 (Phone --PhoneToAccount--> Account 456 = IN edges of 456).
        EdgeQueryResult phoneEdge1 = new EdgeQueryResult("PhoneToAccount", "e2", EdgeSide.IN, "999", Map.of(), 7L, 8L);
        EdgeQueryResult phoneEdge2 = new EdgeQueryResult("PhoneToAccount", "e3", EdgeSide.IN, "888", Map.of(), 9L, 10L);
        when(graphQuery.readEdges("456", "Account", "PhoneToAccount", EdgeSide.IN, List.of()))
                .thenReturn(List.of(phoneEdge1, phoneEdge2));
        stubReadVerticesFromReadVertex(graphQuery);

        GremlinGraphQuery gremlin = new GremlinGraphQuery(graphQuery, NeighborVertexResolver.from(schema));

        List<Object> result = gremlin.execute("g.V(123, 'Account').out('Payment').inE('PhoneToAccount').count()");

        assertEquals(List.of(2L), result);
        verify(graphQuery).readEdges("123", "Account", "Payment", EdgeSide.OUT, List.of());
        verify(graphQuery).readEdges("456", "Account", "PhoneToAccount", EdgeSide.IN, List.of());
    }

    private static void stubReadVerticesFromReadVertex(GraphQuery graphQuery) {
        when(graphQuery.readVertices(anyString(), anyList(), anyList()))
                .thenAnswer(inv -> {
                    String name = inv.getArgument(0);
                    List<Object> ids = inv.getArgument(1);
                    List<String> props = inv.getArgument(2);
                    List<VertexQueryResult> out = new ArrayList<>(ids.size());
                    for (Object id : ids) {
                        out.add(graphQuery.readVertex(id, name, props));
                    }
                    return out;
                });
    }

    private static GraphSchema loadSchema() {
        try (InputStream in = GraphSchemaJsonQueryTest.class.getResourceAsStream("/GraphSchema.json")) {
            assertNotNull(in, "Missing GraphSchema.json test resource");
            return new SchemaJsonCodec().fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load GraphSchema.json", e);
        }
    }
}
