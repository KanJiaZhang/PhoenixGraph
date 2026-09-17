package com.pengcheng.phoenixgraph.storage.rocksdb.inline;

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
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbPropertyValue;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbStoreConfig;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbVertexUpsert;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The RocksDB-Inline scheme: vertices in one table per vertex type, edges folded into one
 * table per edge type (with one entry per Aerospike bin name), degree limits enforced when
 * an edge is written.
 */
class RocksDbInlineStoreTest {

    private static final int ACCOUNT_STATUS = 1;
    private static final int ACCOUNT_TYPE = 2;
    private static final int PAYMENT_STATUS = 3;
    private static final int PAYMENT_AMOUNT = 4;
    private static final int KNOWS_SINCE = 3;

    @TempDir
    Path directory;

    private RocksDbInlineStore store;

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
                        PropertyType.ScalarType.STRING))
                .property(new VertexPropertySchema(ACCOUNT_TYPE, "accountType", null,
                        PropertyType.ScalarType.STRING));
    }

    private static EdgeSchema paymentSchema(int maxOut, int maxIn, int maxDegree) {
        return new EdgeSchema(1, "Payment", "a payment",
                new EdgePropertySchema(0, "id", null, PropertyType.ScalarType.LONG),
                new EdgeEndpointSchema(1, "source", null, PropertyType.ScalarType.LONG, "Account"),
                new EdgeEndpointSchema(2, "target", null, PropertyType.ScalarType.LONG, "Account"),
                MetaPropertySchema.createdTime(), MetaPropertySchema.lastUpdatedTime(),
                EdgeDirection.DIRECTED, maxOut, maxIn, maxDegree)
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

    /** Opens a store whose Payment edge class carries the given degree limits. */
    private GraphSchema openStore(int maxOut, int maxIn, int maxDegree) {
        GraphSchema schema = new GraphSchema(1, "inline-test")
                .vertex(accountSchema())
                .edge(paymentSchema(maxOut, maxIn, maxDegree))
                .edge(knowsSchema());
        store = RocksDbInlineStore.open(schema, RocksDbStoreConfig.of(directory.resolve("db").toString()));
        return schema;
    }

    private GraphSchema openStore() {
        return openStore(0, 0, 0);
    }

    private RocksDbVertexUpsert account(GraphSchema schema, long id, String status, long version) {
        return new RocksDbVertexUpsert(id, schema.vertexSchema("Account"),
                Map.of(ACCOUNT_STATUS, new RocksDbPropertyValue(status, version),
                        ACCOUNT_TYPE, new RocksDbPropertyValue("savings", version)), 1_000L, version);
    }

    private RocksDbEdgeUpsert payment(GraphSchema schema, long id, long source, long target,
                                     String status, long version) {
        return new RocksDbEdgeUpsert(id, schema.edgeSchema("Payment"), source, target,
                Map.of(PAYMENT_STATUS, new RocksDbEdgeProperty(status, version, version),
                        PAYMENT_AMOUNT, new RocksDbEdgeProperty(12.5d, version, version)),
                version, version);
    }

    private RocksDbEdgeUpsert knows(GraphSchema schema, long id, long source, long target, long version) {
        return new RocksDbEdgeUpsert(id, schema.edgeSchema("Knows"), source, target,
                Map.of(KNOWS_SINCE, new RocksDbEdgeProperty(version, version, version)), version, version);
    }

    private static RocksDbInlineGraphQuery queryOf(GraphSchema schema) {
        return new RocksDbInlineGraphQuery(null, schema);
    }

    // --- vertices ---

    @Test
    void verticesAreStoredPerTypeAndReadWithProjection() {
        GraphSchema schema = openStore();
        store.upsertVertices(List.of(account(schema, 135L, "ACTIVE", 100L),
                account(schema, 136L, "FROZEN", 200L)));
        RocksDbInlineGraphQuery query = new RocksDbInlineGraphQuery(store, schema);

        VertexQueryResult vertex = query.readVertex(135L, "Account", List.of("accountStatus"));
        assertEquals(135L, vertex.vertexId());
        assertEquals("Account", vertex.vertexName());
        assertEquals(Map.of("accountStatus", "ACTIVE"), vertex.properties());
        assertEquals(1_000L, vertex.createdTime());
        assertEquals(100L, vertex.lastUpdatedTime());

        // all properties when nothing is requested
        assertEquals(Map.of("accountStatus", "ACTIVE", "accountType", "savings"),
                query.readVertex(135L, "Account", List.of()).properties());
        assertNull(query.readVertex(999L, "Account", List.of()));
        // ids keep their type: the record key holds the text form, the record the type
        assertEquals(136L, query.readVertex(136L, "Account", List.of()).vertexId());
    }

    @Test
    void vertexTablesAreSeparateColumnFamilies() {
        GraphSchema schema = openStore();
        store.upsertVertex(account(schema, 1L, "ACTIVE", 10L));
        assertTrue(store.columnFamilies().contains("vertex:Account"));
        assertTrue(store.columnFamilies().contains("edge-inline:Payment"));
        assertTrue(store.columnFamilies().contains("edge-inline:Knows"));
        assertEquals(1L, store.vertices().count("Account"));
    }

    // --- directed edges ---

    @Test
    void directedEdgeIsStoredOnBothEndpointsAndReadableFromEither() {
        GraphSchema schema = openStore();
        store.upsertVertices(List.of(account(schema, 100L, "ACTIVE", 10L),
                account(schema, 200L, "ACTIVE", 10L)));
        assertEquals(WriteOutcome.SUCCESS, store.upsertEdge(payment(schema, 1L, 100L, 200L, "SUCCESS", 500L)));
        RocksDbInlineGraphQuery query = new RocksDbInlineGraphQuery(store, schema);

        // outgoing from the source
        List<EdgeQueryResult> out = query.readEdges(100L, "Account", "Payment", EdgeSide.OUT, List.of("status"));
        assertEquals(1, out.size());
        assertEquals(EdgeSide.OUT, out.get(0).side());
        assertEquals(200L, out.get(0).otherVertexId());
        assertEquals(Map.of("status", "SUCCESS"), out.get(0).properties());
        assertEquals("1", out.get(0).edgeId());

        // incoming at the destination
        List<EdgeQueryResult> in = query.readEdges(200L, "Account", "Payment", EdgeSide.IN, List.of("status"));
        assertEquals(1, in.size());
        assertEquals(EdgeSide.IN, in.get(0).side());
        assertEquals(100L, in.get(0).otherVertexId());

        // and each endpoint has only its own side
        assertTrue(query.readEdges(100L, "Account", "Payment", EdgeSide.IN, List.of()).isEmpty());
        assertTrue(query.readEdges(200L, "Account", "Payment", EdgeSide.OUT, List.of()).isEmpty());

        // both = the two sides merged
        List<EdgeQueryResult> both = query.readEdges(100L, "Account", "Payment", EdgeSide.BOTH, List.of());
        assertEquals(1, both.size());
        assertEquals(EdgeSide.OUT, both.get(0).side());
    }

    @Test
    void bothMergesBothDirectionsNewestFirst() {
        GraphSchema schema = openStore();
        // 100 pays 200, 300 pays 100 and 100 pays 400
        store.upsertEdges(List.of(payment(schema, 1L, 100L, 200L, "A", 100L),
                payment(schema, 2L, 300L, 100L, "B", 300L),
                payment(schema, 3L, 100L, 400L, "C", 200L)));
        RocksDbInlineGraphQuery query = new RocksDbInlineGraphQuery(store, schema);

        List<EdgeQueryResult> both = query.readEdges(100L, "Account", "Payment", EdgeSide.BOTH, List.of("status"));
        assertEquals(3, both.size());
        // newest first: edge 2 (300), edge 3 (200), edge 1 (100)
        assertEquals(List.of("2", "3", "1"), both.stream().map(EdgeQueryResult::edgeId).toList());
        assertEquals(EdgeSide.IN, both.get(0).side());
        assertEquals(EdgeSide.OUT, both.get(1).side());
        assertEquals(List.of(300L, 400L, 200L), both.stream().map(EdgeQueryResult::otherVertexId).toList());
    }

    @Test
    void edgePropertyProjectionReadsOnlyWhatIsAskedFor() {
        GraphSchema schema = openStore();
        store.upsertEdge(payment(schema, 1L, 100L, 200L, "SUCCESS", 100L));
        RocksDbInlineGraphQuery query = new RocksDbInlineGraphQuery(store, schema);

        EdgeQueryResult onlyAmount = query.readEdges(100L, "Account", "Payment", EdgeSide.OUT,
                List.of("amount")).get(0);
        assertEquals(Map.of("amount", 12.5d), onlyAmount.properties());
        assertEquals(2, query.readEdges(100L, "Account", "Payment", EdgeSide.OUT, List.of()).get(0)
                .properties().size());
    }

    @Test
    void anOlderEdgeWriteCannotUndoANewerOne() {
        GraphSchema schema = openStore();
        store.upsertEdge(payment(schema, 1L, 100L, 200L, "SUCCESS", 500L));
        store.upsertEdge(payment(schema, 1L, 100L, 200L, "FAILED", 400L));
        RocksDbInlineGraphQuery query = new RocksDbInlineGraphQuery(store, schema);

        EdgeQueryResult edge = query.readEdges(100L, "Account", "Payment", EdgeSide.OUT, List.of("status")).get(0);
        assertEquals("SUCCESS", edge.properties().get("status"));
        assertEquals(500L, edge.lastUpdatedTime());

        // a newer write replaces it
        store.upsertEdge(payment(schema, 1L, 100L, 200L, "FAILED", 600L));
        assertEquals("FAILED", query.readEdges(100L, "Account", "Payment", EdgeSide.OUT, List.of("status"))
                .get(0).properties().get("status"));
    }

    @Test
    void deleteEdgeRemovesBothSides() {
        GraphSchema schema = openStore();
        store.upsertEdge(payment(schema, 7L, 100L, 200L, "SUCCESS", 100L));
        RocksDbInlineGraphQuery query = new RocksDbInlineGraphQuery(store, schema);
        assertEquals(1, query.readEdges(100L, "Account", "Payment", EdgeSide.OUT, List.of()).size());

        assertTrue(store.deleteEdge(schema.edgeSchema("Payment"), 7L, 100L, 200L));
        assertTrue(query.readEdges(100L, "Account", "Payment", EdgeSide.OUT, List.of()).isEmpty());
        assertTrue(query.readEdges(200L, "Account", "Payment", EdgeSide.IN, List.of()).isEmpty());
        // deleting again is a no-op, not an error
        assertFalse(store.deleteEdge(schema.edgeSchema("Payment"), 7L, 100L, 200L));
    }

    // --- degree limits ---

    @Test
    void maxOutDegreeEvictsTheOldestOutgoingEdges() {
        GraphSchema schema = openStore(3, 0, 0);
        List<RocksDbEdgeUpsert> edges = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            edges.add(payment(schema, i, 100L, 1000L + i, "S" + i, 100L * i));
        }
        store.upsertEdges(edges);
        RocksDbInlineGraphQuery query = new RocksDbInlineGraphQuery(store, schema);

        List<EdgeQueryResult> out = query.readEdges(100L, "Account", "Payment", EdgeSide.OUT, List.of("status"));
        assertEquals(3, out.size(), "maxOutDegree=3 keeps three edges");
        // the three newest survive: versions 500, 400, 300
        assertEquals(List.of(1005L, 1004L, 1003L),
                out.stream().map(EdgeQueryResult::otherVertexId).toList());
        // eviction is per endpoint and direction (the Aerospike store does the same): the
        // target's incoming bin keeps its copy unless its own maxInDegree evicts it
        assertEquals(1, query.readEdges(1001L, "Account", "Payment", EdgeSide.IN, List.of()).size());
    }

    @Test
    void maxInDegreeIsEnforcedIndependentlyOfMaxOutDegree() {
        GraphSchema schema = openStore(0, 2, 0);
        store.upsertEdges(List.of(payment(schema, 1L, 10L, 500L, "A", 100L),
                payment(schema, 2L, 20L, 500L, "B", 200L),
                payment(schema, 3L, 30L, 500L, "C", 300L)));
        RocksDbInlineGraphQuery query = new RocksDbInlineGraphQuery(store, schema);

        List<EdgeQueryResult> in = query.readEdges(500L, "Account", "Payment", EdgeSide.IN, List.of("status"));
        assertEquals(2, in.size());
        assertEquals(List.of(30L, 20L), in.stream().map(EdgeQueryResult::otherVertexId).toList());
        // the sender's outgoing bin is unlimited, so its own copy survives the target's eviction
        assertEquals(1, query.readEdges(10L, "Account", "Payment", EdgeSide.OUT, List.of()).size());
    }

    @Test
    void unlimitedDegreeKeepsEveryEdge() {
        GraphSchema schema = openStore();
        List<RocksDbEdgeUpsert> edges = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            edges.add(payment(schema, i, 100L, 1000L + i, "S", 100L + i));
        }
        store.upsertEdges(edges);
        RocksDbInlineGraphQuery query = new RocksDbInlineGraphQuery(store, schema);
        assertEquals(500, query.readEdges(100L, "Account", "Payment", EdgeSide.OUT, List.of()).size());
    }

    // --- undirected edges ---

    @Test
    void undirectedEdgeLivesInOneBinReadableFromBothEndpoints() {
        GraphSchema schema = openStore();
        store.upsertEdge(knows(schema, 1L, 100L, 200L, 100L));
        RocksDbInlineGraphQuery query = new RocksDbInlineGraphQuery(store, schema);

        for (EdgeSide side : List.of(EdgeSide.OUT, EdgeSide.IN, EdgeSide.BOTH)) {
            List<EdgeQueryResult> fromA = query.readEdges(100L, "Account", "Knows", side, List.of("since"));
            assertEquals(1, fromA.size(), "side " + side);
            assertEquals(EdgeSide.BOTH, fromA.get(0).side());
            assertEquals(200L, fromA.get(0).otherVertexId());
        }
        assertEquals(100L, query.readEdges(200L, "Account", "Knows", EdgeSide.BOTH, List.of())
                .get(0).otherVertexId());
        assertEquals(Map.of("ve_2_0", 1), store.binSizes("Knows", 100L));
        assertEquals(Map.of("ve_2_0", 1), store.binSizes("Knows", 200L));
    }

    @Test
    void anEdgeWithANullableTargetIsStoredOnItsSourceOnly() {
        // Opens: Merchant -> Merchant, but the target may be absent
        EdgeSchema opens = new EdgeSchema(4, "Opens", "an edge with nothing at the far end",
                new EdgePropertySchema(0, "id", null, PropertyType.ScalarType.LONG),
                new EdgeEndpointSchema(1, "source", null, PropertyType.ScalarType.LONG, "Account"),
                new EdgeEndpointSchema(2, "target", null, PropertyType.ScalarType.LONG, "Account")
                        .nullable(true),
                MetaPropertySchema.createdTime(), MetaPropertySchema.lastUpdatedTime(),
                EdgeDirection.DIRECTED)
                .property(new EdgePropertySchema(3, "since", null, PropertyType.ScalarType.LONG));
        GraphSchema schema = new GraphSchema(1, "inline-null-target").vertex(accountSchema()).edge(opens);
        store = RocksDbInlineStore.open(schema, RocksDbStoreConfig.of(directory.resolve("db-null").toString()));
        RocksDbInlineGraphQuery query = new RocksDbInlineGraphQuery(store, schema);

        // the target is null: the edge is written on the source, nowhere else
        assertEquals(WriteOutcome.SUCCESS, store.upsertEdge(new RocksDbEdgeUpsert(1L, opens, 100L, null,
                Map.of(3, new RocksDbEdgeProperty(1_000L, 5L, 5L)), 5L, 5L)));

        List<EdgeQueryResult> out = query.readEdges(100L, "Account", "Opens", EdgeSide.OUT, List.of("since"));
        assertEquals(1, out.size());
        assertEquals(EdgeSide.OUT, out.get(0).side());
        assertNull(out.get(0).otherVertexId(), "the edge has no far endpoint");
        assertEquals(1_000L, out.get(0).properties().get("since"));
        // its target bin is empty - there is no other side at all
        assertEquals(1, store.binSizes("Opens", 100L).values().iterator().next());
        assertEquals(1L, store.edgeCount("Opens"), "one side only");

        // a schema that does not allow it refuses the write
        assertThrows(IllegalArgumentException.class, () -> new RocksDbEdgeUpsert(2L,
                paymentSchema(0, 0, 0), 100L, null, Map.of(), 5L, 5L));

        // deleting the edge removes the one side it has
        assertTrue(store.deleteEdge(opens, 1L, 100L, null));
        assertTrue(query.readEdges(100L, "Account", "Opens", EdgeSide.OUT, List.of()).isEmpty());
    }

    @Test
    void binNamesFollowTheAerospikeLayout() {
        GraphSchema schema = openStore();
        store.upsertEdge(payment(schema, 1L, 100L, 200L, "S", 10L));
        assertEquals(Map.of("ve_1_2", 1), store.binSizes("Payment", 100L), "outgoing bin ve_1_2");
        assertEquals(Map.of("ve_1_1", 1), store.binSizes("Payment", 200L), "incoming bin ve_1_1");
    }

    // --- batched reads and caching ---

    @Test
    void batchedReadsMatchSingleReads() {
        GraphSchema schema = openStore();
        List<Object> ids = new ArrayList<>();
        List<RocksDbVertexUpsert> vertices = new ArrayList<>();
        List<RocksDbEdgeUpsert> edges = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            ids.add((long) i);
            vertices.add(account(schema, i, "S" + i, 10L));
            edges.add(payment(schema, i, (long) i, 10_000L + i, "P" + i, 100L + i));
        }
        store.upsertVertices(vertices);
        store.upsertEdges(edges);
        RocksDbInlineGraphQuery query = new RocksDbInlineGraphQuery(store, schema);

        List<List<EdgeQueryResult>> batched = query.readEdgesBatch("Payment", EdgeSide.OUT, ids, List.of("status"));
        assertEquals(250, batched.size());
        for (int i = 0; i < 250; i++) {
            List<EdgeQueryResult> single = query.readEdges(ids.get(i), "Account", "Payment", EdgeSide.OUT,
                    List.of("status"));
            assertEquals(single, batched.get(i), "vertex " + i);
        }

        // all edge classes at once (the in-memory algorithm path)
        List<List<EdgeQueryResult>> all = query.readAllEdgesBatch(ids, EdgeSide.BOTH, List.of());
        assertEquals(250, all.size());
        assertEquals(1, all.get(0).size());
    }

    @Test
    void cacheIsInvalidatedWhenAnEdgeIsWritten() {
        GraphSchema schema = openStore();
        RocksDbInlineGraphQuery query = new RocksDbInlineGraphQuery(store, schema);
        store.upsertEdge(payment(schema, 1L, 100L, 200L, "A", 100L));
        assertEquals(1, query.readEdges(100L, "Account", "Payment", EdgeSide.OUT, List.of()).size());
        assertTrue(store.edgeCache().size() > 0, "the read populated the cache");

        store.upsertEdge(payment(schema, 2L, 100L, 300L, "B", 200L));
        assertEquals(2, query.readEdges(100L, "Account", "Payment", EdgeSide.OUT, List.of()).size(),
                "a write invalidates the cached edge list");
        store.deleteEdge(schema.edgeSchema("Payment"), 1L, 100L, 200L);
        assertEquals(1, query.readEdges(100L, "Account", "Payment", EdgeSide.OUT, List.of()).size());
    }

    @Test
    void verticesAndEdgesOfOneVertexAreStoredInDifferentTables() {
        GraphSchema schema = openStore();
        store.upsertVertex(account(schema, 100L, "ACTIVE", 10L));
        store.upsertEdge(payment(schema, 1L, 100L, 200L, "A", 20L));
        // the vertex table has exactly one record, the edge table has the two sides
        assertEquals(1L, store.vertices().count("Account"));
        assertEquals(2L, store.edgeCount("Payment"));
        assertEquals(1L, store.vertices().count("Account"));
    }
}
