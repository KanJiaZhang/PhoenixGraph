package com.pengcheng.phoenixgraph.query.gremlin;

import com.pengcheng.phoenixgraph.query.QueryException;
import com.pengcheng.phoenixgraph.query.storage.EdgeQueryResult;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
import com.pengcheng.phoenixgraph.query.storage.GraphQuery;
import com.pengcheng.phoenixgraph.query.storage.ScannedEdge;
import com.pengcheng.phoenixgraph.query.storage.VertexQueryResult;
import com.pengcheng.phoenixgraph.query.traversal.NeighborVertexResolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The distributed start: {@code g.V(DIST)...} / {@code g.E(DIST)...}.
 *
 * <p>A flag on the start says <em>where</em> the scan runs - one mapper per partition of the store -
 * and the rows are the same ones a single-process scan returns, so every assertion here is an equality
 * between the two runs: the attribute condition, the hops after it and the whole-set steps (which the
 * reducer does once over the mappers' outputs) must not change the answer. A store that cannot split
 * its scan says so instead of quietly scanning in one process.
 */
class GremlinDistributedScanTest {

    private static final String STATUS = "accountStatus";
    private static final String PAY_STATUS = "status";

    /** Twelve accounts (every third frozen) and a payment out of each account. */
    private static final int ACCOUNTS = 12;

    @Test
    void theFourAttributeFormsAnswerTheSameDistributedAndInProcess() {
        GremlinGraphQuery gremlin = new GremlinGraphQuery(new PartitionedStore(4), NeighborVertexResolver.of(
                Map.of("Payment", "Account"), Map.of("Payment", "Account")));

        // 1. g.V('Account').has('accountStatus','FROZEN')
        assertSame(gremlin, "g.V('Account').has('accountStatus','FROZEN').values('id')",
                "g.V(DIST,'Account').has('accountStatus','FROZEN').values('id')");
        // 2. g.V().has('Account','accountStatus','FROZEN').outE('Payment')
        assertSame(gremlin, "g.V().has('Account','accountStatus','FROZEN').outE('Payment').values('id')",
                "g.V(DIST).has('Account','accountStatus','FROZEN').outE('Payment').values('id')");
        // 3. g.E('Payment').has('status','SUCCESS')
        assertSame(gremlin, "g.E('Payment').has('status','SUCCESS').values('id')",
                "g.E(DIST,'Payment').has('status','SUCCESS').values('id')");
        // 4. g.E().has('Payment','status','SUCCESS')
        assertSame(gremlin, "g.E().has('Payment','status','SUCCESS').values('id')",
                "g.E(DIST).has('Payment','status','SUCCESS').values('id')");
        // a hop after a distributed scan reaches the edges the same way
        assertSame(gremlin, "g.V('Account').has('accountStatus','FROZEN').outE('Payment').values('status')",
                "g.V(DIST,'Account').has('accountStatus','FROZEN').outE('Payment').values('status')");
        // and the whole-set steps are reduced once over the mappers' outputs
        assertSame(gremlin, "g.V('Account').has('accountStatus','FROZEN').count()",
                "g.V(DIST,'Account').has('accountStatus','FROZEN').count()");
        assertSame(gremlin, "g.E('Payment').has('status','SUCCESS').groupCount('status')",
                "g.E(DIST,'Payment').has('status','SUCCESS').groupCount('status')");

        // a global limit keeps the same number of rows: which rows is not defined without an order
        List<Object> single = gremlin.execute("g.V('Account').has('accountStatus','FROZEN').values('id').limit(2)");
        List<Object> spread = gremlin.execute("g.V(DIST,'Account').has('accountStatus','FROZEN').values('id').limit(2)");
        assertEquals(single.size(), spread.size(), "the reduce phase applies the limit to the whole answer");
        assertEquals(2, spread.size());
    }

    /** The distributed answer is the in-process answer: the same rows, and as many of them. */
    private static void assertSame(GremlinGraphQuery gremlin, String inProcess, String distributed) {
        List<Object> single = gremlin.execute(inProcess);
        List<Object> spread = gremlin.execute(distributed);
        assertFalse(single.isEmpty(), "the fixture answers something: " + inProcess);
        assertEquals(new LinkedHashSet<>(single), new LinkedHashSet<>(spread),
                "the same rows, distributed: " + distributed);
        assertEquals(single.size(), spread.size(), "and the same count: " + distributed);
    }

    @Test
    void aDistributedStartRunsItsAlgorithmOnTheClusterToo() {
        // The start's DIST is the whole query's: cc() here runs on the cluster without writing cc(DIST),
        // over the subgraph the mappers expanded (the frozen accounts and their payments).
        GremlinGraphQuery plain = new GremlinGraphQuery(new PartitionedStore(4), NeighborVertexResolver.of(
                Map.of("Payment", "Account"), Map.of("Payment", "Account")));
        List<Object> inProcess = plain.execute(
                "g.V('Account').has('accountStatus','FROZEN').bothE('Payment').cc()");

        try (com.pengcheng.phoenixgraph.gemini.GeminiCluster cluster =
                     com.pengcheng.phoenixgraph.gemini.GeminiCluster.local().start()) {
            GremlinGraphQuery distributed = new GremlinGraphQuery(new PartitionedStore(4),
                    NeighborVertexResolver.of(Map.of("Payment", "Account"), Map.of("Payment", "Account")))
                    .geminiCluster(cluster);
            List<Object> onCluster = distributed.execute(
                    "g.V(DIST,'Account').has('accountStatus','FROZEN').bothE('Payment').cc()");

            assertEquals(1, onCluster.size(), "an algorithm step answers with one map");
            assertEquals(components(inProcess), components(onCluster),
                    "the same vertices end up in the same components, whichever engine ran it");
            assertFalse(components(onCluster).isEmpty(), "the fixture has components to compare");
        }
    }

    /**
     * An algorithm answer as equivalence classes: the component ids are labels, and the two engines
     * number them from their own traversal order, so what must match is which vertices share one.
     */
    private static Set<Set<String>> components(List<Object> answer) {
        Map<?, ?> values = (Map<?, ?>) answer.get(0);
        Map<String, Set<String>> byLabel = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            byLabel.computeIfAbsent(String.valueOf(entry.getValue()), key -> new LinkedHashSet<>())
                    .add(String.valueOf(entry.getKey()));
        }
        return new LinkedHashSet<>(byLabel.values());
    }

    @Test
    void aDistributedScanCannotRunOnAStoreThatCannotSplit() {
        GremlinGraphQuery gremlin = new GremlinGraphQuery(new PartitionedStore(0, false),
                NeighborVertexResolver.none());
        QueryException error = assertThrows(QueryException.class,
                () -> gremlin.execute("g.V(DIST,'Account').has('accountStatus','FROZEN')"));
        assertTrue(error.getMessage().contains("partitions"), error.getMessage());
        assertTrue(error.getMessage().contains("DIST"), error.getMessage());
    }

    @Test
    void theFlagTakesATypeAndNoIds() {
        GremlinGraphQuery gremlin = new GremlinGraphQuery(new PartitionedStore(4),
                NeighborVertexResolver.none());
        QueryException error = assertThrows(QueryException.class,
                () -> gremlin.execute("g.V(DIST, '1001', 'Account')"));
        assertTrue(error.getMessage().contains("no ids"), error.getMessage());
    }

    // --- an in-memory store that can be scanned in partitions ---

    /** A store whose scan can be cut into {@code partitions} slices (or not, when {@code partitions == 0}). */
    private static final class PartitionedStore implements GraphQuery {

        private final Map<String, Map<String, Object>> vertices = new LinkedHashMap<>();
        private final Map<String, List<EdgeQueryResult>> edges = new LinkedHashMap<>();
        private final Map<String, Map<String, Object>> edgeProperties = new LinkedHashMap<>();
        private final int partitions;
        private final boolean splittable;

        PartitionedStore(int partitions) {
            this(partitions, true);
        }

        PartitionedStore(int partitions, boolean splittable) {
            this.partitions = partitions;
            this.splittable = splittable;
            for (int i = 0; i < ACCOUNTS; i++) {
                String id = String.valueOf(1000 + i);
                vertices.put(id, new LinkedHashMap<>(Map.of(STATUS, i % 3 == 0 ? "FROZEN" : "ACTIVE")));
            }
            for (int i = 0; i < ACCOUNTS; i++) {
                String owner = String.valueOf(1000 + i);
                String other = String.valueOf(1000 + ((i + 1) % ACCOUNTS));
                String edgeId = "pay_" + i;
                String status = i % 4 == 0 ? "FAILED" : "SUCCESS";
                edgeProperties.put(edgeId, Map.of(PAY_STATUS, status));
                edges.computeIfAbsent(owner, key -> new ArrayList<>()).add(
                        new EdgeQueryResult("Payment", edgeId, EdgeSide.OUT, other,
                                Map.of(PAY_STATUS, status), 5_000L + i, 5_000L + i));
            }
        }

        @Override
        public VertexQueryResult readVertex(Object vertexId, String vertexName, List<String> propertyNames) {
            Map<String, Object> properties = vertices.get(String.valueOf(vertexId));
            return properties == null ? null : new VertexQueryResult(vertexName, vertexId, properties, 1L, 2L);
        }

        @Override
        public List<VertexQueryResult> readVertices(String vertexName, List<Object> vertexIds,
                                                    List<String> propertyNames) {
            List<VertexQueryResult> out = new ArrayList<>(vertexIds.size());
            for (Object id : vertexIds) {
                out.add(readVertex(id, vertexName, propertyNames));
            }
            return out;
        }

        @Override
        public List<EdgeQueryResult> readEdges(Object vertexId, String vertexName, String edgeName,
                                               EdgeSide direction, List<String> propertyNames) {
            return edges.getOrDefault(String.valueOf(vertexId), List.of());
        }

        @Override
        public List<List<EdgeQueryResult>> readEdgesBatch(String edgeName, EdgeSide direction,
                                                          List<Object> vertexIds, List<String> propertyNames) {
            List<List<EdgeQueryResult>> fetched = readEdgesBatched(edgeName, direction, vertexIds,
                    propertyNames);
            return fetched;
        }

        private List<List<EdgeQueryResult>> readEdgesBatched(String edgeName, EdgeSide direction,
                                                             List<Object> vertexIds,
                                                             List<String> propertyNames) {
            List<List<EdgeQueryResult>> out = new ArrayList<>(vertexIds.size());
            for (Object id : vertexIds) {
                out.add(readEdges(id, "Account", edgeName, direction, propertyNames));
            }
            return out;
        }

        // --- scans ---

        @Override
        public boolean supportsScan() {
            return true;
        }

        @Override
        public List<VertexQueryResult> scanVertices(String vertexName, List<String> propertyNames,
                                                    int maxResults) {
            List<VertexQueryResult> out = new ArrayList<>();
            for (String id : vertices.keySet()) {
                out.add(readVertex(id, vertexName, propertyNames));
            }
            return out;
        }

        @Override
        public List<ScannedEdge> scanEdges(String edgeName, List<String> propertyNames, int maxResults) {
            List<ScannedEdge> out = new ArrayList<>();
            for (Map.Entry<String, List<EdgeQueryResult>> entry : edges.entrySet()) {
                for (EdgeQueryResult edge : entry.getValue()) {
                    out.add(new ScannedEdge(entry.getKey(), edge.otherVertexId(), edge));
                }
            }
            return out;
        }

        @Override
        public List<List<EdgeQueryResult>> readAllEdgesBatch(List<Object> vertexIds, EdgeSide direction,
                                                             List<String> propertyNames) {
            return readEdgesBatched("Payment", direction, vertexIds, propertyNames);
        }

        @Override
        public boolean supportsPartitionedScan() {
            return splittable;
        }

        @Override
        public int scanPartitionCount() {
            return partitions;
        }

        @Override
        public void scanVerticesPartition(String vertexName, List<String> propertyNames, int partition,
                                          int partitions, Consumer<VertexQueryResult> consumer) {
            int index = 0;
            for (String id : vertices.keySet()) {
                // a deterministic split: the union over the partitions is the table, each row once
                if (index++ % partitions == partition) {
                    consumer.accept(readVertex(id, vertexName, propertyNames));
                }
            }
        }

        @Override
        public void scanEdgesPartition(String edgeName, List<String> propertyNames, int partition,
                                       int partitions, int maxResults,
                                       Consumer<ScannedEdge> consumer) {
            int index = 0;
            for (Map.Entry<String, List<EdgeQueryResult>> entry : edges.entrySet()) {
                for (EdgeQueryResult edge : entry.getValue()) {
                    if (index++ % partitions == partition) {
                        consumer.accept(new ScannedEdge(entry.getKey(), edge.otherVertexId(), edge));
                    }
                }
            }
        }
    }
}
