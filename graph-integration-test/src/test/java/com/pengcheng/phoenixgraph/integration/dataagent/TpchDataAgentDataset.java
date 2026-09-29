package com.pengcheng.phoenixgraph.integration.dataagent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The semantic layer of an enterprise data agent, modelled over the <b>TPC-H</b> decision-support
 * benchmark (a public, reproducible retail/order business model: 8 tables and 22 standard queries).
 *
 * <p>What an agent needs in order to answer a business question is not the raw warehouse, it is the
 * <em>meaning</em> around it: which table a metric comes from, how it is aggregated, which columns it
 * reads, which dimensions it can be sliced by, which query defines it, and which base metrics drive
 * it (the attribution edges). This dataset is exactly that: nodes of six kinds and relations of ten,
 * so an agent can
 * <ul>
 *   <li><b>定义指标</b> — {@code metric/revenue} carries its aggregation, SQL expression and unit;</li>
 *   <li><b>生成准确查询</b> — the joins come from the {@code references} edges and the columns from
 *       {@code usesColumn}, so the SQL is assembled, not guessed;</li>
 *   <li><b>分析结果与归因</b> — {@code contributesTo} says revenue is driven by gross revenue,
 *       discount amount, promotion revenue and order count × average order value;</li>
 *   <li><b>回答业务问题</b> — {@code belongsTo} places each metric in a domain.</li>
 * </ul>
 */
public final class TpchDataAgentDataset {

    /** A semantic-layer node. Only the fields its kind uses are set; the rest are {@code null}. */
    public record Node(String name, String entityType, String description, List<String> aliases,
                       String dataType, String columnRole, String tableName,
                       String aggFunction, String expression, String metricKind, String unit,
                       String sqlText, Long queryNumber) {

        public static Node table(String name, String description) {
            return new Node(name, "Table", description, null, null, null, null, null, null, null, null, null, null);
        }

        public static Node column(String name, String table, String dataType, String role, String description) {
            return new Node(name, "Column", description, null, dataType, role, table, null, null, null, null, null, null);
        }

        public static Node metric(String name, String description, String kind, String agg, String expression, String unit) {
            return new Node(name, "Metric", description, null, null, null, null, agg, expression, kind, unit, null, null);
        }

        public static Node dimension(String name, String description) {
            return new Node(name, "Dimension", description, null, null, null, null, null, null, null, null, null, null);
        }

        public static Node domain(String name, String description) {
            return new Node(name, "Domain", description, null, null, null, null, null, null, null, null, null, null);
        }

        public static Node query(String name, long number, String description, String sql) {
            return new Node(name, "Query", description, null, null, null, null, null, null, null, null, sql, number);
        }

        public boolean is(String type) {
            return entityType.equals(type);
        }
    }

    /** A directed semantic-layer edge; its Chinese label and family come from {@link #relationMeta}. */
    public record Relation(String head, String relation, String tail) {
    }

    /** Chinese name and family of every relation of this graph. */
    public static Map<String, String[]> relationMeta() {
        Map<String, String[]> meta = new LinkedHashMap<>();
        meta.put("hasColumn", new String[] {"包含字段", "结构"});
        meta.put("references", new String[] {"外键引用", "结构"});
        meta.put("computedFrom", new String[] {"取自表", "血缘"});
        meta.put("usesColumn", new String[] {"使用字段", "血缘"});
        meta.put("readsTable", new String[] {"读取表", "血缘"});
        meta.put("groupedBy", new String[] {"按维度分组", "维度"});
        meta.put("dimensionColumn", new String[] {"维度字段", "维度"});
        meta.put("definedBy", new String[] {"定义查询", "指标"});
        meta.put("contributesTo", new String[] {"贡献于", "归因"});
        meta.put("belongsTo", new String[] {"属于业务域", "指标"});
        return meta;
    }

    // --- the eight TPC-H tables -------------------------------------------------------------

    public static List<Node> tables() {
        return List.of(
                Node.table("table/region", "地区表：五大区（AFRICA/AMERICA/ASIA/EUROPE/MIDDLE EAST），地理层级的顶层"),
                Node.table("table/nation", "国家表：25 个国家，各自归属一个地区"),
                Node.table("table/supplier", "供应商表：供货方的账户、地址、余额"),
                Node.table("table/customer", "客户表：客户的账户、地址、市场细分、余额"),
                Node.table("table/part", "商品表：零件的品牌、类型、尺寸、容器、零售价"),
                Node.table("table/partsupp", "供货关系表：商品 × 供应商的供货量与供货成本"),
                Node.table("table/orders", "订单表：订单的客户、状态、总价、日期、优先级、配送优先级"),
                Node.table("table/lineitem", "订单行表：一张订单的每一行明细（数量、单价、折扣、税、收货日期）"));
    }

    // --- the columns (TPC-H canonical schema) -----------------------------------------------

