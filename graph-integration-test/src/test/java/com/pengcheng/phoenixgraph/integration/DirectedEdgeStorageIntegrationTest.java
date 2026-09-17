package com.pengcheng.phoenixgraph.integration;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.aerospike.client.Value;
import com.pengcheng.phoenixgraph.query.aerospike.AerospikeGraphQuery;
import com.pengcheng.phoenixgraph.query.gremlin.GremlinEdge;
import com.pengcheng.phoenixgraph.query.gremlin.GremlinGraphQuery;
import com.pengcheng.phoenixgraph.query.gremlin.GremlinVertex;
import com.pengcheng.phoenixgraph.query.gremlin.NeighborVertexResolver;
import com.pengcheng.phoenixgraph.query.storage.EdgeQueryResult;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import com.pengcheng.phoenixgraph.storage.aerospike.StoredEdgeValue;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A directed edge is stored twice: the <em>out</em>-edge on its source (bin
 * {@code ve_<schema>_2}) and the <em>in</em>-edge on its destination
 * ({@code ve_<schema>_1}). This test proves that both from storage and through the
 * traversal language - hops must work in the direction the edge was created in,
 * and two-hop walks must work in either direction and in mixed order.
 *
 * <p>The data is the deterministic payment graph of {@link QueryDataSeeder}: the
 * accounts {@code 100..199} form a directed ring ({@code p_i: 100+i -> 100+(i+1)%100})
 * plus chords ({@code p_i: 100+i -> 100+(i+3)%100}), and every phone {@code 300+i}
 * points at the account {@code 100+i} ({@code pa_i}). Concretely:
 *
 * <pre>
 *   account 100: out = {101 (p0), 103 (p100)}, in = {199 (p99), 197 (p197)}
 *   account 101: out = {102 (p1), 104 (p101)}, in = {100 (p0), 198 (p198)}
 * </pre>
 */
class DirectedEdgeStorageIntegrationTest {

    /** Own vertex set so the test never depends on (or disturbs) another test's data. */
    private static final String DEFAULT_SET = "directed_edges_v1";

    @Test
    void directedEdgesAreStoredAsOutEdgeAtSourceAndInEdgeAtTarget() {
        IntegrationConfig config = IntegrationConfig.fromEnv();
        AerospikeClient client = config.connectOrNull();
        assumeTrue(client != null,
                "Aerospike not available at " + config.host() + ":" + config.port() + ", skipping integration test");
        String set = env("AEROSPIKE_DIRECTED_SET", DEFAULT_SET);
        try (client) {
            GraphSchema schema = QueryDataSeeder.loadSchema();
            AerospikeVertexStore vertexStore = new AerospikeVertexStore(client, config.namespace(), set);
            AerospikeEdgeStore edgeStore = new AerospikeEdgeStore(client, config.namespace(), set, schema);

            truncate(client, config.namespace(), set);
            QueryDataSeeder.seedVertices(vertexStore, schema);
            QueryDataSeeder.seedEdges(edgeStore, schema);

            EdgeSchema payment = schema.edgeSchema("Payment");
            EdgeSchema phoneToAccount = schema.edgeSchema("PhoneToAccount");

            assertRawBins(client, config, set, payment);
            assertStorageLayout(edgeStore, payment, phoneToAccount);
            assertQueryLayerDirections(vertexStore, edgeStore, schema, payment);

            GremlinGraphQuery gremlin = new GremlinGraphQuery(
                    new AerospikeGraphQuery(vertexStore, edgeStore, schema), NeighborVertexResolver.from(schema));
            assertOneHopDirections(gremlin);
            assertTwoHopDirections(gremlin);
            assertCrossSchemaDirectedness(gremlin);
        }
    }

    // --- storage: which bin holds the edge -------------------------------------------------

