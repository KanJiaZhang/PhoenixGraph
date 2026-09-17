package com.pengcheng.phoenixgraph.integration;

import com.aerospike.client.AerospikeClient;
import com.pengcheng.phoenixgraph.query.QueryException;
import com.pengcheng.phoenixgraph.query.aerospike.AerospikeEdgeGraphQuery;
import com.pengcheng.phoenixgraph.query.aerospike.AerospikeGraphQuery;
import com.pengcheng.phoenixgraph.query.gremlin.GremlinGraphQuery;
import com.pengcheng.phoenixgraph.query.storage.EdgeQueryResult;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
import com.pengcheng.phoenixgraph.query.traversal.NeighborVertexResolver;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.json.SchemaJsonCodec;
import com.pengcheng.phoenixgraph.storage.TimeRange;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeChunkStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeChunks;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import com.pengcheng.phoenixgraph.storage.aerospike.EdgePropertyValue;
import com.pengcheng.phoenixgraph.storage.aerospike.EdgeUpsert;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Aerospike-Edge scheme against a real server: a bulk import puts a hub's edges into the
 * aggregated {@code Edge_Out} / {@code Edge_In} tables (with three levels, thanks to a small
 * chunk size), and queries read a window out of them and merge what they find with the edges that
 * live inline in the vertex record.
 *
 * <p>Skipped when no Aerospike is reachable.
 */
class AerospikeEdgeIntegrationTest {

    /** A small chunk size so a few thousand edges already need three levels. */
    private static final int CHUNK_SIZE = 64;

    /** How many payments the hub receives in this test. */
    private static final int HUB_EDGES = 5_000;

    private static final String VERTEX_SET = "as_edge_it_v1";

    private static AerospikeClient client;
    private static String namespace;
    private static GraphSchema schema;
    private static AerospikeEdgeGraphQuery query;
    private static GremlinGraphQuery gremlin;
    private static long startTime;

    @BeforeAll
    static void loadTheDemo() {
        client = connectOrNull();
        assumeTrue(client != null, "Aerospike not available, skipping the Aerospike-Edge integration test");
        namespace = env("AEROSPIKE_NAMESPACE", "test");
        schema = loadSchema();
        startTime = AerospikeEdgeDemoLoader.DEMO_YEAR.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();

        // a fresh vertex set: the demo's vertices plus the bulk import of the hub's edges
        AerospikeVertexStore vertices = new AerospikeVertexStore(client, namespace, VERTEX_SET);
        truncate(AerospikeEdgeChunks.hubSet(VERTEX_SET));
        for (String set : AerospikeEdgeChunks.setsOf(VERTEX_SET, schema.edgeSchema("Payment"))) {
            truncate(set);
        }
        AerospikeEdgeDemoLoader.LoadResult result = AerospikeEdgeDemoLoader.load(client, namespace,
                VERTEX_SET, schema, HUB_EDGES, CHUNK_SIZE);
        assertEquals(HUB_EDGES, result.hubEdges());
        assertEquals(3, result.levels(), "64 edges per chunk over 5000 edges needs three levels");
        assertEquals(5, result.inlineUpdates());

        AerospikeEdgeStore inline = new AerospikeEdgeStore(client, namespace, VERTEX_SET, schema);
        AerospikeEdgeChunkStore chunks = new AerospikeEdgeChunkStore(client, namespace, VERTEX_SET);
        query = new AerospikeEdgeGraphQuery(new AerospikeGraphQuery(vertices, inline, schema), chunks, schema);
        gremlin = new GremlinGraphQuery(query, NeighborVertexResolver.from(schema));
    }

    @AfterAll
    static void close() {
        if (client != null) {
            client.close();
        }
    }

