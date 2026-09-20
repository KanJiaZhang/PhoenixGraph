package com.pengcheng.phoenixgraph.integration;

import com.pengcheng.phoenixgraph.query.cypher.CypherGraphQuery;
import com.pengcheng.phoenixgraph.query.cypher.CypherResult;
import com.pengcheng.phoenixgraph.query.gremlin.GremlinGraphQuery;
import com.pengcheng.phoenixgraph.query.result.Subgraph;
import com.pengcheng.phoenixgraph.query.storage.EdgeQueryResult;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
import com.pengcheng.phoenixgraph.query.storage.GraphQuery;
import com.pengcheng.phoenixgraph.query.traversal.NeighborVertexResolver;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.GraphStorageType;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbStoreConfig;
import com.pengcheng.phoenixgraph.storage.rocksdb.edge.RocksDbEdgeGraphQuery;
import com.pengcheng.phoenixgraph.storage.rocksdb.edge.RocksDbEdgeStore;
import com.pengcheng.phoenixgraph.storage.TimeRange;
import com.pengcheng.phoenixgraph.storage.rocksdb.inline.RocksDbEdgeBins;
import com.pengcheng.phoenixgraph.storage.rocksdb.inline.RocksDbInlineGraphQuery;
import com.pengcheng.phoenixgraph.storage.rocksdb.inline.RocksDbInlineStore;
import com.pengcheng.phoenixgraph.storage.TimeRange;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The payment graph served by both RocksDB schemes, queried with Gremlin and with Cypher.
 *
 * <p>The same generator loads both instances, so every query is run twice and the two answers
 * are compared: the storage layout may differ (edges folded into the vertex records versus an
 * edge table per class), but the graph a query sees must not. On top of that the queries the
 * live demo uses are checked against the known shape of the demo data.
 */
class RocksDbStorageIntegrationTest {

    @TempDir
    static Path directory;

    private static GraphSchema inlineSchema;
    private static GraphSchema edgeSchema;
    private static RocksDbInlineStore inlineStore;
    private static RocksDbEdgeStore edgeStore;
    private static GremlinGraphQuery inlineGremlin;
    private static GremlinGraphQuery edgeGremlin;
    private static CypherGraphQuery inlineCypher;
    private static CypherGraphQuery edgeCypher;

    /** The queries every engine runs on both schemes, so their answers can be compared. */
    private static final List<String> GREMLIN_QUERIES = List.of(
            "g.V('135','Account').out('Payment').count()",
            "g.V('135','Account').outE('Payment').values('status')",
            "g.V('135','Account').out('Payment').out('AccountToIP').values('ipAddress')",
            "g.V('1000','Account').out('AccountToPhone').values('cnCode')",
            "g.V('1000','Account').in('AccountToPhone').values('phoneNumber')",
            "g.V('135','Account').both('Payment').both('Payment').limit(3).values('accountStatus')",
            "g.V('135','Account').outE('Payment').limit(2).elementMap()",
            "g.V('135','Account').both().count()",
            "g.V('" + PaymentGraphRocksDbLoader.ipAddressOf(0)
                    + "','IP').out('IPToAccount').out('AccountToPhone').values('phoneNumber')");

    private static final List<String> CYPHER_QUERIES = List.of(
            "MATCH (a:Account {id:'135'})-[p:Payment]->(b:Account) RETURN count(*) AS n",
            "MATCH (a:Account {id:'135'})-[p:Payment]->(b:Account) "
                    + "RETURN p.status AS status, count(*) AS n ORDER BY n DESC",
            "MATCH (a:Account {id:'1000'})-[:AccountToPhone]->(ph:Phone) RETURN ph.cnCode AS cn",
            "MATCH (a:Account {id:'1000'})-[:AccountToIP]->(ip:IP), (ip)-[:IPToAccount]->(b:Account) "
                    + "RETURN ip.ipAddress AS ip, b.id AS id",
            "MATCH (a:Account)-[p:Payment]->(b:Account) WHERE a.id = '135' RETURN b.firstName AS name");