    public static List<Node> columns() {
        List<Node> c = new ArrayList<>();
        // region
        c.add(Node.column("column/region.r_regionkey", "region", "INTEGER", "PK", "地区主键"));
        c.add(Node.column("column/region.r_name", "region", "CHAR(25)", "attribute", "地区名"));
        c.add(Node.column("column/region.r_comment", "region", "VARCHAR(152)", "attribute", "备注"));
        // nation
        c.add(Node.column("column/nation.n_nationkey", "nation", "INTEGER", "PK", "国家主键"));
        c.add(Node.column("column/nation.n_name", "nation", "CHAR(25)", "attribute", "国家名"));
        c.add(Node.column("column/nation.n_regionkey", "nation", "INTEGER", "FK", "所属地区（→ region）"));
        c.add(Node.column("column/nation.n_comment", "nation", "VARCHAR(152)", "attribute", "备注"));
        // supplier
        c.add(Node.column("column/supplier.s_suppkey", "supplier", "INTEGER", "PK", "供应商主键"));
        c.add(Node.column("column/supplier.s_name", "supplier", "CHAR(25)", "attribute", "供应商名"));
        c.add(Node.column("column/supplier.s_address", "supplier", "VARCHAR(40)", "attribute", "地址"));
        c.add(Node.column("column/supplier.s_nationkey", "supplier", "INTEGER", "FK", "所属国家（→ nation）"));
        c.add(Node.column("column/supplier.s_phone", "supplier", "CHAR(15)", "attribute", "电话"));
        c.add(Node.column("column/supplier.s_acctbal", "supplier", "DECIMAL(15,2)", "measure", "账户余额"));
        c.add(Node.column("column/supplier.s_comment", "supplier", "VARCHAR(101)", "attribute", "备注"));
        // customer
        c.add(Node.column("column/customer.c_custkey", "customer", "INTEGER", "PK", "客户主键"));
        c.add(Node.column("column/customer.c_name", "customer", "CHAR(25)", "attribute", "客户名"));
        c.add(Node.column("column/customer.c_address", "customer", "VARCHAR(40)", "attribute", "地址"));
        c.add(Node.column("column/customer.c_nationkey", "customer", "INTEGER", "FK", "所属国家（→ nation）"));
        c.add(Node.column("column/customer.c_phone", "customer", "CHAR(15)", "attribute", "电话"));
        c.add(Node.column("column/customer.c_acctbal", "customer", "DECIMAL(15,2)", "measure", "账户余额"));
        c.add(Node.column("column/customer.c_mktsegment", "customer", "CHAR(10)", "attribute", "市场细分（BUILDING/AUTOMOBILE/MACHINERY/HOUSEHOLD/FURNITURE）"));
        c.add(Node.column("column/customer.c_comment", "customer", "VARCHAR(117)", "attribute", "备注"));
        // part
        c.add(Node.column("column/part.p_partkey", "part", "INTEGER", "PK", "商品主键"));
        c.add(Node.column("column/part.p_name", "part", "CHAR(55)", "attribute", "商品名"));
        c.add(Node.column("column/part.p_mfgr", "part", "CHAR(25)", "attribute", "制造商"));
        c.add(Node.column("column/part.p_brand", "part", "CHAR(10)", "attribute", "品牌"));
        c.add(Node.column("column/part.p_type", "part", "VARCHAR(25)", "attribute", "类型（PROMO 前缀=促销品）"));
        c.add(Node.column("column/part.p_size", "part", "INTEGER", "measure", "尺寸"));
        c.add(Node.column("column/part.p_container", "part", "CHAR(10)", "attribute", "容器"));
        c.add(Node.column("column/part.p_retailprice", "part", "DECIMAL(15,2)", "measure", "零售价"));
        c.add(Node.column("column/part.p_comment", "part", "VARCHAR(23)", "attribute", "备注"));
        // partsupp
        c.add(Node.column("column/partsupp.ps_partkey", "partsupp", "INTEGER", "FK", "商品（→ part）"));
        c.add(Node.column("column/partsupp.ps_suppkey", "partsupp", "INTEGER", "FK", "供应商（→ supplier）"));
        c.add(Node.column("column/partsupp.ps_availqty", "partsupp", "INTEGER", "measure", "可供货量"));
        c.add(Node.column("column/partsupp.ps_supplycost", "partsupp", "DECIMAL(15,2)", "measure", "供货成本（单价）"));
        c.add(Node.column("column/partsupp.ps_comment", "partsupp", "VARCHAR(199)", "attribute", "备注"));
        // orders
        c.add(Node.column("column/orders.o_orderkey", "orders", "INTEGER", "PK", "订单主键"));
        c.add(Node.column("column/orders.o_custkey", "orders", "INTEGER", "FK", "下单客户（→ customer）"));
        c.add(Node.column("column/orders.o_orderstatus", "orders", "CHAR(1)", "attribute", "订单状态（O/F/P）"));
        c.add(Node.column("column/orders.o_totalprice", "orders", "DECIMAL(15,2)", "measure", "订单总价"));
        c.add(Node.column("column/orders.o_orderdate", "orders", "DATE", "attribute", "下单日期"));
        c.add(Node.column("column/orders.o_orderpriority", "orders", "CHAR(15)", "attribute", "订单优先级（1-URGENT..5-LOW）"));
        c.add(Node.column("column/orders.o_clerk", "orders", "CHAR(15)", "attribute", "经办人"));
        c.add(Node.column("column/orders.o_shippriority", "orders", "INTEGER", "attribute", "配送优先级"));
        c.add(Node.column("column/orders.o_comment", "orders", "VARCHAR(79)", "attribute", "备注"));
        // lineitem
        c.add(Node.column("column/lineitem.l_orderkey", "lineitem", "INTEGER", "FK", "所属订单（→ orders）"));
        c.add(Node.column("column/lineitem.l_partkey", "lineitem", "INTEGER", "FK", "商品（→ part）"));
        c.add(Node.column("column/lineitem.l_suppkey", "lineitem", "INTEGER", "FK", "供应商（→ supplier）"));
        c.add(Node.column("column/lineitem.l_linenumber", "lineitem", "INTEGER", "attribute", "订单内行号"));
        c.add(Node.column("column/lineitem.l_quantity", "lineitem", "DECIMAL(15,2)", "measure", "数量"));
        c.add(Node.column("column/lineitem.l_extendedprice", "lineitem", "DECIMAL(15,2)", "measure", "金额 = 单价 × 数量"));
        c.add(Node.column("column/lineitem.l_discount", "lineitem", "DECIMAL(15,2)", "measure", "折扣率（0..1）"));
        c.add(Node.column("column/lineitem.l_tax", "lineitem", "DECIMAL(15,2)", "measure", "税率（0..1）"));
        c.add(Node.column("column/lineitem.l_returnflag", "lineitem", "CHAR(1)", "attribute", "退货状态（R 退货 / A 受理 / N 未退）"));
        c.add(Node.column("column/lineitem.l_linestatus", "lineitem", "CHAR(1)", "attribute", "行状态（O 未发货 / F 已发货）"));
        c.add(Node.column("column/lineitem.l_shipdate", "lineitem", "DATE", "attribute", "发货日期"));
        c.add(Node.column("column/lineitem.l_commitdate", "lineitem", "DATE", "attribute", "承诺交付日期"));
        c.add(Node.column("column/lineitem.l_receiptdate", "lineitem", "DATE", "attribute", "实际收货日期"));
        c.add(Node.column("column/lineitem.l_shipinstruct", "lineitem", "CHAR(25)", "attribute", "发货指令"));
        c.add(Node.column("column/lineitem.l_shipmode", "lineitem", "CHAR(10)", "attribute", "运输方式（AIR/MAIL/SHIP/TRUCK/RAIL/REG AIR）"));
        c.add(Node.column("column/lineitem.l_comment", "lineitem", "VARCHAR(44)", "attribute", "备注"));
        return c;
    }

