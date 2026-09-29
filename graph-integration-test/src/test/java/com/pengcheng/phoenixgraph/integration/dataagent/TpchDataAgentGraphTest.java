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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The enterprise data-agent graph {@code tpc-h-analytics}: what an agent asks of a semantic layer.
 *
 * <p>Each test is one question an agent has to answer before it can serve a business user —
 * <em>how is this metric defined, which tables and columns does it read, what can it be sliced by,
 * what drives it</em> — and the answer comes from the graph, not from a prompt.
 */
class TpchDataAgentGraphTest {

    @Test
    void answersTheMetricQuestions(@TempDir Path directory) {
        GraphSchema schema = TpchDataAgentGraph.loadSchema();
        TpchDataAgentGraph.Built built = TpchDataAgentGraph.build();
        Path path = directory.resolve(schema.storage().requireRocksDbPath());
        TpchDataAgentGraph.load(built, directory.toString(), true);

        RocksDbStoreConfig config = new RocksDbStoreConfig(path.toString(), schema.storage().regionCount(),
                schema.storage().maxVersions(), true, schema.storage().cacheEdges());
        try (RocksDbEdgeStore store = RocksDbEdgeStore.open(schema, config)) {
            GraphQuery graph = new RocksDbEdgeGraphQuery(store, schema);
            GremlinGraphQuery g = new GremlinGraphQuery(graph, NeighborVertexResolver.from(schema));

            System.out.println();
            System.out.println("======================================================================");
            System.out.println(" 企业 data agent 的语义层（tpc-h-analytics）");
            System.out.println("======================================================================");
            System.out.println(" 节点: " + built.dataset().nodes().size() + "（表 " + count(built, "Table")
                    + " / 字段 " + count(built, "Column") + " / 指标 " + count(built, "Metric")
                    + " / 维度 " + count(built, "Dimension") + " / 业务域 " + count(built, "Domain")
                    + " / 查询 " + count(built, "Query") + "）"
                    + " 关系: " + built.dataset().relations().size());

            // ① 怎么定义指标？—— 一个节点就带着口径
            List<Object> def = g.execute("g.V('metric/revenue','Node')"
                    + ".values('aggFunction','expression','unit','metricKind')");
            System.out.println("\n① revenue 怎么定义？");
            System.out.println("   聚合=" + def.get(0) + "  表达式=" + def.get(1) + "  单位=" + def.get(2)
                    + "  类型=" + def.get(3));
            assertEquals("SUM", def.get(0));
            assertEquals("SUM(l_extendedprice * (1 - l_discount))", def.get(1));

            // ② 指标读哪些表？—— computedFrom（血缘）
            List<Object> tables = g.execute("g.V('metric/revenue','Node')"
                    + ".outE().filter(relation.equals('computedFrom')).otherV().values('name')");
            System.out.println("② revenue 从哪些表来？  " + tables);
            assertEquals(List.of("table/lineitem"), tables);

            // ③ 指标用哪些字段？—— usesColumn（生成 SQL 需要的列）
            List<Object> columns = g.execute("g.V('metric/revenue','Node')"
                    + ".outE().filter(relation.equals('usesColumn')).otherV().values('name')");
            System.out.println("③ revenue 用哪些字段？  " + columns);
            assertTrue(columns.contains("column/lineitem.l_extendedprice"));

            // ④ 指标能按什么切片？—— groupedBy（维度下钻）
            List<Object> dims = g.execute("g.V('metric/revenue','Node')"
                    + ".outE().filter(relation.equals('groupedBy')).otherV().values('name')");
            System.out.println("④ revenue 能按什么维度看？  " + dims);
            assertTrue(dims.contains("dim/region"));

            // ⑤ 指标属于哪个域？—— belongsTo
            List<Object> domain = g.execute("g.V('metric/revenue','Node')"
                    + ".outE().filter(relation.equals('belongsTo')).otherV().values('name')");
            System.out.println("⑤ revenue 属于哪个业务域？  " + domain);
            assertEquals(List.of("domain/sales"), domain);

            // ⑥ 归因：哪些指标驱动 revenue？—— inE(contributesTo)
            List<Object> drivers = g.execute("g.V('metric/revenue','Node')"
                    + ".inE().filter(relation.equals('contributesTo')).otherV().values('name')");
            System.out.println("⑥ revenue 由哪些指标驱动（归因）？  " + drivers);
            assertTrue(drivers.contains("metric/gross_revenue"));
            assertTrue(drivers.contains("metric/discount_amount"));

            // ⑦ 归因链：profit 的上游（2 跳）
            List<Object> profitRoots = g.execute("g.V('metric/profit','Node')"
                    + ".inE().filter(relation.equals('contributesTo')).otherV()"
                    + ".inE().filter(relation.equals('contributesTo')).otherV().dedup().values('name')");
            System.out.println("⑦ profit 的二阶驱动（归因链）？  " + profitRoots);
            assertTrue(profitRoots.contains("metric/gross_revenue"));

            // ⑧ 定义查询：哪个标准查询定义了 revenue？它的 SQL 是什么？—— definedBy → sqlText
            List<Object> sql = g.execute("g.V('metric/revenue','Node')"
                    + ".outE().filter(relation.equals('definedBy')).otherV().values('queryNumber','sqlText')");
            System.out.println("⑧ revenue 的定义查询 + SQL（截断）:");
            System.out.println("   TPC-H Q" + sql.get(0) + ": " + truncate(String.valueOf(sql.get(1)), 130));

            // ⑨ 反向血缘：某字段被哪些指标使用？—— inE(usesColumn)
            List<Object> users = g.execute("g.V('column/lineitem.l_extendedprice','Node')"
                    + ".inE().filter(relation.equals('usesColumn')).otherV().values('name')");
            System.out.println("⑨ l_extendedprice 被哪些指标使用（反向血缘）？  " + users);
            assertTrue(users.contains("metric/revenue"));

            // ⑩ 表结构：orders 有哪些字段、哪些是键？—— hasColumn + columnRole
            List<Object> orderKeys = g.execute("g.V('table/orders','Node')"
                    + ".outE().filter(relation.equals('hasColumn')).otherV()"
                    + ".has('columnRole','FK').values('name')");
            System.out.println("⑩ orders 的外键（生成 join 用）？  " + orderKeys);
            assertTrue(orderKeys.contains("column/orders.o_custkey"));

            // ⑪ 生成查询：从指标走「表 → 外键 → 目标表」得到 join 路径
            List<Object> joins = g.execute("g.V('metric/revenue','Node')"
                    + ".outE().filter(relation.equals('computedFrom')).otherV()"
                    + ".outE().filter(relation.equals('hasColumn')).otherV()"
                    + ".has('columnRole','FK').outE().filter(relation.equals('references')).otherV()"
                    + ".values('name')");
            System.out.println("⑪ revenue 的 join 目标（外键指向的主键）？  " + joins);
            assertTrue(joins.contains("column/orders.o_orderkey"));

            // ⑫ 组成 SQL 的片段：表 + 度量列 + 聚合
            List<Object> measure = g.execute("g.V('metric/revenue','Node')"
                    + ".outE().filter(relation.equals('usesColumn')).otherV()"
                    + ".has('columnRole','measure').values('name')");
            System.out.println("⑫ revenue 的度量列？  " + measure);
            assertFalse(measure.isEmpty());

            // ⑬ 两个指标的共同字段（关系推断）
            List<Object> shared = g.execute("g.V('metric/revenue','Node')"
                    + ".outE().filter(relation.equals('usesColumn')).otherV().dedup()"
                    + ".where(inE().filter(relation.equals('usesColumn')).otherV().hasId('metric/avg_order_value'))"
                    + ".values('name')");
            System.out.println("⑬ revenue 与 avg_order_value 的共同字段？  " + shared);
            assertTrue(shared.contains("column/lineitem.l_extendedprice"));
        }
    }

    private static long count(TpchDataAgentGraph.Built built, String type) {
        return built.dataset().nodesOfType(type).size();
    }

    /** Loads the instance the service serves, so the UI can query the semantic layer. */
    @Test
    void loadsTheInstanceTheServiceServes() {
        String root = System.getenv().getOrDefault("TPCH_ROCKSDB_ROOT", "../.rocksdb");
        boolean reseed = Boolean.parseBoolean(System.getenv().getOrDefault("TPCH_ROCKSDB_RESEED", "false"));
        if (!reseed && TpchDataAgentGraph.isLoaded(root)) {
            System.out.printf("[tpch] %s already loaded under %s "
                            + "- set TPCH_ROCKSDB_RESEED=true (service stopped) to rebuild%n",
                    TpchDataAgentGraph.GRAPH, root);
            return;
        }
        TpchDataAgentGraph.Built built = TpchDataAgentGraph.build();
        TpchDataAgentGraph.Result result = TpchDataAgentGraph.load(built, root, reseed);
        System.out.printf("[tpch] loaded %s at %s: %d nodes, %d relations%n",
                result.graph(), result.path(), result.nodes(), result.relations());
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
