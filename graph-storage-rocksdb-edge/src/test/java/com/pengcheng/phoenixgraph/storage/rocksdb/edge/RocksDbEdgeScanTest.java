package com.pengcheng.phoenixgraph.storage.rocksdb.edge;

import com.pengcheng.phoenixgraph.query.QueryException;
import com.pengcheng.phoenixgraph.query.storage.EdgeQueryResult;
import com.pengcheng.phoenixgraph.query.storage.GraphQuery;
import com.pengcheng.phoenixgraph.query.storage.ScannedEdge;
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
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbEdgeProperty;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbEdgeUpsert;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbPropertyValue;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbStoreConfig;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbVertexUpsert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scan port of the edge scheme: walking a whole vertex type or a whole edge class instead of
 * looking one record up by key.
 *
 * <p>This is what makes the scheme the analytical one - a query that starts from a class
 * ({@code g.V('Account')}, {@code g.E('Payment').pagerank(DIST)}) has no key to ask for, and the
 * ordered tables answer it with one iterator.
 */
class RocksDbEdgeScanTest {

    private static final int STATUS = 1;
    private static final int PAYMENT_STATUS = 3;
    private static final int PAYMENT_AMOUNT = 4;

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

    private GraphSchema openStore() {
        GraphSchema schema = new GraphSchema(1, "scan-test")
                .vertex(new VertexSchema(1, "Account", "an account")
                        .property(new VertexPropertySchema(STATUS, "accountStatus", null,
                                PropertyType.ScalarType.STRING)))
                .edge(new EdgeSchema(1, "Payment", "a payment",
                        new EdgePropertySchema(0, "id", null, PropertyType.ScalarType.LONG),
                        new EdgeEndpointSchema(1, "source", null, PropertyType.ScalarType.LONG, "Account"),
                        new EdgeEndpointSchema(2, "target", null, PropertyType.ScalarType.LONG, "Account"),
                        MetaPropertySchema.createdTime(), MetaPropertySchema.lastUpdatedTime(),
                        EdgeDirection.DIRECTED, 0, 0, 0)
                        .property(new EdgePropertySchema(PAYMENT_STATUS, "status", null,
                                PropertyType.ScalarType.STRING))
                        .property(new EdgePropertySchema(PAYMENT_AMOUNT, "amount", null,
                                PropertyType.ScalarType.DOUBLE)))
                .edge(new EdgeSchema(2, "Knows", "undirected friendship",
                        new EdgePropertySchema(0, "id", null, PropertyType.ScalarType.LONG),
                        new EdgeEndpointSchema(1, "source", null, PropertyType.ScalarType.LONG, "Account"),
                        new EdgeEndpointSchema(2, "target", null, PropertyType.ScalarType.LONG, "Account"),
                        MetaPropertySchema.createdTime(), MetaPropertySchema.lastUpdatedTime(),
                        EdgeDirection.UNDIRECTED));
        store = RocksDbEdgeStore.open(schema, RocksDbStoreConfig.of(directory.resolve("db").toString()));
        return schema;
    }

    /** Three accounts and three payments: 100 -> 200 -> 300, plus 100 -> 300. */
    private GraphSchema seeded() {
        GraphSchema schema = openStore();
        store.upsertVertices(List.of(
                new RocksDbVertexUpsert(100L, schema.vertexSchema("Account"),
                        Map.of(STATUS, new RocksDbPropertyValue("ACTIVE", 10L)), 1_000L, 10L),
                new RocksDbVertexUpsert(200L, schema.vertexSchema("Account"),
                        Map.of(STATUS, new RocksDbPropertyValue("FROZEN", 10L)), 1_000L, 10L),
                new RocksDbVertexUpsert(300L, schema.vertexSchema("Account"),
                        Map.of(STATUS, new RocksDbPropertyValue("ACTIVE", 10L)), 1_000L, 10L)));
        store.upsertEdges(List.of(
                payment(schema, 1L, 100L, 200L, "SUCCESS", 5_000L),
                payment(schema, 2L, 200L, 300L, "FAILED", 6_000L),
                payment(schema, 3L, 100L, 300L, "SUCCESS", 7_000L)));
        return schema;
    }

    private RocksDbEdgeUpsert payment(GraphSchema schema, long id, long source, long target,
                                     String status, long createdTime) {
        return new RocksDbEdgeUpsert(id, schema.edgeSchema("Payment"), source, target,
                Map.of(PAYMENT_STATUS, new RocksDbEdgeProperty(status, createdTime, createdTime),
                        PAYMENT_AMOUNT, new RocksDbEdgeProperty(9.5d, createdTime, createdTime)),
                createdTime, createdTime);
    }

    // --- the capability ---

    @Test
    void theSchemeScansAndThePortIsTheOneThatAsks() {
        GraphSchema schema = seeded();
        GraphQuery query = new RocksDbEdgeGraphQuery(store, schema);
        assertTrue(query.supportsScan(), "the ordered tables answer a whole class");
    }

