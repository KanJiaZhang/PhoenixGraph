package com.pengcheng.phoenixgraph.query.cypher;

import com.pengcheng.phoenixgraph.query.QueryException;
import com.pengcheng.phoenixgraph.query.storage.EdgeQueryResult;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
import com.pengcheng.phoenixgraph.query.storage.GraphQuery;
import com.pengcheng.phoenixgraph.query.storage.VertexQueryResult;
import com.pengcheng.phoenixgraph.query.traversal.NeighborVertexResolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Cypher engine over an in-memory {@link GraphQuery}.
 *
 * <p>The fixture is a small payment graph:
 * <pre>
 *   100 --Payment p0-->  101 --Payment p2-->  103
 *   100 --Payment p1-->  102
 *   300 --PhoneToAccount pa0--> 100
 * </pre>
 * with amounts 10/20/30, statuses SUCCESS/PENDING/SUCCESS and account types
 * savings/checking/savings.
 */
class CypherGraphQueryTest {

    private final FakeGraph graph = new FakeGraph();
    private final CypherGraphQuery cypher = new CypherGraphQuery(graph, NeighborVertexResolver.of(
            Map.of("Payment", "Account", "PhoneToAccount", "Account"),
            Map.of("Payment", "Account", "PhoneToAccount", "Phone")));

    @Test
    void returnsTheStartingVertex() {
        CypherResult result = cypher.execute(
                "MATCH (a:Account {id: '100'}) RETURN a.id, a.accountType, a.accountStatus");

        assertEquals(List.of("a.id", "a.accountType", "a.accountStatus"), result.columns());
        assertEquals(1, result.rows().size());
        assertEquals(Map.of("a.id", "100", "a.accountType", "savings", "a.accountStatus", "ACTIVE"),
                result.rows().get(0));
    }

    @Test
    void startsFromAWhereEqualityToo() {
        CypherResult result = cypher.execute(
                "MATCH (a:Account) WHERE a.id = '100' RETURN a.accountType");
        assertEquals(List.of(Map.of("a.accountType", "savings")), result.rows());
    }

    @Test
    void followsOutgoingRelationships() {
        CypherResult result = cypher.execute("MATCH (a:Account {id:'100'})-[p:Payment]->(b:Account) "
                + "RETURN b.id AS receiver, p.amount, p.status ORDER BY p.amount DESC");

        assertEquals(List.of("receiver", "p.amount", "p.status"), result.columns());
        assertEquals(2, result.rows().size());
        assertEquals(Map.of("receiver", "102", "p.amount", 20L, "p.status", "PENDING"), result.rows().get(0));
        assertEquals(Map.of("receiver", "101", "p.amount", 10L, "p.status", "SUCCESS"), result.rows().get(1));
    }

    @Test
    void followsIncomingAndUndirectedRelationships() {
        assertEquals(List.of("100"), ids(cypher.execute(
                "MATCH (a:Account {id:'101'})<-[p:Payment]-(b:Account) RETURN b.id"), "b.id"));
        // undirected matches both ways: 100 -> 101 and 101 -> 103
        assertEquals(Set.of("100", "103"), Set.copyOf(ids(cypher.execute(
                "MATCH (a:Account {id:'101'})-[p:Payment]-(b:Account) RETURN b.id"), "b.id")));
        // and a relationship pattern with no type at all
        assertEquals(Set.of("100", "103"), Set.copyOf(ids(cypher.execute(
                "MATCH (a:Account {id:'101'})--(b) RETURN b.id"), "b.id")));
    }

    @Test
    void walksTwoHopsAndVariableLengthPaths() {
        assertEquals(List.of("103"), ids(cypher.execute(
                "MATCH (a:Account {id:'100'})-[:Payment]->(:Account)-[:Payment]->(c:Account) RETURN c.id"),
                "c.id"));
        // *1..2 finds the direct receivers and the one after them
        assertEquals(Set.of("101", "102", "103"), Set.copyOf(ids(cypher.execute(
                "MATCH (a:Account {id:'100'})-[:Payment*1..2]->(c:Account) RETURN c.id"), "c.id")));
        // *2..2 only the far ones
        assertEquals(List.of("103"), ids(cypher.execute(
                "MATCH (a:Account {id:'100'})-[:Payment*2..2]->(c:Account) RETURN c.id"), "c.id"));
    }

