package com.pengcheng.phoenixgraph.integration.dataagent;

import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.VertexSchema;
import com.pengcheng.phoenixgraph.schema.json.SchemaJsonCodec;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbEdgeProperty;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbEdgeUpsert;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbPropertyValue;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbStoreConfig;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbVertexUpsert;
import com.pengcheng.phoenixgraph.storage.rocksdb.edge.RocksDbEdgeStore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The marketing demo of the semantic layer: a second graph ({@code demo-semantics}) plus its fact instance
 * ({@code demo-data}), proving the runner, the interpreter and the renderer are not hardcoded to TPC-H.
 *
 * <p>The metrics are the ones an acquisition team argues about - spend, impressions, clicks, revenue, GMV,
 * new customers, and the ratios derived from them (CAC, ROI, CTR). The base metrics bind to a fact property
 * ({@code graphProperty} on the metric node) and answer from the graph; the ratios bind nowhere and are
 * refused with the warehouse SQL, which is the refusal rule working as designed. The dimensions are date
 * (year/month grains), channel, campaign and region. The binding lives on the nodes themselves (the S1 final
 * form), written here by the same class that writes the fact rows, so the two graphs cannot drift.
 */
public final class DemoMarketingGraph {

    /** The semantic graph (its schema file is {@code graph-http/src/main/resources/demo-semantics.json}). */
    public static final String SEMANTIC_GRAPH = "demo-semantics";
    /** The fact graph (its schema file is {@code graph-http/src/main/resources/demo-data.json}). */
    public static final String DATA_GRAPH = "demo-data";
    /** The RocksDB root the service reads, relative to the module directory. */
    public static final String DEFAULT_ROOT = ".rocksdb";

    private static final String[] CHANNELS = {"搜索", "社交", "邮件", "联盟"};
    private static final String[] CAMPAIGNS = {"品牌", "促销", "拉新"};
    private static final String[] REGIONS = {"华东", "华南", "华北", "西部"};
    private static final int YEARS = 2; // 2024, 2025
    private static final long SEED = 7L;

    // --- the semantic dataset ----------------------------------------------------------------------

    /** A semantic-layer node; only the fields its kind uses are set, the rest are null. */
    public record Node(String name, String entityType, String description, List<String> aliases,
                       String dataType, String columnRole, String tableName,
                       String aggFunction, String expression, String metricKind, String unit,
                       String graphProperty, List<String> columns) {

        static Node table(String name, String description) {
            return new Node(name, "Table", description, null, null, null, null, null, null, null, null, null, null);
        }

        static Node column(String name, String table, String dataType, String role, String description) {
            return new Node(name, "Column", description, null, dataType, role, table, null, null, null, null, null, null);
        }

        static Node metric(String name, String description, String kind, String agg, String expression,
                           String unit, List<String> aliases, String graphProperty, List<String> columns) {
            return new Node(name, "Metric", description, aliases, null, null, null, agg, expression, kind, unit,
                    graphProperty, columns);
        }

        static Node dimension(String name, String description, List<String> aliases, String graphProperty) {
            return new Node(name, "Dimension", description, aliases, null, null, null, null, null, null, null,
                    graphProperty, null);
        }

        static Node domain(String name, String description) {
            return new Node(name, "Domain", description, null, null, null, null, null, null, null, null, null, null);
        }
    }

    /** A directed semantic-layer edge. */
    public record Relation(String head, String relation, String tail) {
    }

    private static final Map<String, String> REVERSE = Map.of(
            "hasColumn", "belongsToTable",
            "computedFrom", "sourceFor",
            "usesColumn", "usedByMetric",
            "groupedBy", "groupsMetric",
            "dimensionColumn", "dimensionOf",
            "belongsTo", "containsMetric");

