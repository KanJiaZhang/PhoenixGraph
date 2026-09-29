package com.pengcheng.phoenixgraph.integration;

import com.pengcheng.phoenixgraph.query.gremlin.GremlinGraphQuery;
import com.pengcheng.phoenixgraph.query.storage.GraphQuery;
import com.pengcheng.phoenixgraph.query.traversal.NeighborVertexResolver;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbStoreConfig;
import com.pengcheng.phoenixgraph.storage.rocksdb.edge.RocksDbEdgeGraphQuery;
import com.pengcheng.phoenixgraph.storage.rocksdb.edge.RocksDbEdgeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 倒排索引用于非三国图（payment-graph）：给 {@code Account.accountStatus}（enum）声明
 * {@code isFullText}，验证同一查询在「全扫描 / 倒排索引」下结果一致，并打印耗时对比。
 *
 * <p>这是倒排索引通用性的验证：它不绑定三国，任何图的任何字段声明 {@code isFullText} 即可。
 * {@code accountStatus} 是英文 enum（ACTIVE/FROZEN/CLOSED），因此顺带验证了索引与
 * {@code TextP.containing} 的大小写不敏感语义一致（{@code 'frozen'} 能命中 {@code FROZEN}）。
 */
class PaymentGraphFullTextIndexTest {

    @Test
    void accountStatusFullTextIndexBeatsScan(@TempDir Path directory) {
        GraphSchema schema = PaymentGraphRocksDbLoader.loadSchema(PaymentGraphRocksDbLoader.EDGE_GRAPH);
        assertTrue(schema.vertexSchema("Account").property("accountStatus").isFullText(),
                "accountStatus 声明了 isFullText");

        Path path = directory.resolve("payment-rocksdb-edge");
        PaymentGraphRocksDbLoader.load(schema, path.toString(), true);

        RocksDbStoreConfig config = new RocksDbStoreConfig(path.toString(), schema.storage().regionCount(),
                schema.storage().maxVersions(), true, schema.storage().cacheEdges());
        try (RocksDbEdgeStore store = RocksDbEdgeStore.open(schema, config)) {
            // 额外播种 10 万 Account，让全扫描有可测的耗时（demo 只有 51 个，对比无差异）
            seedExtraAccounts(store, schema, 100_000);

            long indexRows = store.fullTextIndex().count("Account", "accountStatus");
            assertTrue(indexRows > 0, "accountStatus 的倒排索引在写入时已建立");

            GraphQuery graph = new RocksDbEdgeGraphQuery(store, schema);
            GremlinGraphQuery scan = new GremlinGraphQuery(graph, NeighborVertexResolver.from(schema))
                    .schema(schema).fullTextIndex(false);
            GremlinGraphQuery indexed = new GremlinGraphQuery(graph, NeighborVertexResolver.from(schema))
                    .schema(schema).fullTextIndex(true);

            String query = "g.V('Account').has('accountStatus', TextP.containing('FROZEN')).count()";
            String lowerCaseQuery = "g.V('Account').has('accountStatus', TextP.containing('frozen')).count()";

            // warmup：编译缓存 + RocksDB 块缓存
            scan.execute(query);
            indexed.execute(query);
            List<Object> scanResult = scan.execute(query);
            List<Object> indexedResult = indexed.execute(query);

            assertEquals(scanResult, indexedResult, "索引与全扫描结果一致");
            assertEquals(indexedResult, indexed.execute(lowerCaseQuery),
                    "大小写不敏感：containing('frozen') 命中 FROZEN");

            long scanMs = medianMillis(scan, query, 5);
            long indexedMs = medianMillis(indexed, query, 5);
            System.out.println();
            System.out.println("======================================================================");
            System.out.println(" payment-graph 倒排索引 vs 全扫描（Account.accountStatus）");
            System.out.println("======================================================================");
            System.out.println(" accountStatus 倒排索引行数: " + indexRows);
            System.out.printf(" %s%n   全扫描 %d ms (%s)  |  索引 %d ms (%s)%n",
                    query, scanMs, resultOf(scanResult), indexedMs, resultOf(indexedResult));
        }
    }

    /** Seeds many accounts with only their status, so the scan has enough rows to measure. */
    private static void seedExtraAccounts(RocksDbEdgeStore store, GraphSchema schema, int count) {
        com.pengcheng.phoenixgraph.schema.VertexSchema account = schema.vertexSchema("Account");
        int statusId = account.property("accountStatus").id();
        String[] statuses = {"ACTIVE", "FROZEN", "CLOSED"};
        long now = System.currentTimeMillis();
        long baseId = 1_000_000L;
        java.util.List<com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbVertexUpsert> batch =
                new java.util.ArrayList<>(1_000);
        for (int i = 0; i < count; i++) {
            java.util.Map<Integer, com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbPropertyValue> values =
                    new java.util.LinkedHashMap<>();
            values.put(statusId, new com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbPropertyValue(
                    statuses[i % statuses.length], now));
            batch.add(new com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbVertexUpsert(
                    baseId + i, account, values, now, now));
            if (batch.size() == 1_000) {
                store.upsertVertices(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            store.upsertVertices(batch);
        }
    }

    private static long medianMillis(GremlinGraphQuery engine, String query, int rounds) {
        long[] samples = new long[rounds];
        for (int i = 0; i < rounds; i++) {
            long start = System.nanoTime();
            engine.execute(query);
            samples[i] = (System.nanoTime() - start) / 1_000_000;
        }
        java.util.Arrays.sort(samples);
        return samples[rounds / 2];
    }

    private static String resultOf(List<Object> rows) {
        return rows.size() == 1 && rows.get(0) instanceof Number number ? String.valueOf(number) : rows.toString();
    }
}
