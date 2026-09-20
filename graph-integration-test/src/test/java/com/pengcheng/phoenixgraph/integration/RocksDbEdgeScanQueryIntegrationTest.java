package com.pengcheng.phoenixgraph.integration;

import com.pengcheng.phoenixgraph.gemini.GeminiCluster;
import com.pengcheng.phoenixgraph.query.QueryException;
import com.pengcheng.phoenixgraph.query.gremlin.GremlinGraphQuery;
import com.pengcheng.phoenixgraph.query.storage.EdgeQueryResult;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
import com.pengcheng.phoenixgraph.query.storage.ScannedEdge;
import com.pengcheng.phoenixgraph.query.storage.VertexQueryResult;
import com.pengcheng.phoenixgraph.query.traversal.NeighborVertexResolver;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbStoreConfig;
import com.pengcheng.phoenixgraph.storage.rocksdb.edge.RocksDbEdgeGraphQuery;
import com.pengcheng.phoenixgraph.storage.rocksdb.edge.RocksDbEdgeStore;
import com.pengcheng.phoenixgraph.storage.rocksdb.inline.RocksDbInlineGraphQuery;
import com.pengcheng.phoenixgraph.storage.rocksdb.inline.RocksDbInlineStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The analytical half of the two RocksDB schemes: a query that starts from a whole type.
 *
 * <p>{@code g.E('Payment').pagerank(DIST)} and {@code g.V('Account').filter(...).bothE('Payment')
 * .cc(DIST)} are questions about a class, not about a record - so they are answered by the store
 * whose tables are ordered ranges (RocksDB-Edge) and refused, with a message that says why, by the
 * one that folds its edges into records (RocksDB-Inline, like Aerospike, answers by key).
 *
 * <p>The graph is the payment demo the other integration tests use, so the scan can be checked
 * against the keyed reads of the same data: a scan of the payment class has to find exactly the edges
 * the per-vertex reads find.
 */
class RocksDbEdgeScanQueryIntegrationTest {

    @TempDir
    static Path directory;

    private static GraphSchema edgeSchema;
    private static GraphSchema inlineSchema;
    private static RocksDbEdgeStore edgeStore;
    private static RocksDbInlineStore inlineStore;
    private static RocksDbEdgeGraphQuery edgeQuery;
    private static GremlinGraphQuery edgeGremlin;
    private static GremlinGraphQuery inlineGremlin;
    private static GeminiCluster cluster;

    private static final String ACTIVE = "accountStatus.equals('ACTIVE')";

    @BeforeAll
    static void loadAndOpen() {
        edgeSchema = PaymentGraphRocksDbLoader.loadSchema(PaymentGraphRocksDbLoader.EDGE_GRAPH);
        inlineSchema = PaymentGraphRocksDbLoader.loadSchema(PaymentGraphRocksDbLoader.INLINE_GRAPH);
        PaymentGraphRocksDbLoader.load(edgeSchema, directory.resolve("edge").toString(), true);
        PaymentGraphRocksDbLoader.load(inlineSchema, directory.resolve("inline").toString(), true);

        edgeStore = RocksDbEdgeStore.open(edgeSchema,
                RocksDbStoreConfig.of(directory.resolve("edge").toString()));
        inlineStore = RocksDbInlineStore.open(inlineSchema,
                RocksDbStoreConfig.of(directory.resolve("inline").toString()));
        edgeQuery = new RocksDbEdgeGraphQuery(edgeStore, edgeSchema);

        NeighborVertexResolver edgeResolver = NeighborVertexResolver.from(edgeSchema);
        edgeGremlin = new GremlinGraphQuery(edgeQuery, edgeResolver);
        inlineGremlin = new GremlinGraphQuery(new RocksDbInlineGraphQuery(inlineStore, inlineSchema),
                NeighborVertexResolver.from(inlineSchema));
        cluster = GeminiCluster.local().start();
        edgeGremlin.geminiCluster(cluster);
    }

    @AfterAll
    static void close() {
        if (cluster != null) {
            cluster.close();
        }
        if (edgeStore != null) {
            edgeStore.close();
        }
        if (inlineStore != null) {
            inlineStore.close();
        }
    }

    // --- the scan agrees with the keyed reads ---

