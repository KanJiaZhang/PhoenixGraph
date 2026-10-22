package com.pengcheng.phoenixgraph.http.semantic;

import com.pengcheng.phoenixgraph.http.service.QueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads a graph's semantic layer out of the graph, and renders SQL from it.
 *
 * <p>The model is data in the graph (see {@link SemanticModel}), so loading it means running the same
 * traversals {@code tools/semantic-sql.mjs} runs - deliberately the same, so the CLI and the API cannot read
 * two different models. The queries are per-vertex rather than one clever scan: a metric's lineage is
 * {@code outE().filter(relation.equals('usesColumn'))} exactly as the CLI asks it, and the cost is a few dozen
 * small reads against a graph of about ninety vertices. What is cached is the result, per graph, and
 * {@link #forget(String)} drops it when the graph is re-authored.
 */
@Service
public class SemanticService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SemanticService.class);

    private final QueryService queries;
    private final Map<String, SemanticModel> models = new ConcurrentHashMap<>();
    private final Map<String, Binding> bindings = new ConcurrentHashMap<>();

    public SemanticService(QueryService queries) {
        this.queries = queries;
    }

    /**
     * The fact-graph binding of a semantic model: which fact graph and vertex type carry its facts, and - as a
     * fallback for a graph that has not been re-seeded - which fact property carries each dimension and metric.
     *
     * <p>It is data on purpose. Semantic column names and fact property names are different vocabularies
     * ({@code region.r_name} vs {@code Sale.region}), and a string-matching rule resolves nine of the eleven
     * TPC-H dimensions while silently returning plausible wrong numbers for the rest. A null value means
     * "the model has this dimension, this deployment cannot group by it", which the API reports as a
     * refusal carrying the SQL rather than guessing. The per-node property binding and the synonyms live on the
     * model's own nodes ({@code graphProperty} and {@code alias}); the file keeps only {@code dataGraph} and
     * {@code vertexType}, which the model cannot know itself. {@link SemanticRunner} reads the model first and
     * falls back to these two maps for a graph written before the migration.
     *
     * @param dimensions dimension id -&gt; fact property, or a {@code grain=property} list for a date dimension
     * @param metrics    metric id -&gt; fact property, or {@code count} for "how many vertices"
     */
    public record Binding(String graph, String dataGraph, String vertexType, Map<String, String> dimensions,
                          Map<String, String> metrics) {

        public String metricProperty(String metricId) {
            return metricId == null ? null : metrics.get(metricId);
        }

        /** The fact property for a dimension at a grain, or null when the binding cannot express it. */
        public String dimensionProperty(String dimensionId, String grain) {
            String declared = dimensions.get(dimensionId);
            if (declared == null) {
                return null;
            }
            if (!declared.contains("=")) {
                return declared;
            }
            Map<String, String> byGrain = new LinkedHashMap<>();
            for (String part : declared.split(",")) {
                int equals = part.indexOf('=');
                if (equals > 0) {
                    byGrain.put(part.substring(0, equals).trim().toLowerCase(), part.substring(equals + 1).trim());
                }
            }
            String wanted = byGrain.get(grain == null ? "raw" : grain.toLowerCase());
            return wanted != null ? wanted : byGrain.get("raw");
        }
    }

    /** The binding of one graph, loaded once from the repository's data file. */
    public Binding binding(String graphName) {
        return bindings.computeIfAbsent(graphName, this::loadBinding);
    }

    private Binding loadBinding(String graphName) {
        String resource = "semantic-bindings/" + graphName + ".json";
        try (java.io.InputStream in = SemanticService.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("No fact-graph binding for '" + graphName
                        + "': translation and validation work without one, execution does not (" + resource + ")");
            }
            Map<String, Object> raw = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(in, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
            Map<String, String> dimensions = new LinkedHashMap<>();
            Map<String, String> metrics = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : stringKeyed(raw.get("dimensions")).entrySet()) {
                if (entry.getValue() != null) {
                    dimensions.put(entry.getKey(), String.valueOf(entry.getValue()));
                } else {
                    dimensions.put(entry.getKey(), null);
                }
            }
            for (Map.Entry<String, Object> entry : stringKeyed(raw.get("metrics")).entrySet()) {
                if (entry.getValue() != null) {
                    metrics.put(entry.getKey(), String.valueOf(entry.getValue()));
                }
            }
            return new Binding(graphName, String.valueOf(raw.get("dataGraph")),
                    String.valueOf(raw.get("vertexType")), dimensions, metrics);
        } catch (java.io.IOException broken) {
            throw new IllegalStateException("Cannot read " + resource, broken);
        }
    }

    /** The model of one graph, loaded once and kept until {@link #forget(String)}. */
    public SemanticModel model(String graphName) {
        return models.computeIfAbsent(graphName, this::load);
    }

    /** Drops a cached model (a re-authored schema, or a test). */
    public void forget(String graphName) {
        models.remove(graphName);
    }

    /**
     * Renders the SQL of a semantic query.
     *
     * @param metricId    {@code metric/revenue} or {@code revenue}
     * @param dimensionId {@code dim/region} or {@code region}, or null for a grand total
     */
    public SqlRenderer.Plan translate(String graphName, String metricId, String dimensionId, String grain,
                                      String dialect) {
        return SqlRenderer.render(model(graphName), SemanticModel.qualify("metric", metricId),
                dimensionId == null || dimensionId.isBlank() ? null : SemanticModel.qualify("dim", dimensionId),
                SqlRenderer.Grain.of(grain), SqlRenderer.Dialect.of(dialect));
    }

    /** Runs one traversal against a graph (the loader's own reads, and the executor's later). */
    List<Object> ask(String graphName, String gremlin) {
        return queries.query(graphName, gremlin, Map.of());
    }

    // --- loading ------------------------------------------------------------------------------------

    private SemanticModel load(String graphName) {
        List<Object> rows = ask(graphName, "g.V('Node').elementMap()");
        Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
        for (Object row : rows) {
            Map<String, Object> node = stringKeyed(row);
            String id = string(node.get("id"));
            if (id == null) {
                id = string(node.get("name"));
            }
            if (id != null) {
                nodes.put(id, node);
            }
        }

        Map<String, SemanticModel.Column> columns = new LinkedHashMap<>();
        nodes.forEach((id, node) -> {
            if (id.startsWith("column/")) {
                columns.put(id, new SemanticModel.Column(id, id.replaceFirst("^column/", ""),
                        string(node.get("tableName")), string(node.get("dataType")), string(node.get("columnRole"))));
            }
        });

        // foreign keys: the columns the model marks FK, each pointing at the primary key it references
        Map<String, SemanticModel.ForeignKey> foreignKeys = new LinkedHashMap<>();
        columns.forEach((id, column) -> {
            if (!"FK".equalsIgnoreCase(column.columnRole())) {
                return;
            }
            for (Object target : ask(graphName, "g.V('" + id + "','Node').outE()"
                    + ".filter(relation.equals('references')).otherV().values('name')")) {
                String referenced = string(target);
                if (referenced == null) {
                    continue;
                }
                foreignKeys.put(id, new SemanticModel.ForeignKey(column.name(), column.table(),
                        referenced.replaceFirst("^column/", ""), SemanticModel.tableOfColumnId(referenced)));
            }
        });

        Map<String, SemanticModel.Dimension> dimensions = new LinkedHashMap<>();
        nodes.forEach((id, node) -> {
            if (!id.startsWith("dim/")) {
                return;
            }
            List<Object> values = ask(graphName, "g.V('" + id + "','Node').outE()"
                    + ".filter(relation.equals('dimensionColumn')).otherV().values('name','tableName','dataType')");
            String column = values.size() > 0 ? string(values.get(0)) : null;
            dimensions.put(id, new SemanticModel.Dimension(id, string(node.get("description")),
                    column == null ? null : column.replaceFirst("^column/", ""),
                    values.size() > 1 ? string(values.get(1)) : null,
                    values.size() > 2 ? string(values.get(2)) : "",
                    strings(node.get("alias")), bindingOf(node.get("graphProperty"))));
        });

        Map<String, SemanticModel.Metric> metrics = new LinkedHashMap<>();
        nodes.forEach((id, node) -> {
            if (!id.startsWith("metric/")) {
                return;
            }
            List<String> lineageColumns = new ArrayList<>();
            for (Object used : ask(graphName, "g.V('" + id + "','Node').outE()"
                    + ".filter(relation.equals('usesColumn')).otherV().values('name')")) {
                String name = string(used);
                if (name != null) {
                    lineageColumns.add(name.startsWith("column/") ? name : "column/" + name);
                }
            }
            List<String> tables = new ArrayList<>();
            for (Object table : ask(graphName, "g.V('" + id + "','Node').outE()"
                    + ".filter(relation.equals('computedFrom')).otherV().values('name')")) {
                String name = string(table);
                if (name != null) {
                    tables.add(SemanticModel.shortId(name));
                }
            }
            Set<String> slices = new LinkedHashSet<>(stringsFrom(graphName, id, "groupedBy"));
            List<String> definedBy = stringsFrom(graphName, id, "definedBy");
            Set<String> derivedFrom = new LinkedHashSet<>(stringsFromIncoming(graphName, id, "contributesTo"));
            metrics.put(id, new SemanticModel.Metric(id, string(node.get("description")),
                    string(node.get("expression")), string(node.get("aggFunction")), string(node.get("unit")),
                    string(node.get("metricKind")), strings(node.get("alias")), slices,
                    List.copyOf(lineageColumns), List.copyOf(tables), derivedFrom, definedBy,
                    string(node.get("graphProperty"))));
        });

        List<SemanticModel.ReferenceQuery> referenceQueries = new ArrayList<>();
        nodes.forEach((id, node) -> {
            if (id.startsWith("query/")) {
                Set<String> tables = new LinkedHashSet<>(stringsFrom(graphName, id, "readsTable"));
                referenceQueries.add(new SemanticModel.ReferenceQuery(id,
                        node.get("queryNumber") instanceof Number number ? number.longValue() : 0L,
                        string(node.get("description")), string(node.get("sqlText")), tables));
            }
        });

        LOGGER.info("semantic model of {} loaded: {} metrics, {} dimensions, {} columns, {} reference queries",
                graphName, metrics.size(), dimensions.size(), columns.size(), referenceQueries.size());
        return new SemanticModel(graphName, metrics, dimensions, columns, foreignKeys, referenceQueries, Map.of());
    }

    /** The outgoing targets of one relation, as a list of ids. */
    private List<String> stringsFrom(String graphName, String nodeId, String relation) {
        List<String> found = new ArrayList<>();
        for (Object value : ask(graphName, "g.V('" + nodeId + "','Node').outE()"
                + ".filter(relation.equals('" + relation + "')).otherV().values('name')")) {
            String name = string(value);
            if (name != null) {
                found.add(name);
            }
        }
        return found;
    }

    /** The incoming sources of one relation (who points at this node). */
    private List<String> stringsFromIncoming(String graphName, String nodeId, String relation) {
        List<String> found = new ArrayList<>();
        for (Object value : ask(graphName, "g.V('" + nodeId + "','Node').inE()"
                + ".filter(relation.equals('" + relation + "')).otherV().values('name')")) {
            String name = string(value);
            if (name != null) {
                found.add(name);
            }
        }
        return found;
    }

    /**
     * The fact-graph binding of a dimension.
     *
     * <p>{@code graphProperty} is either a plain property name ({@code region}) or a grain-keyed list
     * ({@code year=orderYear,month=orderMonth}), because a date dimension is a different property per grain. It
     * is data on purpose: the semantic column name and the fact property name are different vocabularies, and a
     * string-matching rule cannot bridge {@code l_returnflag} to {@code returned} without guessing.
     */
    static Map<String, String> bindingOf(Object graphProperty) {
        Map<String, String> binding = new LinkedHashMap<>();
        if (graphProperty == null) {
            return binding;
        }
        String text = String.valueOf(graphProperty).trim();
        if (text.isEmpty()) {
            return binding;
        }
        if (!text.contains("=")) {
            binding.put("raw", text);
            return binding;
        }
        for (String part : text.split(",")) {
            int equals = part.indexOf('=');
            if (equals > 0) {
                binding.put(part.substring(0, equals).trim().toLowerCase(), part.substring(equals + 1).trim());
            }
        }
        return binding;
    }

    static String string(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list) {
            return list.isEmpty() ? null : string(list.get(0));
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    static List<String> strings(Object value) {
        List<String> found = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                String text = string(item);
                if (text != null) {
                    found.add(text);
                }
            }
        } else {
            String text = string(value);
            if (text != null) {
                found.add(text);
            }
        }
        return found;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> stringKeyed(Object row) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (row instanceof Map<?, ?> map) {
            ((Map<Object, Object>) map).forEach((key, value) -> out.put(String.valueOf(key), value));
        }
        return out;
    }
}
