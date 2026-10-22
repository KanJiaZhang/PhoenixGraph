package com.pengcheng.phoenixgraph.http.semantic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders a semantic query as warehouse SQL, from the model alone.
 *
 * <p>This is a port of {@code tools/semantic-sql.mjs}, and the point of the port is that the two agree
 * <em>byte for byte</em>: the CLI is what the semantic-layer case study quoted and what
 * {@code verification/verify-tpch-semantic-layer.mjs} checks, so the API's SQL is compared against the CLI's
 * output over a matrix of metrics, dimensions, grains and dialects
 * ({@code verification/verify-semantic-api.mjs}). "Similar SQL" would be a second source of truth for the same
 * number.
 *
 * <p>Four rules come straight from the model and are not choices made here:
 *
 * <ul>
 *   <li><b>The expression is the metric.</b> A metric without one is refused: the model has to say how it is
 *       computed, or nobody can.</li>
 *   <li><b>The base table is where the measure's columns live</b> (most columns wins, ties by declaration order),
 *       not "the first table mentioned" - a metric reads its measures from one table and joins out from there.</li>
 *   <li><b>Joins come from {@code references} edges</b>, shortest path first, and a missing path is a
 *       <em>warning</em> in the document rather than a silent omission: a query missing a join is a wrong
 *       number, not a smaller answer.</li>
 *   <li><b>Grain is a function choice per dialect</b> (MySQL has {@code YEAR}/{@code QUARTER}/{@code DATE_FORMAT},
 *       everything else {@code EXTRACT}/{@code DATE_TRUNC}), because the same semantic query has to run on both
 *       without the caller rewriting it.</li>
 * </ul>
 */
public final class SqlRenderer {

    /** The dialects a semantic query can be rendered for. */
    public enum Dialect {
        POSTGRES, DUCKDB, MYSQL;

        static Dialect of(String name) {
            if (name == null || name.isBlank()) {
                return POSTGRES;
            }
            try {
                return valueOf(name.trim().toUpperCase());
            } catch (IllegalArgumentException unknown) {
                throw new IllegalArgumentException("Unknown dialect '" + name
                        + "'; use postgres, duckdb or mysql");
            }
        }
    }

    /** The grain a dimension is aggregated to. */
    public enum Grain {
        RAW, YEAR, QUARTER, MONTH;

        static Grain of(String name) {
            if (name == null || name.isBlank()) {
                return RAW;
            }
            try {
                return valueOf(name.trim().toUpperCase());
            } catch (IllegalArgumentException unknown) {
                throw new IllegalArgumentException("Unknown grain '" + name
                        + "'; use raw, year, quarter or month");
            }
        }

        String configName() {
            return name().toLowerCase();
        }
    }

    /**
     * What was rendered: the SQL, the document a human reads, and the lineage behind both.
     *
     * @param statement the SQL, ending in a semicolon
     * @param document  the header comments plus the statement (what the CLI prints)
     */
    public record Plan(String graph, String metricId, String metricDescription, String expression,
                       String aggFunction, String unit, List<String> tables, List<SemanticModel.Column> columns,
                       String baseTable, String dimensionId, String dimensionColumn, String dimensionTable,
                       String dimensionDataType, String grain, List<SemanticModel.ForeignKey> joins,
                       List<String> warnings, String statement, String document) {
    }

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private SqlRenderer() {
    }

