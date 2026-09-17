package com.pengcheng.phoenixgraph.integration;

import com.aerospike.client.AerospikeClient;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.VertexSchema;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeChunks;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeBulkImporter;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
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
 * The Aerospike-Edge demo: the payment graph copied into {@code payment-graph-as-edge}, whose
 * big vertices keep their edges in the aggregated tables.
 *
 * <p>The interesting vertex is {@link #HUB} - a merchant account with
 * {@link #HUB_TRANSACTIONS} received payments spread over {@link #DEMO_YEAR} - far more than the
 * degree limit of the inline layout could hold and than one record could store. Its edges are
 * written the way a real bulk import does it: the importer groups them by creation time, folds
 * the chunks into the levels above and writes every generated key/value in batches
 * ({@link AerospikeEdgeBulkImporter}); nothing is written edge by edge.
 *
 * <p>A few of the hub's edges are then written through the ordinary inline path, the way a
 * near-line update would arrive, so a query can be seen to merge both sources (the inline copy
 * of an edge wins when it is newer).
 *
 * <pre>
 * ./mvnw -pl graph-integration-test -am test -Dtest=AerospikeEdgeLiveLoadTest \
 *        -DAEROSPIKE_AS_EDGE_SET=payment_as_edge_v1 -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
public final class AerospikeEdgeDemoLoader {

    /** The graph the demo is served as. */
    public static final String GRAPH = "payment-graph-as-edge";

    /** The hub account: a merchant receiving payments all year. */
    public static final String HUB = "9000001";

    /** How many payments the hub receives over the year. */
    public static final int HUB_TRANSACTIONS = 300_000;

    /** How many other accounts the demo holds. */
    public static final int ACCOUNT_COUNT = 50;

    /** The first of the other accounts (the hub pays them too). */
    public static final long FIRST_ACCOUNT = 1_000L;

    /** The year the demo transactions are spread over. */
    public static final LocalDate DEMO_YEAR = LocalDate.of(2025, 1, 1);

    /** How many edges the hub's edges are cut into per chunk (the import's chunk size). */
    public static final int CHUNK_SIZE = AerospikeEdgeChunks.DEFAULT_CHUNK_SIZE;

    /** How many edges a one-day window of the demo holds (the query in the docs asks for 500). */
    public static int edgesPerDay() {
        return HUB_TRANSACTIONS / 365;
    }

    private static final String[] PAYMENT_STATUS = {"SUCCESS", "PENDING", "FAILED"};
    private static final String[] PAYMENT_TYPE = {"transfer", "invoice", "refund"};

    private AerospikeEdgeDemoLoader() {
    }

    /** What a load wrote. */
    public record LoadResult(String graph, String vertexSet, int vertices, int hubEdges,
                             int levels, int chunkRecords, int inlineUpdates) {
    }

    /** Writes the demo into the vertex set of the given namespace. */
    public static LoadResult load(AerospikeClient client, String namespace, String vertexSet,
                                 GraphSchema schema) {
        return load(client, namespace, vertexSet, schema, HUB_TRANSACTIONS, CHUNK_SIZE);
    }

    /** Writes the demo, with the number of hub transactions and the chunk size the caller wants. */
    public static LoadResult load(AerospikeClient client, String namespace, String vertexSet,
                                 GraphSchema schema, int hubTransactions, int chunkSize) {
        AerospikeVertexStore vertices = new AerospikeVertexStore(client, namespace, vertexSet);
        AerospikeEdgeStore inline = new AerospikeEdgeStore(client, namespace, vertexSet, schema);
        AerospikeEdgeBulkImporter importer = new AerospikeEdgeBulkImporter(client, namespace, vertexSet,
                schema, chunkSize, new com.pengcheng.phoenixgraph.storage.log.JulBusinessLog());

        // 1. the vertices: the hub plus the accounts it trades with
        List<VertexUpsert> upserts = new ArrayList<>();
        VertexSchema account = schema.vertexSchema("Account");
        long startTime = DEMO_YEAR.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        upserts.add(new VertexUpsert(HUB, account, accountProperties(account, 0, "hub"), startTime, startTime));
        for (int i = 0; i < ACCOUNT_COUNT; i++) {
            upserts.add(new VertexUpsert(String.valueOf(FIRST_ACCOUNT + i), account,
                    accountProperties(account, i + 1, "acct"), startTime, startTime));
        }
        vertices.upsertVertices(upserts);

        // 2. the hub's payments: generated in bulk and imported through the aggregated tables
        EdgeSchema payment = schema.edgeSchema("Payment");
        List<AerospikeEdgeChunks.EdgeEntry> hubEdges = new ArrayList<>(hubTransactions);
        Map<String, Object> sourceByEdgeId = new LinkedHashMap<>(hubTransactions * 2);
        Map<String, Object> destinationByEdgeId = new LinkedHashMap<>(hubTransactions * 2);
        Map<String, Map<String, Object>> propertyCache = new LinkedHashMap<>();
        long spanMillis = 365L * 24 * 60 * 60 * 1000;
        for (int i = 0; i < hubTransactions; i++) {
            String edgeId = "hub_pay_" + i;
            long createdTime = startTime + (spanMillis * i) / Math.max(hubTransactions, 1);
            String source = String.valueOf(FIRST_ACCOUNT + (i % ACCOUNT_COUNT));
            // the edges repeat the same few property shapes, so those maps are interned (the
            // serialized value itself carries the timestamps and is built per edge)
            int statusIndex = i % PAYMENT_STATUS.length;
            int typeIndex = i % PAYMENT_TYPE.length;
            int amount = i % 500;
            String shape = statusIndex + "/" + typeIndex + "/" + amount;
            Map<String, Object> properties = propertyCache.computeIfAbsent(shape, key -> Map.of(
                    "status", PAYMENT_STATUS[statusIndex],
                    "type", PAYMENT_TYPE[typeIndex],
                    "amount", 1.0d + amount,
                    "currency", "USD"));
            List<Object> value = AerospikeEdgeBulkImporter.serialize(payment, source, createdTime,
                    createdTime, properties);
            hubEdges.add(new AerospikeEdgeChunks.EdgeEntry(edgeId, source, createdTime, createdTime, value));
            sourceByEdgeId.put(edgeId, source);
            destinationByEdgeId.put(edgeId, HUB);
        }
        AerospikeEdgeBulkImporter.ImportResult imported =
                importer.importEdges(payment, hubEdges, sourceByEdgeId, destinationByEdgeId);

        // 3. a handful of inline updates: the near-line writes a merge has to reconcile today
        int inlineUpdates = 0;
        for (int i = 0; i < 5; i++) {
            String edgeId = "hub_pay_" + i;
            long createdTime = startTime + (spanMillis * i) / Math.max(hubTransactions, 1);
            String source = String.valueOf(FIRST_ACCOUNT + (i % ACCOUNT_COUNT));
            long updated = startTime + spanMillis + 1_000 + i;
            Map<Integer, EdgePropertyValue> properties = new LinkedHashMap<>();
            properties.put(payment.property("status").id(), new EdgePropertyValue("REFUNDED", createdTime, updated));
            properties.put(payment.property("type").id(), new EdgePropertyValue("refund", createdTime, updated));
            inline.upsertEdge(new EdgeUpsert(edgeId, payment, source, HUB, properties, createdTime, updated));
            inlineUpdates++;
        }
        return new LoadResult(GRAPH, vertexSet, upserts.size(), hubTransactions,
                imported.levels().values().stream().max(Integer::compareTo).orElse(0),
                imported.chunks(), inlineUpdates);
    }

    /** An account's properties, keyed by the property indexes of the schema. */
    private static Map<Integer, VertexPropertyValue> accountProperties(VertexSchema account, int index,
                                                                      String kind) {
        long now = DEMO_YEAR.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        Map<String, Object> byName = new LinkedHashMap<>();
        byName.put("accountStatus", "ACTIVE");
        byName.put("accountType", "savings");
        byName.put("primaryAddress", "addr-" + index);
        byName.put("accountUsage", List.of("Personal", "Business"));
        byName.put("accountLimited", Map.of("174", "open", "175", "kyc"));
        byName.put("firstName", kind + "-first-" + index);
        byName.put("lastName", kind + "-last-" + index);
        Map<Integer, VertexPropertyValue> values = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : byName.entrySet()) {
            values.put(account.property(entry.getKey()).id(), new VertexPropertyValue(entry.getValue(), now));
        }
        return values;
    }
}