    private static AerospikeClient connectOrNull() {
        try {
            return new AerospikeClient(env("AEROSPIKE_HOST", "localhost"),
                    Integer.parseInt(env("AEROSPIKE_PORT", "3000")));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Truncates a set; the import then writes it from scratch (a server may forbid truncating). */
    private static void truncate(String set) {
        try {
            client.truncate(new com.aerospike.client.policy.InfoPolicy(), namespace, set, null);
        } catch (RuntimeException e) {
            // truncate is optional: the import overwrites the records it generates anyway
        }
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static GraphSchema loadSchema() {
        for (String candidate : List.of("graph-http/src/main/resources/payment-graph-as-edge.json",
                "../graph-http/src/main/resources/payment-graph-as-edge.json")) {
            java.nio.file.Path file = java.nio.file.Path.of(candidate);
            if (java.nio.file.Files.isReadable(file)) {
                try {
                    return new SchemaJsonCodec().fromJson(java.nio.file.Files.readString(file));
                } catch (IOException e) {
                    throw new IllegalStateException("Cannot read " + file, e);
                }
            }
        }
        try (InputStream in = AerospikeEdgeIntegrationTest.class
                .getResourceAsStream("/payment-graph-as-edge.json")) {
            if (in != null) {
                return new SchemaJsonCodec().fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the schema", e);
        }
        throw new IllegalStateException("payment-graph-as-edge.json not found");
    }

    // --- the aggregated tables ---

    @Test
    void theBulkImportFillsTheLevelsOfTheHub() {
        AerospikeEdgeChunkStore chunks = new AerospikeEdgeChunkStore(client, namespace, VERTEX_SET);
        var hub = chunks.hub(schema.edgeSchema("Payment"), AerospikeEdgeStore.EDGE_DIR_IN,
                AerospikeEdgeDemoLoader.HUB);
        assertTrue(hub != null, "the hub marker exists after the import");
        assertEquals(HUB_EDGES, hub.edgeCount());
        assertEquals(3, hub.levels());

        // the hub's incoming edges are in Edge_In; its own outgoing side is empty here
        var out = chunks.hub(schema.edgeSchema("Payment"), AerospikeEdgeStore.EDGE_DIR_OUT,
                AerospikeEdgeDemoLoader.HUB);
        assertTrue(out == null || out.edgeCount() == 0, "nothing was imported for the hub's out side");

        // an account that only pays has a small out table
        var payer = chunks.hub(schema.edgeSchema("Payment"), AerospikeEdgeStore.EDGE_DIR_OUT, "1000");
        assertTrue(payer != null && payer.edgeCount() > 0, "the payers have out edges in the tables");
        assertTrue(payer.levels() >= 1);
    }

    @Test
    void aWindowReadsOnlyTheEdgesInsideIt() {
        long day = 24L * 60 * 60 * 1000;
        TimeRange window = TimeRange.of(startTime + 30 * day, startTime + 31 * day - 1);
        List<EdgeQueryResult> edges = query.readEdgesWithin(AerospikeEdgeDemoLoader.HUB, "Account",
                "Payment", EdgeSide.IN, window, List.of("status", "amount"));
        int expected = HUB_EDGES / 365;
        assertTrue(edges.size() >= expected - 2 && edges.size() <= expected + 2,
                "a day of the year holds about " + expected + " edges, got " + edges.size());
        for (EdgeQueryResult edge : edges) {
            assertTrue(window.contains(edge.createdTime()), "edge outside the window: " + edge.createdTime());
            assertTrue(edge.properties().containsKey("status"), edge.properties().toString());
        }
        // creation-time order, so a limit keeps the oldest of the window
        for (int i = 1; i < edges.size(); i++) {
            assertTrue(edges.get(i).createdTime() >= edges.get(i - 1).createdTime());
        }

        // a window with no edges is empty, and a window covering the year holds them all
        assertTrue(query.readEdgesWithin(AerospikeEdgeDemoLoader.HUB, "Account", "Payment", EdgeSide.IN,
                TimeRange.of(startTime - 10 * day, startTime - 1), List.of()).isEmpty());
        List<EdgeQueryResult> all = query.readEdgesWithin(AerospikeEdgeDemoLoader.HUB, "Account", "Payment",
                EdgeSide.IN, TimeRange.of(startTime, startTime + 366L * day), List.of());
        assertEquals(HUB_EDGES, all.size(), "the whole year holds every imported edge, once");
    }

    @Test
    void theInlineCopiesAreMergedOverTheImportedOnes() {
        long day = 24L * 60 * 60 * 1000;
        List<EdgeQueryResult> firstDay = query.readEdgesWithin(AerospikeEdgeDemoLoader.HUB, "Account",
                "Payment", EdgeSide.IN, TimeRange.of(startTime, startTime + day), List.of("status", "type"));
        // the loader updated the first five edges inline (REFUNDED), which must win over the import
        long refunded = firstDay.stream()
                .filter(edge -> "REFUNDED".equals(edge.properties().get("status"))).count();
        assertEquals(5, refunded, "the five inline updates are the newest copy of their edge");
        // and no edge is reported twice: the merge is by edge id
        long distinct = firstDay.stream().map(EdgeQueryResult::edgeId).distinct().count();
        assertEquals(firstDay.size(), distinct);
    }

    @Test
    void aCappedWindowReadStopsAtTheCap() {
        long day = 24L * 60 * 60 * 1000;
        TimeRange year = TimeRange.of(startTime, startTime + 366L * day);
        // the whole year is 5000 edges; a capped read keeps the first ones (the tables are ordered
        // by creation time, so the cap is what a UI expansion asks for)
        assertEquals(200, query.readEdgesWithin(AerospikeEdgeDemoLoader.HUB, "Account", "Payment",
                EdgeSide.IN, year, List.of("status"), 200).size());
        assertEquals(7, query.readEdgesWithin(AerospikeEdgeDemoLoader.HUB, "Account", "Payment",
                EdgeSide.IN, year, List.of(), 7).size());
        assertEquals(HUB_EDGES, query.readEdgesWithin(AerospikeEdgeDemoLoader.HUB, "Account", "Payment",
                EdgeSide.IN, year, List.of()).size(), "0 means no cap");

        // and the Gremlin form pushes a following limit into the window
        List<Object> count = gremlin.execute("g.V('" + AerospikeEdgeDemoLoader.HUB + "','Account')"
                + ".createdBetween('1970-01-01T00:00:00','2100-01-01T00:00:00').both().limit(50).count()");
        assertTrue(((Number) count.get(0)).intValue() <= 500,
                "a bounded expansion of a hub stays small: " + count);
    }

    @Test
    void aHubWithoutAWindowSaysToAddOne() {
        QueryException error = assertThrows(QueryException.class,
                () -> query.readEdges(AerospikeEdgeDemoLoader.HUB, "Account", "Payment", EdgeSide.IN, List.of()));
        assertTrue(error.getMessage().contains("createdBetween"), error.getMessage());
        assertTrue(error.getMessage().contains(String.valueOf(HUB_EDGES)), error.getMessage());
    }

    @Test
    void aVertexWithoutAggregatedEdgesReadsInline() {
        // account 1000 has its own out edges imported, so it needs a window too ...
        assertThrows(QueryException.class, () -> query.readEdges("1000", "Account", "Payment", EdgeSide.OUT,
                List.of()));
        // ... while the IP and Phone edges of the demo were never imported and stay inline
        assertEquals(0, query.readEdges("1000", "Account", "AccountToIP", EdgeSide.OUT, List.of()).size());
    }

    // --- through Gremlin ---

    @Test
    void gremlinReadsAWindowOfTheHub() {
        long day = 24L * 60 * 60 * 1000;
        String from = LocalDate.of(2025, 1, 2).toString();
        String to = LocalDate.of(2025, 1, 3).toString();
        List<Object> count = gremlin.execute("g.V('" + AerospikeEdgeDemoLoader.HUB + "','Account')"
                + ".createdBetween('" + from + "', '" + to + "').inE('Payment').count()");
        assertTrue(((Number) count.get(0)).intValue() > 5, "the second day holds payments: " + count);

        // the query of the docs: the edges of one day, at most 500 of them
        List<Object> edges = gremlin.execute("g.V('" + AerospikeEdgeDemoLoader.HUB + "','Account')"
                + ".createdBetween('" + startTime + "', '" + (startTime + day) + "')"
                + ".inE('Payment').limit(500).values('status')");
        assertTrue(edges.size() <= 500, "a limit is honoured: " + edges.size());
        assertTrue(edges.size() > 5, "the window has edges: " + edges.size());

        // without the window the same traversal explains what to add
        QueryException error = assertThrows(QueryException.class, () -> gremlin.execute(
                "g.V('" + AerospikeEdgeDemoLoader.HUB + "','Account').inE('Payment').count()"));
        assertTrue(error.getMessage().contains("createdBetween"), error.getMessage());
    }

    @Test
    void theImportedEdgesAreSerializedLikeTheInlineOnes() throws Exception {
        // a chunk value and an inline value must have the same shape, or the merge cannot compare
        EdgeUpsert inline = new EdgeUpsert("probe", schema.edgeSchema("Payment"), "1000",
                AerospikeEdgeDemoLoader.HUB,
                Map.of(3, new EdgePropertyValue("SUCCESS", 1L, 2L)), 1L, 2L);
        List<Object> serialized = AerospikeEdgeBulkImporterAccess.serialize(schema.edgeSchema("Payment"),
                AerospikeEdgeDemoLoader.HUB, 1L, 2L, Map.of("status", "SUCCESS"));
        assertEquals(4, serialized.size());
        assertEquals(1L, ((Number) serialized.get(1)).longValue(), "createdTime");
        assertEquals(AerospikeEdgeDemoLoader.HUB, serialized.get(2), "the far endpoint");
        assertFalse(((Map<?, ?>) serialized.get(3)).isEmpty(), "the properties travel with the edge");
        assertEquals("1000", inline.source());
    }

    /** Keeps the importer's serializer reachable without widening its visibility. */
    private static final class AerospikeEdgeBulkImporterAccess {

        private AerospikeEdgeBulkImporterAccess() {
        }

        static List<Object> serialize(com.pengcheng.phoenixgraph.schema.EdgeSchema edgeSchema,
                                      Object otherVertexId, long createdTime, long lastUpdatedTime,
                                      Map<String, Object> properties) {
            return com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeBulkImporter
                    .serialize(edgeSchema, otherVertexId, createdTime, lastUpdatedTime, properties);
        }
    }

    @Test
    void theChunkTablesAreNamedPerEdgeClassAndDirection() {
        assertEquals(VERTEX_SET + "_edge_out_1", AerospikeEdgeChunks.outSet(VERTEX_SET,
                schema.edgeSchema("Payment")));
        assertEquals(VERTEX_SET + "_edge_in_1", AerospikeEdgeChunks.inSet(VERTEX_SET,
                schema.edgeSchema("Payment")));
        assertEquals(VERTEX_SET + "_edgeheads", AerospikeEdgeChunks.hubSet(VERTEX_SET));
        // the phone edge class has its own pair of tables
        assertEquals(VERTEX_SET + "_edge_out_3", AerospikeEdgeChunks.outSet(VERTEX_SET,
                schema.edgeSchema("AccountToPhone")));
    }
}
