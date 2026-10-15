package com.pengcheng.phoenixgraph.http.semantic;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The SQL the API renders has to be the SQL the CLI renders.
 *
 * <p>{@code tools/semantic-sql.mjs} produced the numbers the semantic-layer case study quotes and is what
 * {@code verification/verify-tpch-semantic-layer.mjs} checks, so "the API renders something equivalent" is not
 * good enough: two renderers that disagree make the same question have two answers. These tests pin the exact
 * strings on a hand-built model - the live equivalence over the served graph is
 * {@code verification/verify-semantic-api.mjs}, and this is the fast, offline half.
 */
class SqlRendererTest {

    /** revenue = SUM(extendedprice * (1 - discount)) over lineitem, joined to orders by the order key. */
    private static SemanticModel model() {
        Map<String, SemanticModel.Column> columns = new LinkedHashMap<>();
        columns.put("column/lineitem.l_extendedprice",
                new SemanticModel.Column("column/lineitem.l_extendedprice", "lineitem.l_extendedprice",
                        "lineitem", "DECIMAL", "measure"));
        columns.put("column/lineitem.l_discount",
                new SemanticModel.Column("column/lineitem.l_discount", "lineitem.l_discount",
                        "lineitem", "DECIMAL", "measure"));
        columns.put("column/lineitem.l_orderkey",
                new SemanticModel.Column("column/lineitem.l_orderkey", "lineitem.l_orderkey",
                        "lineitem", "INTEGER", "FK"));
        columns.put("column/orders.o_orderdate",
                new SemanticModel.Column("column/orders.o_orderdate", "orders.o_orderdate",
                        "orders", "DATE", "attribute"));

        Map<String, SemanticModel.ForeignKey> keys = new LinkedHashMap<>();
        keys.put("column/lineitem.l_orderkey", new SemanticModel.ForeignKey("lineitem.l_orderkey", "lineitem",
                "orders.o_orderkey", "orders"));

        Map<String, SemanticModel.Metric> metrics = new LinkedHashMap<>();
        metrics.put("metric/revenue", new SemanticModel.Metric("metric/revenue", "净收入",
                "SUM(l_extendedprice * (1 - l_discount))", "SUM", "CNY", "additive",
                List.of("营收", "收入"), new LinkedHashSet<>(List.of("dim/order_date")),
                List.of("column/lineitem.l_extendedprice", "column/lineitem.l_discount"),
                List.of("lineitem"), new LinkedHashSet<>(), List.of("query/q1")));

        Map<String, SemanticModel.Dimension> dimensions = new LinkedHashMap<>();
        dimensions.put("dim/order_date", new SemanticModel.Dimension("dim/order_date", "下单日期",
                "orders.o_orderdate", "orders", "DATE", List.of("日期", "时间"),
                Map.of("year", "orderYear", "month", "orderMonth")));
        dimensions.put("dim/region", new SemanticModel.Dimension("dim/region", "地区",
                "region.r_name", "region", "CHAR", List.of("大区"),
                Map.of("raw", "region")));

        return new SemanticModel("tpc-h-analytics", metrics, dimensions, columns, keys, List.of(), Map.of());
    }

    @Test
    void aMeasureWithoutADimensionIsOneSelect() {
        SqlRenderer.Plan plan = SqlRenderer.render(model(), "metric/revenue", null,
                SqlRenderer.Grain.RAW, SqlRenderer.Dialect.POSTGRES);

        assertEquals("SELECT SUM(lineitem.l_extendedprice * (1 - lineitem.l_discount)) AS revenue\n"
                + "FROM lineitem;", plan.statement());
        assertEquals("lineitem", plan.baseTable());
        assertTrue(plan.joins().isEmpty(), "a grand total joins nothing");
        assertTrue(plan.document().startsWith("-- metric: metric/revenue (净收入)"));
    }

    @Test
    void aDimensionOnAnotherTableBringsItsJoinAndItsGrain() {
        SqlRenderer.Plan plan = SqlRenderer.render(model(), "metric/revenue", "dim/order_date",
                SqlRenderer.Grain.YEAR, SqlRenderer.Dialect.POSTGRES);

        assertEquals("SELECT EXTRACT(YEAR FROM orders.o_orderdate) AS order_date_year,\n"
                + "       SUM(lineitem.l_extendedprice * (1 - lineitem.l_discount)) AS revenue\n"
                + "FROM lineitem\n"
                + "JOIN orders ON lineitem.l_orderkey = orders.o_orderkey\n"
                + "GROUP BY EXTRACT(YEAR FROM orders.o_orderdate)\n"
                + "ORDER BY order_date_year;", plan.statement());
        assertEquals(1, plan.joins().size());
        assertEquals("orders", plan.joins().get(0).referencesTable());
        assertTrue(plan.document().contains("-- join from references: lineitem.l_orderkey -> orders.o_orderkey"));
    }