    @Test
    void aVertexScanFindsEveryVertexThePointReadsFind() {
        List<VertexQueryResult> accounts = edgeQuery.scanVertices("Account", List.of("accountStatus"));
        assertEquals(51, accounts.size(), "the hub and the 50 accounts it pays");
        assertEquals(51L, edgeQuery.countScanVertices("Account"));
        assertEquals(151L, edgeQuery.countScanVertices("IP") + edgeQuery.countScanVertices("Phone") + 51L,
                "the demo has 151 vertices in total");

        for (VertexQueryResult account : accounts) {
            VertexQueryResult point = edgeQuery.readVertex(account.vertexId(), "Account",
                    List.of("accountStatus"));
            assertEquals(point.properties(), account.properties(), "id " + account.vertexId());
            assertEquals(point.createdTime(), account.createdTime());
        }
    }

    @Test
    void anEdgeScanFindsExactlyTheEdgesTheKeyedReadsFind() {
        List<ScannedEdge> scanned = edgeQuery.scanEdges("Payment", List.of("status", "amount"));

        // the same edges, read the transactional way: the payments of every account, both sides
        List<Object> accountIds = new ArrayList<>();
        for (VertexQueryResult account : edgeQuery.scanVertices("Account", List.of())) {
            accountIds.add(account.vertexId());
        }
        Set<String> keyed = new LinkedHashSet<>();
        for (Object id : accountIds) {
            for (EdgeQueryResult edge : edgeQuery.readEdges(id, "Account", "Payment", EdgeSide.OUT, List.of())) {
                keyed.add(id + "->" + edge.otherVertexId());
            }
        }
        Set<String> fromScan = new LinkedHashSet<>();
        for (ScannedEdge edge : scanned) {
            fromScan.add(edge.source() + "->" + edge.target());
        }
        assertEquals(keyed, fromScan, "a scan of the class and the per-vertex reads see one graph");
        assertEquals(scanned.size(), edgeQuery.countScanEdges("Payment"));
        assertEquals(scanned.size(), scanned.stream().map(ScannedEdge::edgeId).distinct().count(),
                "every edge is read once");
    }

    // --- the two query forms the analytical store exists for ---

    @Test
    void aClassOfEdgesCanBeWalkedAndCounted() {
        List<Object> payments = edgeGremlin.execute("g.E('Payment').count()");
        assertEquals(1, payments.size());
        assertTrue(((Number) payments.get(0)).longValue() > 100, "the demo has hundreds of payments");

        List<Object> statuses = edgeGremlin.execute("g.E('Payment').values('status')");
        assertTrue(statuses.stream().allMatch(s -> List.of("SUCCESS", "PENDING", "FAILED").contains(s)),
                "every scanned payment carries a status: " + statuses.size());
    }

    @Test
    void aWholeVertexTypeCanBeFiltered() {
        // the scan reads the records, and the filter runs on them
        List<Object> active = edgeGremlin.execute("g.V('Account').filter(" + ACTIVE + ").count()");
        assertEquals(1, active.size());
        long activeCount = ((Number) active.get(0)).longValue();
        assertTrue(activeCount > 0 && activeCount < 51, "some accounts are active: " + activeCount);

        // ... and it is the same answer the keyed reads give
        long keyed = 0;
        for (VertexQueryResult account : edgeQuery.scanVertices("Account", List.of("accountStatus"))) {
            if ("ACTIVE".equals(account.properties().get("accountStatus"))) {
                keyed++;
            }
        }
        assertEquals(keyed, activeCount);
    }

    // --- the distributed algorithms on a scan-seeded traversal ---

