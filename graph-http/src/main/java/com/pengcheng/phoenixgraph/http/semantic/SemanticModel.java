package com.pengcheng.phoenixgraph.http.semantic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The semantic layer of one graph, loaded out of the graph itself.
 *
 * <p>This project keeps its semantic model <em>as data</em>: {@code tpc-h-analytics} holds
 * {@code metric/…}, {@code dim/…}, {@code column/…}, {@code table/…} and {@code query/…} vertices joined by
 * {@code Relation} edges ({@code groupedBy}, {@code usesColumn}, {@code computedFrom}, {@code dimensionColumn},
 * {@code references}, {@code definedBy}, {@code contributesTo}). That is why this class is a plain value object
 * with lookups rather than a parser: whoever writes the graph writes the model, and
 * {@code tools/semantic-sql.mjs} has been reading the same shapes since the first version. The Java port exists
 * so the model, the SQL and the answers can be served over the API, and so an agent can call them.
 *
 * <p>Every id in here is the graph's id ({@code metric/revenue}, {@code dim/region},
 * {@code column/region.r_name}), and the SQL identifiers are the column's {@code name} (which carries its table,
 * e.g. {@code region.r_name}) exactly as the CLI renders them.
 */
public final class SemanticModel {

    /** A metric: what is measured, how, in what unit, which columns and tables it reads, and its synonyms. */
    public record Metric(String id, String description, String expression, String aggFunction, String unit,
                         String metricKind, List<String> synonyms, Set<String> dimensions,
                         List<String> columns, List<String> tables, Set<String> derivedFrom,
                         List<String> definedBy, String graphProperty) {

        /** The name the SQL aliases this metric to ({@code metric/revenue} -&gt; {@code revenue}). */
        public String shortName() {
            return SemanticModel.shortId(id);
        }
    }

    /** A dimension, with the column it slices by and the graph property the facts carry it in. */
    public record Dimension(String id, String description, String column, String table, String dataType,
                            List<String> synonyms, Map<String, String> graphPropertyByGrain) {

        public String shortName() {
            return SemanticModel.shortId(id);
        }

        /** Whether this dimension is a date, by the warehouse type of the column it slices by. */
        public boolean isDate() {
            return dataType != null && dataType.toUpperCase(java.util.Locale.ROOT).contains("DATE");
        }

        /**
         * The fact-graph property this dimension reads at the given grain, or null when the model does not bind
         * one.
         *
         * <p>The binding is data ({@code graphProperty} on the dimension's node, either {@code region} or a
         * {@code grain=property} list such as {@code year=orderYear,month=orderMonth}). It exists because the
         * semantic column name and the fact property name are different vocabularies
         * ({@code region.r_name} vs {@code Sale.region}), and because guessing the mapping with string matching
         * resolves most pairs and silently returns plausible wrong numbers for the rest -
         * {@code l_returnflag} would have to become {@code returned}, which no rule can know.
         */
        public String graphProperty(String grain) {
            if (graphPropertyByGrain.isEmpty()) {
                return null;
            }
            String forGrain = graphPropertyByGrain.get(grain);
            if (forGrain != null) {
                return forGrain;
            }
            // a single binding without a grain applies to every grain; a grain-keyed map falls back to raw
            return graphPropertyByGrain.size() == 1 && !graphPropertyByGrain.containsKey("raw")
                    ? graphPropertyByGrain.values().iterator().next()
                    : graphPropertyByGrain.get("raw");
        }
    }

    /** A column of the warehouse model: its SQL name (table-qualified), its table and its role. */
    public record Column(String id, String name, String table, String dataType, String columnRole) {
    }

    /** A foreign key to primary key step, used to build join paths. */
    public record ForeignKey(String column, String table, String referencesColumn, String referencesTable) {
    }

    /** One of the 22 reference queries the model carries (its original SQL and the tables it reads). */
    public record ReferenceQuery(String id, long number, String description, String sqlText, Set<String> tables) {
    }

    private final String graph;
    private final Map<String, Metric> metrics;
    private final Map<String, Dimension> dimensions;
    private final Map<String, Column> columns;
    private final Map<String, ForeignKey> foreignKeysByColumn;
    private final List<ReferenceQuery> queries;
    private final Map<String, List<String>> contributorEdges;

    public SemanticModel(String graph, Map<String, Metric> metrics, Map<String, Dimension> dimensions,
                         Map<String, Column> columns, Map<String, ForeignKey> foreignKeysByColumn,
                         List<ReferenceQuery> queries, Map<String, List<String>> contributorEdges) {
        this.graph = Objects.requireNonNull(graph, "graph");
        this.metrics = Map.copyOf(metrics);
        this.dimensions = Map.copyOf(dimensions);
        this.columns = Map.copyOf(columns);
        this.foreignKeysByColumn = Map.copyOf(foreignKeysByColumn);
        this.queries = List.copyOf(queries);
        this.contributorEdges = Map.copyOf(contributorEdges);
    }

    public String graph() {
        return graph;
    }

    public Map<String, Metric> metrics() {
        return metrics;
    }

    public Map<String, Dimension> dimensions() {
        return dimensions;
    }

    public List<ReferenceQuery> queries() {
        return queries;
    }

