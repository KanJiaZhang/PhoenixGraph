package com.pengcheng.phoenixgraph.http.semantic;

import com.pengcheng.phoenixgraph.http.service.QueryService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs a semantic query on the graph the facts live in, and does the analysis and attribution arithmetic.
 *
 * <p>Two things this class deliberately does <em>not</em> do. It does not generate SQL: the graph is the
 * execution engine here, the SQL is the export ({@link SqlRenderer}), and keeping the two apart is what makes
 * the answer's provenance checkable - the answer comes from a traversal, the SQL from the model. And it does not
 * guess the fact-graph binding: {@link SemanticService.Binding} carries it, a missing binding is a refusal with
 * the reason, and a dimension the binding cannot express is reported as such instead of being dropped.
 *
 * <p>Analysis uses one traversal per period and groups in the engine ({@code group().by(<property>)}); the
 * arithmetic - contributions to a change, shares, concentration, an outlier score - is plain Java over the rows,
 * so it is testable without a graph and cannot invent a number the rows do not contain.
 */
public final class SemanticRunner {

    private SemanticRunner() {
    }

    /** One executed query: the rows, the total, and everything a reader needs to check them. */
    public record Executed(String dataGraph, String vertexType, String measureProperty, String dimensionId,
                           String dimensionProperty, String grain, List<Map<String, Object>> rows, double total,
                           String traversal, List<String> warnings) {

        public Map<String, Double> byDimension() {
            Map<String, Double> values = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                values.put(String.valueOf(row.get("value")), ((Number) row.get("measure")).doubleValue());
            }
            return values;
        }
    }

    /**
     * Executes a semantic query by grouping the fact vertices.
     *
     * <p>The traversal is {@code g.V('<type>').group().by('<property>').by(values('<measure>').sum())}, or
     * {@code by(count())} for a count metric. Whether the engine can express a given binding is checked before
     * the traversal runs, because "the engine answered something else" is the failure mode that looks like data.
     */
    public static Executed execute(QueryService queries, SemanticService service, String modelGraph,
                                   String metricId, String dimensionId, String grain, Integer year) {
        SemanticModel model = service.model(modelGraph);
        SemanticService.Binding binding = service.binding(modelGraph);
        String dataGraph = binding.dataGraph();
        String vertexType = binding.vertexType();

        // The binding is the model's own graphProperty, with the file binding as a fallback for a graph that
        // has not been re-seeded yet. A metric bound nowhere is a refusal, not a guess.
        String metric = model.metric(metricId).graphProperty();
        if (metric == null) {
            metric = binding.metricProperty(metricId);
        }
        if (metric == null) {
            throw new IllegalArgumentException("Metric '" + metricId + "' cannot be executed on graph '"
                    + dataGraph + "': neither the model nor the binding says which property carries it. "
                    + "The SQL is still available from /translate.");
        }
        String property = null;
        if (dimensionId != null) {
            property = model.dimension(dimensionId).graphProperty(grain);
            if (property == null) {
                property = binding.dimensionProperty(dimensionId, grain);
            }
            if (property == null) {
                String why = binding.dimensions().containsKey(dimensionId)
                        ? "the binding declares no property for it at grain '" + grain + "'"
                        : "the binding does not mention it";
                throw new IllegalArgumentException("Cannot group by '" + dimensionId + "' on graph '"
                        + dataGraph + "': " + why + ". The SQL is still available from /translate.");
            }
        }
        String measure = "count".equals(metric)
                ? "count()"
                : "values('" + metric + "').sum()";
        StringBuilder traversal = new StringBuilder("g.V('").append(vertexType).append("')");
        // A year is a filter *on the fact vertices*, not a post-filter on the grouped rows: grouping first and
        // dropping rows afterwards sums every year and returns a number that looks entirely plausible and is
        // simply wrong. This is not hypothetical - region totals came back at about 194M for a question about
        // 1996 (the verified figure is 98,879,432.89), because the traversal grouped both years and the row
        // filter had nothing left to remove. The date property is the model's own (year=orderYear), so the
        // filter and the grain can never disagree about which column carries the year.
        if (year != null) {
            String dateProperty = yearProperty(model, binding);
            if (dateProperty != null) {
                traversal.append(".has('").append(dateProperty).append("', ").append(year).append(")");
            }
        }
        if (property == null) {
            traversal.append(".").append(measure);
        } else {
            traversal.append(".group().by('").append(property).append("').by(").append(measure).append(")");
        }
        List<Object> answer = queries.query(dataGraph, traversal.toString(), Map.of());

        List<Map<String, Object>> rows = new ArrayList<>();
        double total = 0;
        if (property == null) {
            // a grand total: the engine answers with the scalar the reducer produced
            double value = scalar(answer);
            rows.add(row("total", value));
            total = value;
        } else {
            Map<String, Object> grouped = answer.isEmpty() ? Map.of() : SemanticService.stringKeyed(answer.get(0));
            for (Map.Entry<String, Object> entry : grouped.entrySet()) {
                double value = entry.getValue() instanceof Number number ? number.doubleValue() : 0d;
                rows.add(row(entry.getKey(), value));
                total += value;
            }
            rows.sort((left, right) -> Double.compare(((Number) right.get("measure")).doubleValue(),
                    ((Number) left.get("measure")).doubleValue()));
        }
        List<String> warnings = new ArrayList<>();
        if (year != null && rows.isEmpty()) {
            // A zero has two meanings and the caller cannot tell them apart: "no revenue in that year" and "no
            // data for that year". The ledger recorded that as a gap; saying which one it is costs one line, and
            // a business reading 0 as a collapse is exactly the kind of wrong answer this layer exists to stop.
            String dateProperty = yearProperty(model, binding);
            warnings.add("no rows for " + year + " on graph '" + dataGraph + "': the data does not cover "
                    + "that year (the traversal filtered " + (dateProperty == null ? "the year" : dateProperty) + ")");
        }
        if (dimensionId != null && !"raw".equals(grain) && !isDateDimension(model, dimensionId)) {
            // the grain was applied to a dimension that is not a date: the traversal groups by the property the
            // binding names, so this is the *value*, not a year - and a caller who asked for a year should know
            warnings.add("grain '" + grain + "' does not apply to " + dimensionId
                    + ": it is not a date dimension, so the values are grouped unchanged");
        }
        return new Executed(dataGraph, vertexType, metric, dimensionId, property, grain,
                List.copyOf(rows), total, traversal.toString(), List.copyOf(warnings));
    }

    /** The fact property that carries the year, from the model's date dimension (the one with a year grain). */
    private static String yearProperty(SemanticModel model, SemanticService.Binding binding) {
        for (SemanticModel.Dimension dimension : model.dimensions().values()) {
            if (dimension.graphPropertyByGrain().containsKey("year")) {
                return dimension.graphPropertyByGrain().get("year");
            }
        }
        for (Map.Entry<String, String> entry : binding.dimensions().entrySet()) {
            String declared = entry.getValue();
            if (declared == null || !declared.contains("=")) {
                continue;
            }
            for (String part : declared.split(",")) {
                int equals = part.indexOf('=');
                if (equals > 0 && part.substring(0, equals).trim().equalsIgnoreCase("year")) {
                    return part.substring(equals + 1).trim();
                }
            }
        }
        return null;
    }

    /** Whether a dimension is the model's date dimension: its binding carries an explicit year grain. */
    private static boolean isDateDimension(SemanticModel model, String dimensionId) {
        SemanticModel.Dimension dimension = model.dimension(dimensionId);
        return dimension != null && dimension.graphPropertyByGrain().containsKey("year");
    }

    private static Map<String, Object> row(String value, double measure) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("value", value);
        row.put("measure", measure);
        return row;
    }

    private static double scalar(List<Object> answer) {
        if (answer.isEmpty()) {
            return 0d;
        }
        Object first = answer.get(0);
        if (first instanceof Number number) {
            return number.doubleValue();
        }
        if (first instanceof Map<?, ?> map && map.size() == 1) {
            Object only = map.values().iterator().next();
            return only instanceof Number number ? number.doubleValue() : 0d;
        }
        return 0d;
    }

    /**
     * Analysis of a metric over two periods: the change, the movers, the concentration and the outlier score.
     *
     * <p>Everything here is arithmetic on the two row sets. That is the point: an analysis that cannot be
     * recomputed from its own numbers is not an analysis a business should act on, so the answer carries the
     * rows it was computed from.
     */
    public static Map<String, Object> analyze(Executed from, Executed to, String fromLabel, String toLabel) {
        Map<String, Double> before = from.byDimension();
        Map<String, Double> after = to.byDimension();
        Set<String> keys = new LinkedHashSet<>(before.keySet());
        keys.addAll(after.keySet());

        List<Map<String, Object>> movers = new ArrayList<>();
        for (String key : keys) {
            double was = before.getOrDefault(key, 0d);
            double now = after.getOrDefault(key, 0d);
            Map<String, Object> mover = new LinkedHashMap<>();
            mover.put("value", key);
            mover.put(fromLabel, was);
            mover.put(toLabel, now);
            mover.put("delta", now - was);
            mover.put("deltaPercent", was == 0d ? null : Math.round((now - was) / was * 10000d) / 100d);
            mover.put("shareBefore", from.total() == 0d ? null : Math.round(was / from.total() * 10000d) / 100d);
            mover.put("shareAfter", to.total() == 0d ? null : Math.round(now / to.total() * 10000d) / 100d);
            movers.add(mover);
        }
        movers.sort((left, right) -> Double.compare(((Number) right.get("delta")).doubleValue(),
                ((Number) left.get("delta")).doubleValue()));

        double delta = to.total() - from.total();
        double percent = from.total() == 0d ? Double.NaN : delta / from.total() * 100d;
        Map<String, Object> findings = new LinkedHashMap<>();
        findings.put("totalBefore", round(from.total()));
        findings.put("totalAfter", round(to.total()));
        findings.put("delta", round(delta));
        findings.put("deltaPercent", Double.isNaN(percent) ? null : Math.round(percent * 100d) / 100d);
        findings.put("direction", delta > 0 ? "up" : delta < 0 ? "down" : "flat");
        findings.put("topMover", movers.isEmpty() ? null : movers.get(0).get("value"));
        findings.put("concentrationAfter", concentration(movers, toLabel));
        findings.put("outlierScore", outlierScore(movers));
        findings.put("movers", movers);
        return findings;
    }

    /**
     * Attribution: how much of a change each dimension value accounts for.
     *
     * <p>Additive decomposition for an additive measure - the contributions sum to the change by construction, and
     * the answer states that sum so a reader can check it. It is <b>descriptive</b>: the numbers say which slice
     * moved and by how much, not why, and the answer says so rather than implying a cause.
     */
    public static Map<String, Object> attribute(Executed from, Executed to, String fromLabel, String toLabel) {
        Map<String, Double> before = from.byDimension();
        Map<String, Double> after = to.byDimension();
        Set<String> keys = new LinkedHashSet<>(before.keySet());
        keys.addAll(after.keySet());
        double delta = to.total() - from.total();

        List<Map<String, Object>> contributions = new ArrayList<>();
        double accounted = 0;
        for (String key : keys) {
            double was = before.getOrDefault(key, 0d);
            double now = after.getOrDefault(key, 0d);
            double change = now - was;
            accounted += change;
            Map<String, Object> contribution = new LinkedHashMap<>();
            contribution.put("value", key);
            contribution.put(fromLabel, was);
            contribution.put(toLabel, now);
            contribution.put("delta", round(change));
            contribution.put("shareOfChange", delta == 0d ? null : Math.round(change / delta * 10000d) / 100d);
            contributions.add(contribution);
        }
        contributions.sort((left, right) -> Double.compare(Math.abs(((Number) right.get("delta")).doubleValue()),
                Math.abs(((Number) left.get("delta")).doubleValue())));

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("metric", from.measureProperty());
        answer.put("dimension", from.dimensionId());
        answer.put("from", fromLabel);
        answer.put("to", toLabel);
        answer.put("totalBefore", round(from.total()));
        answer.put("totalAfter", round(to.total()));
        answer.put("delta", round(delta));
        answer.put("contributions", contributions);
        answer.put("contributionsSum", round(accounted));
        answer.put("decompositionAddsUp", Math.abs(accounted - delta) < 0.01);
        answer.put("limits", List.of("Descriptive attribution: which slice moved and by how much, computed by "
                + "decomposition over the dimension given. It is not a causal claim, and a slice that explains "
                + "most of a change is not necessarily what caused it."));
        return answer;
    }

    /** The share of the largest slice, as a one-number concentration reading. */
    static double concentration(List<Map<String, Object>> movers, String label) {
        double total = movers.stream().mapToDouble(mover -> ((Number) mover.get(label)).doubleValue()).sum();
        double largest = movers.stream().mapToDouble(mover -> ((Number) mover.get(label)).doubleValue()).max().orElse(0d);
        return total == 0d ? 0d : Math.round(largest / total * 10000d) / 100d;
    }

    /**
     * How unusual the largest mover is, as a robust z-score (median absolute deviation).
     *
     * <p>MAD rather than standard deviation: with five regions one huge mover inflates a standard deviation enough
     * to hide itself, which is the case this number exists for. Fewer than four slices means there is no
     * distribution to be unusual in, and the answer says that instead of returning a number.
     */
    static Object outlierScore(List<Map<String, Object>> movers) {
        if (movers.size() < 4) {
            return null;
        }
        double[] deltas = movers.stream().mapToDouble(mover -> ((Number) mover.get("delta")).doubleValue())
                .sorted().toArray();
        double median = deltas[deltas.length / 2];
        double[] deviations = new double[deltas.length];
        for (int i = 0; i < deltas.length; i += 1) {
            deviations[i] = Math.abs(deltas[i] - median);
        }
        java.util.Arrays.sort(deviations);
        double mad = deviations[deviations.length / 2];
        if (mad == 0d) {
            return null;
        }
        double largest = Math.abs(deltas[deltas.length - 1] - median) > Math.abs(deltas[0] - median)
                ? deltas[deltas.length - 1] : deltas[0];
        return Math.round(0.6745d * (largest - median) / mad * 100d) / 100d;
    }

    static double round(double value) {
        return Math.round(value * 100d) / 100d;
    }
}