    /** Whether a column is a foreign key (its {@code relation} edge target is the primary key). */
    public static String foreignKeyTarget(String columnName) {
        return switch (columnName) {
            case "column/nation.n_regionkey" -> "column/region.r_regionkey";
            case "column/supplier.s_nationkey", "column/customer.c_nationkey" -> "column/nation.n_nationkey";
            case "column/partsupp.ps_partkey", "column/lineitem.l_partkey" -> "column/part.p_partkey";
            case "column/partsupp.ps_suppkey", "column/lineitem.l_suppkey" -> "column/supplier.s_suppkey";
            case "column/orders.o_custkey" -> "column/customer.c_custkey";
            case "column/lineitem.l_orderkey" -> "column/orders.o_orderkey";
            default -> null;
        };
    }

    // --- the metrics ------------------------------------------------------------------------

    public static List<Node> metrics() {
        List<Node> m = new ArrayList<>();
        m.add(Node.metric("metric/revenue", "净收入：订单行的金额扣掉折扣后的收入", "derived", "SUM",
                "SUM(l_extendedprice * (1 - l_discount))", "元"));
        m.add(Node.metric("metric/gross_revenue", "毛收入：未扣折扣的订单行金额", "base", "SUM",
                "SUM(l_extendedprice)", "元"));
        m.add(Node.metric("metric/discount_amount", "折扣额：让利给客户的金额", "base", "SUM",
                "SUM(l_extendedprice * l_discount)", "元"));
        m.add(Node.metric("metric/tax_amount", "税额：按折后金额计征的税", "base", "SUM",
                "SUM(l_extendedprice * (1 - l_discount) * l_tax)", "元"));
        m.add(Node.metric("metric/order_count", "订单数：不重复的订单个数", "base", "COUNT_DISTINCT",
                "COUNT(DISTINCT o_orderkey)", "个"));
        m.add(Node.metric("metric/customer_count", "客户数：下单的不重复客户数", "base", "COUNT_DISTINCT",
                "COUNT(DISTINCT c_custkey)", "个"));
        m.add(Node.metric("metric/avg_order_value", "客单价：净收入 / 订单数", "ratio", "RATIO",
                "metric/revenue / metric/order_count", "元"));
        m.add(Node.metric("metric/quantity_sold", "销量：售出的商品数量合计", "base", "SUM",
                "SUM(l_quantity)", "件"));
        m.add(Node.metric("metric/promotion_revenue", "促销收入：促销品（PROMO 前缀）贡献的净收入", "base", "SUM",
                "SUM(CASE WHEN p_type LIKE 'PROMO%' THEN l_extendedprice * (1 - l_discount) ELSE 0 END)", "元"));
        m.add(Node.metric("metric/promotion_effect", "促销效果：促销收入占净收入的百分比", "ratio", "RATIO",
                "100 * metric/promotion_revenue / metric/revenue", "%"));
        m.add(Node.metric("metric/market_share", "市场份额：某维度切片的净收入占全局净收入的百分比", "ratio", "RATIO",
                "100 * metric/revenue / metric/total_revenue", "%"));
        m.add(Node.metric("metric/total_revenue", "全局净收入：作为市场份额分母的全量净收入", "base", "SUM",
                "SUM(l_extendedprice * (1 - l_discount))", "元"));
        m.add(Node.metric("metric/supply_cost", "供应成本：可供货量与供货成本的乘积", "base", "SUM",
                "SUM(ps_supplycost * ps_availqty)", "元"));
        m.add(Node.metric("metric/return_rate", "退货率：退货行数占总行数的百分比", "ratio", "RATIO",
                "100 * SUM(CASE WHEN l_returnflag = 'R' THEN 1 ELSE 0 END) / COUNT(*)", "%"));
        m.add(Node.metric("metric/shipping_delay", "运输延迟：实际收货日 - 承诺交付日的平均天数", "base", "AVG",
                "AVG(l_receiptdate - l_commitdate)", "天"));
        m.add(Node.metric("metric/late_delivery_count", "延迟交付数：实际收货日晚于承诺交付日的行数", "base", "COUNT",
                "COUNT(CASE WHEN l_receiptdate > l_commitdate THEN 1 END)", "行"));
        m.add(Node.metric("metric/profit", "利润：净收入 - 供应成本 - 折扣额（管理口径）", "derived", "RATIO",
                "metric/revenue - metric/supply_cost", "元"));
        return m;
    }