    /** Renders {@code metric} optionally grouped by {@code dimensionId}, at {@code grain}, for {@code dialect}. */
    public static Plan render(SemanticModel model, String metricIdOrName, String dimensionIdOrName, Grain grain,
                              Dialect dialect) {
        Objects.requireNonNull(model, "model");
        // short names are accepted (`revenue`, `region`) and qualified here, so a caller that read the model's
        // keys and a caller that guessed both end up asking the same question
        String metricId = SemanticModel.qualify("metric", metricIdOrName);
        String dimensionId = dimensionIdOrName == null || dimensionIdOrName.isBlank()
                ? null : SemanticModel.qualify("dim", dimensionIdOrName);
        SemanticModel.Metric metric = model.metric(metricId);
        if (metric == null) {
            throw new IllegalArgumentException("The semantic layer has no metric '" + metricId + "'; it holds "
                    + String.join(", ", model.metrics().keySet()));
        }
        if (metric.expression() == null || metric.expression().isBlank()) {
            throw new IllegalArgumentException(metricId + " has no 'expression': the semantic layer must define it");
        }
        String expression = metric.expression();
        List<SemanticModel.Column> columns = model.columnsOf(metric.columns());

        // the base table: where the measure's columns live (lineitem for revenue: two columns against none)
        Map<String, Integer> byTable = new LinkedHashMap<>();
        for (SemanticModel.Column column : columns) {
            byTable.merge(column.table(), 1, Integer::sum);
        }
        String baseTable = byTable.entrySet().stream()
                .max(Comparator.comparingInt(Map.Entry::getValue))
                .map(Map.Entry::getKey)
                .orElseGet(() -> metric.tables().isEmpty() ? null : metric.tables().get(0));
        if (baseTable == null) {
            throw new IllegalArgumentException(metricId + " declares no columns and no tables; "
                    + "the semantic layer has nothing to select from");
        }

        SemanticModel.Dimension dimension = null;
        if (dimensionId != null) {
            dimension = model.dimension(dimensionId);
            if (dimension == null) {
                throw new IllegalArgumentException("The semantic layer has no dimension '" + dimensionId
                        + "'; it holds " + String.join(", ", model.dimensions().keySet()));
            }
            if (dimension.column() == null || dimension.column().isBlank()) {
                throw new IllegalArgumentException(dimensionId
                        + " has no dimensionColumn: the semantic layer must say which column it is");
            }
        }

        // the joins: shortest path from the base table to every other table the query touches
        Set<String> wanted = new LinkedHashSet<>();
        if (dimension != null) {
            wanted.add(dimension.table());
        }
        for (SemanticModel.Column column : columns) {
            wanted.add(column.table());
        }
        wanted.remove(baseTable);
        List<SemanticModel.ForeignKey> joins = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (dimension != null && grain != Grain.RAW && !dimension.isDate()) {
            // the renderer stays byte-identical to the CLI (portability first), but a grain on a non-date
            // column is semantically wrong, so the document says so instead of silently shipping the SQL
            warnings.add("grain '" + grain.configName() + "' was asked for on " + dimension.id()
                    + " whose dataType is " + (dimension.dataType() == null || dimension.dataType().isBlank()
                    ? "not a date" : dimension.dataType()) + ": the rendered SQL applies "
                    + grainSql(dimension.column(), grain, dialect) + " to a non-date column");
        }
        for (String target : wanted) {
            List<SemanticModel.ForeignKey> path = model.joinPath(baseTable, target);
            if (path == null) {
                warnings.add("no join path from " + baseTable + " to " + target + ": the query would need one");
                continue;
            }
            for (SemanticModel.ForeignKey step : path) {
                if (joins.stream().anyMatch(join -> join.referencesTable().equals(step.referencesTable()))) {
                    continue;
                }
                joins.add(step);
            }
        }

        String qualified = qualify(expression, columns);
        String alias = metric.shortName();
        List<String> select = new ArrayList<>();
        String groupByExpression = null;
        String groupByAlias = null;
        if (dimension != null) {
            String column = dimension.column();
            String selected = grain == Grain.RAW ? column : grainSql(column, grain, dialect);
            String grainAlias = grain == Grain.RAW ? dimension.shortName()
                    : dimension.shortName() + "_" + grain.configName();
            select.add(selected + " AS " + grainAlias);
            groupByExpression = selected;
            groupByAlias = grainAlias;
        }
        select.add(qualified + " AS " + alias);

        List<String> lines = new ArrayList<>();
        lines.add("-- metric: " + metric.id()
                + (metric.description() == null || metric.description().isBlank() ? "" : " (" + metric.description() + ")"));
        lines.add("-- expression from the graph: " + expression
                + (metric.unit() == null || metric.unit().isBlank() ? "" : ", unit: " + metric.unit()));
        for (SemanticModel.ForeignKey join : joins) {
            lines.add("-- join from references: " + join.column() + " -> " + join.referencesColumn());
        }
        for (String warning : warnings) {
            lines.add("-- WARNING: " + warning);
        }

        List<String> sql = new ArrayList<>();
        sql.add("SELECT " + String.join(",\n       ", select));
        sql.add("FROM " + baseTable);
        for (SemanticModel.ForeignKey join : joins) {
            sql.add("JOIN " + join.referencesTable() + " ON " + join.column() + " = " + join.referencesColumn());
        }
        if (groupByExpression != null) {
            sql.add("GROUP BY " + groupByExpression);
            sql.add("ORDER BY " + groupByAlias);
        }
        String statement = String.join("\n", sql) + ";";
        String document = String.join("\n", lines) + "\n" + statement;

        return new Plan(model.graph(), metric.id(),
                metric.description() == null ? "" : metric.description(), expression,
                metric.aggFunction() == null ? "" : metric.aggFunction(),
                metric.unit() == null ? "" : metric.unit(),
                List.copyOf(metric.tables()), columns, baseTable,
                dimension == null ? null : dimension.id(),
                dimension == null ? null : dimension.column(),
                dimension == null ? null : dimension.table(),
                dimension == null ? "" : dimension.dataType(),
                dimension == null ? null : grain.configName(),
                List.copyOf(joins), List.copyOf(warnings), statement, document);
    }

