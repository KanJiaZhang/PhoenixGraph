package com.pengcheng.phoenixgraph.integration;

import com.aerospike.client.AerospikeClient;
import com.pengcheng.phoenixgraph.query.aerospike.AerospikeEdgeGraphQuery;
import com.pengcheng.phoenixgraph.query.aerospike.AerospikeGraphQuery;
import com.pengcheng.phoenixgraph.query.storage.EdgeQueryResult;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
import com.pengcheng.phoenixgraph.schema.EdgeDirection;
import com.pengcheng.phoenixgraph.schema.EdgeEndpointSchema;
import com.pengcheng.phoenixgraph.schema.EdgePropertySchema;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.MetaPropertySchema;
import com.pengcheng.phoenixgraph.schema.PropertyType;
import com.pengcheng.phoenixgraph.schema.VertexPropertySchema;
import com.pengcheng.phoenixgraph.schema.VertexSchema;
import com.pengcheng.phoenixgraph.storage.TimeRange;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeBulkImporter;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeChunkStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeChunks;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeOfflineJob;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeNearline;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeNearlineStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVolumeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.EdgePropertyValue;
import com.pengcheng.phoenixgraph.storage.aerospike.EdgeTiering;
import com.pengcheng.phoenixgraph.storage.aerospike.EdgeUpsert;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The three tiers of the Aerospike-Edge scheme against a real server: cold edges in the offline
 * tables, near-line edges in the TTL'd KVs of the last 24 hours, and online edges inline in the
 * vertex record. A read is routed by which creation-time spans its window overlaps, and the
 * copies are merged by edge id.
 *
 * <p>Skipped when no Aerospike is reachable.
 */
class AerospikeEdgeTieringIntegrationTest {

    private static final String VERTEX_SET = "as_edge_tier_it";

    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final long HOUR = 60L * 60 * 1000;
    /** A fixed "now" so the tier boundaries are deterministic. */
    private static final long NOW = Instant.parse("2026-09-21T12:00:00Z").toEpochMilli();

    private static final String HUB = "tier_hub";
    private static final String ACCT = "tier_acct";

    private static final int COLD_EDGES = 2_000;
    private static final int NEARLINE_EDGES = 40;
    private static final int ONLINE_EDGES = 3;

    private static AerospikeClient client;
    private static String namespace;
    private static GraphSchema schema;
    private static EdgeSchema payment;
    private static AerospikeEdgeGraphQuery query;