    // --- the dimensions ---------------------------------------------------------------------

    public static List<Node> dimensions() {
        return List.of(
                Node.dimension("dim/order_date", "下单日期：可按年 / 季 / 月下单"),
                Node.dimension("dim/nation", "国家：客户或供应商所在国家"),
                Node.dimension("dim/region", "地区：国家上卷到五大区"),
                Node.dimension("dim/mktsegment", "市场细分：客户所属行业（BUILDING/AUTOMOBILE/...）"),
                Node.dimension("dim/order_priority", "订单优先级：1-URGENT 到 5-LOW"),
                Node.dimension("dim/return_flag", "退货状态：R / A / N"),
                Node.dimension("dim/line_status", "行状态：已发货 / 未发货"),
                Node.dimension("dim/ship_mode", "运输方式：AIR / MAIL / SHIP / TRUCK / RAIL / REG AIR"),
                Node.dimension("dim/part_type", "商品类型：含 PROMO 前缀的促销分类"),
                Node.dimension("dim/brand", "品牌：商品品牌"),
                Node.dimension("dim/supplier", "供应商：按供货方切片"));
    }

    /** The column each dimension slices a metric by. */
    public static String dimensionColumn(String dimension) {
        return switch (dimension) {
            case "dim/order_date" -> "column/orders.o_orderdate";
            case "dim/nation" -> "column/nation.n_name";
            case "dim/region" -> "column/region.r_name";
            case "dim/mktsegment" -> "column/customer.c_mktsegment";
            case "dim/order_priority" -> "column/orders.o_orderpriority";
            case "dim/return_flag" -> "column/lineitem.l_returnflag";
            case "dim/line_status" -> "column/lineitem.l_linestatus";
            case "dim/ship_mode" -> "column/lineitem.l_shipmode";
            case "dim/part_type" -> "column/part.p_type";
            case "dim/brand" -> "column/part.p_brand";
            case "dim/supplier" -> "column/supplier.s_name";
            default -> null;
        };
    }

    // --- the business domains ---------------------------------------------------------------

    public static List<Node> domains() {
        return List.of(
                Node.domain("domain/sales", "销售域：收入、订单、客户与市场表现"),
                Node.domain("domain/customer", "客户域：客户结构与价值"),
                Node.domain("domain/supplier", "供应域：供货成本与本地供应"),
                Node.domain("domain/part", "商品域：商品结构与促销"),
                Node.domain("domain/shipping", "物流域：配送时效与延迟"),
                Node.domain("domain/pricing", "价格域：折扣、税与定价"));
    }

    // --- the standard queries (a representative subset of the TPC-H 22) ----------------------