    @Test
    void mysqlGetsItsOwnGrainFunctions() {
        assertEquals("SELECT DATE_FORMAT(orders.o_orderdate, '%Y-%m') AS order_date_month,\n"
                        + "       SUM(lineitem.l_extendedprice * (1 - lineitem.l_discount)) AS revenue\n"
                        + "FROM lineitem\n"
                        + "JOIN orders ON lineitem.l_orderkey = orders.o_orderkey\n"
                        + "GROUP BY DATE_FORMAT(orders.o_orderdate, '%Y-%m')\n"
                        + "ORDER BY order_date_month;",
                SqlRenderer.render(model(), "revenue", "order_date", SqlRenderer.Grain.MONTH,
                        SqlRenderer.Dialect.MYSQL).statement());
        // the raw grain is the column itself, and duckdb differs from postgres only in the month function
        assertTrue(SqlRenderer.render(model(), "revenue", "order_date", SqlRenderer.Grain.QUARTER,
                SqlRenderer.Dialect.DUCKDB).statement().contains("EXTRACT(QUARTER FROM orders.o_orderdate)"));
    }

    @Test
    void aMissingJoinPathBecomesAWarningInTheDocumentNotASilentOmission() {
        SqlRenderer.Plan plan = SqlRenderer.render(model(), "revenue", "dim/region",
                SqlRenderer.Grain.RAW, SqlRenderer.Dialect.POSTGRES);

        assertTrue(plan.joins().isEmpty(), "the model holds no path from lineitem to region");
        assertEquals(List.of("no join path from lineitem to region: the query would need one"), plan.warnings());
        assertTrue(plan.document().contains("-- WARNING: no join path from lineitem to region"));
        assertEquals("SELECT region.r_name AS region,\n"
                + "       SUM(lineitem.l_extendedprice * (1 - lineitem.l_discount)) AS revenue\n"
                + "FROM lineitem\n"
                + "GROUP BY region.r_name\n"
                + "ORDER BY region;", plan.statement());
    }

    @Test
    void anUnknownMetricOrDimensionIsRefusedWithTheVocabulary() {
        IllegalArgumentException noMetric = assertThrows(IllegalArgumentException.class,
                () -> SqlRenderer.render(model(), "metric/nope", null, SqlRenderer.Grain.RAW,
                        SqlRenderer.Dialect.POSTGRES));
        assertTrue(noMetric.getMessage().contains("It holds") || noMetric.getMessage().contains("it holds"),
                noMetric.getMessage());
        assertTrue(noMetric.getMessage().contains("metric/revenue"), noMetric.getMessage());

        IllegalArgumentException noDimension = assertThrows(IllegalArgumentException.class,
                () -> SqlRenderer.render(model(), "revenue", "dim/nope", SqlRenderer.Grain.RAW,
                        SqlRenderer.Dialect.POSTGRES));
        assertTrue(noDimension.getMessage().contains("dim/order_date"), noDimension.getMessage());

        assertThrows(IllegalArgumentException.class,
                () -> SqlRenderer.render(model(), "revenue", null, SqlRenderer.Grain.RAW, SqlRenderer.Dialect.of("oracle")));
        assertThrows(IllegalArgumentException.class,
                () -> SqlRenderer.render(model(), "revenue", null, SqlRenderer.Grain.of("week"), SqlRenderer.Dialect.POSTGRES));
    }

    @Test
    void theGrainBindingIsDataAndNotAguess() {
        // the binding decides which fact property a dimension reads at which grain
        assertEquals("orderYear", model().dimension("dim/order_date").graphProperty("year"));
        assertEquals("orderMonth", model().dimension("dim/order_date").graphProperty("month"));
        assertEquals("region", model().dimension("dim/region").graphProperty("raw"));
        // a dimension nobody bound returns null rather than a plausible property name
        SemanticModel.Dimension unbound = new SemanticModel.Dimension("dim/x", null, "x.y", "x", "CHAR",
                List.of(), Map.of());
        assertEquals(null, unbound.graphProperty("raw"));
    }

    @Test
    void theDescribeShapeCarriesWhatAClientNeedsToPlan() {
        Map<String, Object> described = SqlRenderer.describe(SqlRenderer.render(model(), "revenue",
                "order_date", SqlRenderer.Grain.YEAR, SqlRenderer.Dialect.POSTGRES));

        assertEquals("tpc-h-analytics", described.get("graph"));
        assertEquals("lineitem", described.get("baseTable"));
        assertEquals("year", described.get("grain"));
        assertFalse(described.get("warnings").toString().contains("no join path"), described.toString());
        assertEquals(described.get("statement"), described.get("sql"));
        assertTrue(described.toString().contains("order_date_year"));
    }
}
