package com.pengcheng.phoenixgraph.integration;

import com.aerospike.client.AerospikeClient;
import com.pengcheng.phoenixgraph.query.aerospike.AerospikeGraphQuery;
import com.pengcheng.phoenixgraph.query.cypher.CypherGraphQuery;
import com.pengcheng.phoenixgraph.query.cypher.CypherResult;
import com.pengcheng.phoenixgraph.query.gremlin.GremlinGraphQuery;
import com.pengcheng.phoenixgraph.query.gremlin.GremlinVertex;
import com.pengcheng.phoenixgraph.query.result.Subgraph;
import com.pengcheng.phoenixgraph.query.traversal.NeighborVertexResolver;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The openCypher engine against Aerospike, checked against the Gremlin engine on the
 * same data: both read the same directed payment graph (the deterministic ring plus
 * chords of {@link QueryDataSeeder}, including the {@code PhoneToAccount} edges), so
 * every Cypher answer must equal the equivalent traversal.
 *
 * <p>Skipped when Aerospike is unreachable.
 */
class CypherQueryIntegrationTest {

    /** Own vertex set, so the test never depends on another test's data. */
    private static final String DEFAULT_SET = "cypher_demo_v1";

    @Test
    void cypherMatchesGremlinOnTheSameDirectedGraph() {
        IntegrationConfig config = IntegrationConfig.fromEnv();
        AerospikeClient client = config.connectOrNull();
        assumeTrue(client != null,
                "Aerospike not available at " + config.host() + ":" + config.port() + ", skipping integration test");
        String set = env("AEROSPIKE_CYPHER_SET", DEFAULT_SET);
        try (client) {
            GraphSchema schema = QueryDataSeeder.loadSchema();
            AerospikeVertexStore vertexStore = new AerospikeVertexStore(client, config.namespace(), set);
            AerospikeEdgeStore edgeStore = new AerospikeEdgeStore(client, config.namespace(), set, schema);

            truncate(client, config.namespace(), set);
            QueryDataSeeder.seedVertices(vertexStore, schema);
            QueryDataSeeder.seedEdges(edgeStore, schema);

            AerospikeGraphQuery graphQuery = new AerospikeGraphQuery(vertexStore, edgeStore, schema);
            CypherGraphQuery cypher = new CypherGraphQuery(graphQuery, NeighborVertexResolver.from(schema));
            GremlinGraphQuery gremlin = new GremlinGraphQuery(graphQuery, NeighborVertexResolver.from(schema));

            // 100 -> {101 (p0), 103 (p100)}, 101 -> {102 (p1), 104 (p101)}, phones 300+i -> 100+i
            assertCypherMatchesGremlin(cypher, gremlin,
                    "MATCH (a:Account {id:'100'})-[p:Payment]->(b:Account) RETURN b.id",
                    "g.V('100','Account').out('Payment')");
            assertCypherMatchesGremlin(cypher, gremlin,
                    "MATCH (a:Account {id:'100'})<-[p:Payment]-(b:Account) RETURN b.id",
                    "g.V('100','Account').in('Payment')");
            assertCypherMatchesGremlin(cypher, gremlin,
                    "MATCH (a:Account {id:'100'})-[p:Payment]-(b:Account) RETURN b.id",
                    "g.V('100','Account').both('Payment')");
            assertCypherMatchesGremlin(cypher, gremlin,
                    "MATCH (a:Account {id:'100'})-[:Payment]->(:Account)-[:Payment]->(c:Account) RETURN DISTINCT c.id",
                    "g.V('100','Account').out('Payment').out('Payment').dedup()");
            // *1..2 is the union of one and two hops
            Set<String> oneAndTwoHops = new HashSet<>(ids(cypher.execute(
                    "MATCH (a:Account {id:'100'})-[:Payment]->(c:Account) RETURN DISTINCT c.id", Map.of()), "c.id"));
            oneAndTwoHops.addAll(ids(cypher.execute(
                    "MATCH (a:Account {id:'100'})-[:Payment]->(:Account)-[:Payment]->(c:Account) "
                            + "RETURN DISTINCT c.id", Map.of()), "c.id"));
            assertEquals(oneAndTwoHops, new HashSet<>(ids(cypher.execute(
                    "MATCH (a:Account {id:'100'})-[:Payment*1..2]->(c:Account) RETURN DISTINCT c.id",
                    Map.of()), "c.id")), "*1..2 covers one and two hops");
            // *2..2 only the far end
            assertEquals(List.of("102", "104", "106"), ids(cypher.execute(
                    "MATCH (a:Account {id:'100'})-[:Payment*2..2]->(c:Account) RETURN DISTINCT c.id "
                            + "ORDER BY c.id", Map.of()), "c.id"), "two hops from 100 reach 102, 104 and 106");
            assertCypherMatchesGremlin(cypher, gremlin,
                    "MATCH (a:Account {id:'100'})-[p:Payment]->(b) WHERE p.status IS NOT NULL RETURN DISTINCT b.id",
                    "g.V('100','Account').out('Payment').dedup()");
            assertCypherMatchesGremlin(cypher, gremlin,
                    "MATCH (a:Account {id:'100'})-[:PhoneToAccount]-(ph:Phone) RETURN ph.id",
                    "g.V('100','Account').both('PhoneToAccount')");

            // counts and aggregations have to agree too
            CypherResult counted = cypher.execute(
                    "MATCH (a:Account {id:'100'})-[p:Payment]->(b:Account) RETURN count(*) AS n");
            assertEquals(gremlin.execute("g.V('100','Account').out('Payment').count()").get(0),
                    counted.rows().get(0).get("n"), "count(*) equals the Gremlin count");

            CypherResult grouped = cypher.execute("MATCH (a:Account {id:'100'})-[p:Payment]->(b:Account) "
                    + "RETURN p.status AS status, count(*) AS n ORDER BY status");
            long groupedTotal = grouped.rows().stream()
                    .mapToLong(row -> ((Number) row.get("n")).longValue()).sum();
            assertEquals(2L, groupedTotal, "both outgoing payments are grouped (statuses SUCCESS and PENDING)");
            System.out.println("[cypher] grouped by status: " + grouped.rows());

            // native parameters (the ${name} template form is substituted by QueryService
            // before the query reaches the engine, and is covered by the API tests)
            Map<String, Object> params = Map.of("id", "100");
            assertEquals(List.of("100"), ids(cypher.execute(
                    "MATCH (a:Account {id: $id}) RETURN a.id", params), "a.id"));
            // a numeric literal finds the string keyed record
            assertEquals(List.of("100"), ids(cypher.execute(
                    "MATCH (a:Account {id: 100}) RETURN a.id", Map.of()), "a.id"));

            // the subgraph of a Cypher query is the same induced subgraph the renderer gets
            CypherResult withSubgraph = cypher.executeSubgraph(
                    "MATCH (a:Account {id:'100'})-[p:Payment]->(b:Account) RETURN b.id", Map.of());
            Subgraph subgraph = withSubgraph.subgraph();
            assertEquals(3, subgraph.vertices().size(), "100 plus its two receivers");
            assertEquals(2, subgraph.edges().size(), "p0 and p1");
            assertTrue(subgraph.edges().stream().allMatch(edge -> "Payment".equals(edge.label())));
            System.out.println("[cypher] " + withSubgraph.describe());

            // unanchored queries are rejected with a hint instead of scanning the set
            QueryExceptionAsserts.assertUnanchored(cypher);
        }
    }