    private static final Map<String, String[]> RELATION_META = Map.of(
            "hasColumn", new String[] {"包含字段", "结构"},
            "computedFrom", new String[] {"取自表", "血缘"},
            "usesColumn", new String[] {"使用字段", "血缘"},
            "groupedBy", new String[] {"按维度分组", "维度"},
            "dimensionColumn", new String[] {"维度字段", "维度"},
            "belongsTo", new String[] {"属于业务域", "指标"});

    public static List<Node> nodes() {
        List<Node> nodes = new ArrayList<>();
        nodes.add(Node.table("table/marketing", "营销事实表：每日 × 渠道 × 活动 × 地区的投放与转化"));
        nodes.add(Node.column("column/marketing.date", "marketing", "DATE", "attribute", "日期"));
        nodes.add(Node.column("column/marketing.channel", "marketing", "VARCHAR(16)", "attribute", "渠道"));
        nodes.add(Node.column("column/marketing.campaign", "marketing", "VARCHAR(16)", "attribute", "活动"));
        nodes.add(Node.column("column/marketing.region", "marketing", "VARCHAR(16)", "attribute", "地区"));
        nodes.add(Node.column("column/marketing.spend", "marketing", "DECIMAL(15,2)", "measure", "广告花费"));
        nodes.add(Node.column("column/marketing.impressions", "marketing", "BIGINT", "measure", "曝光量"));
        nodes.add(Node.column("column/marketing.clicks", "marketing", "BIGINT", "measure", "点击量"));
        nodes.add(Node.column("column/marketing.revenue", "marketing", "DECIMAL(15,2)", "measure", "归因收入"));
        nodes.add(Node.column("column/marketing.gmv", "marketing", "DECIMAL(15,2)", "measure", "成交总额"));
        nodes.add(Node.column("column/marketing.new_customers", "marketing", "BIGINT", "measure", "新客数"));

        nodes.add(Node.dimension("dim/date", "日期：按年 / 月下钻", List.of("日期", "时间", "年度", "年份", "月份", "date"),
                "year=year,month=month"));
        nodes.add(Node.dimension("dim/channel", "渠道：搜索 / 社交 / 邮件 / 联盟", List.of("渠道", "通道", "channel"), "channel"));
        nodes.add(Node.dimension("dim/campaign", "活动：品牌 / 促销 / 拉新", List.of("活动", "营销活动", "campaign"), "campaign"));
        nodes.add(Node.dimension("dim/region", "地区：华东 / 华南 / 华北 / 西部", List.of("地区", "大区", "区域", "region"), "region"));

        nodes.add(Node.metric("metric/spend", "广告花费：投放支出合计", "base", "SUM", "SUM(spend)", "元",
                List.of("花费", "广告费", "支出", "spend"), "spend", List.of("column/marketing.spend")));
        nodes.add(Node.metric("metric/impressions", "曝光量：展示次数合计", "base", "SUM", "SUM(impressions)", "次",
                List.of("曝光", "展示", "impressions"), "impressions", List.of("column/marketing.impressions")));
        nodes.add(Node.metric("metric/clicks", "点击量：点击次数合计", "base", "SUM", "SUM(clicks)", "次",
                List.of("点击", "clicks"), "clicks", List.of("column/marketing.clicks")));
        nodes.add(Node.metric("metric/revenue", "归因收入：投放带来的收入合计", "base", "SUM", "SUM(revenue)", "元",
                List.of("收入", "营收", "revenue"), "revenue", List.of("column/marketing.revenue")));
        nodes.add(Node.metric("metric/gmv", "成交总额：订单成交金额合计", "base", "SUM", "SUM(gmv)", "元",
                List.of("成交额", "gmv"), "gmv", List.of("column/marketing.gmv")));
        nodes.add(Node.metric("metric/new_customers", "新客数：新增客户合计", "base", "SUM", "SUM(new_customers)", "个",
                List.of("新客", "新增客户", "new customers"), "newCustomers", List.of("column/marketing.new_customers")));
        nodes.add(Node.metric("metric/orders", "订单数：事实行数", "base", "COUNT", "COUNT(*)", "单",
                List.of("订单数", "orders"), "count", List.of("column/marketing.revenue")));
        nodes.add(Node.metric("metric/cac", "获客成本：花费 / 新客数", "ratio", "RATIO",
                "SUM(spend) / SUM(new_customers)", "元", List.of("获客成本", "cac"), null,
                List.of("column/marketing.spend", "column/marketing.new_customers")));
        nodes.add(Node.metric("metric/roi", "投入产出比：收入 / 花费", "ratio", "RATIO",
                "SUM(revenue) / SUM(spend)", "", List.of("roi", "投入产出比"), null,
                List.of("column/marketing.revenue", "column/marketing.spend")));
        nodes.add(Node.metric("metric/ctr", "点击率：点击 / 曝光", "ratio", "RATIO",
                "100.0 * SUM(clicks) / SUM(impressions)", "%", List.of("点击率", "ctr"), null,
                List.of("column/marketing.clicks", "column/marketing.impressions")));

        nodes.add(Node.domain("domain/acquisition", "获客域：投放、曝光、点击、新客与成本"));
        return nodes;
    }