    // --- a whole vertex type ---

    @Test
    void aVertexScanReadsEveryVertexOfTheTypeWithItsProjection() {
        GraphSchema schema = seeded();
        GraphQuery query = new RocksDbEdgeGraphQuery(store, schema);

        List<VertexQueryResult> all = query.scanVertices("Account", List.of("accountStatus"));
        assertEquals(3, all.size());
        assertEquals(Set.of(100L, 200L, 300L),
                all.stream().map(VertexQueryResult::vertexId).collect(Collectors.toSet()));
        assertEquals("FROZEN", all.stream()
                .filter(v -> Long.valueOf(200L).equals(v.vertexId()))
                .findFirst().orElseThrow().properties().get("accountStatus"));
        // the type is read from its own table: no edge row is touched
        assertEquals(3L, query.countScanVertices("Account"));
    }

    @Test
    void aVertexScanStopsAtItsCapAndRefusesAnUnknownType() {
        GraphSchema schema = seeded();
        GraphQuery query = new RocksDbEdgeGraphQuery(store, schema);
        assertEquals(2, query.scanVertices("Account", List.of(), 2).size());
        assertThrows(QueryException.class, () -> query.scanVertices("Nope", List.of(), 0));
    }

    // --- a whole edge class ---

    @Test
    void anEdgeScanReadsEveryEdgeOnceWithBothOfItsEndpoints() {
        GraphSchema schema = seeded();
        GraphQuery query = new RocksDbEdgeGraphQuery(store, schema);

        List<ScannedEdge> edges = query.scanEdges("Payment", List.of("status", "amount"));
        assertEquals(3, edges.size(), "one entry per edge, not per stored row");
        Map<String, ScannedEdge> byId = edges.stream()
                .collect(Collectors.toMap(ScannedEdge::edgeId, e -> e));
        assertEquals(List.of(1L, 2L, 3L), byId.keySet().stream().map(Long::valueOf).sorted().toList());

        ScannedEdge first = byId.get("1");
        assertEquals(100L, first.source());
        assertEquals(200L, first.target());
        assertEquals(200L, first.other(100L));
        assertEquals(100L, first.other(200L));
        assertEquals("SUCCESS", first.properties().get("status"));
        assertEquals(9.5d, first.properties().get("amount"));
        assertEquals(5_000L, first.createdTime());

        ScannedEdge other = byId.get("2");
        assertEquals(200L, other.source());
        assertEquals(300L, other.target());
        assertFalse(other.properties().containsKey("id"), "the id lives in the key");
    }

    @Test
    void anEdgeScanLeavesOutThePropertiesItWasNotAskedFor() {
        GraphSchema schema = seeded();
        GraphQuery query = new RocksDbEdgeGraphQuery(store, schema);
        List<ScannedEdge> edges = query.scanEdges("Payment", List.of("status"));
        assertEquals(3, edges.size());
        assertEquals(Set.of("status"), edges.get(0).properties().keySet());
    }

    @Test
    void anEdgeScanStopsAtItsCapAndCountsWithoutDecoding() {
        GraphSchema schema = seeded();
        GraphQuery query = new RocksDbEdgeGraphQuery(store, schema);
        assertEquals(2, query.scanEdges("Payment", List.of(), 2).size());
        assertEquals(3L, query.countScanEdges("Payment"));
        assertThrows(QueryException.class, () -> query.scanEdges("Nope", List.of(), 0));
    }

    @Test
    void anUndirectedEdgeIsScannedOnceToo() {
        GraphSchema schema = seeded();
        // edge 9 is stored on both endpoints (direction 0); the scan must keep one of them
        store.upsertEdge(new RocksDbEdgeUpsert(9L, schema.edgeSchema("Knows"), 200L, 300L,
                Map.of(), 8_000L, 8_000L));
        RocksDbEdgeGraphQuery query = new RocksDbEdgeGraphQuery(store, schema);

        List<ScannedEdge> edges = query.scanEdges("Knows", List.of());
        assertEquals(1, edges.size());
        assertEquals(Set.of(200L, 300L), Set.of(edges.get(0).source(), edges.get(0).target()));
        assertEquals(2L, store.edgeRowCount("Knows"), "both endpoints hold a row");
        assertEquals(1L, query.countScanEdges("Knows"), "but it is one edge");
    }

    @Test
    void anEdgeThatPointsAtItsOwnSourceIsScannedOnce() {
        GraphSchema schema = seeded();
        store.upsertEdge(payment(schema, 4L, 100L, 100L, "SELF", 9_000L));
        RocksDbEdgeGraphQuery query = new RocksDbEdgeGraphQuery(store, schema);
        assertEquals(4, query.scanEdges("Payment", List.of()).size());
        assertEquals(4L, query.countScanEdges("Payment"));
    }
}
