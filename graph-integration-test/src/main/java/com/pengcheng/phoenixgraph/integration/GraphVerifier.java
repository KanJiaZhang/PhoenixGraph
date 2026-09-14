package com.pengcheng.phoenixgraph.integration;

import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import com.pengcheng.phoenixgraph.storage.aerospike.StoredEdgeValue;
import com.pengcheng.phoenixgraph.storage.aerospike.StoredVertex;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Queries a vertex and its in/out edges and verifies that the imported
 * properties and edges are correct.
 */
public final class GraphVerifier {

    private final AerospikeVertexStore vertexStore;
    private final AerospikeEdgeStore edgeStore;

    public GraphVerifier(AerospikeVertexStore vertexStore, AerospikeEdgeStore edgeStore) {
        this.vertexStore = vertexStore;
        this.edgeStore = edgeStore;
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

        // Out-edges of 1001: Payment e1 (schema 1) and Account->IP e2 (schema 2)
        List<StoredEdgeValue> outEdges = new java.util.ArrayList<>(
                edgeStore.getEdges("1001", 1, AerospikeEdgeStore.EDGE_DIR_OUT));
        outEdges.addAll(edgeStore.getEdges("1001", 2, AerospikeEdgeStore.EDGE_DIR_OUT));
        Map<String, StoredEdgeValue> byId = outEdges.stream()
                .collect(Collectors.toMap(StoredEdgeValue::edgeId, e -> e));
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
}