    public static List<Node> queries() {
        return List.of(
                Node.query("query/q1", 1, "Pricing Summary Report：按退货状态/行状态汇总数量、金额、折扣、税",
                        "SELECT l_returnflag, l_linestatus, SUM(l_quantity) AS sum_qty, "
                                + "SUM(l_extendedprice) AS sum_base_price, "
                                + "SUM(l_extendedprice*(1-l_discount)) AS sum_disc_price, "
                                + "SUM(l_extendedprice*(1-l_discount)*(1+l_tax)) AS sum_charge "
                                + "FROM lineitem WHERE l_shipdate <= DATE '1998-12-01' - INTERVAL '90' DAY "
                                + "GROUP BY l_returnflag, l_linestatus ORDER BY l_returnflag, l_linestatus"),
                Node.query("query/q3", 3, "Shipping Priority：某市场细分下未发货订单的收入 TopN",
                        "SELECT l_orderkey, SUM(l_extendedprice*(1-l_discount)) AS revenue, o_orderdate, o_shippriority "
                                + "FROM customer, orders, lineitem "
                                + "WHERE c_mktsegment = 'BUILDING' AND c_custkey = o_custkey AND l_orderkey = o_orderkey "
                                + "AND o_orderdate < DATE '1995-03-15' AND l_shipdate > DATE '1995-03-15' "
                                + "GROUP BY l_orderkey, o_orderdate, o_shippriority ORDER BY revenue DESC, o_orderdate "
                                + "LIMIT 10"),
                Node.query("query/q5", 5, "Local Supplier Volume：某地区各国家的本地供应收入",
                        "SELECT n_name, SUM(l_extendedprice*(1-l_discount)) AS revenue "
                                + "FROM customer, orders, lineitem, supplier, nation, region "
                                + "WHERE c_custkey = o_custkey AND l_orderkey = o_orderkey AND l_suppkey = s_suppkey "
                                + "AND c_nationkey = s_nationkey AND s_nationkey = n_nationkey AND n_regionkey = r_regionkey "
                                + "AND r_name = 'ASIA' AND o_orderdate >= DATE '1994-01-01' "
                                + "GROUP BY n_name ORDER BY revenue DESC"),
                Node.query("query/q6", 6, "Forecasting Revenue Change：某折扣区间与数量的收入增量",
                        "SELECT SUM(l_extendedprice*l_discount) AS revenue FROM lineitem "
                                + "WHERE l_shipdate >= DATE '1994-01-01' AND l_shipdate < DATE '1995-01-01' "
                                + "AND l_discount BETWEEN 0.05 AND 0.07 AND l_quantity < 24"),
                Node.query("query/q10", 10, "Returned Item Reporting：退货订单的客户收入 TopN",
                        "SELECT c_custkey, c_name, SUM(l_extendedprice*(1-l_discount)) AS revenue, c_acctbal, n_name "
                                + "FROM customer, orders, lineitem, nation "
                                + "WHERE c_custkey = o_custkey AND l_orderkey = o_orderkey AND c_nationkey = n_nationkey "
                                + "AND o_orderdate >= DATE '1993-10-01' AND l_returnflag = 'R' "
                                + "GROUP BY c_custkey, c_name, c_acctbal, c_phone, n_name, c_address, c_comment "
                                + "ORDER BY revenue DESC LIMIT 20"),
                Node.query("query/q13", 13, "Customer Distribution：客户下单数量的分布",
                        "SELECT c_count, COUNT(*) AS custdist FROM customer LEFT OUTER JOIN orders "
                                + "ON c_custkey = o_custkey AND o_comment NOT LIKE '%special%requests%' "
                                + "GROUP BY c_count ORDER BY custdist DESC, c_count DESC"),
                Node.query("query/q14", 14, "Promotion Effect：促销品收入占当月收入的百分比",
                        "SELECT 100.00 * SUM(CASE WHEN p_type LIKE 'PROMO%' THEN l_extendedprice*(1-l_discount) ELSE 0 END) "
                                + "/ SUM(l_extendedprice*(1-l_discount)) AS promo_revenue FROM lineitem, part "
                                + "WHERE l_partkey = p_partkey AND l_shipdate >= DATE '1995-09-01' "
                                + "AND l_shipdate < DATE '1995-10-01'"),
                Node.query("query/q18", 18, "Large Volume Customer：大额订单客户",
                        "SELECT c_name, c_custkey, o_orderkey, o_orderdate, o_totalprice, SUM(l_quantity) AS sum_qty "
                                + "FROM customer, orders, lineitem WHERE o_orderkey IN "
                                + "(SELECT l_orderkey FROM lineitem GROUP BY l_orderkey HAVING SUM(l_quantity) > 300) "
                                + "AND c_custkey = o_custkey AND o_orderkey = l_orderkey "
                                + "GROUP BY c_name, c_custkey, o_orderkey, o_orderdate, o_totalprice "
                                + "ORDER BY o_totalprice DESC, o_orderdate LIMIT 100"),
                Node.query("query/q22", 22, "Global Sales Opportunity：各国潜在客户的余额分布",
                        "SELECT cntrycode, COUNT(*) AS numcust, SUM(c_acctbal) AS totacctbal FROM "
                                + "(SELECT SUBSTRING(c_phone FROM 1 FOR 2) AS cntrycode, c_acctbal FROM customer "
                                + "WHERE SUBSTRING(c_phone FROM 1 FOR 2) IN ('13','31','23') "
                                + "AND c_acctbal > (SELECT AVG(c_acctbal) FROM customer WHERE c_acctbal > 0)) AS custsale "
                                + "GROUP BY cntrycode ORDER BY cntrycode"),
                Node.query("query/q2", 2, "Minimum Cost Supplier：某地区某类型商品的最低供货成本",
                        "SELECT s_acctbal, s_name, n_name, p_partkey, p_mfgr, s_address, s_phone, s_comment "
                                + "FROM part, supplier, partsupp, nation, region "
                                + "WHERE p_partkey = ps_partkey AND s_suppkey = ps_suppkey AND p_size = 15 "
                                + "AND p_type LIKE '%BRASS' AND s_nationkey = n_nationkey AND n_regionkey = r_regionkey "
                                + "AND r_name = 'EUROPE' AND ps_supplycost = (SELECT MIN(ps_supplycost) FROM partsupp, supplier, nation, region "
                                + "WHERE p_partkey = ps_partkey AND s_suppkey = ps_suppkey AND s_nationkey = n_nationkey "
                                + "AND n_regionkey = r_regionkey AND r_name = 'EUROPE') "
                                + "ORDER BY s_acctbal DESC, n_name, s_name, p_partkey LIMIT 100"),
                Node.query("query/q4", 4, "Order Priority Checking：各优先级里延迟交付的订单数",
                        "SELECT o_orderpriority, COUNT(*) AS order_count FROM orders "
                                + "WHERE o_orderdate >= DATE '1993-07-01' AND o_orderdate < DATE '1993-10-01' "
                                + "AND EXISTS (SELECT * FROM lineitem WHERE l_orderkey = o_orderkey AND l_commitdate < l_receiptdate) "
                                + "GROUP BY o_orderpriority ORDER BY o_orderpriority"),
                Node.query("query/q7", 7, "Volume Shipping：两国之间的货运量",
                        "SELECT supp_nation, cust_nation, l_year, SUM(volume) AS revenue FROM "
                                + "(SELECT n1.n_name AS supp_nation, n2.n_name AS cust_nation, "
                                + "EXTRACT(YEAR FROM l_shipdate) AS l_year, l_extendedprice*(1-l_discount) AS volume "
                                + "FROM supplier, lineitem, orders, customer, nation n1, nation n2 "
                                + "WHERE s_suppkey = l_suppkey AND o_orderkey = l_orderkey AND c_custkey = o_custkey "
                                + "AND s_nationkey = n1.n_nationkey AND c_nationkey = n2.n_nationkey "
                                + "AND ((n1.n_name = 'FRANCE' AND n2.n_name = 'GERMANY'))) AS shipping "
                                + "GROUP BY supp_nation, cust_nation, l_year ORDER BY supp_nation, cust_nation, l_year"));
    }

