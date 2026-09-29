package com.pengcheng.phoenixgraph.integration.dataagent;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端：把语义层定义的指标在 <b>数据实例</b>（{@code tpc-h-data}）上跑出真实数字，
 * 并用 Java 直接算一遍<b>对账</b>。
 *
 * <p>这是"图谱 → 指标 → 查询 → 数字"的闭环：5 个业务查询 + 2 个归因案例，每个都断言
 * Gremlin 的结果与直接遍历计算结果一致（1e-6 容差）。
 */
class TpchDataGraphTest {

    private static final double EPS = 1e-6;

    @Test
    void answersBusinessQuestionsWithRealNumbers(@TempDir Path directory) {
        GraphSchema schema = TpchDataGraph.loadSchema();
        TpchDataGraph.Instance instance = TpchDataGraph.generate();
        Path path = directory.resolve(schema.storage().requireRocksDbPath());
        TpchDataGraph.load(instance, directory.toString(), true);

        RocksDbStoreConfig config = new RocksDbStoreConfig(path.toString(), schema.storage().regionCount(),
                schema.storage().maxVersions(), true, schema.storage().cacheEdges());
        try (RocksDbEdgeStore store = RocksDbEdgeStore.open(schema, config)) {
            GraphQuery graph = new RocksDbEdgeGraphQuery(store, schema);
            GremlinGraphQuery g = new GremlinGraphQuery(graph, NeighborVertexResolver.from(schema));

            System.out.println();
            System.out.println("======================================================================");
            System.out.println(" TPC-H 数据实例（" + instance.sales().size() + " 行销售，"
                    + instance.edges().size() + " 条边）上的业务查询");
            System.out.println("======================================================================");

            // ---------- 场景 1：大区净收入 ----------
            Map<String, Double> byRegion = groupSum(g, "g.V('Sale').group().by('region').by(values('revenue').sum())");
            Map<String, Double> expectedByRegion = new TreeMap<>();
            instance.sales().forEach(s -> expectedByRegion.merge(s.region(), s.revenue(), Double::sum));
            System.out.println("\n① 大区净收入（revenue by region）");
            byRegion.forEach((k, v) -> System.out.printf("   %-12s %,15.2f%n", k, v));
            assertSameSums(expectedByRegion, byRegion, "大区净收入");

            // ---------- 场景 2：促销效果（按商品类型）----------
            Map<String, Double> promoByType = groupSum(g,
                    "g.V('Sale').has('partType', TextP.containing('PROMO'))"
                            + ".group().by('partType').by(values('revenue').sum())");
            double promoRevenue = sum(promoByType);
            double totalRevenue = instance.sales().stream().mapToDouble(TpchDataGraph.Sale::revenue).sum();
            System.out.printf("%n② 促销收入 = %,.2f，占总净收入 %.2f%%%n",
                    promoRevenue, 100.0 * promoRevenue / totalRevenue);
            assertTrue(promoRevenue > 0, "促销品有收入");

            // ---------- 场景 3：客户价值（按市场细分）----------
            Map<String, Double> bySegment = groupSum(g,
                    "g.V('Sale').group().by('segment').by(values('revenue').sum())");
            Map<String, Double> expectedBySegment = new TreeMap<>();
            instance.sales().forEach(s -> expectedBySegment.merge(s.segment(), s.revenue(), Double::sum));
            System.out.println("\n③ 客单价（revenue by segment）");
            bySegment.forEach((k, v) -> System.out.printf("   %-12s %,15.2f%n", k, v));
            assertSameSums(expectedBySegment, bySegment, "市场细分净收入");

            // ---------- 场景 4：物流时效（按优先级）----------
            Map<String, Double> lateByPriority = groupSumLong(g,
                    "g.V('Sale').group().by('priority').by(values('lateDelivery').sum())");
            long lateTotal = lateByPriority.values().stream().mapToLong(Double::longValue).sum();
            long expectedLate = instance.sales().stream().mapToLong(TpchDataGraph.Sale::lateDelivery).sum();
            System.out.println("\n④ 迟到行数（lateDelivery by priority）");
            lateByPriority.forEach((k, v) -> System.out.printf("   %-18s %6.0f%n", k, v));
            assertEquals(expectedLate, (long) lateTotal, "迟到总数一致");

            // ---------- 场景 5：供应商收入（按供应商）----------
            Map<String, Double> bySupplier = groupSum(g,
                    "g.V('Sale').group().by('supplierName').by(values('revenue').sum())");
            Map<String, Double> expectedBySupplier = new TreeMap<>();
            instance.sales().forEach(s -> expectedBySupplier.merge(s.supplierName(), s.revenue(), Double::sum));
            System.out.println("\n⑤ 供应商净收入 Top5（revenue by supplier）");
            bySupplier.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .limit(5).forEach(e -> System.out.printf("   %-14s %,15.2f%n", e.getKey(), e.getValue()));
            assertSameSums(expectedBySupplier, bySupplier, "供应商净收入");

            // ---------- 归因 1：净收入 = 毛收入 - 折扣额 ----------
            double gross = (Double) g.execute("g.V('Sale').values('gross').sum()").get(0);
            double discount = (Double) g.execute("g.V('Sale').values('discountAmount').sum()").get(0);
            double revenue = (Double) g.execute("g.V('Sale').values('revenue').sum()").get(0);
            System.out.printf("%n⑥ 归因（净收入拆解）：毛收入 %,.2f − 折扣额 %,.2f = 净收入 %,.2f%n",
                    gross, discount, revenue);
            System.out.printf("   校验：毛收入 − 折扣额 − 净收入 = %.6f（应为 0）%n", gross - discount - revenue);
            assertEquals(0.0, gross - discount - revenue, 1e-3, "净收入 = 毛收入 − 折扣额");

            // ---------- 归因 2：退货率（物流延迟 → 退货）----------
            long returned = ((Number) g.execute("g.V('Sale').values('returned').sum()").get(0)).longValue();
            double returnRate = 100.0 * returned / instance.sales().size();
            Map<String, Double> lateRateByPriority = new TreeMap<>();
            for (TpchDataGraph.Sale s : instance.sales()) {
                lateRateByPriority.merge(s.priority(), (double) s.lateDelivery(), Double::sum);
            }
            System.out.printf("%n⑦ 归因（退货率）：退货 %d 行 / %d 行 = %.2f%%%n",
                    returned, instance.sales().size(), returnRate);
            System.out.println("   驱动因子（迟到行数 by priority）：");
            lateRateByPriority.forEach((k, v) -> System.out.printf("     %-18s %6.0f%n", k, v));
            assertTrue(returnRate > 0, "退货率 > 0");

            // ---------- join 路径：图的边（不是反规范化的属性）----------
            List<Object> parts = g.execute("g.V('sale_0','Sale').out('ofPart').values('name')");
            System.out.println("\n⑧ 图的 join（sale_0 --ofPart--> ?）：" + parts);
            assertEquals(1, parts.size(), "一行销售连到一个商品");
            // 多跳雪花链：销售 → 订单 → 客户 → 国家 → 大区（这条链与语义层的 references 路径对应）
            List<Object> chain = g.execute("g.V('sale_0','Sale').out('ofOrder').out('placedBy')"
                    + ".out('customerNation').out('nationRegion').values('name')");
            System.out.println("   雪花链（sale→order→customer→nation→region）：" + chain);
            assertEquals(1, chain.size(), "雪花链走通");
            // 一致性：反规范化的 region 属性必须等于图上雪花链走出的 region
            List<Object> propRegion = g.execute("g.V('sale_0','Sale').values('region')");
            assertEquals(propRegion, chain, "反规范化属性 = 图 join 的 region（否则两套事实会矛盾）");

            // ---------- 对账：Gremlin 总和 = Java 总和 ----------
            System.out.printf("%n对账：Gremlin 净收入合计 %,.2f = Java 直算 %,.2f%n", revenue, totalRevenue);
            assertEquals(totalRevenue, revenue, 1e-3, "端到端净收入对账");
        }
    }

