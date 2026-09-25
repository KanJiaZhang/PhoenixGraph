package com.pengcheng.phoenixgraph.storage.rocksdb.edge;

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
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbHash;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbPropertyValue;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbStoreConfig;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbVertexUpsert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The partitioned scan of the edge scheme: the same rows a whole-table scan returns, cut into hash
 * ranges that independent mappers can read at the same time.
 *
 * <p>Every key starts with the fixed-width hash of the id, so a hash range is a contiguous key range
 * and one partition is one iterator. This is the input split of a distributed scan
 * ({@code g.V(DIST).has('Account','accountStatus','FROZEN')}): the partitions must cover every row
 * exactly once, whatever the partition count, and reading them in parallel threads must give the same
 * union as reading them one after another.
 */
class RocksDbEdgePartitionedScanTest {

    private static final int STATUS = 1;
    private static final int PAYMENT_STATUS = 3;

    private static final int ACCOUNTS = 300;
    private static final int PAYMENTS = 400;

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

    private GraphSchema seeded() {
        GraphSchema schema = new GraphSchema(1, "partition-test")
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
                                PropertyType.ScalarType.STRING)));
        store = RocksDbEdgeStore.open(schema, RocksDbStoreConfig.of(directory.resolve("db").toString()));

        List<RocksDbVertexUpsert> vertices = new ArrayList<>(ACCOUNTS);
        for (int i = 0; i < ACCOUNTS; i++) {
            vertices.add(new RocksDbVertexUpsert((long) i, schema.vertexSchema("Account"),
                    Map.of(STATUS, new RocksDbPropertyValue(i % 7 == 0 ? "FROZEN" : "ACTIVE", 10L)),
                    1_000L, 10L));
        }
        store.upsertVertices(vertices);
        List<RocksDbEdgeUpsert> edges = new ArrayList<>(PAYMENTS);
        for (int i = 0; i < PAYMENTS; i++) {
            edges.add(new RocksDbEdgeUpsert((long) i, schema.edgeSchema("Payment"),
                    (long) (i % ACCOUNTS), (long) ((i * 7 + 3) % ACCOUNTS),
                    Map.of(PAYMENT_STATUS, new RocksDbEdgeProperty(i % 3 == 0 ? "FAILED" : "SUCCESS",
                            5_000L + i, 5_000L + i)),
                    5_000L + i, 5_000L + i));
        }
        store.upsertEdges(edges);
        return schema;
    }

    /** The union of every partition's vertices, in partition order. */
    private static List<VertexQueryResult> partitionedVertices(GraphQuery query, int partitions) {
        List<VertexQueryResult> out = new ArrayList<>();
        for (int p = 0; p < partitions; p++) {
            query.scanVerticesPartition("Account", List.of("accountStatus"), p, partitions, out::add);
        }
        return out;
    }

    private static List<ScannedEdge> partitionedEdges(GraphQuery query, int partitions) {
        List<ScannedEdge> out = new ArrayList<>();
        for (int p = 0; p < partitions; p++) {
            query.scanEdgesPartition("Payment", List.of("status"), p, partitions, 0, out::add);
        }
        return out;
    }

    @Test
    void thePartitionsCoverEveryRowExactlyOnceWhateverTheCount() {
        GraphSchema schema = seeded();
        GraphQuery query = new RocksDbEdgeGraphQuery(store, schema);
        assertTrue(query.supportsPartitionedScan());
        assertEquals(store.config().regionCount(), query.scanPartitionCount());

        List<Long> allVertexIds = query.scanVertices("Account", List.of("accountStatus")).stream()
                .map(VertexQueryResult::vertexId).map(String::valueOf).map(Long::valueOf).sorted().toList();
        List<String> allEdgeIds = query.scanEdges("Payment", List.of("status")).stream()
                .map(edge -> String.valueOf(edge.edge().edgeId())).sorted().toList();

        for (int partitions : new int[]{1, 2, 3, 4, 8, 16}) {
            List<VertexQueryResult> vertices = partitionedVertices(query, partitions);
            assertEquals(ACCOUNTS, vertices.size(), "partitions=" + partitions);
            assertEquals(vertices.size(), new LinkedHashSet<>(vertices.stream()
                    .map(VertexQueryResult::vertexId).toList()).size(),
                    "no vertex twice with partitions=" + partitions);
            assertEquals(allVertexIds, vertices.stream().map(VertexQueryResult::vertexId)
                    .map(String::valueOf).map(Long::valueOf).sorted().toList(),
                    "the union is the whole table with partitions=" + partitions);

            List<ScannedEdge> edges = partitionedEdges(query, partitions);
            assertEquals(PAYMENTS, edges.size(), "partitions=" + partitions);
            assertEquals(edges.size(), new LinkedHashSet<>(edges.stream()
                    .map(edge -> String.valueOf(edge.edge().edgeId())).toList()).size(),
                    "no edge twice with partitions=" + partitions);
            assertEquals(allEdgeIds, edges.stream().map(edge -> String.valueOf(edge.edge().edgeId()))
                    .sorted().toList(), "the union is the whole class with partitions=" + partitions);
        }
    }

    @Test
    void mappersReadTheirPartitionsInParallelAndReduceToTheSameScan() throws Exception {
        GraphSchema schema = seeded();
        GraphQuery query = new RocksDbEdgeGraphQuery(store, schema);
        int partitions = query.scanPartitionCount();
        assertTrue(partitions > 1, "the demo store has more than one region");

        // the map phase: one task per partition, one thread each, exactly as the engine runs it
        ExecutorService pool = Executors.newFixedThreadPool(partitions);
        try {
            List<Callable<List<VertexQueryResult>>> tasks = new ArrayList<>(partitions);
            for (int p = 0; p < partitions; p++) {
                final int partition = p;
                tasks.add(() -> {
                    List<VertexQueryResult> mine = new ArrayList<>();
                    query.scanVerticesPartition("Account", List.of("accountStatus"), partition, partitions,
                            mine::add);
                    return mine;
                });
            }
            List<Future<List<VertexQueryResult>>> futures = pool.invokeAll(tasks);
            List<VertexQueryResult> merged = new ArrayList<>();
            for (Future<List<VertexQueryResult>> future : futures) {
                merged.addAll(future.get());
            }
            assertEquals(ACCOUNTS, merged.size(), "the reducer sees every mapper's output");
            assertEquals(merged.size(), new LinkedHashSet<>(merged.stream()
                    .map(VertexQueryResult::vertexId).toList()).size());
            // and the union is order-independent: shuffled partitions still cover the same rows
            List<List<VertexQueryResult>> perPartition = new ArrayList<>();
            for (Future<List<VertexQueryResult>> future : futures) {
                perPartition.add(future.get());
            }
            Collections.reverse(perPartition);
            List<Object> shuffled = new ArrayList<>();
            for (List<VertexQueryResult> part : perPartition) {
                for (VertexQueryResult vertex : part) {
                    shuffled.add(vertex.vertexId());
                }
            }
            assertEquals(new LinkedHashSet<>(merged.stream().map(VertexQueryResult::vertexId).toList()),
                    new LinkedHashSet<>(shuffled), "the same rows, whichever order the mappers finish in");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theHashRangeOfAPartitionIsAContiguousKeyRange() {
        assertEquals(0L, RocksDbHash.partitionBoundary(0, 4));
        assertEquals(Long.MIN_VALUE, RocksDbHash.partitionBoundary(1, 2), "half of the space, unsigned");
        // the boundaries are strictly increasing in the unsigned order the keys are written in, up to
        // the last slot: that one is the end of the space, which as a long wraps to 0 - which is exactly
        // why the last partition's range has an open end instead of a bound
        for (int partitions : new int[]{2, 4, 16, 1000}) {
            long previous = RocksDbHash.partitionBoundary(0, partitions);
            for (int p = 1; p < partitions; p++) {
                long boundary = RocksDbHash.partitionBoundary(p, partitions);
                assertTrue(Long.compareUnsigned(previous, boundary) < 0,
                        "boundary " + p + " of " + partitions);
                previous = boundary;
            }
            assertEquals(0L, RocksDbHash.partitionBoundary(partitions, partitions),
                    "the end of the space wraps to 0 when read as a long");
            assertTrue(Long.compareUnsigned(previous, RocksDbHash.partitionBoundary(partitions - 1,
                    partitions)) >= 0, "the last slot starts at or after the previous one");
        }
        // and the last partition's range is open, so nothing wraps back to hash 0
        byte[][] last = RocksDbHash.partitionKeyRange(3, 4);
        assertEquals(null, last[1]);
        for (int p = 0; p < 4; p++) {
            byte[][] range = RocksDbHash.partitionKeyRange(p, 4);
            assertTrue(range[0] != null);
        }
    }
}