    // --- the relations ----------------------------------------------------------------------

    /** Every edge of the graph: structure, lineage, dimensions, metric definitions and attribution. */
    public static List<Relation> relations() {
        List<Relation> r = new ArrayList<>();

        // structure: table -> its columns
        for (Node column : columns()) {
            r.add(new Relation("table/" + column.tableName(), "hasColumn", column.name()));
        }
        // structure: foreign key -> primary key
        for (Node column : columns()) {
            String target = foreignKeyTarget(column.name());
            if (target != null) {
                r.add(new Relation(column.name(), "references", target));
            }
        }
        // structure: dimension -> the column it slices by
        for (Node dimension : dimensions()) {
            String column = dimensionColumn(dimension.name());
            if (column != null) {
                r.add(new Relation(dimension.name(), "dimensionColumn", column));
            }
        }

        // lineage: which tables and columns a metric reads
        metricTables().forEach((metric, tables) -> tables.forEach(table ->
                r.add(new Relation(metric, "computedFrom", "table/" + table))));
        metricColumns().forEach((metric, columns) -> columns.forEach(column ->
                r.add(new Relation(metric, "usesColumn", column))));
        // lineage: which tables a query reads
        queryTables().forEach((query, tables) -> tables.forEach(table ->
                r.add(new Relation(query, "readsTable", "table/" + table))));

        // metrics: the query that defines a metric, the dimensions it can be sliced by
        metricQueries().forEach((metric, queries) -> queries.forEach(query ->
                r.add(new Relation(metric, "definedBy", query))));
        metricDimensions().forEach((metric, dimensions) -> dimensions.forEach(dimension ->
                r.add(new Relation(metric, "groupedBy", dimension))));
        // metrics: which domain a metric belongs to
        metricDomain().forEach((metric, domain) -> r.add(new Relation(metric, "belongsTo", domain)));
        // attribution: which base metric drives which derived one
        attribution().forEach((from, to) -> r.add(new Relation(from, "contributesTo", to)));

        return r;
    }