    @BeforeAll
    static void loadTheScenario() {
        client = connectOrNull();
        assumeTrue(client != null, "Aerospike not available, skipping the tiering integration test");
        namespace = env("AEROSPIKE_NAMESPACE", "test");
        schema = schema();
        payment = schema.edgeSchema("Payment");

        // a fresh set: truncating is optional (the writes overwrite), but keeps re-runs deterministic
        for (String set : List.of(VERTEX_SET, AerospikeEdgeChunks.hubSet(VERTEX_SET),
                AerospikeNearline.dataSet(VERTEX_SET), AerospikeNearline.indexSet(VERTEX_SET),
                VERTEX_SET + "_edge_volume")) {
            truncate(set);
        }
        for (String set : AerospikeEdgeChunks.setsOf(VERTEX_SET, payment)) {
            truncate(set);
        }

        AerospikeVertexStore vertices = new AerospikeVertexStore(client, namespace, VERTEX_SET);
        vertices.upsertVertex(HUB, Map.of(), NOW - 400 * DAY, NOW);
        vertices.upsertVertex(ACCT, Map.of(), NOW - 400 * DAY, NOW);

        // 1. cold: a year of the hub's incoming payments, ending 25 hours ago (bulk import)
        long coldStart = NOW - 365 * DAY;
        long coldEnd = NOW - DAY - HOUR;
        List<AerospikeEdgeChunks.EdgeEntry> coldEdges = new ArrayList<>();
        Map<String, Object> sourceByEdgeId = new LinkedHashMap<>();
        Map<String, Object> destinationByEdgeId = new LinkedHashMap<>();
        for (int i = 0; i < COLD_EDGES; i++) {
            String edgeId = "c_" + i;
            long created = coldStart + (coldEnd - coldStart) * i / Math.max(1, COLD_EDGES - 1);
            coldEdges.add(entry(edgeId, ACCT, created, created, "SUCCESS"));
            sourceByEdgeId.put(edgeId, ACCT);
            destinationByEdgeId.put(edgeId, HUB);
        }
        AerospikeEdgeBulkImporter importer = new AerospikeEdgeBulkImporter(client, namespace, VERTEX_SET,
                schema, 64, new com.pengcheng.phoenixgraph.storage.log.JulBusinessLog());
        importer.importEdges(payment, coldEdges, sourceByEdgeId, destinationByEdgeId);

        // 2. near-line: the last hour, in the TTL'd KVs (pre-partitioned by the volume store)
        AerospikeVolumeStore volume = new AerospikeVolumeStore(client, namespace, VERTEX_SET);
        volume.put(payment.id(), AerospikeEdgeStore.EDGE_DIR_IN, HUB, Map.of(
                AerospikeVolumeStore.monthBin(NOW), (long) COLD_EDGES + NEARLINE_EDGES,
                AerospikeVolumeStore.yearBin(NOW), (long) COLD_EDGES + NEARLINE_EDGES));
        AerospikeNearlineStore nearline = new AerospikeNearlineStore(client, namespace, VERTEX_SET);
        nearline.prePartition(HUB, payment.id(), AerospikeEdgeStore.EDGE_DIR_IN,
                AerospikeNearline.dayOf(NOW), AerospikeNearline.partitionCount(COLD_EDGES + NEARLINE_EDGES));
        Map<String, List<Object>> nearlineEdges = new LinkedHashMap<>();
        for (int i = 0; i < NEARLINE_EDGES; i++) {
            nearlineEdges.put("n_" + i, entry("n_" + i, ACCT, NOW - HOUR, NOW - HOUR, "PENDING").value());
        }
        nearline.write(HUB, payment.id(), AerospikeEdgeStore.EDGE_DIR_IN, NOW - HOUR, nearlineEdges);

        // 3. online: the newest few edges, inline in the vertex record
        AerospikeEdgeStore inline = new AerospikeEdgeStore(client, namespace, VERTEX_SET, schema);
        for (int i = 0; i < ONLINE_EDGES; i++) {
            long created = NOW - 10 * 60_000L + i;
            Map<Integer, EdgePropertyValue> properties = Map.of(
                    payment.property("status").id(), new EdgePropertyValue("NEW", created, created));
            inline.upsertEdge(new EdgeUpsert("o_" + i, payment, ACCT, HUB, properties, created, created));
        }

        query = new AerospikeEdgeGraphQuery(
                new AerospikeGraphQuery(vertices, inline, schema),
                new AerospikeEdgeChunkStore(client, namespace, VERTEX_SET), schema, nearline,
                Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC),
                EdgeTiering.DEFAULT_NEARLINE_WINDOW_MILLIS);
    }

    @AfterAll
    static void close() {
        if (client != null) {
            client.close();
        }
    }

    private static EdgeSchema schemaEdge() {
        return new EdgeSchema(1, "Payment", "a payment",
                new EdgePropertySchema(0, "id", null, PropertyType.ScalarType.STRING),
                new EdgeEndpointSchema(1, "source", null, PropertyType.ScalarType.STRING, "Account"),
                new EdgeEndpointSchema(2, "target", null, PropertyType.ScalarType.STRING, "Account"),
                MetaPropertySchema.createdTime(), MetaPropertySchema.lastUpdatedTime(),
                EdgeDirection.DIRECTED, 0, 0, 0).property(
                new EdgePropertySchema(3, "status", null, PropertyType.ScalarType.STRING));
    }

    private static GraphSchema schema() {
        return new GraphSchema(1, "tier", "tiered demo")
                .vertex(new VertexSchema(1, "Account", "an account",
                        new VertexPropertySchema(0, "id", null, PropertyType.ScalarType.STRING)))
                .edge(schemaEdge());
    }

    private static AerospikeEdgeChunks.EdgeEntry entry(String edgeId, Object otherVertexId,
                                                       long created, long updated, String status) {
        Map<String, Object> props = Map.of("status", status);
        List<Object> value = AerospikeEdgeBulkImporter.serialize(paymentSchema(), otherVertexId,
                created, updated, props);
        return new AerospikeEdgeChunks.EdgeEntry(edgeId, otherVertexId, created, updated, value);
    }

    /** The schema is built in {@link #loadTheScenario}; entries need the Payment schema for serialization. */
    private static EdgeSchema paymentSchema() {
        return schema.edgeSchema("Payment");
    }

    private static AerospikeClient connectOrNull() {
        try {
            return new AerospikeClient(env("AEROSPIKE_HOST", "localhost"),
                    Integer.parseInt(env("AEROSPIKE_PORT", "3000")));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Truncates a set; the writes then start from scratch (a server may forbid truncating). */
    private static void truncate(String set) {
        try {
            client.truncate(new com.aerospike.client.policy.InfoPolicy(), namespace, set, null);
        } catch (RuntimeException e) {
            // truncate is optional: the writes overwrite the records they generate anyway
        }
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    // --- the tiers ---

    @Test
    void aRecentWindowReadsOnlineAndNearlineAndMergesThem() {
        TimeRange recent = TimeRange.of(NOW - 2 * HOUR, NOW);
        List<EdgeQueryResult> edges = query.readEdgesWithin(HUB, "Account", "Payment", EdgeSide.IN,
                recent, List.of("status"));
        assertEquals(NEARLINE_EDGES + ONLINE_EDGES, edges.size(),
                "the last two hours hold the near-line and online edges");
        long distinct = edges.stream().map(EdgeQueryResult::edgeId).distinct().count();
        assertEquals(edges.size(), distinct, "no edge is reported twice");
    }

    @Test
    void aColdWindowReadsColdAlone() {
        long dayStart = NOW - 10 * DAY;
        TimeRange cold = TimeRange.of(dayStart, dayStart + DAY - 1);
        List<EdgeQueryResult> edges = query.readEdgesWithin(HUB, "Account", "Payment", EdgeSide.IN,
                cold, List.of("status"));
        assertTrue(edges.size() >= 3 && edges.size() <= 8,
                "a day of the year holds about " + COLD_EDGES / 365 + " edges, got " + edges.size());
        for (EdgeQueryResult edge : edges) {
            assertTrue(edge.edgeId().startsWith("c_"), "only cold edges: " + edge.edgeId());
        }
    }

    @Test
    void aWindowSpanningColdNearlineAndOnlineMergesAllThree() {
        TimeRange span = TimeRange.of(NOW - 2 * DAY, NOW);
        List<EdgeQueryResult> edges = query.readEdgesWithin(HUB, "Account", "Payment", EdgeSide.IN,
                span, List.of("status"));
        long distinct = edges.stream().map(EdgeQueryResult::edgeId).distinct().count();
        assertEquals(edges.size(), distinct);
        // cold edges from the last two days + near-line + online
        assertTrue(edges.size() >= NEARLINE_EDGES + ONLINE_EDGES + COLD_EDGES / 365,
                "the span holds all three tiers: " + edges.size());
    }

    // --- the offline job ---

    @Test
    void theOfflineJobRecordsVolumePrePartitionsNearlineAndAppliesTheColdDelta() {
        AerospikeVolumeStore volume = new AerospikeVolumeStore(client, namespace, VERTEX_SET);
        AerospikeNearlineStore nearline = new AerospikeNearlineStore(client, namespace, VERTEX_SET);
        AerospikeEdgeChunkStore cold = new AerospikeEdgeChunkStore(client, namespace, VERTEX_SET);
        AerospikeEdgeOfflineJob job = new AerospikeEdgeOfflineJob(volume, nearline, cold, 64);

        // the latest cold day (ending 25h ago) gets one updated edge - a delta the offline job
        // folds into the cold tables
        long dayStart = NOW - 2 * DAY;
        long coldEnd = NOW - DAY - HOUR;
        List<AerospikeEdgeChunks.EdgeEntry> deltas = List.of(
                entry("c_" + (COLD_EDGES - 1), ACCT, coldEnd, NOW - DAY, "REFUNDED"));

        AerospikeEdgeOfflineJob.Result result = job.daily(payment, AerospikeEdgeStore.EDGE_DIR_IN,
                HUB, dayStart, dayStart + DAY - 1, deltas,
                Map.of(AerospikeVolumeStore.monthBin(NOW), (long) COLD_EDGES + NEARLINE_EDGES,
                        AerospikeVolumeStore.yearBin(NOW), (long) COLD_EDGES + NEARLINE_EDGES),
                COLD_EDGES + NEARLINE_EDGES + ONLINE_EDGES, 10);

        assertTrue(result.big(), "2043 edges far exceed a cap of 10");
        assertEquals(COLD_EDGES + NEARLINE_EDGES, result.latestMonthlyVolume());
        assertEquals(AerospikeNearline.partitionCount(COLD_EDGES + NEARLINE_EDGES),
                result.nearlinePartitions(), "the near-line day is cut from the volume");
        assertTrue(result.coldRecords() > 0, "the cold delta wrote records");

        // the delta is now visible through the cold tier alone (a cold window ignores online/near-line)
        List<EdgeQueryResult> day = query.readEdgesWithin(HUB, "Account", "Payment", EdgeSide.IN,
                TimeRange.of(coldEnd, coldEnd + 1), List.of("status"));
        assertTrue(day.stream().anyMatch(e -> "REFUNDED".equals(e.properties().get("status"))),
                "the delta was folded into the cold tables");
    }
}