    /** Runs the Cypher query and the equivalent Gremlin query and compares the ids. */
    private void assertCypherMatchesGremlin(CypherGraphQuery cypher, GremlinGraphQuery gremlin,
                                            String cypherQuery, String gremlinQuery) {
        String column = cypher.execute(cypherQuery, Map.of()).columns().get(0);
        Set<String> fromCypher = new HashSet<>(ids(cypher.execute(cypherQuery, Map.of()), column));
        Set<String> fromGremlin = new HashSet<>();
        for (Object traverser : gremlin.execute(gremlinQuery)) {
            fromGremlin.add(String.valueOf(((GremlinVertex) traverser).id()));
        }
        assertEquals(fromGremlin, fromCypher, cypherQuery + "  vs  " + gremlinQuery);
    }

    private static List<String> ids(CypherResult result, String column) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> row : result.rows()) {
            out.add(String.valueOf(row.get(column)));
        }
        return out;
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static void truncate(AerospikeClient client, String namespace, String set) {
        try {
            client.truncate(new com.aerospike.client.policy.InfoPolicy(), namespace, set, null);
            Thread.sleep(300);
        } catch (com.aerospike.client.AerospikeException e) {
            // truncate may be disabled on a server; the seeding then upserts on top
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The one assertion that needs a lambda of its own. */
    private static final class QueryExceptionAsserts {
        private QueryExceptionAsserts() {
        }

        static void assertUnanchored(CypherGraphQuery cypher) {
            var error = assertThrows(com.pengcheng.phoenixgraph.query.QueryException.class,
                    () -> cypher.execute("MATCH (a:Account) RETURN a.id", Map.of()));
            assertTrue(error.getMessage().contains("starting vertex"), error.getMessage());
        }
    }
}