    @Test
    void filtersWithWhere() {
        assertEquals(List.of("102"), ids(cypher.execute("MATCH (a:Account {id:'100'})-[p:Payment]->(b:Account) "
                + "WHERE p.amount > 10 AND p.status = 'PENDING' RETURN b.id"), "b.id"));
        assertEquals(List.of("102"), ids(cypher.execute("MATCH (a:Account {id:'100'})-[p:Payment]->(b:Account) "
                + "WHERE p.status IN ['PENDING', 'FAILED'] RETURN b.id"), "b.id"));
        assertEquals(2, cypher.execute("MATCH (a:Account {id:'100'})-[p:Payment]->(b:Account) "
                + "WHERE b.accountType IS NOT NULL RETURN b.id").rows().size());
        assertEquals(List.of("101"), ids(cypher.execute("MATCH (a:Account {id:'100'})-[p:Payment]->(b:Account) "
                + "WHERE b.accountStatus STARTS WITH 'ACT' RETURN b.id"), "b.id"));
        // NOT and OR
        assertEquals(Set.of("101", "102"), Set.copyOf(ids(cypher.execute(
                "MATCH (a:Account {id:'100'})-[p:Payment]->(b:Account) "
                        + "WHERE NOT p.status = 'FAILED' RETURN b.id"), "b.id")));
        assertEquals(0, cypher.execute("MATCH (a:Account {id:'100'})-[p:Payment]->(b:Account) "
                + "WHERE b.accountStatus = 'CLOSED' RETURN b.id").rows().size());
    }

    @Test
    void groupsAndAggregates() {
        CypherResult result = cypher.execute("MATCH (a:Account {id:'100'})-[p:Payment]->(b:Account) "
                + "RETURN p.status AS status, count(*) AS n, sum(p.amount) AS total "
                + "ORDER BY n DESC, status");

        assertEquals(List.of("status", "n", "total"), result.columns());
        assertEquals(2, result.rows().size());
        // both statuses appear once, so the secondary key (status ascending) decides
        assertEquals(Map.of("status", "PENDING", "n", 1L, "total", 20.0), result.rows().get(0));
        assertEquals(Map.of("status", "SUCCESS", "n", 1L, "total", 10.0), result.rows().get(1));

        // count over everything, and min/max/avg/collect
        assertEquals(Map.of("count(*)", 2L), cypher.execute("MATCH (a:Account {id:'100'})-[p:Payment]->(b) "
                + "RETURN count(*)").rows().get(0));
        Map<String, Object> aggregated = cypher.execute("MATCH (a:Account {id:'100'})-[p:Payment]->(b) "
                + "RETURN min(p.amount) AS lo, max(p.amount) AS hi, avg(p.amount) AS mean, "
                + "collect(b.id) AS receivers").rows().get(0);
        assertEquals(10L, aggregated.get("lo"));
        assertEquals(20L, aggregated.get("hi"));
        assertEquals(15.0, aggregated.get("mean"));
        assertEquals(List.of("101", "102"), aggregated.get("receivers"));
    }

    @Test
    void supportsDistinctSkipAndLimit() {
        CypherResult distinct = cypher.execute("MATCH (a:Account {id:'100'})-[p:Payment]->(b:Account) "
                + "RETURN DISTINCT p.status");
        assertEquals(2, distinct.rows().size());

        assertEquals(1, cypher.execute("MATCH (a:Account {id:'100'})-[p:Payment]->(b:Account) "
                + "RETURN b.id ORDER BY b.id SKIP 1 LIMIT 1").rows().size());
        assertEquals("102", cypher.execute("MATCH (a:Account {id:'100'})-[p:Payment]->(b:Account) "
                + "RETURN b.id ORDER BY b.id SKIP 1 LIMIT 1").rows().get(0).get("b.id"));
    }