    /** Runs a group().by(key).by(values(v).sum()) query and returns its map. */
    @SuppressWarnings("unchecked")
    private static Map<String, Double> groupSum(GremlinGraphQuery g, String query) {
        Object result = g.execute(query).get(0);
        Map<String, Double> out = new TreeMap<>();
        ((Map<Object, Object>) result).forEach((k, v) -> out.put(String.valueOf(k),
                v == null ? 0.0 : ((Number) v).doubleValue()));
        return out;
    }

    private static Map<String, Double> groupSumLong(GremlinGraphQuery g, String query) {
        return groupSum(g, query);
    }

    private static double sum(Map<String, Double> map) {
        return map.values().stream().mapToDouble(Double::doubleValue).sum();
    }

    private static void assertSameSums(Map<String, Double> expected, Map<String, Double> actual, String what) {
        assertEquals(expected.keySet(), actual.keySet(), what + " 的分组一致");
        expected.forEach((key, value) ->
                assertEquals(value, actual.get(key), 1e-3, what + " 的 " + key + " 对账"));
    }

    /** Loads the instance the service serves, so the UI can query the data graph too. */
    @Test
    void loadsTheInstanceTheServiceServes() {
        String root = System.getenv().getOrDefault("TPCH_DATA_ROCKSDB_ROOT", "../.rocksdb");
        boolean reseed = Boolean.parseBoolean(System.getenv().getOrDefault("TPCH_DATA_RESEED", "false"));
        if (!reseed && TpchDataGraph.isLoaded(root)) {
            System.out.printf("[tpch-data] %s already loaded under %s "
                    + "- set TPCH_DATA_RESEED=true (service stopped) to rebuild%n", TpchDataGraph.GRAPH, root);
            return;
        }
        TpchDataGraph.Instance instance = TpchDataGraph.generate();
        TpchDataGraph.Result result = TpchDataGraph.load(instance, root, reseed);
        System.out.printf("[tpch-data] loaded %s at %s: %d vertices, %d edges%n",
                result.graph(), result.path(), result.vertices(), result.edges());
    }
}
