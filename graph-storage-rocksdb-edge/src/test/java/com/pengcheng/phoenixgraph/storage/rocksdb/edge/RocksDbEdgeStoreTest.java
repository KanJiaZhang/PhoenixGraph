package com.pengcheng.phoenixgraph.storage.rocksdb.edge;

import com.pengcheng.phoenixgraph.query.storage.EdgeQueryResult;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
import com.pengcheng.phoenixgraph.query.storage.VertexQueryResult;
import com.pengcheng.phoenixgraph.schema.EdgeDirection;
import com.pengcheng.phoenixgraph.schema.EdgeEndpointSchema;
import com.pengcheng.phoenixgraph.schema.EdgePropertySchema;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.MetaPropertySchema;
import com.pengcheng.phoenixgraph.schema.PropertyType;
import com.pengcheng.phoenixgraph.schema.VertexPropertySchema;
import com.pengcheng.phoenixgraph.schema.VertexSchema;
import com.pengcheng.phoenixgraph.storage.WriteOutcome;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbEdgeProperty;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbEdgeUpsert;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbKeys;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbPropertyValue;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbStoreConfig;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbVertexUpsert;
import com.pengcheng.phoenixgraph.storage.TimeRange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The RocksDB-Edge scheme: a table per vertex type plus an edge table per edge class keyed by
 * hash prefix, direction, vertex id, creation time and edge id; no degree limits, but time
 * windows are a seek instead of a filter.
 */
class RocksDbEdgeStoreTest {

    private static final int ACCOUNT_STATUS = 1;
    private static final int PAYMENT_STATUS = 3;
    private static final int PAYMENT_AMOUNT = 4;
    private static final int KNOWS_SINCE = 3; // 0 id, 1 source, 2 target are taken by the endpoints

    @TempDir
    Path directory;