    public static List<Relation> relations() {
        List<Relation> relations = new ArrayList<>();
        // the marketing table has its columns
        for (String column : List.of("column/marketing.date", "column/marketing.channel",
                "column/marketing.campaign", "column/marketing.region", "column/marketing.spend",
                "column/marketing.impressions", "column/marketing.clicks", "column/marketing.revenue",
                "column/marketing.gmv", "column/marketing.new_customers")) {
            relations.add(new Relation("table/marketing", "hasColumn", column));
        }
        // every dimension slices by one column
        relations.add(new Relation("dim/date", "dimensionColumn", "column/marketing.date"));
        relations.add(new Relation("dim/channel", "dimensionColumn", "column/marketing.channel"));
        relations.add(new Relation("dim/campaign", "dimensionColumn", "column/marketing.campaign"));
        relations.add(new Relation("dim/region", "dimensionColumn", "column/marketing.region"));

        // every metric: its tables, its columns, its dimensions and its domain
        List<String> dims = List.of("dim/date", "dim/channel", "dim/campaign", "dim/region");
        for (Node metric : nodes()) {
            if (!metric.entityType().equals("Metric")) {
                continue;
            }
            relations.add(new Relation(metric.name(), "computedFrom", "table/marketing"));
            for (String column : metric.columns()) {
                relations.add(new Relation(metric.name(), "usesColumn", column));
            }
            for (String dim : dims) {
                relations.add(new Relation(metric.name(), "groupedBy", dim));
            }
            relations.add(new Relation(metric.name(), "belongsTo", "domain/acquisition"));
        }
        return relations;
    }

    // --- the fact data ----------------------------------------------------------------------------

    /** One materialised marketing fact row, with its dimensions denormalised. */
    public record Fact(String id, double spend, double impressions, double clicks, double revenue,
                       double gmv, double newCustomers, String channel, String campaign, String region,
                       long year, long month) {
    }

    /** Generates the deterministic instance: 2 years × 12 months × 4 channels × 3 campaigns × 4 regions. */
    public static List<Fact> facts() {
        Random random = new Random(SEED);
        List<Fact> facts = new ArrayList<>();
        for (int year = 2024; year < 2024 + YEARS; year++) {
            for (int month = 1; month <= 12; month++) {
                for (String channel : CHANNELS) {
                    for (String campaign : CAMPAIGNS) {
                        for (String region : REGIONS) {
                            double spend = 1000 + random.nextDouble() * 49000;
                            double impressions = 10000 + random.nextDouble() * 490000;
                            double ctr = 0.005 + random.nextDouble() * 0.025;
                            double clicks = impressions * ctr;
                            double revenue = clicks * (50 + random.nextDouble() * 250);
                            double gmv = revenue * (1.1 + random.nextDouble() * 0.4);
                            double newCustomers = clicks * (0.02 + random.nextDouble() * 0.06);
                            String id = "mkt_" + year + "_" + month + "_" + channel + "_" + campaign + "_" + region;
                            facts.add(new Fact(id, spend, impressions, clicks, revenue, gmv, newCustomers,
                                    channel, campaign, region, year, month));
                        }
                    }
                }
            }
        }
        return facts;
    }