    /** Replaces every identifier the expression mentions with the model's table-qualified column name. */
    static String qualify(String expression, List<SemanticModel.Column> columns) {
        Matcher matcher = IDENTIFIER.matcher(expression);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String token = matcher.group();
            String replacement = token;
            for (SemanticModel.Column column : columns) {
                String bare = column.name().substring(column.name().lastIndexOf('.') + 1);
                if (bare.equals(token)) {
                    replacement = column.name();
                    break;
                }
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** The grain expression for a dialect. */
    static String grainSql(String column, Grain grain, Dialect dialect) {
        return switch (grain) {
            case RAW -> column;
            case YEAR -> dialect == Dialect.MYSQL ? "YEAR(" + column + ")" : "EXTRACT(YEAR FROM " + column + ")";
            case QUARTER -> dialect == Dialect.MYSQL ? "QUARTER(" + column + ")"
                    : "EXTRACT(QUARTER FROM " + column + ")";
            case MONTH -> dialect == Dialect.MYSQL ? "DATE_FORMAT(" + column + ", '%Y-%m')"
                    : "DATE_TRUNC('month', " + column + ")";
        };
    }

    /** The shape the API answers with: the CLI's {@code --json} fields plus the document and the statement. */
    public static Map<String, Object> describe(Plan plan) {
        Map<String, Object> metric = new LinkedHashMap<>();
        metric.put("id", plan.metricId());
        metric.put("description", plan.metricDescription());
        metric.put("expression", plan.expression());
        metric.put("aggFunction", plan.aggFunction());
        metric.put("unit", plan.unit());

        Map<String, Object> dimension = null;
        if (plan.dimensionId() != null) {
            dimension = new LinkedHashMap<>();
            dimension.put("id", plan.dimensionId());
            dimension.put("column", plan.dimensionColumn());
            dimension.put("table", plan.dimensionTable());
            dimension.put("dataType", plan.dimensionDataType());
        }
        List<Map<String, Object>> columns = new ArrayList<>();
        plan.columns().forEach(column -> columns.add(Map.of("name", column.name(), "table", column.table(),
                "dataType", column.dataType() == null ? "" : column.dataType())));
        List<Map<String, Object>> joins = new ArrayList<>();
        plan.joins().forEach(join -> joins.add(Map.of("fk", join.column(), "pk", join.referencesColumn(),
                "to", join.referencesTable())));

        Map<String, Object> described = new LinkedHashMap<>();
        described.put("graph", plan.graph());
        described.put("metric", metric);
        described.put("tables", plan.tables());
        described.put("columns", columns);
        described.put("baseTable", plan.baseTable());
        described.put("dimension", dimension);
        described.put("grain", plan.grain());
        described.put("joins", joins);
        described.put("warnings", plan.warnings());
        described.put("statement", plan.statement());
        described.put("sql", plan.statement());
        described.put("document", plan.document());
        return described;
    }
}