    private RocksDbEdgeStore store;

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
            store = null;
        }
    }

    // --- schema + fixtures ---

    private static VertexSchema accountSchema() {
        return new VertexSchema(1, "Account", "an account")
                .property(new VertexPropertySchema(ACCOUNT_STATUS, "accountStatus", null,
                        PropertyType.ScalarType.STRING));
    }

    private static EdgeSchema paymentSchema() {
        return new EdgeSchema(1, "Payment", "a payment",
                new EdgePropertySchema(0, "id", null, PropertyType.ScalarType.LONG),
                new EdgeEndpointSchema(1, "source", null, PropertyType.ScalarType.LONG, "Account"),
                new EdgeEndpointSchema(2, "target", null, PropertyType.ScalarType.LONG, "Account"),
                MetaPropertySchema.createdTime(), MetaPropertySchema.lastUpdatedTime(),
                EdgeDirection.DIRECTED, 0, 0, 0)
                .property(new EdgePropertySchema(PAYMENT_STATUS, "status", null, PropertyType.ScalarType.STRING))
                .property(new EdgePropertySchema(PAYMENT_AMOUNT, "amount", null, PropertyType.ScalarType.DOUBLE));
    }

    private static EdgeSchema knowsSchema() {
        return new EdgeSchema(2, "Knows", "undirected friendship",
                new EdgePropertySchema(0, "id", null, PropertyType.ScalarType.LONG),
                new EdgeEndpointSchema(1, "source", null, PropertyType.ScalarType.LONG, "Account"),
                new EdgeEndpointSchema(2, "target", null, PropertyType.ScalarType.LONG, "Account"),
                MetaPropertySchema.createdTime(), MetaPropertySchema.lastUpdatedTime(),
                EdgeDirection.UNDIRECTED)
                .property(new EdgePropertySchema(KNOWS_SINCE, "since", null, PropertyType.ScalarType.LONG));
    }

    private GraphSchema openStore() {
        GraphSchema schema = new GraphSchema(1, "edge-test")
                .vertex(accountSchema())
                .edge(paymentSchema())
                .edge(knowsSchema());
        store = RocksDbEdgeStore.open(schema, RocksDbStoreConfig.of(directory.resolve("db").toString()));
        return schema;
    }

    private RocksDbVertexUpsert account(GraphSchema schema, long id, String status, long version) {
        return new RocksDbVertexUpsert(id, schema.vertexSchema("Account"),
                Map.of(ACCOUNT_STATUS, new RocksDbPropertyValue(status, version)), 1_000L, version);
    }

    private RocksDbEdgeUpsert payment(GraphSchema schema, long id, long source, long target,
                                     String status, long createdTime) {
        return new RocksDbEdgeUpsert(id, schema.edgeSchema("Payment"), source, target,
                Map.of(PAYMENT_STATUS, new RocksDbEdgeProperty(status, createdTime, createdTime),
                        PAYMENT_AMOUNT, new RocksDbEdgeProperty(9.5d, createdTime, createdTime)),
                createdTime, createdTime);
    }

    private RocksDbEdgeUpsert knows(GraphSchema schema, long id, long source, long target, long createdTime) {
        return new RocksDbEdgeUpsert(id, schema.edgeSchema("Knows"), source, target,
                Map.of(KNOWS_SINCE, new RocksDbEdgeProperty(createdTime, createdTime, createdTime)),
                createdTime, createdTime);
    }

    // --- vertices ---

    @Test
    void verticesAreReadWithProjectionFromTheirOwnTable() {
        GraphSchema schema = openStore();
        store.upsertVertices(List.of(account(schema, 135L, "ACTIVE", 100L),
                account(schema, 136L, "FROZEN", 200L)));
        RocksDbEdgeGraphQuery query = new RocksDbEdgeGraphQuery(store, schema);

        VertexQueryResult vertex = query.readVertex(135L, "Account", List.of("accountStatus"));
        assertEquals(135L, vertex.vertexId());
        assertEquals(Map.of("accountStatus", "ACTIVE"), vertex.properties());
        assertEquals(1_000L, vertex.createdTime());
        assertNull(query.readVertex(999L, "Account", List.of()));
        // the vertex table is separate from the edge table
        assertTrue(store.columnFamilies().contains("vertex:Account"));
        assertTrue(store.columnFamilies().contains("edge:Payment"));
        assertEquals(2L, store.vertices().count("Account"));
    }

    // --- directed edges ---

    @Test
    void directedEdgeHasOneRowPerEndpointWithItsDirection() {
        GraphSchema schema = openStore();
        store.upsertEdge(payment(schema, 1L, 100L, 200L, "SUCCESS", 5_000L));
        RocksDbEdgeGraphQuery query = new RocksDbEdgeGraphQuery(store, schema);

        List<EdgeQueryResult> out = query.readEdges(100L, "Account", "Payment", EdgeSide.OUT, List.of("status"));
        assertEquals(1, out.size());
        assertEquals(EdgeSide.OUT, out.get(0).side());
        assertEquals(200L, out.get(0).otherVertexId());
        assertEquals(Map.of("status", "SUCCESS"), out.get(0).properties());
        assertEquals(5_000L, out.get(0).createdTime());

        List<EdgeQueryResult> in = query.readEdges(200L, "Account", "Payment", EdgeSide.IN, List.of("status"));
        assertEquals(1, in.size());
        assertEquals(EdgeSide.IN, in.get(0).side());
        assertEquals(100L, in.get(0).otherVertexId());

        assertTrue(query.readEdges(100L, "Account", "Payment", EdgeSide.IN, List.of()).isEmpty());
        assertTrue(query.readEdges(200L, "Account", "Payment", EdgeSide.OUT, List.of()).isEmpty());
        assertEquals(2L, store.edgeRowCount("Payment"), "one row per endpoint");
    }

    @Test
    void bothReadsTwoIteratorsAndMergesThemNewestFirst() {
        GraphSchema schema = openStore();
        store.upsertEdges(List.of(payment(schema, 1L, 100L, 200L, "A", 100L),
                payment(schema, 2L, 300L, 100L, "B", 300L),
                payment(schema, 3L, 100L, 400L, "C", 200L)));
        RocksDbEdgeGraphQuery query = new RocksDbEdgeGraphQuery(store, schema);

        List<EdgeQueryResult> both = query.readEdges(100L, "Account", "Payment", EdgeSide.BOTH, List.of("status"));
        assertEquals(3, both.size());
        assertEquals(List.of("2", "3", "1"), both.stream().map(EdgeQueryResult::edgeId).toList());
        assertEquals(EdgeSide.IN, both.get(0).side());
        assertEquals(EdgeSide.OUT, both.get(1).side());
    }

    @Test
    void noDegreeLimitKeepsEveryEdgeOfAHub() {
        GraphSchema schema = openStore();
        List<RocksDbEdgeUpsert> edges = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            edges.add(payment(schema, i, 100L, 1_000L + i, "S", 100L + i));
        }
        store.upsertEdges(edges);
        RocksDbEdgeGraphQuery query = new RocksDbEdgeGraphQuery(store, schema);
        assertEquals(1_000, query.readEdges(100L, "Account", "Payment", EdgeSide.OUT, List.of()).size(),
                "the edge scheme has no maxOutDegree");

        // and a hub may receive any number of edges: 1000 senders into one account
        List<RocksDbEdgeUpsert> incoming = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            incoming.add(payment(schema, 10_000 + i, 2_000L + i, 500L, "S", 100L + i));
        }
        store.upsertEdges(incoming);
        assertEquals(1_000, query.readEdges(500L, "Account", "Payment", EdgeSide.IN, List.of()).size(),
                "nor a maxInDegree");
    }

    // --- time windows ---

    @Test
    void timeWindowIsASeekIntoTheOrderedEdgeTable() {
        GraphSchema schema = openStore();
        List<RocksDbEdgeUpsert> edges = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            edges.add(payment(schema, i, 100L, 1_000L + i, "S" + i, 1_000L + i * 10L));
        }
        store.upsertEdges(edges);
        RocksDbEdgeGraphQuery query = new RocksDbEdgeGraphQuery(store, schema);

        // createdTime of edge i is 1000 + i*10, so [1300, 1500] holds edges 30..50
        List<EdgeQueryResult> window = query.readEdgesWithin(100L, "Account", "Payment", EdgeSide.OUT,
                TimeRange.of(1_300L, 1_500L), List.of("status"));
        assertEquals(21, window.size());
        assertEquals(1_300L, window.stream().mapToLong(EdgeQueryResult::createdTime).min().orElseThrow());
        assertEquals(1_500L, window.stream().mapToLong(EdgeQueryResult::createdTime).max().orElseThrow());

        // an open ended window
        assertEquals(100, query.readEdgesWithin(100L, "Account", "Payment", EdgeSide.OUT,
                TimeRange.from(1_000L), List.of()).size());
        assertEquals(10, query.readEdgesWithin(100L, "Account", "Payment", EdgeSide.OUT,
                TimeRange.until(1_090L), List.of()).size());
        // a window with nothing in it
        assertTrue(query.readEdgesWithin(100L, "Account", "Payment", EdgeSide.OUT,
                TimeRange.of(9_000L, 9_999L), List.of()).isEmpty());
        // the unbounded read sees everything
        assertEquals(100, query.readEdges(100L, "Account", "Payment", EdgeSide.OUT, List.of()).size());
    }

    @Test
    void edgesOfOneVertexAreOnePrefixRangeScan() {
        GraphSchema schema = openStore();
        // three vertices, each with its own edges; a scan of one vertex must not see the others
        store.upsertEdges(List.of(payment(schema, 1L, 100L, 200L, "A", 100L),
                payment(schema, 2L, 100L, 300L, "B", 200L),
                payment(schema, 3L, 101L, 200L, "C", 300L)));
        RocksDbEdgeGraphQuery query = new RocksDbEdgeGraphQuery(store, schema);

        assertEquals(2, query.readEdges(100L, "Account", "Payment", EdgeSide.OUT, List.of()).size());
        assertEquals(1, query.readEdges(101L, "Account", "Payment", EdgeSide.OUT, List.of()).size());
        assertEquals(1, query.readEdges(300L, "Account", "Payment", EdgeSide.IN, List.of()).size());
        // vertex 200 receives edge 1 (from 100) and edge 3 (from 101)
        assertEquals(2, query.readEdges(200L, "Account", "Payment", EdgeSide.IN, List.of()).size());
    }

    // --- updates, deletes, cache ---

    @Test
    void anOlderEdgeWriteCannotUndoANewerOne() {
        GraphSchema schema = openStore();
        store.upsertEdge(payment(schema, 1L, 100L, 200L, "SUCCESS", 500L));
        // same edge id and creation time, older property timestamps
        RocksDbEdgeUpsert stale = new RocksDbEdgeUpsert(1L, schema.edgeSchema("Payment"), 100L, 200L,
                Map.of(PAYMENT_STATUS, new RocksDbEdgeProperty("FAILED", 400L, 400L)), 500L, 500L);
        store.upsertEdge(stale);
        RocksDbEdgeGraphQuery query = new RocksDbEdgeGraphQuery(store, schema);
        assertEquals("SUCCESS", query.readEdges(100L, "Account", "Payment", EdgeSide.OUT, List.of("status"))
                .get(0).properties().get("status"));

        RocksDbEdgeUpsert newer = new RocksDbEdgeUpsert(1L, schema.edgeSchema("Payment"), 100L, 200L,
                Map.of(PAYMENT_STATUS, new RocksDbEdgeProperty("FAILED", 600L, 600L)), 500L, 600L);
        store.upsertEdge(newer);
        assertEquals("FAILED", query.readEdges(100L, "Account", "Payment", EdgeSide.OUT, List.of("status"))
                .get(0).properties().get("status"));
    }

    @Test
    void deleteEdgeRemovesBothRows() {
        GraphSchema schema = openStore();
        store.upsertEdge(payment(schema, 7L, 100L, 200L, "SUCCESS", 5_000L));
        assertTrue(store.deleteEdge(schema.edgeSchema("Payment"), 7L, 5_000L, 100L, 200L));
        RocksDbEdgeGraphQuery query = new RocksDbEdgeGraphQuery(store, schema);
        assertTrue(query.readEdges(100L, "Account", "Payment", EdgeSide.OUT, List.of()).isEmpty());
        assertTrue(query.readEdges(200L, "Account", "Payment", EdgeSide.IN, List.of()).isEmpty());
        assertFalse(store.deleteEdge(schema.edgeSchema("Payment"), 7L, 5_000L, 100L, 200L));
    }

    @Test
    void cacheIsInvalidatedWhenAnEdgeIsWritten() {
        GraphSchema schema = openStore();
        RocksDbEdgeGraphQuery query = new RocksDbEdgeGraphQuery(store, schema);
        store.upsertEdge(payment(schema, 1L, 100L, 200L, "A", 100L));
        assertEquals(1, query.readEdges(100L, "Account", "Payment", EdgeSide.OUT, List.of()).size());
        assertTrue(store.edgeCache().size() > 0);
        store.upsertEdge(payment(schema, 2L, 100L, 300L, "B", 200L));
        assertEquals(2, query.readEdges(100L, "Account", "Payment", EdgeSide.OUT, List.of()).size());
        store.deleteEdge(schema.edgeSchema("Payment"), 1L, 100L, 100L, 200L);
        assertEquals(1, query.readEdges(100L, "Account", "Payment", EdgeSide.OUT, List.of()).size());
    }

    // --- undirected edges + batched reads ---

    @Test
    void undirectedEdgeUsesDirectionZeroOnBothEndpoints() {
        GraphSchema schema = openStore();
        store.upsertEdge(knows(schema, 1L, 100L, 200L, 4_000L));
        RocksDbEdgeGraphQuery query = new RocksDbEdgeGraphQuery(store, schema);

        for (EdgeSide side : List.of(EdgeSide.OUT, EdgeSide.IN, EdgeSide.BOTH)) {
            List<EdgeQueryResult> edges = query.readEdges(100L, "Account", "Knows", side, List.of("since"));
            assertEquals(1, edges.size(), "side " + side);
            assertEquals(EdgeSide.BOTH, edges.get(0).side());
            assertEquals(200L, edges.get(0).otherVertexId());
        }
        assertEquals(100L, query.readEdges(200L, "Account", "Knows", EdgeSide.BOTH, List.of())
                .get(0).otherVertexId());
        assertEquals(2L, store.edgeRowCount("Knows"));
    }

    @Test
    void batchedReadsMatchSingleReads() {
        GraphSchema schema = openStore();
        List<Object> ids = new ArrayList<>();
        List<RocksDbVertexUpsert> vertices = new ArrayList<>();
        List<RocksDbEdgeUpsert> edges = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            ids.add((long) i);
            vertices.add(account(schema, i, "S" + i, 10L));
            edges.add(payment(schema, i, i, 10_000L + i, "P" + i, 100L + i));
        }
        store.upsertVertices(vertices);
        store.upsertEdges(edges);
        RocksDbEdgeGraphQuery query = new RocksDbEdgeGraphQuery(store, schema);

        List<List<EdgeQueryResult>> batched = query.readEdgesBatch("Payment", EdgeSide.OUT, ids, List.of("status"));
        assertEquals(250, batched.size());
        for (int i = 0; i < 250; i++) {
            assertEquals(query.readEdges(ids.get(i), "Account", "Payment", EdgeSide.OUT, List.of("status")),
                    batched.get(i), "vertex " + i);
        }
        List<List<EdgeQueryResult>> all = query.readAllEdgesBatch(ids, EdgeSide.BOTH, List.of());
        assertEquals(250, all.size());
        assertEquals(1, all.get(0).size());
    }

    @Test
    void edgeRowsAreSpreadOverTheConfiguredRegions() {
        GraphSchema schema = openStore();
        List<RocksDbEdgeUpsert> edges = new ArrayList<>();
        for (int i = 0; i < 2_000; i++) {
            edges.add(payment(schema, i, 100L, 1_000_000L + i, "S", 100L + i));
        }
        store.upsertEdges(edges);
        int regions = store.regionsUsed("Payment");
        assertTrue(regions > 100, "2000 distinct targets should cover many of the 512 regions, got " + regions);
        assertTrue(store.regionOf(100L) < 512);
        assertEquals(512, store.config().regionCount());
    }

    @Test
    void keyLayoutEncodesDirectionAndTimeForTheScheme() {
        // direction 1 = outgoing, 2 = incoming, 0 = undirected (the RocksDB-Edge convention)
        assertEquals(0, RocksDbKeys.DIRECTION_UNDIRECTED);
        assertEquals(1, RocksDbKeys.DIRECTION_OUT);
        assertEquals(2, RocksDbKeys.DIRECTION_IN);
    }
}