    /**
     * The records themselves: the out-edges of account 100 live in its out-bin, the
     * in-edges of account 101 in its in-bin, and each bin only holds the edges of that
     * direction.
     */
    @SuppressWarnings("unchecked")
    private void assertRawBins(AerospikeClient client, IntegrationConfig config, String set, EdgeSchema payment) {
        Record source = client.get(null, new Key(config.namespace(), set, Value.get("100")));
        Record target = client.get(null, new Key(config.namespace(), set, Value.get("101")));
        String outBin = AerospikeEdgeStore.edgeBin(payment.id(), AerospikeEdgeStore.EDGE_DIR_OUT);
        String inBin = AerospikeEdgeStore.edgeBin(payment.id(), AerospikeEdgeStore.EDGE_DIR_IN);

        assertTrue(source.bins.containsKey(outBin),
                "the source record holds the out-edge bin " + outBin + ": " + source.bins.keySet());
        assertTrue(target.bins.containsKey(inBin),
                "the target record holds the in-edge bin " + inBin + ": " + target.bins.keySet());
        // account 100 is a source of p0 and p100, so its out-bin has both entries
        assertEquals(Set.of("p0", "p100"), edgeIds((java.util.Map<String, Object>) source.bins.get(outBin)));
        // and the same edges are the in-edges of their targets
        assertEquals(Set.of("p0", "p198"), edgeIds((java.util.Map<String, Object>) target.bins.get(inBin)));
        System.out.println("[directed] account 100 bins: out=" + edgeIds(
                (java.util.Map<String, Object>) source.bins.get(outBin))
                + ", in=" + (source.bins.containsKey(inBin) ? "present" : "absent"));
    }

    private void assertStorageLayout(AerospikeEdgeStore edgeStore, EdgeSchema payment, EdgeSchema phoneToAccount) {
        // account 100: p0 -> 101 and p100 -> 103 are out-edges; p99 (199) and p197 (197) are in-edges
        assertEquals(Set.of("p0", "p100"), edgeIds(edgeStore.getEdges("100", payment.id(),
                AerospikeEdgeStore.EDGE_DIR_OUT)));
        assertEquals(Set.of("p99", "p197"), edgeIds(edgeStore.getEdges("100", payment.id(),
                AerospikeEdgeStore.EDGE_DIR_IN)));
        // a directed edge has no shared bin at all: the two directions live in the two
        // endpoint records, so the "both" bin (the one undirected edges use) is empty
        assertTrue(edgeStore.getEdges("100", payment.id(), AerospikeEdgeStore.EDGE_DIR_BOTH).isEmpty(),
                "a directed edge is not stored in a shared both-bin");

        // the far endpoint stored with the edge points the other way in each bin
        List<StoredEdgeValue> outOf100 = edgeStore.getEdges("100", payment.id(), AerospikeEdgeStore.EDGE_DIR_OUT);
        assertEquals("101", otherVertexOf(outOf100, "p0"), "the out-edge of the source points at the target");
        List<StoredEdgeValue> inOf101 = edgeStore.getEdges("101", payment.id(), AerospikeEdgeStore.EDGE_DIR_IN);
        assertEquals("100", otherVertexOf(inOf101, "p0"), "the in-edge of the target points back at the source");

        // and p0 is in exactly one bin of each endpoint
        assertFalse(edgeIds(edgeStore.getEdges("100", payment.id(), AerospikeEdgeStore.EDGE_DIR_IN)).contains("p0"),
                "the source does not keep p0 as an in-edge");
        assertFalse(edgeIds(edgeStore.getEdges("101", payment.id(), AerospikeEdgeStore.EDGE_DIR_OUT)).contains("p0"),
                "the target does not keep p0 as an out-edge");

        // cross-schema: PhoneToAccount goes Phone -> Account only
        assertEquals(2, edgeStore.getEdges("300", phoneToAccount.id(), AerospikeEdgeStore.EDGE_DIR_OUT).size(),
                "two pa edges leave phone 300");
        assertTrue(edgeStore.getEdges("300", phoneToAccount.id(), AerospikeEdgeStore.EDGE_DIR_IN).isEmpty(),
                "phone 300 has no incoming PhoneToAccount edge");
        assertTrue(edgeStore.getEdges("100", phoneToAccount.id(), AerospikeEdgeStore.EDGE_DIR_OUT).isEmpty(),
                "account 100 has no outgoing PhoneToAccount edge");
        assertEquals(2, edgeStore.getEdges("100", phoneToAccount.id(), AerospikeEdgeStore.EDGE_DIR_IN).size(),
                "account 100 receives both pa edges");
    }