    public Metric metric(String id) {
        return metrics.get(id);
    }

    public Dimension dimension(String id) {
        return dimensions.get(id);
    }

    /** The columns the given ids name, for lineage output. */
    public List<Column> columnsOf(Iterable<String> ids) {
        List<Column> found = new ArrayList<>();
        for (String id : ids) {
            Column column = columns.get(id);
            if (column != null) {
                found.add(column);
            }
        }
        return found;
    }

    /** The metrics that a derived metric is computed from ({@code contributesTo} edges point at it). */
    public List<String> derivedFrom(String metricId) {
        return contributorEdges.getOrDefault(metricId, List.of());
    }

    /**
     * The shortest chain of joins from one table to another, breadth-first over {@code references}.
     *
     * <p>Breadth-first rather than "any path": a star schema has one obvious chain but a snowflake does not, and
     * the shortest one is the one a reader would write. Returns null when the graph holds no path, which the
     * caller must report rather than drop - a query missing a join is a wrong number, not a smaller answer.
     */
    public List<ForeignKey> joinPath(String fromTable, String toTable) {
        if (Objects.equals(fromTable, toTable)) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        seen.add(fromTable);
        List<Object[]> queue = new ArrayList<>();
        queue.add(new Object[] {fromTable, List.<ForeignKey>of()});
        for (int head = 0; head < queue.size(); head += 1) {
            Object[] entry = queue.get(head);
            String table = (String) entry[0];
            @SuppressWarnings("unchecked")
            List<ForeignKey> path = (List<ForeignKey>) entry[1];
            for (ForeignKey key : foreignKeysOf(table)) {
                if (seen.contains(key.referencesTable())) {
                    continue;
                }
                List<ForeignKey> next = new ArrayList<>(path);
                next.add(key);
                if (key.referencesTable().equals(toTable)) {
                    return List.copyOf(next);
                }
                seen.add(key.referencesTable());
                queue.add(new Object[] {key.referencesTable(), next});
            }
        }
        return null;
    }

    /** The foreign keys that leave a table, in the order the model declares them. */
    public List<ForeignKey> foreignKeysOf(String table) {
        List<ForeignKey> keys = new ArrayList<>();
        for (ForeignKey key : foreignKeysByColumn.values()) {
            if (key.table().equals(table)) {
                keys.add(key);
            }
        }
        return keys;
    }

    /** {@code metric/revenue} -&gt; {@code revenue}: the part after the type prefix. */
    public static String shortId(String id) {
        if (id == null) {
            return null;
        }
        int slash = id.indexOf('/');
        return slash < 0 ? id : id.substring(slash + 1);
    }

    /** {@code column/region.r_name} -&gt; {@code region}: the table a column id belongs to. */
    public static String tableOfColumnId(String columnId) {
        String name = columnId == null ? "" : columnId.replaceFirst("^column/", "");
        int dot = name.indexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    /** A metric id by its short name, for callers that pass {@code revenue} instead of {@code metric/revenue}. */
    public static String qualify(String kind, String idOrName) {
        if (idOrName == null) {
            return null;
        }
        return idOrName.contains("/") ? idOrName : kind + "/" + idOrName;
    }

    /** The map the API renders for the model endpoint: enough for a client to plan without a second call. */
    public Map<String, Object> describe() {
        List<Map<String, Object>> metricRows = new ArrayList<>();
        metrics.values().forEach(metric -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", metric.id());
            row.put("description", metric.description());
            row.put("expression", metric.expression());
            row.put("unit", metric.unit());
            row.put("aggFunction", metric.aggFunction());
            row.put("synonyms", metric.synonyms());
            row.put("dimensions", metric.dimensions());
            row.put("columns", metric.columns());
            row.put("tables", metric.tables());
            row.put("derivedFrom", List.copyOf(metric.derivedFrom()));
            row.put("definedBy", metric.definedBy());
            row.put("graphProperty", metric.graphProperty());
            metricRows.add(row);
        });
        List<Map<String, Object>> dimensionRows = new ArrayList<>();
        dimensions.values().forEach(dimension -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", dimension.id());
            row.put("description", dimension.description());
            row.put("column", dimension.column());
            row.put("table", dimension.table());
            row.put("dataType", dimension.dataType());
            row.put("synonyms", dimension.synonyms());
            row.put("graphProperty", dimension.graphPropertyByGrain());
            dimensionRows.add(row);
        });
        List<Map<String, Object>> queryRows = new ArrayList<>();
        queries.forEach(query -> queryRows.add(Map.of("id", query.id(), "number", query.number(),
                "description", query.description() == null ? "" : query.description(),
                "tables", query.tables())));
        Map<String, Object> described = new LinkedHashMap<>();
        described.put("graph", graph);
        described.put("metrics", metricRows);
        described.put("dimensions", dimensionRows);
        described.put("referenceQueries", queryRows);
        described.put("counts", Map.of("metrics", metrics.size(), "dimensions", dimensions.size(),
                "columns", columns.size(), "referenceQueries", queries.size()));
        return described;
    }

    private List<String> derivedBy(String metricId) {
        return metrics.containsKey(metricId) ? metrics.get(metricId).definedBy() : List.of();
    }
}