    static Map<String, List<String>> metricTables() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("metric/revenue", List.of("lineitem"));
        m.put("metric/gross_revenue", List.of("lineitem"));
        m.put("metric/discount_amount", List.of("lineitem"));
        m.put("metric/tax_amount", List.of("lineitem"));
        m.put("metric/order_count", List.of("orders"));
        m.put("metric/customer_count", List.of("customer", "orders"));
        m.put("metric/avg_order_value", List.of("lineitem", "orders"));
        m.put("metric/quantity_sold", List.of("lineitem"));
        m.put("metric/promotion_revenue", List.of("lineitem", "part"));
        m.put("metric/promotion_effect", List.of("lineitem", "part"));
        m.put("metric/market_share", List.of("lineitem"));
        m.put("metric/total_revenue", List.of("lineitem"));
        m.put("metric/supply_cost", List.of("partsupp"));
        m.put("metric/return_rate", List.of("lineitem"));
        m.put("metric/shipping_delay", List.of("lineitem"));
        m.put("metric/late_delivery_count", List.of("lineitem", "orders"));
        m.put("metric/profit", List.of("lineitem", "partsupp"));
        return m;
    }

    static Map<String, List<String>> metricColumns() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("metric/revenue", List.of("column/lineitem.l_extendedprice", "column/lineitem.l_discount"));
        m.put("metric/gross_revenue", List.of("column/lineitem.l_extendedprice"));
        m.put("metric/discount_amount", List.of("column/lineitem.l_extendedprice", "column/lineitem.l_discount"));
        m.put("metric/tax_amount", List.of("column/lineitem.l_extendedprice", "column/lineitem.l_discount", "column/lineitem.l_tax"));
        m.put("metric/order_count", List.of("column/orders.o_orderkey"));
        m.put("metric/customer_count", List.of("column/customer.c_custkey"));
        m.put("metric/avg_order_value", List.of("column/lineitem.l_extendedprice", "column/lineitem.l_discount", "column/orders.o_orderkey"));
        m.put("metric/quantity_sold", List.of("column/lineitem.l_quantity"));
        m.put("metric/promotion_revenue", List.of("column/part.p_type", "column/lineitem.l_extendedprice", "column/lineitem.l_discount"));
        m.put("metric/promotion_effect", List.of("column/part.p_type", "column/lineitem.l_extendedprice", "column/lineitem.l_discount"));
        m.put("metric/market_share", List.of("column/lineitem.l_extendedprice", "column/lineitem.l_discount"));
        m.put("metric/total_revenue", List.of("column/lineitem.l_extendedprice", "column/lineitem.l_discount"));
        m.put("metric/supply_cost", List.of("column/partsupp.ps_supplycost", "column/partsupp.ps_availqty"));
        m.put("metric/return_rate", List.of("column/lineitem.l_returnflag"));
        m.put("metric/shipping_delay", List.of("column/lineitem.l_receiptdate", "column/lineitem.l_commitdate"));
        m.put("metric/late_delivery_count", List.of("column/lineitem.l_receiptdate", "column/lineitem.l_commitdate"));
        m.put("metric/profit", List.of("column/lineitem.l_extendedprice", "column/lineitem.l_discount", "column/partsupp.ps_supplycost"));
        return m;
    }

    static Map<String, List<String>> queryTables() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("query/q1", List.of("lineitem"));
        m.put("query/q3", List.of("customer", "orders", "lineitem"));
        m.put("query/q5", List.of("customer", "orders", "lineitem", "supplier", "nation", "region"));
        m.put("query/q6", List.of("lineitem"));
        m.put("query/q10", List.of("customer", "orders", "lineitem", "nation"));
        m.put("query/q13", List.of("customer", "orders"));
        m.put("query/q14", List.of("lineitem", "part"));
        m.put("query/q18", List.of("customer", "orders", "lineitem"));
        m.put("query/q22", List.of("customer"));
        m.put("query/q2", List.of("part", "supplier", "partsupp", "nation", "region"));
        m.put("query/q4", List.of("orders", "lineitem"));
        m.put("query/q7", List.of("supplier", "lineitem", "orders", "customer", "nation"));
        return m;
    }

    static Map<String, List<String>> metricQueries() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("metric/discount_amount", List.of("query/q1", "query/q6"));
        m.put("metric/tax_amount", List.of("query/q1"));
        m.put("metric/revenue", List.of("query/q3", "query/q5", "query/q10"));
        m.put("metric/gross_revenue", List.of("query/q1"));
        m.put("metric/avg_order_value", List.of("query/q3", "query/q18"));
        m.put("metric/market_share", List.of("query/q5", "query/q7"));
        m.put("metric/return_rate", List.of("query/q10"));
        m.put("metric/customer_count", List.of("query/q13", "query/q22"));
        m.put("metric/promotion_effect", List.of("query/q14"));
        m.put("metric/promotion_revenue", List.of("query/q14"));
        m.put("metric/quantity_sold", List.of("query/q18"));
        m.put("metric/supply_cost", List.of("query/q2"));
        m.put("metric/late_delivery_count", List.of("query/q4"));
        m.put("metric/total_revenue", List.of("query/q7"));
        m.put("metric/shipping_delay", List.of("query/q4"));
        m.put("metric/order_count", List.of("query/q4"));
        m.put("metric/profit", List.of("query/q3"));
        return m;
    }

    static Map<String, List<String>> metricDimensions() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("metric/revenue", List.of("dim/order_date", "dim/nation", "dim/region", "dim/mktsegment"));
        m.put("metric/gross_revenue", List.of("dim/order_date", "dim/nation"));
        m.put("metric/discount_amount", List.of("dim/order_date", "dim/mktsegment"));
        m.put("metric/order_count", List.of("dim/order_date", "dim/order_priority", "dim/nation"));
        m.put("metric/customer_count", List.of("dim/nation", "dim/region", "dim/mktsegment"));
        m.put("metric/avg_order_value", List.of("dim/order_date", "dim/mktsegment"));
        m.put("metric/quantity_sold", List.of("dim/brand", "dim/part_type", "dim/order_date"));
        m.put("metric/promotion_revenue", List.of("dim/part_type", "dim/brand"));
        m.put("metric/promotion_effect", List.of("dim/order_date", "dim/part_type"));
        m.put("metric/market_share", List.of("dim/region", "dim/nation", "dim/part_type"));
        m.put("metric/supply_cost", List.of("dim/supplier", "dim/nation"));
        m.put("metric/return_rate", List.of("dim/return_flag", "dim/nation", "dim/mktsegment"));
        m.put("metric/shipping_delay", List.of("dim/ship_mode", "dim/order_priority"));
        m.put("metric/late_delivery_count", List.of("dim/order_priority", "dim/ship_mode"));
        m.put("metric/profit", List.of("dim/region", "dim/mktsegment", "dim/supplier"));
        return m;
    }

    static Map<String, String> metricDomain() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("metric/revenue", "domain/sales");
        m.put("metric/gross_revenue", "domain/pricing");
        m.put("metric/discount_amount", "domain/pricing");
        m.put("metric/tax_amount", "domain/pricing");
        m.put("metric/order_count", "domain/sales");
        m.put("metric/customer_count", "domain/customer");
        m.put("metric/avg_order_value", "domain/customer");
        m.put("metric/quantity_sold", "domain/part");
        m.put("metric/promotion_revenue", "domain/part");
        m.put("metric/promotion_effect", "domain/part");
        m.put("metric/market_share", "domain/sales");
        m.put("metric/total_revenue", "domain/sales");
        m.put("metric/supply_cost", "domain/supplier");
        m.put("metric/return_rate", "domain/customer");
        m.put("metric/shipping_delay", "domain/shipping");
        m.put("metric/late_delivery_count", "domain/shipping");
        m.put("metric/profit", "domain/sales");
        return m;
    }

    /**
     * The attribution edges: the base metric on the left drives the derived metric on the right.
     * An agent reads these to break a movement of {@code metric/profit} or {@code metric/revenue}
     * into its drivers instead of reporting a single number.
     */
    static Map<String, String> attribution() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("metric/gross_revenue", "metric/revenue");
        m.put("metric/discount_amount", "metric/revenue");
        m.put("metric/tax_amount", "metric/revenue");
        m.put("metric/promotion_revenue", "metric/revenue");
        m.put("metric/order_count", "metric/revenue");
        m.put("metric/avg_order_value", "metric/revenue");
        m.put("metric/revenue", "metric/market_share");
        m.put("metric/revenue", "metric/profit");
        m.put("metric/supply_cost", "metric/profit");
        m.put("metric/quantity_sold", "metric/promotion_revenue");
        m.put("metric/promotion_effect", "metric/market_share");
        m.put("metric/return_rate", "metric/profit");
        m.put("metric/late_delivery_count", "metric/return_rate");
        m.put("metric/shipping_delay", "metric/late_delivery_count");
        return m;
    }

    // --- the whole dataset ------------------------------------------------------------------

    public record Dataset(List<Node> nodes, List<Relation> relations) {

        public List<Node> nodesOfType(String entityType) {
            return nodes.stream().filter(n -> n.is(entityType)).toList();
        }
    }

    public static Dataset build() {
        List<Node> nodes = new ArrayList<>();
        nodes.addAll(tables());
        nodes.addAll(columns());
        nodes.addAll(metrics());
        nodes.addAll(dimensions());
        nodes.addAll(domains());
        nodes.addAll(queries());
        return new Dataset(List.copyOf(nodes), List.copyOf(relations()));
    }

    private TpchDataAgentDataset() {
    }
}
