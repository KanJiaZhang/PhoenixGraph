package com.pengcheng.phoenixgraph.integration;

import com.aerospike.client.AerospikeClient;
import com.pengcheng.phoenixgraph.query.aerospike.AerospikeGraphQuery;
import com.pengcheng.phoenixgraph.query.gremlin.GremlinGraphQuery;
import com.pengcheng.phoenixgraph.query.traversal.NeighborVertexResolver;
import com.pengcheng.phoenixgraph.schema.EdgeDirection;
import com.pengcheng.phoenixgraph.schema.EdgeEndpointSchema;
import com.pengcheng.phoenixgraph.schema.EdgePropertySchema;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.PropertyType;
import com.pengcheng.phoenixgraph.schema.VertexPropertySchema;
import com.pengcheng.phoenixgraph.schema.VertexSchema;
import com.pengcheng.phoenixgraph.storage.WriteOutcome;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import com.pengcheng.phoenixgraph.storage.aerospike.EdgePropertyValue;
import com.pengcheng.phoenixgraph.storage.aerospike.EdgeUpsert;
import com.pengcheng.phoenixgraph.storage.aerospike.VertexPropertyValue;
import com.pengcheng.phoenixgraph.storage.aerospike.VertexUpsert;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs in-memory graph algorithms (pagerank / louvain / cc) over a seeded
 * connected Account graph in Aerospike.
 */
class GremlinAlgorithmIntegrationTest {

    @Test
    void runsPagerankLouvainAndCc() {
        IntegrationConfig config = IntegrationConfig.fromEnv();
        AerospikeClient client = config.connectOrNull();
        assumeTrue(client != null,
                "Aerospike not available at " + config.host() + ":" + config.port() + ", skipping");
        try (client) {
            GraphSchema schema = schema();
            String set = "algo_vertices";
            AerospikeVertexStore vertexStore = new AerospikeVertexStore(client, config.namespace(), set);
            AerospikeEdgeStore edgeStore = new AerospikeEdgeStore(client, config.namespace(), set, schema);
            seed(vertexStore, edgeStore, schema);

            GremlinGraphQuery gremlin = new GremlinGraphQuery(
                    new AerospikeGraphQuery(vertexStore, edgeStore, schema),
                    NeighborVertexResolver.from(schema));

            String base = "g.V('1000', 'Account').batch(40).both('Payment').both('Payment')";

            List<Object> cc = gremlin.execute(base + ".cc()");
            @SuppressWarnings("unchecked")
            Map<Object, Long> ccMap = (Map<Object, Long>) cc.get(0);
            assertTrue(ccMap.size() >= 3, "expected a connected neighbourhood, got " + ccMap.size());
            System.out.println("  cc       -> " + ccMap.size() + " vertices, " + ccMap.values().stream().distinct().count() + " components");

            List<Object> pr = gremlin.execute(base + ".pagerank()");
            @SuppressWarnings("unchecked")
            Map<Object, Double> prMap = (Map<Object, Double>) pr.get(0);
            assertEquals(ccMap.size(), prMap.size());
            System.out.println("  pagerank -> " + prMap.size() + " vertices (top: "
                    + prMap.entrySet().stream().sorted(Map.Entry.<Object, Double>comparingByValue().reversed())
                            .limit(3).map(e -> e.getKey() + "=" + String.format("%.3f", e.getValue())).toList() + ")");

            List<Object> lv = gremlin.execute(base + ".louvain()");
            @SuppressWarnings("unchecked")
            Map<Object, Long> lvMap = (Map<Object, Long>) lv.get(0);
            assertEquals(ccMap.size(), lvMap.size());
            System.out.println("  louvain  -> " + lvMap.size() + " vertices, " + lvMap.values().stream().distinct().count() + " communities");
        }
    }

    private static GraphSchema schema() {
        VertexSchema account = new VertexSchema(1, "Account", null)
                .property(new VertexPropertySchema(1, "accountType", null, PropertyType.ScalarType.STRING));
        EdgeSchema payment = new EdgeSchema(1, "Payment", null,
                new EdgeEndpointSchema(1, "source", null, PropertyType.ScalarType.LONG, "Account"),
                new EdgeEndpointSchema(2, "target", null, PropertyType.ScalarType.LONG, "Account"),
                EdgeDirection.DIRECTED)
                .property(new EdgePropertySchema(3, "status", null, PropertyType.ScalarType.STRING));
        return new GraphSchema(1, "algo", null).vertex(account).edge(payment);
    }

    private static void seed(AerospikeVertexStore vertexStore, AerospikeEdgeStore edgeStore, GraphSchema schema) {
        VertexSchema account = schema.vertexSchema("Account");
        EdgeSchema payment = schema.edgeSchema("Payment");
        long t = System.currentTimeMillis();

        int n = 12;
        List<VertexUpsert> vertices = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            vertices.add(new VertexUpsert(String.valueOf(1000 + i), account,
                    Map.of(1, new VertexPropertyValue("savings", t)), t, t));
        }
        List<WriteOutcome> vOut = vertexStore.upsertVertices(vertices);
        assertNoFailures(vOut, "vertices");

        List<EdgeUpsert> edges = new ArrayList<>();
        int seq = 0;
        // cycle
        for (int i = 0; i < n; i++) {
            edges.add(new EdgeUpsert("pay" + seq++, payment,
                    String.valueOf(1000 + i), String.valueOf(1000 + (i + 1) % n),
                    Map.of(3, new EdgePropertyValue("SUCCESS", t, t)), t, t));
        }
        // a few chords
        for (int[] chord : new int[][]{{0, 5}, {2, 8}, {4, 10}}) {
            edges.add(new EdgeUpsert("pay" + seq++, payment,
                    String.valueOf(1000 + chord[0]), String.valueOf(1000 + chord[1]),
                    Map.of(3, new EdgePropertyValue("SUCCESS", t, t)), t, t));
        }
        List<WriteOutcome> eOut = edgeStore.upsertEdges(edges);
        assertNoFailures(eOut, "edges");
    }

    private static void assertNoFailures(List<WriteOutcome> outcomes, String kind) {
        long failed = outcomes.stream().filter(o -> o == WriteOutcome.FAILED).count();
        if (failed > 0) {
            throw new IllegalStateException(kind + " writes failed: " + failed);
        }
    }
}
