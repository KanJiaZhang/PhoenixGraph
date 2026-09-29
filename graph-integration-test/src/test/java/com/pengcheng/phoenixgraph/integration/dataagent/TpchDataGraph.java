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
 * The TPC-H <b>data instance</b> ({@code tpc-h-data}): materialised order-line fact rows and the
 * star-schema links, so the metrics the semantic layer defines can be answered with real numbers.
 *
 * <p>The rows are <b>synthesised deterministically</b> (a fixed seed) at the TPC-H ratio
 * (1 order : 4 lines) — a small, reproducible instance that fits in one RocksDB store, not the
 * 6-million-row official SF=1. The measure expressions ({@code revenue = extendedprice*(1-discount)},
 * tax, discount amount) are <b>pre-computed and stored</b> because the Gremlin engine has no
 * arithmetic in expressions; the dimensions are denormalised onto the fact row for the same reason
 * (a {@code group().by('region')} is a scan, a four-hop walk would not be).
 */
public final class TpchDataGraph {

    public static final String GRAPH = "tpc-h-data";
    public static final String DEFAULT_ROOT = "../.rocksdb";

    /** The deterministic seed: the same instance every rebuild. */
    private static final long SEED = 42L;

    private static final String[] REGIONS = {"AFRICA", "AMERICA", "ASIA", "EUROPE", "MIDDLE EAST"};
    private static final String[] NATIONS = {
            "ALGERIA", "ARGENTINA", "BRAZIL", "CANADA", "EGYPT", "ETHIOPIA", "FRANCE", "GERMANY",
            "INDIA", "INDONESIA", "IRAN", "IRAQ", "JAPAN", "JORDAN", "KENYA", "MOROCCO",
            "MOZAMBIQUE", "PERU", "CHINA", "ROMANIA", "SAUDI ARABIA", "VIETNAM", "RUSSIA",
            "UNITED KINGDOM", "UNITED STATES"};
    private static final String[] SEGMENTS = {"BUILDING", "AUTOMOBILE", "MACHINERY", "HOUSEHOLD", "FURNITURE"};
    private static final String[] PRIORITIES = {"1-URGENT", "2-HIGH", "3-MEDIUM", "4-NOT SPECIFIED", "5-LOW"};
    private static final String[] SHIPMODES = {"AIR", "MAIL", "SHIP", "TRUCK", "RAIL", "REG AIR"};
    private static final String[] PART_TYPES = {
            "STANDARD ANODIZED TIN", "PROMO BRUSHED STEEL", "SMALL PLATED COPPER", "PROMO POLISHED BRASS",
            "MEDIUM BURNISHED NICKEL", "LARGE ANODIZED STEEL", "PROMO ECONOMY TIN", "STANDARD POLISHED TIN"};
    private static final String[] BRANDS = {"Brand#11", "Brand#21", "Brand#31", "Brand#41", "Brand#51"};

    private static final int REGION_COUNT = REGIONS.length;
    private static final int NATION_COUNT = NATIONS.length;
    private static final int CUSTOMER_COUNT = 500;
    private static final int SUPPLIER_COUNT = 100;
    private static final int PART_COUNT = 500;
    private static final int ORDER_COUNT = 2_000;
    private static final int LINES_PER_ORDER = 4;
    private static final int SALE_COUNT = ORDER_COUNT * LINES_PER_ORDER;

    /** One materialised sale (a lineitem row with its dimensions folded in). */
    public record Sale(String id, double extendedPrice, double discount, double tax, double quantity,
                       double revenue, double gross, double discountAmount, double taxAmount,
                       long lateDelivery, long returned, String region, String nation, String segment,
                       String priority, String shipMode, String partType, String brand, String supplierName,
                       long orderYear, long orderMonth, String partId, String supplierId, String orderId,
                       String customerId) {
    }

    /** The generated instance: the sale rows plus the dimension rows they link to. */
    public record Instance(List<Sale> sales, List<String[]> edges) {
    }

