package com.pengcheng.phoenixgraph.integration;

import com.aerospike.client.AerospikeClient;
import com.pengcheng.phoenixgraph.query.aerospike.AerospikeGraphQuery;
import com.pengcheng.phoenixgraph.query.gremlin.GremlinEdge;
import com.pengcheng.phoenixgraph.query.gremlin.GremlinGraphQuery;
import com.pengcheng.phoenixgraph.query.gremlin.NeighborVertexResolver;
import com.pengcheng.phoenixgraph.query.storage.EdgeQueryResult;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
import com.pengcheng.phoenixgraph.query.storage.VertexQueryResult;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test that seeds Aerospike from {@code GraphSchema.json}, then
 * reads vertices/edges through the query layer and runs a Gremlin traversal.
 *
 * <p>Seeds: 100 vertices of each type (Account / IP / Phone) and 200 edges of
 * each type (Payment / PhoneToAccount). Skipped when Aerospike is unreachable.
 */
class AerospikeQueryIntegrationTest {

    @Test
    void seedsGraphThenReadsViaQueryLayerAndGremlin() {
        IntegrationConfig config = IntegrationConfig.fromEnv();
        AerospikeClient client = config.connectOrNull();
        assumeTrue(client != null,
                "Aerospike not available at " + config.host() + ":" + config.port() + ", skipping integration test");
        try (client) {
            GraphSchema schema = QueryDataSeeder.loadSchema();
            AerospikeVertexStore vertexStore = new AerospikeVertexStore(client, config.namespace(), config.vertexSet());
            AerospikeEdgeStore edgeStore = new AerospikeEdgeStore(client, config.namespace(), config.vertexSet(), schema);

            QueryDataSeeder.seedVertices(vertexStore, schema);
            QueryDataSeeder.seedEdges(edgeStore, schema);

            AerospikeGraphQuery graphQuery = new AerospikeGraphQuery(vertexStore, edgeStore, schema);
            assertReadVertex(graphQuery);
            assertReadEdges(graphQuery);
            assertGremlinTraversal(graphQuery, schema);
        }
    }

    // --- query-layer reads ---

    private void assertReadVertex(AerospikeGraphQuery graphQuery) {
        VertexQueryResult all = graphQuery.readVertex("123", "Account", List.of());
        assertNotNull(all);
        assertEquals("Account", all.vertexName());
        assertEquals("123", all.vertexId());
        // Account 123 = index 23
        assertEquals("CLOSED", all.properties().get("accountStatus"));
        assertEquals("checking", all.properties().get("accountType"));
        assertEquals("addr-123", all.properties().get("primaryAddress"));

        VertexQueryResult projected = graphQuery.readVertex("123", "Account", List.of("accountType"));
        assertEquals(Map.of("accountType", "checking"), projected.properties());
    }

    private void assertReadEdges(AerospikeGraphQuery graphQuery) {
        // Account 123 has two outgoing Payment edges: p23 (->124) and p123 (->126).
        List<EdgeQueryResult> payments =
                graphQuery.readEdges("123", "Account", "Payment", EdgeSide.OUT, List.of());
        assertEquals(List.of("p123", "p23"), payments.stream().map(EdgeQueryResult::edgeId).sorted().toList());
        for (EdgeQueryResult edge : payments) {
            assertEquals("Payment", edge.edgeName());
            assertEquals(EdgeSide.OUT, edge.side());
            assertEquals(List.of("amount", "currency", "status", "type"),
                    edge.properties().keySet().stream().sorted().toList());
        }

        // Account 124 is the target of two PhoneToAccount edges from phone 324.
        List<EdgeQueryResult> phoneLinks =
                graphQuery.readEdges("124", "Account", "PhoneToAccount", EdgeSide.IN, List.of());
        assertEquals(List.of("pa124", "pa24"), phoneLinks.stream().map(EdgeQueryResult::edgeId).sorted().toList());
        for (EdgeQueryResult edge : phoneLinks) {
            assertEquals("PhoneToAccount", edge.edgeName());
            assertEquals(EdgeSide.IN, edge.side());
            assertEquals("324", edge.otherVertexId());
            assertTrue(edge.properties().containsKey("linkedAt"));
        }
    }

    // --- Gremlin traversal ---

    private void assertGremlinTraversal(AerospikeGraphQuery graphQuery, GraphSchema schema) {
        GremlinGraphQuery gremlin = new GremlinGraphQuery(graphQuery, NeighborVertexResolver.from(schema));

        List<Object> result = gremlin.execute("g.V(123, 'Account').out('Payment').inE('PhoneToAccount')");

        assertEquals(4, result.size(), "expected 2 phones linked to the 2 accounts reached by 123's payments");
        for (Object o : result) {
            assertTrue(o instanceof GremlinEdge, "expected edges but got: " + o);
            GremlinEdge edge = (GremlinEdge) o;
            assertEquals("PhoneToAccount", edge.label());
            assertEquals(EdgeSide.IN, edge.side());
        }
        List<String> otherVertexIds = result.stream()
                .map(o -> String.valueOf(((GremlinEdge) o).otherVertexId()))
                .sorted()
                .toList();
        assertEquals(List.of("324", "324", "326", "326"), otherVertexIds);
    }
}
