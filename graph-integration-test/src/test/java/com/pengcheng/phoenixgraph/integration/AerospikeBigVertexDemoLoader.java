package com.pengcheng.phoenixgraph.integration;

import com.aerospike.client.AerospikeClient;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.VertexSchema;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeBulkImporter;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeChunks;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeNearline;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeNearlineStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVolumeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.EdgePropertyValue;
import com.pengcheng.phoenixgraph.storage.aerospike.EdgeUpsert;
import com.pengcheng.phoenixgraph.storage.aerospike.VertexPropertyValue;
import com.pengcheng.phoenixgraph.storage.aerospike.VertexUpsert;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The big-vertex demo of the Aerospike-Edge scheme: several <em>big</em> vertices, each covering a
 * different combination of the three tiers the scheme spreads a big vertex over, written into the
 * vertex set the UI serves as {@code payment-graph-as-edge}.
 *
 * <p>A vertex is big when its offline degree exceeds the schema's per-direction cap ({@code Payment}
 * caps at 1000 in and out), and a big vertex's edges live in three tiers with disjoint creation-time
 * spans:
 *
 * <pre>
 * cold      (-infinity, now - 25h]   the aggregated Edge_Out / Edge_In tables (offline import)
 * nearline  (now - 23h, now - 1h]    the TTL'd near-line KVs, pre-partitioned from the volume
 * online    [now - 10m, now]         the inline adjacency bin, under the schema's cap
 * </pre>
 *
 * <p>The five vertices are the five read cases of the spec, one per vertex:
 *
 * <table>
 *   <caption>what each demo vertex holds</caption>
 *   <tr><th>vertex</th><th>direction</th><th>tiers</th><th>the read it demonstrates</th></tr>
 *   <tr><td>{@link #HUB_ALL}</td><td>in</td><td>cold + nearline + online</td>
 *       <td>a window over the last day merges all three</td></tr>
 *   <tr><td>{@link #HUB_NEW}</td><td>in</td><td>nearline + online</td>
 *       <td>a big vertex that has nothing cold yet</td></tr>
 *   <tr><td>{@link #HUB_COLD}</td><td>in</td><td>cold</td>
 *       <td>a quiet vertex: everything is older than the near-line window</td></tr>
 *   <tr><td>{@link #HUB_OUT}</td><td>out</td><td>cold + nearline + online</td>
 *       <td>the same three tiers on the outgoing side (a big payer)</td></tr>
 *   <tr><td>{@link #BELOW_CAP}</td><td>in</td><td>online</td>
 *       <td>a vertex under the cap: not big, so no window is required</td></tr>
 * </table>
 *
 * <p>Two edge ids are written twice on purpose: one is in the cold tables and again in the near-line
 * with a newer update, one is in the near-line and again inline. A merged read must report each id
 * once, with the newest copy - that is the rule the whole store follows.
 *
 * <p>The volume record is what cuts the near-line day into partitions: the demo declares the spec's
 * monthly volume (100 million edges), which is {@code ceil(1e8 / 30 / 4000) = 834} key-values a day
 * (the spec's "1亿 / 30 / 4000 … say 1000").
 *
 * <pre>
 * # write the demo into the graph the UI serves, then query it:
 * ./mvnw -pl graph-integration-test -am test -Dtest=AerospikeEdgeBigVertexDemoTest \
 *        -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
public final class AerospikeBigVertexDemoLoader {

    /** The graph the demo is served as. */
    public static final String GRAPH = "payment-graph-as-edge";

    /** The vertex set behind that graph (the UI reads this one). */
    public static final String VERTEX_SET = "payment_as_edge_v1";

    /** Big in both the cold tables and the near-line, plus a few live edges. */
    public static final String HUB_ALL = "9000002";

    /** Big, but nothing cold yet: the near-line and the inline bin only. */
    public static final String HUB_NEW = "9000003";

    /** Big and quiet: every edge is older than the near-line window. */
    public static final String HUB_COLD = "9000004";

    /** Big on the outgoing side: a payer whose payments go out to the counterparties. */
    public static final String HUB_OUT = "9000005";

    /** Under the schema's cap, so not big at all. */
    public static final String BELOW_CAP = "9000006";

    /** The accounts the demo pays to / is paid by. */
    public static final long FIRST_ACCOUNT = 8_000_000L;

    /** How many counterparty accounts the demo holds. */
    public static final int ACCOUNT_COUNT = 10;

    public static final long MINUTE = 60_000L;
    public static final long HOUR = 60L * MINUTE;
    public static final long DAY = 24L * HOUR;

    /** Where the cold span starts: a year of history. */
    public static final long COLD_DAYS = 365;

    /** The cold span ends 25 hours ago: the last day is already outside the near-line window. */
    public static final long COLD_ENDS_AGO = 25 * HOUR;

    /** The near-line span: inside the last 24 hours, but not the last hour (the online tier's). */
    public static final long NEARLINE_FROM_AGO = 23 * HOUR;
    public static final long NEARLINE_TO_AGO = HOUR;

    /** The online span: the last few minutes, inline in the vertex record. */
    public static final long ONLINE_FROM_AGO = 10 * MINUTE;

    /** Cold edges per big vertex (chunked by the importer, so a day holds ~16 of them). */
    public static final int COLD_EDGES = 6_000;

    /** Near-line edges per big vertex (spread over the near-line span). */
    public static final int NEARLINE_EDGES = 900;

    /** Inline edges per big vertex (the live tail). */
    public static final int ONLINE_EDGES = 5;

    /**
     * The monthly volume the demo declares: the spec's 100 million edges a month, which is what cuts
     * the near-line day into {@link #expectedPartitions()} key-values.
     */
    public static final long DECLARED_MONTHLY_VOLUME = 100_000_000L;

    /** The edge id in both the cold tables and the near-line, with a newer copy near-line. */
    public static final String UPDATED_NEARLINE_EDGE = "bv_all_c_0";

    /** The edge id in both the near-line and the inline bin, with a newer copy inline. */
    public static final String UPDATED_ONLINE_EDGE = "bv_all_n_0";

    private static final String[] STATUS = {"SUCCESS", "PENDING", "FAILED"};

    private AerospikeBigVertexDemoLoader() {
    }

    /** The partitions one day of a 100M-a-month vertex is cut into. */
    public static int expectedPartitions() {
        return AerospikeNearline.partitionCount(DECLARED_MONTHLY_VOLUME);
    }

    /**
     * Clears every set the demo writes into: the vertex set itself, the cold chunk sets of the Payment
     * class, the near-line data/index sets and the volume set. A demo run does not need it (the writes
     * overwrite the records they generate), but a set that was used by an older revision of the demo
     * keeps edges under ids that revision used, and a reset is the only way to drop them: an edge id is
     * a record, so "remove the ones with a different naming" is not a deletion the store can do by
     * range.
     *
     * <p>Truncating is optional on a server that forbids it; a failed truncate is ignored, and the load
     * is still correct (it only leaves the older ids in place).
     */
    public static List<String> truncate(AerospikeClient client, String namespace, String vertexSet,
                                        GraphSchema schema) {
        List<String> truncated = new ArrayList<>();
        List<String> sets = new ArrayList<>(List.of(vertexSet,
                AerospikeNearline.dataSet(vertexSet), AerospikeNearline.indexSet(vertexSet),
                vertexSet + "_edge_volume"));
        sets.addAll(AerospikeEdgeChunks.setsOf(vertexSet, schema.edgeSchema("Payment")));
        for (String set : sets) {
            try {
                client.truncate(new com.aerospike.client.policy.InfoPolicy(), namespace, set, null);
                truncated.add(set);
            } catch (RuntimeException ignored) {
                // truncate is optional
            }
        }
        return truncated;
    }

    /** What a load wrote, and the time spans the queries have to use. */
    public record LoadResult(String graph, String vertexSet, long now, long coldFrom, long coldTo,
                             long nearlineFrom, long nearlineTo, long onlineFrom, int vertices,
                             Map<String, Integer> edgesPerVertex, int nearlinePartitions,
                             long declaredMonthlyVolume) {

        /** The example window that covers all three tiers. */
        public String allTiersWindow() {
            return "createdBetween('-2d', '*')";
        }
    }

    /** Writes the demo into {@link #VERTEX_SET}, anchored to now. */
    public static LoadResult load(AerospikeClient client, String namespace, GraphSchema schema) {
        return load(client, namespace, VERTEX_SET, schema, System.currentTimeMillis());
    }

    /** Writes the demo into the given vertex set, anchored to now. */
    public static LoadResult load(AerospikeClient client, String namespace, String vertexSet,
                                  GraphSchema schema) {
        return load(client, namespace, vertexSet, schema, System.currentTimeMillis());
    }

    /** Writes the demo into the given vertex set, anchored to {@code now}. */
    public static LoadResult load(AerospikeClient client, String namespace, String vertexSet,
                                  GraphSchema schema, long now) {
        VertexSchema account = schema.vertexSchema("Account");
        EdgeSchema payment = schema.edgeSchema("Payment");
        AerospikeVertexStore vertices = new AerospikeVertexStore(client, namespace, vertexSet);
        AerospikeEdgeStore inline = new AerospikeEdgeStore(client, namespace, vertexSet, schema);
        AerospikeEdgeBulkImporter importer = new AerospikeEdgeBulkImporter(client, namespace, vertexSet,
                schema, AerospikeEdgeChunks.DEFAULT_CHUNK_SIZE,
                new com.pengcheng.phoenixgraph.storage.log.JulBusinessLog());
        AerospikeNearlineStore nearline = new AerospikeNearlineStore(client, namespace, vertexSet);
        AerospikeVolumeStore volume = new AerospikeVolumeStore(client, namespace, vertexSet);

        long coldFrom = now - COLD_DAYS * DAY;
        long coldTo = now - COLD_ENDS_AGO;
        long nearlineFrom = now - NEARLINE_FROM_AGO;
        long nearlineTo = now - NEARLINE_TO_AGO;
        long onlineFrom = now - ONLINE_FROM_AGO;
        String day = AerospikeNearline.dayOf(now);

        // 1. the vertices: the five hubs plus the counterparties they trade with
        long created = coldFrom;
        List<VertexUpsert> upserts = new ArrayList<>();
        for (String hub : List.of(HUB_ALL, HUB_NEW, HUB_COLD, HUB_OUT, BELOW_CAP)) {
            upserts.add(new VertexUpsert(hub, account, accountProperties(account, hub, "hub"),
                    created, now));
        }
        for (int i = 0; i < ACCOUNT_COUNT; i++) {
            upserts.add(new VertexUpsert(account(i), account,
                    accountProperties(account, account(i), "acct"), created, now));
        }
        vertices.upsertVertices(upserts);

        Map<String, Integer> written = new LinkedHashMap<>();
        // 2. cold + near-line + online, on the incoming side
        written.put(HUB_ALL, writeColdTier(client, namespace, vertexSet, schema, payment, importer,
                HUB_ALL, AerospikeEdgeStore.EDGE_DIR_IN, "bv_all", coldFrom, coldTo));
        written.put(HUB_ALL, written.get(HUB_ALL) + writeNearlineTier(nearline, volume, payment,
                HUB_ALL, AerospikeEdgeStore.EDGE_DIR_IN, "bv_all", nearlineFrom, nearlineTo, day, true)
                + writeOnlineTier(inline, payment, HUB_ALL, AerospikeEdgeStore.EDGE_DIR_IN, "bv_all",
                onlineFrom, now, true));
        // 3. near-line + online only: big, but nothing cold yet
        written.put(HUB_NEW, writeNearlineTier(nearline, volume, payment, HUB_NEW,
                AerospikeEdgeStore.EDGE_DIR_IN, "bv_new", nearlineFrom, nearlineTo, day, false)
                + writeOnlineTier(inline, payment, HUB_NEW, AerospikeEdgeStore.EDGE_DIR_IN, "bv_new",
                onlineFrom, now, false));
        // 4. cold only: every edge predates the near-line window
        written.put(HUB_COLD, writeColdTier(client, namespace, vertexSet, schema, payment, importer,
                HUB_COLD, AerospikeEdgeStore.EDGE_DIR_IN, "bv_cold", coldFrom, coldTo));
        // 5. the same three tiers on the outgoing side
        written.put(HUB_OUT, writeColdTier(client, namespace, vertexSet, schema, payment, importer,
                HUB_OUT, AerospikeEdgeStore.EDGE_DIR_OUT, "bv_out", coldFrom, coldTo));
        written.put(HUB_OUT, written.get(HUB_OUT) + writeNearlineTier(nearline, volume, payment,
                HUB_OUT, AerospikeEdgeStore.EDGE_DIR_OUT, "bv_out", nearlineFrom, nearlineTo, day, false)
                + writeOnlineTier(inline, payment, HUB_OUT, AerospikeEdgeStore.EDGE_DIR_OUT, "bv_out",
                onlineFrom, now, false));
        // 6. under the cap: not big, so it needs no window
        written.put(BELOW_CAP, writeOnlineTier(inline, payment, BELOW_CAP, AerospikeEdgeStore.EDGE_DIR_IN,
                "bv_small", now - 3 * DAY, now, false));

        return new LoadResult(GRAPH, vertexSet, now, coldFrom, coldTo, nearlineFrom, nearlineTo,
                onlineFrom, upserts.size(), Map.copyOf(written), expectedPartitions(),
                DECLARED_MONTHLY_VOLUME);
    }

    /** A year of payments into (or out of) the hub, imported through the aggregated cold tables. */
    private static int writeColdTier(AerospikeClient client, String namespace, String vertexSet,
                                     GraphSchema schema, EdgeSchema payment,
                                     AerospikeEdgeBulkImporter importer, String hub, int direction,
                                     String prefix, long from, long to) {
        boolean incoming = direction == AerospikeEdgeStore.EDGE_DIR_IN;
        List<AerospikeEdgeChunks.EdgeEntry> edges = new ArrayList<>(COLD_EDGES);
        Map<String, Object> sourceByEdgeId = new LinkedHashMap<>(COLD_EDGES * 2);
        Map<String, Object> destinationByEdgeId = new LinkedHashMap<>(COLD_EDGES * 2);
        for (int i = 0; i < COLD_EDGES; i++) {
            String edgeId = prefix + "_c_" + i;
            long at = from + (to - from) * i / Math.max(1, COLD_EDGES - 1);
            String account = account(i % ACCOUNT_COUNT);
            // the stored value carries the far vertex, so it is built for the side this import serves:
            // the cold tables keep one row per side and the value's endpoint must match it
            List<Object> value = AerospikeEdgeBulkImporter.serialize(payment, account, at, at,
                    properties(i, status(i)));
            edges.add(new AerospikeEdgeChunks.EdgeEntry(edgeId, account, at, at, value));
            sourceByEdgeId.put(edgeId, incoming ? account : hub);
            destinationByEdgeId.put(edgeId, incoming ? hub : account);
        }
        importer.importEdges(payment, edges, sourceByEdgeId, destinationByEdgeId);
        return edges.size();
    }

    /**
     * The near-line tier: the pre-partitioned index record (cut from the declared volume) and the
     * edges of the last day in the TTL'd data KVs.
     */
    private static int writeNearlineTier(AerospikeNearlineStore nearline, AerospikeVolumeStore volume,
                                         EdgeSchema payment, String hub, int direction, String prefix,
                                         long from, long to, String day, boolean withUpdatedCopy) {
        volume.put(payment.id(), direction, hub, Map.of(
                AerospikeVolumeStore.monthBin(from), DECLARED_MONTHLY_VOLUME,
                AerospikeVolumeStore.yearBin(from), DECLARED_MONTHLY_VOLUME * 12));

        // one entry per edge id with the time it was created: the store files an edge in the day of
        // the created time it is given, so the calls below are grouped by day and each day is
        // pre-partitioned at the declared volume before its edges are written
        Map<String, Long> createdByEdgeId = new LinkedHashMap<>();
        Map<String, Integer> indexByEdgeId = new LinkedHashMap<>();
        if (withUpdatedCopy) {
            // the cold copy of this id gets a newer near-line copy: a merged read must take this one
            createdByEdgeId.put(UPDATED_NEARLINE_EDGE, to - 2 * HOUR);
            indexByEdgeId.put(UPDATED_NEARLINE_EDGE, 0);
        }
        for (int i = 0; i < NEARLINE_EDGES; i++) {
            String edgeId = prefix + "_n_" + i;
            createdByEdgeId.put(edgeId, from + (to - from) * i / Math.max(1, NEARLINE_EDGES - 1));
            indexByEdgeId.put(edgeId, i);
        }

        Map<String, Map<String, List<Object>>> byDay = new LinkedHashMap<>();
        Map<String, Long> firstTimeOfDay = new LinkedHashMap<>();
        for (Map.Entry<String, Long> edge : createdByEdgeId.entrySet()) {
            String edgeDay = AerospikeNearline.dayOf(edge.getValue());
            int index = indexByEdgeId.get(edge.getKey());
            firstTimeOfDay.putIfAbsent(edgeDay, edge.getValue());
            // the updated copy of the cold edge carries a status the cold copy does not, so a merged
            // read can be seen to have taken the newer copy
            String edgeStatus = edge.getKey().equals(UPDATED_NEARLINE_EDGE) ? "REFUNDED" : status(index);
            byDay.computeIfAbsent(edgeDay, d -> new LinkedHashMap<>()).put(edge.getKey(),
                    AerospikeEdgeBulkImporter.serialize(payment, account(index % ACCOUNT_COUNT),
                            edge.getValue(), edge.getValue(), properties(index, edgeStatus)));
        }
        int dataKvs = 0;
        for (Map.Entry<String, Map<String, List<Object>>> dayGroup : byDay.entrySet()) {
            nearline.prePartition(hub, payment.id(), direction, dayGroup.getKey(), expectedPartitions());
            dataKvs += nearline.write(hub, payment.id(), direction,
                    firstTimeOfDay.get(dayGroup.getKey()), dayGroup.getValue());
        }
        return createdByEdgeId.size();
    }

    /** The inline tail: a few live edges in the vertex record, under the schema's degree cap. */
    private static int writeOnlineTier(AerospikeEdgeStore inline, EdgeSchema payment, String hub,
                                       int direction, String prefix, long from, long to,
                                       boolean withUpdatedCopy) {
        boolean incoming = direction == AerospikeEdgeStore.EDGE_DIR_IN;
        int written = 0;
        if (withUpdatedCopy) {
            // the near-line copy of this id gets a newer inline copy
            written += upsertInline(inline, payment, hub, incoming, UPDATED_ONLINE_EDGE, to - MINUTE,
                    "REFUNDED");
        }
        for (int i = 0; i < ONLINE_EDGES; i++) {
            long at = from + (to - from) * i / Math.max(1, ONLINE_EDGES - 1);
            written += upsertInline(inline, payment, hub, incoming, prefix + "_o_" + i, at, "NEW");
        }
        return written;
    }

    private static int upsertInline(AerospikeEdgeStore inline, EdgeSchema payment, String hub,
                                    boolean incoming, String edgeId, long at, String status) {
        String account = account(Math.abs(edgeId.hashCode()) % ACCOUNT_COUNT);
        String source = incoming ? account : hub;
        String target = incoming ? hub : account;
        Map<Integer, EdgePropertyValue> properties = new LinkedHashMap<>();
        properties.put(payment.property("status").id(), new EdgePropertyValue(status, at, at));
        properties.put(payment.property("type").id(), new EdgePropertyValue("transfer", at, at));
        properties.put(payment.property("amount").id(), new EdgePropertyValue(1.0d, at, at));
        properties.put(payment.property("currency").id(), new EdgePropertyValue("USD", at, at));
        inline.upsertEdge(new EdgeUpsert(edgeId, payment, source, target, properties, at, at));
        return 1;
    }

    /** The properties of one generated payment. */
    private static Map<String, Object> properties(int index, String status) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("status", status);
        properties.put("type", index % 3 == 0 ? "invoice" : "transfer");
        properties.put("amount", 1.0d + (index % 500));
        properties.put("currency", "USD");
        return properties;
    }

    private static String status(int index) {
        return STATUS[index % STATUS.length];
    }

    /** A counterparty account id. */
    public static String account(int index) {
        return String.valueOf(FIRST_ACCOUNT + index);
    }

    /** An account's properties, keyed by the property indexes of the schema. */
    private static Map<Integer, VertexPropertyValue> accountProperties(VertexSchema account, String id,
                                                                      String kind) {
        long now = LocalDate.of(2025, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        Map<String, Object> byName = new LinkedHashMap<>();
        byName.put("accountStatus", kind.equals("hub") ? "ACTIVE" : "ACTIVE");
        byName.put("accountType", "savings");
        byName.put("primaryAddress", "addr-" + id);
        byName.put("accountUsage", List.of("Personal", "Business"));
        byName.put("accountLimited", Map.of("174", "open", "175", "kyc"));
        byName.put("firstName", kind + "-first-" + id);
        byName.put("lastName", kind + "-last-" + id);
        Map<Integer, VertexPropertyValue> values = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : byName.entrySet()) {
            values.put(account.property(entry.getKey()).id(), new VertexPropertyValue(entry.getValue(), now));
        }
        return values;
    }
}