    public record Result(String graph, String path, int vertices, int edges) {
    }

    // --- schema ------------------------------------------------------------------------------

    public static GraphSchema loadSchema() {
        for (String candidate : List.of("graph-http/src/main/resources/" + GRAPH + ".json",
                "../graph-http/src/main/resources/" + GRAPH + ".json")) {
            Path file = Path.of(candidate);
            if (Files.isReadable(file)) {
                try {
                    return new SchemaJsonCodec().fromJson(Files.readString(file, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to read " + file, e);
                }
            }
        }
        try (InputStream in = TpchDataGraph.class.getResourceAsStream("/" + GRAPH + ".json")) {
            if (in != null) {
                return new SchemaJsonCodec().fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read the schema of " + GRAPH, e);
        }
        throw new IllegalStateException(GRAPH + ".json not found");
    }

    // --- the deterministic instance ----------------------------------------------------------

    /** Generates the instance: {@value #SALE_COUNT} sales at the TPC-H 1:4 ratio, fixed seed. */
    public static Instance generate() {
        Random random = new Random(SEED);
        List<Sale> sales = new ArrayList<>(SALE_COUNT);
        List<String[]> edges = new ArrayList<>();
        int saleIndex = 0;
        for (int order = 0; order < ORDER_COUNT; order++) {
            String orderId = "order_" + order;
            // 客户由订单确定（与 placedBy 边一致），否则「属性上的 region」≠「图上 join 出的 region」
            int customer = order % CUSTOMER_COUNT;
            String customerId = "customer_" + customer;
            String segment = SEGMENTS[customer % SEGMENTS.length];
            String nation = NATIONS[customer % NATION_COUNT];
            String region = REGIONS[customer % REGION_COUNT];
            String priority = PRIORITIES[order % PRIORITIES.length];
            int year = 1995 + random.nextInt(2);
            int month = 1 + random.nextInt(12);
            for (int line = 0; line < LINES_PER_ORDER; line++) {
                int part = random.nextInt(PART_COUNT);
                int supplier = random.nextInt(SUPPLIER_COUNT);
                double quantity = 1 + random.nextInt(50);
                double unitPrice = 900 + random.nextInt(200);
                double extendedPrice = unitPrice * quantity;
                double discount = new double[] {0, 0.02, 0.04, 0.05, 0.06, 0.08, 0.1}[random.nextInt(7)];
                double tax = new double[] {0, 0.02, 0.04, 0.06, 0.08}[random.nextInt(5)];
                double net = extendedPrice * (1 - discount);
                String id = "sale_" + saleIndex++;
                sales.add(new Sale(id, extendedPrice, discount, tax, quantity, net, extendedPrice,
                        extendedPrice * discount, net * tax,
                        random.nextDouble() < 0.10 ? 1L : 0L,   // 10% 迟到
                        random.nextDouble() < 0.05 ? 1L : 0L,   // 5% 退货
                        region, nation, segment, priority, SHIPMODES[random.nextInt(SHIPMODES.length)],
                        PART_TYPES[part % PART_TYPES.length], BRANDS[part % BRANDS.length],
                        "Supplier#" + supplier, year, month,
                        "part_" + part, "supplier_" + supplier, orderId, customerId));
            }
        }
        // 星型模型的边（图查询用；聚合用上面反规范化的属性）
        for (Sale sale : sales) {
            edges.add(new String[] {sale.id(), "ofPart", sale.partId()});
            edges.add(new String[] {sale.id(), "ofSupplier", sale.supplierId()});
            edges.add(new String[] {sale.id(), "ofOrder", sale.orderId()});
        }
        for (int order = 0; order < ORDER_COUNT; order++) {
            edges.add(new String[] {"order_" + order, "placedBy", "customer_" + (order % CUSTOMER_COUNT)});
        }
        for (int customer = 0; customer < CUSTOMER_COUNT; customer++) {
            edges.add(new String[] {"customer_" + customer, "customerNation", "nation_" + (customer % NATION_COUNT)});
        }
        for (int supplier = 0; supplier < SUPPLIER_COUNT; supplier++) {
            edges.add(new String[] {"supplier_" + supplier, "supplierNation", "nation_" + (supplier % NATION_COUNT)});
        }
        for (int nation = 0; nation < NATION_COUNT; nation++) {
            edges.add(new String[] {"nation_" + nation, "nationRegion", "region_" + (nation % REGION_COUNT)});
        }
        return new Instance(List.copyOf(sales), List.copyOf(edges));
    }

    // --- load --------------------------------------------------------------------------------

    public static Result load(Instance instance, String rocksDbRoot, boolean reseed) {
        GraphSchema schema = loadSchema();
        String root = rocksDbRoot == null || rocksDbRoot.isBlank() ? DEFAULT_ROOT : rocksDbRoot;
        Path path = Path.of(root).resolve(schema.storage().requireRocksDbPath());
        if (reseed && Files.exists(path)) {
            deleteDirectory(path);
        }
        RocksDbStoreConfig config = new RocksDbStoreConfig(path.toString(), schema.storage().regionCount(),
                schema.storage().maxVersions(), true, schema.storage().cacheEdges());
        List<RocksDbVertexUpsert> vertices = vertexUpserts(schema, instance);
        Map<String, List<RocksDbEdgeUpsert>> edgesByClass = edgeUpserts(schema, instance);
        int edgeCount = edgesByClass.values().stream().mapToInt(List::size).sum();
        try (RocksDbEdgeStore store = RocksDbEdgeStore.open(schema, config)) {
            store.upsertVertices(vertices);
            // 一批边必须同一类
            for (List<RocksDbEdgeUpsert> batch : edgesByClass.values()) {
                store.upsertEdges(batch);
            }
        }
        return new Result(GRAPH, path.toString(), vertices.size(), edgeCount);
    }

    public static boolean isLoaded(String rocksDbRoot) {
        Path path = Path.of(rocksDbRoot == null || rocksDbRoot.isBlank() ? DEFAULT_ROOT : rocksDbRoot)
                .resolve(loadSchema().storage().requireRocksDbPath());
        return Files.exists(path.resolve("CURRENT"));
    }

    static List<RocksDbVertexUpsert> vertexUpserts(GraphSchema schema, Instance instance) {
        VertexSchema sale = schema.vertexSchema("Sale");
        long now = System.currentTimeMillis();
        List<RocksDbVertexUpsert> upserts = new ArrayList<>();
        for (Sale s : instance.sales()) {
            Map<Integer, RocksDbPropertyValue> p = new LinkedHashMap<>();
            put(p, sale, "name", s.id(), now);
            put(p, sale, "extendedprice", s.extendedPrice(), now);
            put(p, sale, "discount", s.discount(), now);
            put(p, sale, "tax", s.tax(), now);
            put(p, sale, "quantity", s.quantity(), now);
            put(p, sale, "revenue", s.revenue(), now);
            put(p, sale, "gross", s.gross(), now);
            put(p, sale, "discountAmount", s.discountAmount(), now);
            put(p, sale, "taxAmount", s.taxAmount(), now);
            put(p, sale, "lateDelivery", s.lateDelivery(), now);
            put(p, sale, "returned", s.returned(), now);
            put(p, sale, "region", s.region(), now);
            put(p, sale, "nation", s.nation(), now);
            put(p, sale, "segment", s.segment(), now);
            put(p, sale, "priority", s.priority(), now);
            put(p, sale, "shipmode", s.shipMode(), now);
            put(p, sale, "partType", s.partType(), now);
            put(p, sale, "brand", s.brand(), now);
            put(p, sale, "supplierName", s.supplierName(), now);
            put(p, sale, "orderYear", s.orderYear(), now);
            put(p, sale, "orderMonth", s.orderMonth(), now);
            upserts.add(new RocksDbVertexUpsert(s.id(), sale, p, now, now));
        }
        // 维度顶点
        for (int i = 0; i < REGION_COUNT; i++) {
            upserts.add(dimension(schema, "Region", "region_" + i, REGIONS[i], null, null, now));
        }
        for (int i = 0; i < NATION_COUNT; i++) {
            upserts.add(dimension(schema, "Nation", "nation_" + i, NATIONS[i], "region", REGIONS[i % REGION_COUNT], now));
        }
        for (int i = 0; i < CUSTOMER_COUNT; i++) {
            upserts.add(dimension(schema, "Customer", "customer_" + i, "Customer#" + i, "segment",
                    SEGMENTS[i % SEGMENTS.length], now));
        }
        for (int i = 0; i < SUPPLIER_COUNT; i++) {
            upserts.add(dimension(schema, "Supplier", "supplier_" + i, "Supplier#" + i, "nation",
                    NATIONS[i % NATION_COUNT], now));
        }
        for (int i = 0; i < PART_COUNT; i++) {
            upserts.add(dimension(schema, "Part", "part_" + i, "Part#" + i, "partType",
                    PART_TYPES[i % PART_TYPES.length], now));
        }
        for (int i = 0; i < ORDER_COUNT; i++) {
            upserts.add(dimension(schema, "Order", "order_" + i, "order_" + i, "priority",
                    PRIORITIES[i % PRIORITIES.length], now));
        }
        return upserts;
    }

    private static RocksDbVertexUpsert dimension(GraphSchema schema, String type, String id, String name,
                                                 String extraKey, String extraValue, long now) {
        VertexSchema vertex = schema.vertexSchema(type);
        Map<Integer, RocksDbPropertyValue> p = new LinkedHashMap<>();
        put(p, vertex, "name", name, now);
        if (extraKey != null) {
            put(p, vertex, extraKey, extraValue, now);
        }
        return new RocksDbVertexUpsert(id, vertex, p, now, now);
    }

    static Map<String, List<RocksDbEdgeUpsert>> edgeUpserts(GraphSchema schema, Instance instance) {
        long now = System.currentTimeMillis();
        Map<String, String> labels = Map.of("ofPart", "含商品", "ofSupplier", "由供应商供", "ofOrder", "属于订单",
                "placedBy", "下单客户", "customerNation", "客户所在国家",
                "supplierNation", "供应商所在国家", "nationRegion", "所在大区");
        Map<String, List<RocksDbEdgeUpsert>> byClass = new LinkedHashMap<>();
        for (String[] e : instance.edges()) {
            EdgeSchema edgeSchema = schema.edgeSchema(e[1]);
            Map<Integer, RocksDbEdgeProperty> p = new LinkedHashMap<>();
            putEdge(p, edgeSchema, "relationLabel", labels.getOrDefault(e[1], e[1]), now);
            byClass.computeIfAbsent(e[1], k -> new ArrayList<>())
                    .add(new RocksDbEdgeUpsert(e[0] + "|" + e[1] + "|" + e[2], edgeSchema, e[0], e[2], p, now, now));
        }
        return byClass;
    }

    private static void put(Map<Integer, RocksDbPropertyValue> p, VertexSchema schema, String name,
                            Object value, long time) {
        if (value != null) {
            p.put(schema.property(name).id(), new RocksDbPropertyValue(value, time));
        }
    }

    private static void putEdge(Map<Integer, RocksDbEdgeProperty> p, EdgeSchema schema, String name,
                                Object value, long time) {
        if (value != null) {
            p.put(schema.property(name).id(), new RocksDbEdgeProperty(value, time, time));
        }
    }

    private static void deleteDirectory(Path path) {
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

    private TpchDataGraph() {
    }
}
