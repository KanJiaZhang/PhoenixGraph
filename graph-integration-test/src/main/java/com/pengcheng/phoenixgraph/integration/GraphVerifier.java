package com.pengcheng.phoenixgraph.integration;

import com.pengcheng.phoenixgraph.storage.BusinessLog;
import com.pengcheng.phoenixgraph.storage.BusinessLogEntry;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import com.pengcheng.phoenixgraph.storage.aerospike.StoredEdgeValue;
import com.pengcheng.phoenixgraph.storage.aerospike.StoredVertex;
import com.pengcheng.phoenixgraph.storage.log.JulBusinessLog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Queries a vertex and its in/out edges and verifies that the imported
 * properties and edges are correct.
 */
public final class GraphVerifier {

    private final AerospikeVertexStore vertexStore;
    private final AerospikeEdgeStore edgeStore;
    private final BusinessLog businessLog;

    public GraphVerifier(AerospikeVertexStore vertexStore, AerospikeEdgeStore edgeStore) {
        this(vertexStore, edgeStore, new JulBusinessLog());
    }

    public GraphVerifier(AerospikeVertexStore vertexStore, AerospikeEdgeStore edgeStore, BusinessLog businessLog) {
        this.vertexStore = vertexStore;
        this.edgeStore = edgeStore;
        this.businessLog = businessLog == null ? new JulBusinessLog() : businessLog;
    }

    /** Verifies the imported graph; throws {@link AssertionError} on mismatch. */
    public void verify() {
        // Account 1001
        StoredVertex account = vertexStore.readVertex("1001");
        require(account != null, "Account 1001 not found");
        require("savings".equals(account.properties().get(1).value()), "accountType mismatch");
        require("Shanghai".equals(account.properties().get(2).value()), "primaryAddress mismatch");
        require("ACTIVE".equals(account.properties().get(3).value()), "accountStatus mismatch");

        // IP 2001
        StoredVertex ip = vertexStore.readVertex("2001");
        require(ip != null, "IP 2001 not found");
        require("1.2.3.4".equals(ip.properties().get(1).value()), "ipAddress mismatch");

        // Out-edges of 1001: Payment e1 (schema 1) and Account->IP e2 (schema 2).
        // The two bins are merged by edge id, so an id that appears in both (data
        // left behind by an earlier import) is recorded in the business log with
        // both values instead of failing the merge; the newest entry wins.
        List<StoredEdgeValue> outEdges = new java.util.ArrayList<>(
                edgeStore.getEdges("1001", 1, AerospikeEdgeStore.EDGE_DIR_OUT));
        outEdges.addAll(edgeStore.getEdges("1001", 2, AerospikeEdgeStore.EDGE_DIR_OUT));
        Map<String, StoredEdgeValue> byId = mergeByEdgeId(outEdges, "1001");
        require(byId.containsKey("e1"), "Payment e1 missing from 1001 out-edges");
        require(byId.containsKey("e2"), "Account->IP e2 missing from 1001 out-edges");

        StoredEdgeValue payment = byId.get("e1");
        require("500.0".equals(payment.properties().get(1).value()), "payment amount mismatch");
        require("USD".equals(payment.properties().get(2).value()), "payment currency mismatch");
        require("SUCCESS".equals(payment.properties().get(3).value()), "payment status mismatch");

        StoredEdgeValue login = byId.get("e2");
        require(Long.toString(TestDataGenerator.BASE_TIME).equals(login.properties().get(1).value()),
                "loginTime mismatch");

        // In-edges: Payment e1 (schema 1) on 1002, Account->IP e2 (schema 2) on 2001
        List<StoredEdgeValue> inOf1002 = edgeStore.getEdges("1002", 1, AerospikeEdgeStore.EDGE_DIR_IN);
        require(inOf1002.stream().anyMatch(e -> "e1".equals(e.edgeId())),
                "Payment e1 missing from 1002 in-edges");

        List<StoredEdgeValue> inOf2001 = edgeStore.getEdges("2001", 2, AerospikeEdgeStore.EDGE_DIR_IN);
        require(inOf2001.stream().anyMatch(e -> "e2".equals(e.edgeId())),
                "Account->IP e2 missing from 2001 in-edges");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /**
     * Merges edges of several classes by edge id, last entry winning. A duplicate
     * id (stale data from an earlier import sharing an id with the current one) is
     * not an error: it is written to the business log with both values - the
     * offending record is exactly what an operator needs to clean up.
     */
    private Map<String, StoredEdgeValue> mergeByEdgeId(List<StoredEdgeValue> edges, Object vertexId) {
        Map<String, StoredEdgeValue> byId = new LinkedHashMap<>();
        for (StoredEdgeValue edge : edges) {
            StoredEdgeValue previous = byId.put(edge.edgeId(), edge);
            if (previous != null) {
                BusinessLogEntry entry = BusinessLogEntry.failure("verifyGraph",
                        String.valueOf(vertexId), Map.of(
                                "edgeId", edge.edgeId(),
                                "kept", describe(edge),
                                "duplicate", describe(previous),
                                "reason", "duplicate edge id in the out-edges of one vertex"));
                businessLog.record(entry);
            }
        }
        return byId;
    }

    private static String describe(StoredEdgeValue edge) {
        return "otherVertex=" + edge.otherVertexId()
                + ", lastUpdatedTime=" + edge.lastUpdatedTime()
                + ", createdTime=" + edge.createdTime()
                + ", properties=" + edge.properties();
    }
}