    @Test
    void returnsWholeElementsAndEveryVariable() {
        Map<String, Object> element = cypher.execute("MATCH (a:Account {id:'100'})-[p:Payment]->(b:Account) "
                + "RETURN p ORDER BY p.amount DESC LIMIT 1").rows().get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> relationship = (Map<String, Object>) element.get("p");
        assertEquals("Payment", relationship.get("type"));
        assertEquals("p1", relationship.get("id"), "the largest outgoing payment of 100");
        assertEquals("100", relationship.get("source"));
        assertEquals("102", relationship.get("target"));
        // and the smallest one the other way round, so ORDER BY on an element really sorts
        Map<String, Object> smallest = cypher.execute("MATCH (a:Account {id:'100'})-[p:Payment]->(b) "
                + "RETURN p ORDER BY p.amount ASC LIMIT 1").rows().get(0);
        assertEquals("p0", ((Map<?, ?>) smallest.get("p")).get("id"));

        CypherResult all = cypher.execute("MATCH (a:Phone {id:'300'})-[r:PhoneToAccount]->(b:Account) RETURN *");
        assertEquals(List.of("a", "r", "b"), all.columns());
        assertTrue(((Map<?, ?>) all.rows().get(0).get("a")).get("id").equals("300"));
        assertTrue(((Map<?, ?>) all.rows().get(0).get("b")).get("id").equals("100"));
    }

    @Test
    void joinsTwoPatternsOnASharedVariable() {
        // the phone of 100 and the accounts 100 pays
        CypherResult result = cypher.execute(
                "MATCH (ph:Phone)-[:PhoneToAccount]->(a:Account {id:'100'}), (a)-[p:Payment]->(b:Account) "
                        + "RETURN ph.id AS phone, b.id AS receiver ORDER BY receiver");

        assertEquals(2, result.rows().size());
        assertEquals(Map.of("phone", "300", "receiver", "101"), result.rows().get(0));
        assertEquals(Map.of("phone", "300", "receiver", "102"), result.rows().get(1));
    }

    @Test
    void substitutesParameters() {
        CypherResult result = cypher.execute("MATCH (a:Account {id: $id})-[p:Payment]->(b:Account) "
                + "WHERE p.amount >= $min RETURN b.id ORDER BY p.amount DESC",
                Map.of("id", "100", "min", 20));

        assertEquals(List.of("102"), ids(result, "b.id"));
        assertEquals(List.of("102"), ids(cypher.withParameters(Map.of("id", "100", "min", 20))
                .execute("MATCH (a:Account {id: $id})-[p:Payment]->(b:Account) "
                        + "WHERE p.amount >= $min RETURN b.id ORDER BY p.amount DESC"), "b.id"));

        QueryException missing = assertThrows(QueryException.class,
                () -> cypher.execute("MATCH (a:Account {id: $id}) RETURN a.id"));
        assertTrue(missing.getMessage().contains("$id"), missing.getMessage());
    }

    @Test
    void collectsTheVisitedSubgraph() {
        CypherResult result = cypher.executeSubgraph("MATCH (a:Account {id:'100'})-[p:Payment]->(b:Account) "
                + "RETURN b.id", Map.of());

        assertEquals(2, result.rows().size());
        assertEquals(3, result.subgraph().vertices().size(), "100, 101 and 102");
        assertEquals(2, result.subgraph().edges().size(), "p0 and p1");
        Set<Object> vertexIds = new java.util.HashSet<>();
        result.subgraph().vertices().forEach(vertex -> vertexIds.add(vertex.id()));
        assertEquals(Set.of("100", "101", "102"), vertexIds);
        // an ordered query keeps the subgraph in step
        assertTrue(result.subgraph().edges().stream().allMatch(edge -> "Payment".equals(edge.label())));
    }

    @Test
    void reportsUnsupportedAndUnanchoredQueries() {
        QueryException unanchored = assertThrows(QueryException.class,
                () -> cypher.execute("MATCH (a:Account) RETURN a.id"));
        assertTrue(unanchored.getMessage().contains("starting vertex"), unanchored.getMessage());

        QueryException unlabeled = assertThrows(QueryException.class,
                () -> cypher.execute("MATCH (a {id:'100'}) RETURN a.id"));
        assertTrue(unlabeled.getMessage().contains("label"), unlabeled.getMessage());

        // an unknown id is an empty result rather than an error
        assertEquals(0, cypher.execute("MATCH (a:Account {id:'999'}) RETURN a.id").rows().size());
    }