    @BeforeAll
    static void loadBothInstances() {
        inlineSchema = PaymentGraphRocksDbLoader.loadSchema(PaymentGraphRocksDbLoader.INLINE_GRAPH);
        edgeSchema = PaymentGraphRocksDbLoader.loadSchema(PaymentGraphRocksDbLoader.EDGE_GRAPH);
        assertEquals(GraphStorageType.ROCKSDB_INLINE, inlineSchema.storage().type());
        assertEquals(GraphStorageType.ROCKSDB_EDGE, edgeSchema.storage().type());

        PaymentGraphRocksDbLoader.LoadResult inlineLoad = PaymentGraphRocksDbLoader.load(inlineSchema,
                directory.resolve("inline").toString(), true);
        PaymentGraphRocksDbLoader.LoadResult edgeLoad = PaymentGraphRocksDbLoader.load(edgeSchema,
                directory.resolve("edge").toString(), true);
        assertEquals(151, inlineLoad.vertices());
        assertEquals(400, inlineLoad.edges());
        assertEquals(inlineLoad.vertices(), edgeLoad.vertices());
        assertEquals(inlineLoad.edges(), edgeLoad.edges());

        inlineStore = RocksDbInlineStore.open(inlineSchema,
                RocksDbStoreConfig.of(directory.resolve("inline").toString()));
        edgeStore = RocksDbEdgeStore.open(edgeSchema,
                RocksDbStoreConfig.of(directory.resolve("edge").toString()));

        GraphQuery inlineQuery = new RocksDbInlineGraphQuery(inlineStore, inlineSchema);
        GraphQuery edgeQuery = new RocksDbEdgeGraphQuery(edgeStore, edgeSchema);
        inlineGremlin = new GremlinGraphQuery(inlineQuery, NeighborVertexResolver.from(inlineSchema));
        edgeGremlin = new GremlinGraphQuery(edgeQuery, NeighborVertexResolver.from(edgeSchema));
        inlineCypher = new CypherGraphQuery(inlineQuery, NeighborVertexResolver.from(inlineSchema));
        edgeCypher = new CypherGraphQuery(edgeQuery, NeighborVertexResolver.from(edgeSchema));
    }

    @AfterAll
    static void closeStores() {
        if (inlineStore != null) {
            inlineStore.close();
        }
        if (edgeStore != null) {
            edgeStore.close();
        }
    }

    private static String describe(List<Map<String, Object>> rows) {
        return rows.toString();
    }

    // --- the same queries on both schemes ---

    @Test
    void gremlinAnswersAgreeBetweenTheSchemeThatInlinesEdgesAndTheOneThatStoresThemApart() {
        for (String query : GREMLIN_QUERIES) {
            List<Object> inline = inlineGremlin.execute(query);
            List<Object> edge = edgeGremlin.execute(query);
            assertEquals(inline, edge, "query: " + query);
        }
    }

    @Test
    void cypherAnswersAgreeBetweenTheTwoSchemes() {
        for (String query : CYPHER_QUERIES) {
            CypherResult inline = inlineCypher.execute(query);
            CypherResult edge = edgeCypher.execute(query);
            assertEquals(inline.columns(), edge.columns(), "query: " + query);
            assertEquals(describe(inline.rows()), describe(edge.rows()), "query: " + query);
        }
    }

    @Test
    void bothSchemesDrawTheSameSubgraph() {
        Subgraph inline = inlineGremlin.executeSubgraph("g.V('135','Account').both('Payment')");
        Subgraph edge = edgeGremlin.executeSubgraph("g.V('135','Account').both('Payment')");
        assertEquals(inline.vertices().size(), edge.vertices().size());
        assertEquals(inline.edges().size(), edge.edges().size());
        assertEquals(51, inline.vertices().size(), "the hub plus the 50 accounts it pays");
    }