    private void assertQueryLayerDirections(AerospikeVertexStore vertexStore, AerospikeEdgeStore edgeStore,
                                            GraphSchema schema, EdgeSchema payment) {
        AerospikeGraphQuery graphQuery = new AerospikeGraphQuery(vertexStore, edgeStore, schema);
        assertEquals(Set.of("101", "103"), otherVertices(
                graphQuery.readEdges("100", "Account", "Payment", EdgeSide.OUT, List.of())));
        assertEquals(Set.of("199", "197"), otherVertices(
                graphQuery.readEdges("100", "Account", "Payment", EdgeSide.IN, List.of())));
        assertEquals(Set.of("101", "103", "199", "197"), otherVertices(
                graphQuery.readEdges("100", "Account", "Payment", EdgeSide.BOTH, List.of())));
        // reading the wrong direction through the query layer gives nothing
        assertTrue(graphQuery.readEdges("100", "Account", "PhoneToAccount", EdgeSide.OUT, List.of()).isEmpty());
        assertTrue(graphQuery.readEdges("300", "Phone", "PhoneToAccount", EdgeSide.IN, List.of()).isEmpty());
    }

    // --- traversal language -----------------------------------------------------------------

    private void assertOneHopDirections(GremlinGraphQuery gremlin) {
        assertEquals(List.of("addr-101", "addr-103"), sorted(
                gremlin.execute("g.V('100','Account').out('Payment').values('primaryAddress')")));
        assertEquals(List.of("addr-197", "addr-199"), sorted(
                gremlin.execute("g.V('100','Account').in('Payment').values('primaryAddress')")));
        assertEquals(List.of("addr-102", "addr-104"), sorted(
                gremlin.execute("g.V('101','Account').out('Payment').values('primaryAddress')")));
        assertEquals(List.of("addr-100", "addr-198"), sorted(
                gremlin.execute("g.V('101','Account').in('Payment').values('primaryAddress')")));
        assertEquals(List.of("addr-101", "addr-103", "addr-197", "addr-199"), sorted(
                gremlin.execute("g.V('100','Account').both('Payment').values('primaryAddress')")));

        // the edge traversers carry the side they were read from
        List<Object> outEdges = gremlin.execute("g.V('100','Account').outE('Payment')");
        assertEquals(2, outEdges.size());
        assertTrue(outEdges.stream().allMatch(edge -> ((GremlinEdge) edge).side() == EdgeSide.OUT));
        List<Object> inEdges = gremlin.execute("g.V('100','Account').inE('Payment')");
        assertEquals(2, inEdges.size());
        assertTrue(inEdges.stream().allMatch(edge -> ((GremlinEdge) edge).side() == EdgeSide.IN));
    }