    private static List<Object> ids(CypherResult result, String column) {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> row : result.rows()) {
            out.add(row.get(column));
        }
        return out;
    }

    /** A tiny in-memory graph: three accounts, one phone, three payment edges, one phone edge. */
    private static final class FakeGraph implements GraphQuery {

        private final Map<String, Map<String, Object>> accounts = new LinkedHashMap<>();
        private final Map<String, Map<String, Object>> phones = new LinkedHashMap<>();
        private final Map<String, List<EdgeQueryResult>> edges = new LinkedHashMap<>();

        private FakeGraph() {
            accounts.put("100", Map.of("accountType", "savings", "accountStatus", "ACTIVE"));
            accounts.put("101", Map.of("accountType", "checking", "accountStatus", "ACTIVE"));
            accounts.put("102", Map.of("accountType", "savings", "accountStatus", "FROZEN"));
            accounts.put("103", Map.of("accountType", "savings", "accountStatus", "ACTIVE"));
            phones.put("300", Map.of("phoneNumber", "138-6000"));

            payment("100", "101", "p0", 10L, "SUCCESS");
            payment("100", "102", "p1", 20L, "PENDING");
            payment("101", "103", "p2", 30L, "SUCCESS");
            edge("300", "Phone", "PhoneToAccount", "pa0", EdgeSide.OUT, "100", "Account",
                    Map.of("linkedAt", 5L));
        }

        private void payment(String from, String to, String id, long amount, String status) {
            edge(from, "Account", "Payment", id, EdgeSide.OUT, to, "Account",
                    Map.of("amount", amount, "status", status, "currency", "USD"));
        }

        /** Stores one directed edge on both endpoints (like the storage layer does). */
        private void edge(String from, String fromLabel, String type, String id, EdgeSide side, String to,
                          String toLabel, Map<String, Object> properties) {
            edges.computeIfAbsent(fromLabel + "|" + from + "|" + type + "|OUT", ignored -> new ArrayList<>())
                    .add(new EdgeQueryResult(type, id, EdgeSide.OUT, to, properties, 1L, 2L));
            edges.computeIfAbsent(toLabel + "|" + to + "|" + type + "|IN", ignored -> new ArrayList<>())
                    .add(new EdgeQueryResult(type, id, EdgeSide.IN, from, properties, 1L, 2L));
        }

        @Override
        public VertexQueryResult readVertex(Object vertexId, String vertexName, List<String> propertyNames) {
            Map<String, Map<String, Object>> source = "Phone".equals(vertexName) ? phones : accounts;
            Map<String, Object> properties = source.get(String.valueOf(vertexId));
            return properties == null ? null
                    : new VertexQueryResult(vertexName, vertexId, new LinkedHashMap<>(properties), 1L, 2L);
        }

        @Override
        public List<EdgeQueryResult> readEdges(Object vertexId, String vertexName, String edgeName,
                                               EdgeSide direction, List<String> propertyNames) {
            List<EdgeQueryResult> out = new ArrayList<>();
            if (direction == EdgeSide.OUT || direction == EdgeSide.BOTH) {
                out.addAll(edges.getOrDefault(vertexName + "|" + vertexId + "|" + edgeName + "|OUT",
                        List.of()));
            }
            if (direction == EdgeSide.IN || direction == EdgeSide.BOTH) {
                out.addAll(edges.getOrDefault(vertexName + "|" + vertexId + "|" + edgeName + "|IN",
                        List.of()));
            }
            return out;
        }

        @Override
        public List<List<EdgeQueryResult>> readAllEdgesBatch(List<Object> vertexIds, EdgeSide direction,
                                                             List<String> propertyNames) {
            List<List<EdgeQueryResult>> out = new ArrayList<>(vertexIds.size());
            for (Object vertexId : vertexIds) {
                List<EdgeQueryResult> all = new ArrayList<>();
                for (String type : List.of("Payment", "PhoneToAccount")) {
                    all.addAll(readEdges(vertexId, "Account", type, direction, propertyNames));
                    all.addAll(readEdges(vertexId, "Phone", type, direction, propertyNames));
                }
                out.add(all);
            }
            return out;
        }
    }
}