    // --- the demo queries, checked against the known data ---

    @Test
    void gremlinSeesTheDemoGraph() {
        assertEquals(List.of(50L), inlineGremlin.execute("g.V('135','Account').out('Payment').count()"));
        assertEquals(List.of(50L), edgeGremlin.execute("g.V('135','Account').out('Payment').count()"));

        // the hub pays 50 accounts, each of which owns one IP and one phone
        assertEquals(50, inlineGremlin.execute("g.V('135','Account').out('Payment').out('AccountToIP')").size());
        assertEquals(List.of("+86"), edgeGremlin.execute(
                "g.V('1000','Account').out('AccountToPhone').values('cnCode')"));
        assertEquals(List.of(PaymentGraphRocksDbLoader.phoneNumberOf(0)), edgeGremlin.execute(
                "g.V('1000','Account').out('AccountToPhone').values('phoneNumber')"));

        // two hops out and back: the status values of the accounts reached from the hub
        List<Object> statuses = inlineGremlin.execute(
                "g.V('135','Account').both('Payment').both('Payment').limit(1).values('accountStatus')");
        assertEquals(1, statuses.size());
        assertNotNull(statuses.get(0));
        assertTrue(List.of("ACTIVE", "FROZEN", "CLOSED").contains(statuses.get(0)), statuses.toString());
    }

    @Test
    void cypherSeesTheDemoGraph() {
        CypherResult byStatus = edgeCypher.execute(
                "MATCH (a:Account {id:'135'})-[p:Payment]->(b:Account) "
                        + "RETURN p.status AS status, count(*) AS n ORDER BY n DESC");
        assertEquals(List.of("status", "n"), byStatus.columns());
        int total = byStatus.rows().stream().mapToInt(row -> ((Number) row.get("n")).intValue()).sum();
        assertEquals(50, total, byStatus.rows().toString());

        CypherResult account = inlineCypher.execute(
                "MATCH (a:Account {id:'135'}) RETURN a.id AS id, a.firstName AS firstName, "
                        + "a.accountStatus AS status");
        assertEquals(1, account.rows().size());
        assertEquals(135L, account.rows().get(0).get("id"));

        CypherResult twoHop = edgeCypher.execute(
                "MATCH (a:Account {id:'1000'})-[:AccountToIP]->(ip:IP), (ip)-[:IPToAccount]->(b:Account) "
                        + "RETURN ip.ipAddress AS ip, b.id AS id");
        assertEquals(1, twoHop.rows().size());
        assertEquals("10.0.0.0", twoHop.rows().get(0).get("ip"));
    }

    // --- storage-level facts of each scheme ---

    @Test
    void inlineKeepsEdgesInTheBinsOfTheAerospikeLayout() {
        // the hub's outgoing payments sit in bin ve_<payment schema id>_2 (Aerospike: 2 = OUT)
        Map<String, Integer> hubBins = inlineStore.binSizes("Payment", PaymentGraphRocksDbLoader.HUB_ACCOUNT);
        assertEquals(1, hubBins.size(), hubBins.toString());
        assertEquals(50, hubBins.values().iterator().next());
        assertEquals(RocksDbEdgeBins.bin(inlineSchema.edgeSchema("Payment"), RocksDbEdgeBins.DIR_OUT),
                hubBins.keySet().iterator().next());
        // 200 payment edges are stored once per endpoint
        assertEquals(400L, inlineStore.edgeCount("Payment"));
        // the vertex tables hold all 151 vertices
        assertEquals(1L + 50L, inlineStore.vertices().count("Account"));
        assertEquals(50L, inlineStore.vertices().count("IP"));
        assertEquals(50L, inlineStore.vertices().count("Phone"));
    }