    private void assertTwoHopDirections(GremlinGraphQuery gremlin) {
        // out().out(): 100 -> {101, 103} -> {102, 104} / {104, 106} (104 reached twice)
        assertEquals(List.of("addr-102", "addr-104", "addr-106"), sorted(
                gremlin.execute("g.V('100','Account').out('Payment').out('Payment').dedup().values('primaryAddress')")));
        // in().in(): 100 <- {199, 197} <- predecessors (196 reached twice)
        assertEquals(List.of("addr-194", "addr-196", "addr-198"), sorted(
                gremlin.execute("g.V('100','Account').in('Payment').in('Payment').dedup().values('primaryAddress')")));
        assertEquals(List.of(4L), gremlin.execute("g.V('100','Account').out('Payment').out('Payment').count()"));
        assertEquals(List.of(4L), gremlin.execute("g.V('100','Account').in('Payment').in('Payment').count()"));
        // in().out() from 100: back through the predecessors and out again
        assertEquals(List.of("addr-100", "addr-102", "addr-198"), sorted(
                gremlin.execute("g.V('100','Account').in('Payment').out('Payment').dedup().values('primaryAddress')")));
        // out().in() from 100: out to the successors, then back to their predecessors
        // (100 -> 101 -> {100, 198} and 100 -> 103 -> {102, 100})
        assertEquals(List.of("addr-100", "addr-102", "addr-198"), sorted(
                gremlin.execute("g.V('100','Account').out('Payment').in('Payment').dedup().values('primaryAddress')")));
        // the same walk without dedup counts the round trip twice
        assertEquals(List.of(4L), gremlin.execute(
                "g.V('100','Account').out('Payment').in('Payment').count()"));
        assertEquals(List.of(3L), gremlin.execute(
                "g.V('100','Account').out('Payment').in('Payment').dedup().count()"));
        // a longer detour in both directions agrees with itself
        assertEquals(gremlin.execute("g.V('100','Account').out('Payment').out('Payment').count()"),
                gremlin.execute("g.V('103','Account').in('Payment').in('Payment').count()"));
        System.out.println("[directed] 2-hop: out.out(100)=" + sorted(gremlin.execute(
                "g.V('100','Account').out('Payment').out('Payment').dedup().values('primaryAddress')"))
                + ", in.in(100)=" + sorted(gremlin.execute(
                "g.V('100','Account').in('Payment').in('Payment').dedup().values('primaryAddress')"))
                + ", in.out(100)=" + sorted(gremlin.execute(
                "g.V('100','Account').in('Payment').out('Payment').dedup().values('primaryAddress')")));
    }

    private void assertCrossSchemaDirectedness(GremlinGraphQuery gremlin) {
        // Phone -> Account only
        assertEquals(List.of("100", "100"), sortedIds(gremlin.execute("g.V('300','Phone').out('PhoneToAccount')")));
        assertEquals(List.of("300", "300"), sortedIds(gremlin.execute("g.V('100','Account').in('PhoneToAccount')")));
        assertEquals(List.of(), gremlin.execute("g.V('100','Account').out('PhoneToAccount')"));
        assertEquals(List.of(), gremlin.execute("g.V('300','Phone').in('PhoneToAccount')"));
        // and the two-hop walks across the schema follow the direction:
        // phone 300 -> account 100 (twice) -> accounts 101 and 103
        assertEquals(List.of("101", "103"), sortedIds(gremlin.execute(
                "g.V('300','Phone').out('PhoneToAccount').out('Payment').dedup()")));
        assertEquals(List.of(), gremlin.execute("g.V('300','Phone').in('PhoneToAccount')"));
    }

    // --- helpers ----------------------------------------------------------------------------

    private static Set<String> edgeIds(List<StoredEdgeValue> edges) {
        return edges.stream().map(StoredEdgeValue::edgeId).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<String> edgeIds(java.util.Map<String, Object> bin) {
        return new LinkedHashSet<>(bin.keySet());
    }

    private static String otherVertexOf(List<StoredEdgeValue> edges, String edgeId) {
        return edges.stream().filter(edge -> edge.edgeId().equals(edgeId))
                .map(edge -> String.valueOf(edge.otherVertexId())).findFirst().orElse(null);
    }

    private static Set<String> otherVertices(List<EdgeQueryResult> edges) {
        return edges.stream().map(edge -> String.valueOf(edge.otherVertexId()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static List<String> sorted(List<Object> values) {
        return values.stream().map(String::valueOf).sorted().toList();
    }

    private static List<String> sortedIds(List<Object> vertices) {
        return vertices.stream().map(value -> String.valueOf(((GremlinVertex) value).id())).sorted().toList();
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static void truncate(AerospikeClient client, String namespace, String set) {
        try {
            client.truncate(new com.aerospike.client.policy.InfoPolicy(), namespace, set, null);
            Thread.sleep(300);
        } catch (com.aerospike.client.AerospikeException e) {
            // truncate may be disabled on a server; the seeding then upserts on top
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}