    // --- loaders ---------------------------------------------------------------------------------

    public static GraphSchema loadSchema(String graph) {
        for (String candidate : List.of("graph-http/src/main/resources/" + graph + ".json",
                "../graph-http/src/main/resources/" + graph + ".json")) {
            Path file = Path.of(candidate);
            if (Files.isReadable(file)) {
                try {
                    return new SchemaJsonCodec().fromJson(Files.readString(file, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to read " + file, e);
                }
            }
        }
        try (InputStream in = DemoMarketingGraph.class.getResourceAsStream("/" + graph + ".json")) {
            if (in != null) {
                return new SchemaJsonCodec().fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read the schema of " + graph, e);
        }
        throw new IllegalStateException(graph + ".json not found");
    }

    public static void loadSemantic(String root, boolean reseed) {
        GraphSchema schema = loadSchema(SEMANTIC_GRAPH);
        String rocksDbRoot = root == null || root.isBlank() ? DEFAULT_ROOT : root;
        Path path = Path.of(rocksDbRoot).resolve(schema.storage().requireRocksDbPath());
        if (reseed) {
            deleteDirectory(path);
        }
        RocksDbStoreConfig config = new RocksDbStoreConfig(path.toString(), schema.storage().regionCount(),
                schema.storage().maxVersions(), true, schema.storage().cacheEdges());
        VertexSchema nodeSchema = schema.vertexSchema("Node");
        EdgeSchema relationSchema = schema.edgeSchema("Relation");
        long now = System.currentTimeMillis();
        List<RocksDbVertexUpsert> vertices = new ArrayList<>();
        long tick = 0;
        for (Node node : nodes()) {
            long time = now + tick++;
            Map<Integer, RocksDbPropertyValue> props = new LinkedHashMap<>();
            put(props, nodeSchema, "name", node.name(), time);
            put(props, nodeSchema, "entityType", node.entityType(), time);
            put(props, nodeSchema, "description", node.description(), time);
            put(props, nodeSchema, "alias", node.aliases(), time);
            put(props, nodeSchema, "dataType", node.dataType(), time);
            put(props, nodeSchema, "columnRole", node.columnRole(), time);
            put(props, nodeSchema, "tableName", node.tableName(), time);
            put(props, nodeSchema, "aggFunction", node.aggFunction(), time);
            put(props, nodeSchema, "expression", node.expression(), time);
            put(props, nodeSchema, "metricKind", node.metricKind(), time);
            put(props, nodeSchema, "unit", node.unit(), time);
            put(props, nodeSchema, "graphProperty", node.graphProperty(), time);
            vertices.add(new RocksDbVertexUpsert(node.name(), nodeSchema, props, time, time));
        }
        List<RocksDbEdgeUpsert> edges = new ArrayList<>();
        tick = 0;
        for (Relation relation : relations()) {
            String[] meta = RELATION_META.get(relation.relation());
            long time = now + tick++;
            Map<Integer, RocksDbEdgeProperty> edgeProps = new LinkedHashMap<>();
            putEdge(edgeProps, relationSchema, "relation", relation.relation(), time);
            putEdge(edgeProps, relationSchema, "xrelation", REVERSE.getOrDefault(relation.relation(), relation.relation()), time);
            putEdge(edgeProps, relationSchema, "relationLabel", meta == null ? relation.relation() : meta[0], time);
            putEdge(edgeProps, relationSchema, "relationFamily", meta == null ? "其他" : meta[1], time);
            String edgeId = relation.head() + "|" + relation.relation() + "|" + relation.tail();
            edges.add(new RocksDbEdgeUpsert(edgeId, relationSchema, relation.head(), relation.tail(), edgeProps, time, time));
        }
        try (RocksDbEdgeStore store = RocksDbEdgeStore.open(schema, config)) {
            store.upsertVertices(vertices);
            store.upsertEdges(edges);
        }
        System.out.printf("[demo] loaded %s at %s: %d nodes, %d relations%n",
                SEMANTIC_GRAPH, path, vertices.size(), edges.size());
    }

    public static void loadData(String root, boolean reseed) {
        GraphSchema schema = loadSchema(DATA_GRAPH);
        String rocksDbRoot = root == null || root.isBlank() ? DEFAULT_ROOT : root;
        Path path = Path.of(rocksDbRoot).resolve(schema.storage().requireRocksDbPath());
        if (reseed) {
            deleteDirectory(path);
        }
        RocksDbStoreConfig config = new RocksDbStoreConfig(path.toString(), schema.storage().regionCount(),
                schema.storage().maxVersions(), true, schema.storage().cacheEdges());
        VertexSchema vertexSchema = schema.vertexSchema("MarketingDay");
        long now = System.currentTimeMillis();
        List<RocksDbVertexUpsert> vertices = new ArrayList<>();
        for (Fact fact : facts()) {
            Map<Integer, RocksDbPropertyValue> props = new LinkedHashMap<>();
            put(props, vertexSchema, "name", fact.id(), now);
            put(props, vertexSchema, "spend", fact.spend(), now);
            put(props, vertexSchema, "impressions", fact.impressions(), now);
            put(props, vertexSchema, "clicks", fact.clicks(), now);
            put(props, vertexSchema, "revenue", fact.revenue(), now);
            put(props, vertexSchema, "gmv", fact.gmv(), now);
            put(props, vertexSchema, "newCustomers", fact.newCustomers(), now);
            put(props, vertexSchema, "channel", fact.channel(), now);
            put(props, vertexSchema, "campaign", fact.campaign(), now);
            put(props, vertexSchema, "region", fact.region(), now);
            put(props, vertexSchema, "year", fact.year(), now);
            put(props, vertexSchema, "month", fact.month(), now);
            vertices.add(new RocksDbVertexUpsert(fact.id(), vertexSchema, props, now, now));
        }
        try (RocksDbEdgeStore store = RocksDbEdgeStore.open(schema, config)) {
            store.upsertVertices(vertices);
        }
        System.out.printf("[demo] loaded %s at %s: %d rows%n", DATA_GRAPH, path, vertices.size());
    }

    public static void loadAll(String root, boolean reseed) {
        loadSemantic(root, reseed);
        loadData(root, reseed);
    }

    public static boolean isLoaded(String root) {
        String rocksDbRoot = root == null || root.isBlank() ? DEFAULT_ROOT : root;
        return Files.exists(Path.of(rocksDbRoot).resolve(loadSchema(SEMANTIC_GRAPH).storage().requireRocksDbPath())
                .resolve("CURRENT"))
                && Files.exists(Path.of(rocksDbRoot).resolve(loadSchema(DATA_GRAPH).storage().requireRocksDbPath())
                .resolve("CURRENT"));
    }

    private static void put(Map<Integer, RocksDbPropertyValue> props, VertexSchema schema, String name,
                            Object value, long time) {
        if (value != null) {
            props.put(schema.property(name).id(), new RocksDbPropertyValue(value, time));
        }
    }

    private static void putEdge(Map<Integer, RocksDbEdgeProperty> props, EdgeSchema schema, String name,
                                Object value, long time) {
        if (value != null) {
            props.put(schema.property(name).id(), new RocksDbEdgeProperty(value, time, time));
        }
    }

    private static void deleteDirectory(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to clear " + path, e);
        }
    }

    private DemoMarketingGraph() {
    }
}