    @Test
    void edgeStoresOneRowPerEndpointAndReadsTimeWindows() {
        assertEquals(400L, edgeStore.edgeRowCount("Payment"));
        List<EdgeQueryResult> out = new RocksDbEdgeGraphQuery(edgeStore, edgeSchema)
                .readEdges(PaymentGraphRocksDbLoader.HUB_ACCOUNT, "Account", "Payment", EdgeSide.OUT, List.of());
        assertEquals(50, out.size());
        // account 1000 receives the hub's payment plus the three ring payments that point at it
        assertEquals(4, new RocksDbEdgeGraphQuery(edgeStore, edgeSchema)
                .readEdges(1_000L, "Account", "Payment", EdgeSide.IN, List.of()).size());

        // the demo edges are created around BASE_TIME, so a window before it is empty
        RocksDbEdgeGraphQuery query = new RocksDbEdgeGraphQuery(edgeStore, edgeSchema);
        long base = 1_700_000_000_000L;
        assertEquals(50, query.readEdgesWithin(PaymentGraphRocksDbLoader.HUB_ACCOUNT, "Account", "Payment",
                EdgeSide.OUT, TimeRange.of(base, base + 1_000L), List.of()).size());
        assertTrue(query.readEdgesWithin(PaymentGraphRocksDbLoader.HUB_ACCOUNT, "Account", "Payment",
                EdgeSide.OUT, TimeRange.of(base - 10_000L, base - 1L), List.of()).isEmpty());
        // and the keys are spread over the configured regions
        assertTrue(edgeStore.regionsUsed("Payment") > 50, "targets should cover many regions");
    }

    @Test
    void bothSchemesReadTheSameEdgesWithTheSameProperties() {
        GraphQuery inline = new RocksDbInlineGraphQuery(inlineStore, inlineSchema);
        GraphQuery edge = new RocksDbEdgeGraphQuery(edgeStore, edgeSchema);
        List<EdgeQueryResult> inlineEdges = inline.readEdges(1_000L, "Account", "Payment", EdgeSide.OUT,
                List.of("status", "amount"));
        List<EdgeQueryResult> edgeEdges = edge.readEdges(1_000L, "Account", "Payment", EdgeSide.OUT,
                List.of("status", "amount"));
        Map<String, Object> inlineByEdge = new LinkedHashMap<>();
        for (EdgeQueryResult result : inlineEdges) {
            inlineByEdge.put(result.edgeId(), result.properties());
        }
        Map<String, Object> edgeByEdge = new LinkedHashMap<>();
        for (EdgeQueryResult result : edgeEdges) {
            edgeByEdge.put(result.edgeId(), result.properties());
        }
        assertEquals(inlineByEdge, edgeByEdge);
        assertEquals(3, inlineEdges.size(), "the ring gives every account three outgoing payments");
        for (EdgeQueryResult result : inlineEdges) {
            assertEquals(EdgeSide.OUT, result.side());
            assertEquals(2, result.properties().size(), "the projection returns exactly status and amount");
        }
    }

    @Test
    void batchedAndSingleReadsAgreeOnEveryScheme() {
        List<Object> ids = new ArrayList<>();
        for (int i = 0; i < PaymentGraphRocksDbLoader.ACCOUNT_COUNT; i++) {
            ids.add(PaymentGraphRocksDbLoader.FIRST_ACCOUNT + i);
        }
        GraphQuery inline = new RocksDbInlineGraphQuery(inlineStore, inlineSchema);
        GraphQuery edge = new RocksDbEdgeGraphQuery(edgeStore, edgeSchema);
        for (GraphQuery query : List.of(inline, edge)) {
            List<List<EdgeQueryResult>> batched = query.readEdgesBatch("Payment", EdgeSide.BOTH, ids,
                    List.of("status"));
            assertEquals(ids.size(), batched.size());
            for (int i = 0; i < ids.size(); i++) {
                assertEquals(query.readEdges(ids.get(i), "Account", "Payment", EdgeSide.BOTH, List.of("status")),
                        batched.get(i), "vertex " + ids.get(i));
            }
        }
    }
}