    @Test
    void pageRankRunsOnAClassOfEdges() {
        Map<?, ?> values = firstMap(edgeGremlin.execute("g.E('Payment').pagerank(DIST, 10)"));

        assertEquals(51, values.size(), "both endpoints of every payment: " + values.size());
        for (Object value : values.values()) {
            assertTrue(((Number) value).doubleValue() > 0);
        }
        // a PageRank score is a share of one, so the scores have to add up. PageRank rewards being paid
        // rather than paying, so the hub is not the highest scorer here - it is the baseline of a vertex
        // nothing points at, (1 - d) / |V|, plus nothing.
        double sum = values.values().stream().mapToDouble(v -> ((Number) v).doubleValue()).sum();
        assertTrue(sum > 0.9 && sum < 1.1, "the scores add up to about one, got " + sum);
        double baseline = (1 - 0.85) / 51;
        assertTrue(number(values, "135").doubleValue() >= baseline - 1e-9,
                "the hub scores at least the baseline: " + number(values, "135"));
        assertTrue(values.values().stream().mapToDouble(v -> ((Number) v).doubleValue()).max().orElseThrow()
                        > baseline,
                "a vertex that is paid ranks above the baseline");

        // the same run, seeded with the vertices the scan itself found: one graph, so one answer.
        // The two seedings spell the ids differently (the scan reports the stored type, the query
        // text is text), so the scores are compared by the text of the id.
        Map<?, ?> bySeed = firstMap(edgeGremlin.execute(
                "g.V('" + accountIds().stream().map(String::valueOf).collect(java.util.stream.Collectors
                        .joining(",")) + "','Account').pagerank(DIST, 10)"));
        Map<String, Object> seededByText = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : bySeed.entrySet()) {
            seededByText.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        assertEquals(bySeed.size(), values.size());
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            assertEquals(((Number) seededByText.get(String.valueOf(entry.getKey()))).doubleValue(),
                    ((Number) entry.getValue()).doubleValue(), 1e-6, "score of " + entry.getKey());
        }
    }

    @Test
    void connectedComponentsRunsFromAFilteredVertexScan() {
        // the query shape the class-based entry point is for: a type, a condition on it, and the
        // payments those vertices make - the accounts themselves are in the answer
        Map<?, ?> values = firstMap(edgeGremlin.execute(
                "g.V('Account').filter(" + ACTIVE + ").bothE('Payment').cc(DIST)"));

        List<Object> active = new ArrayList<>();
        for (VertexQueryResult account : edgeQuery.scanVertices("Account", List.of("accountStatus"))) {
            if ("ACTIVE".equals(account.properties().get("accountStatus"))) {
                active.add(account.vertexId());
            }
        }
        assertTrue(values.keySet().containsAll(active),
                "every active account is in the answer: " + active.size() + " of " + values.size());
        assertEquals(1L, values.values().stream().distinct().count(),
                "the demo's payments are one component: " + values.size() + " vertices");
    }

    @Test
    void shortestPathsRunFromAClassOfEdges() {
        Map<?, ?> values = firstMap(edgeGremlin.execute("g.E('Payment').sssp(DIST, '135')"));

        assertEquals(51, values.size());
        assertEquals(0.0, number(values, "135").doubleValue(), "the hub is the root");
        long reachedInOne = values.values().stream()
                .filter(v -> ((Number) v).doubleValue() == 1.0)
                .count();
        assertTrue(reachedInOne > 1, "the hub pays many accounts: " + reachedInOne);
    }

    // --- the store that cannot scan says so ---

    @Test
    void theInlineSchemeRefusesAClassQuestionWithAMessageThatSaysWhy() {
        assertTrue(inlineStore.columnFamilies().stream().anyMatch(f -> f.startsWith("vertex:")),
                "the inline scheme is the keyed one here");
        QueryException failure = assertThrows(QueryException.class,
                () -> inlineGremlin.execute("g.E('Payment').count()"));
        assertTrue(failure.getMessage().contains("starts from a whole type"), failure.getMessage());
        assertTrue(failure.getMessage().contains("by id"), failure.getMessage());

        // a query with ids is answered by both schemes: only the class question needs a scan
        assertEquals(edgeGremlin.execute("g.V('135','Account').out('Payment').count()"),
                inlineGremlin.execute("g.V('135','Account').out('Payment').count()"));
    }

    // --- helpers ---

    private static List<Object> accountIds() {
        List<Object> ids = new ArrayList<>();
        for (VertexQueryResult account : edgeQuery.scanVertices("Account", List.of())) {
            ids.add(account.vertexId());
        }
        return ids;
    }

    private static Map<?, ?> firstMap(List<Object> answer) {
        assertEquals(1, answer.size(), "an algorithm step answers with one map");
        return assertInstanceOf(Map.class, answer.get(0));
    }

    /** A value of an answer, looked up by the id as the query spells it (ids keep their stored type). */
    private static Number number(Map<?, ?> values, String id) {
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (id.equals(String.valueOf(entry.getKey()))) {
                return assertInstanceOf(Number.class, entry.getValue());
            }
        }
        throw new AssertionError("'" + id + "' is not in " + values.keySet());
    }
}
