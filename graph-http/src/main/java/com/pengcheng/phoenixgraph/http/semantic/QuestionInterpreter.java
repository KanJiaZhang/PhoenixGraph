package com.pengcheng.phoenixgraph.http.semantic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a question into a semantic query - deterministically, and without a model.
 *
 * <p>The market's data agents put an LLM here and then spend their time trying to make its output checkable. This
 * one inverts that: the vocabulary comes from the semantic model (the {@code alias} property on every metric and
 * dimension, which the model already carries in Chinese), the patterns are a small explicit lexicon, and anything
 * the lexicon cannot place is a <b>refusal</b> with the reason rather than a guess. The consequence is that the
 * same question always produces the same plan, which is what makes the answer auditable - and it means an LLM can
 * still be the front-end later, because a plan is a reviewable artefact: it proposes, the confirmation gate
 * (a token, not a conversation) decides.
 *
 * <p>What this understands, by design and no more: which metric (by name or synonym), which dimension (only the
 * ones the model says may slice that metric), the grain (year/quarter/month or none), an explicit year range, a
 * value filter ({@code region=ASIA}), and a "top N" or "sorted" request. Everything else is refused with the
 * vocabulary, which is the second-best answer and honest.
 */
public final class QuestionInterpreter {

    /** What kind of answer the question wants. */
    public enum Kind { ASK, ANALYZE, ATTRIBUTE }

    /**
     * The interpretation: the semantic query, why it was chosen, and what could not be placed.
     *
     * @param refusal non-null when the question cannot be answered, with the reason and the vocabulary
     */
    public record Interpretation(Kind kind, String metricId, String dimensionId, String grain, Integer fromYear,
                                 Integer toYear, String filterProperty, String filterValue, Integer topN,
                                 boolean descending, double confidence, List<String> matchedTerms,
                                 List<String> warnings, String refusal, String question) {
    }

    private QuestionInterpreter() {
    }

    private static final Map<String, String> GRAINS = grains();

    /** The grain words, as a map built in code: {@code Map.of} takes at most ten pairs. */
    private static Map<String, String> grains() {
        Map<String, String> grains = new LinkedHashMap<>();
        for (String word : List.of("年", "年度", "year", "yearly", "annual")) {
            grains.put(word, "year");
        }
        for (String word : List.of("季", "季度", "quarter", "quarterly")) {
            grains.put(word, "quarter");
        }
        for (String word : List.of("月", "月度", "month", "monthly")) {
            grains.put(word, "month");
        }
        return grains;
    }

    private static final List<String> ATTRIBUTION_WORDS = List.of("为什么", "原因", "归因", "why", "attribute",
            "驱动", "contribution", "谁贡献");
    private static final List<String> ANALYSIS_WORDS = List.of("分析", "趋势", "变化", "增长", "下降", "对比",
            "环比", "同比", "异常", "analyze", "trend", "change", "growth", "compare", "anomaly");

    /** Interprets one question against one graph's model. */
    public static Interpretation interpret(SemanticModel model, SemanticService.Binding binding, String question) {
        String text = question == null ? "" : question.trim();
        if (text.isEmpty()) {
            return refusal(model, question, Kind.ASK, "the question is empty; give a metric and optionally a "
                    + "dimension, e.g. 1996 年各地区营收");
        }
        String lowered = text.toLowerCase(Locale.ROOT);
        Map<String, List<String>> synonyms = synonymsFromBinding(model.graph());

        // ---- the metric: the longest synonym wins, so 净收入 beats 收入 ----
        List<String> matched = new ArrayList<>();
        String metricId = null;
        int bestLength = 0;
        for (SemanticModel.Metric metric : model.metrics().values()) {
            for (String term : termsOf(metric.id(), merged(metric.synonyms(), synonyms.get(metric.id())))) {
                if (term.length() > bestLength && lowered.contains(term.toLowerCase(Locale.ROOT))) {
                    bestLength = term.length();
                    metricId = metric.id();
                }
            }
        }
        if (metricId == null) {
            return refusal(model, question, kindOf(lowered),
                    "no metric in this graph matches the question; the model holds "
                            + names(model.metrics().keySet(), 6));
        }
        matched.add(shortestTerm(metricId, model.metric(metricId).synonyms(), lowered));

        // ---- the dimension: only the ones the model says may slice this metric ----
        SemanticModel.Metric metric = model.metric(metricId);
        String dimensionId = null;
        int bestDimension = 0;
        for (String candidate : metric.dimensions()) {
            SemanticModel.Dimension dimension = model.dimension(candidate);
            if (dimension == null) {
                continue;
            }
            for (String term : termsOf(candidate, merged(dimension.synonyms(), synonyms.get(candidate)))) {
                if (term.length() > bestDimension && lowered.contains(term.toLowerCase(Locale.ROOT))) {
                    bestDimension = term.length();
                    dimensionId = candidate;
                }
            }
        }
        List<String> warnings = new ArrayList<>();
        if (dimensionId == null) {
            // a dimension that exists but may not slice this metric is worth saying out loud: the caller asked a
            // question the model cannot answer *that way*, not one it cannot answer at all
            String mentioned = null;
            for (SemanticModel.Dimension dimension : model.dimensions().values()) {
                for (String term : termsOf(dimension.id(), merged(dimension.synonyms(), synonyms.get(dimension.id())))) {
                    if (lowered.contains(term.toLowerCase(Locale.ROOT))) {
                        mentioned = dimension.id();
                        break;
                    }
                }
                if (mentioned != null) {
                    break;
                }
            }
            if (mentioned != null) {
                // The model says this dimension exists but may not slice this metric. Answering the grand total
                // anyway would silently answer a different question - the caller asked for a breakdown by that
                // dimension and would receive one number - so it is refused with the dimensions that do work
                // (docs/DATA-AGENT.md §6, rule 2). The warning is kept in the answer for the model endpoint.
                return refusal(model, question, kindOf(lowered), mentioned + " may not slice " + metricId
                        + "; the model allows " + names(metric.dimensions(), 8));
            }
            if (mentionsBreakdown(lowered)) {
                // The same failure with a word the model has never heard: "1996 年各渠道营收" asks for a breakdown
                // by a dimension nobody defined, and answering the grand total returns a right number to a
                // question nobody asked. The ledger recorded that behaviour as a gap; it is a refusal now.
                return refusal(model, question, kindOf(lowered), "the question asks for a breakdown but names no "
                        + "dimension this graph holds; the dimensions that may slice " + metricId + " are "
                        + names(metric.dimensions(), 8));
            }
        } else {
            matched.add(shortestTerm(dimensionId, model.dimension(dimensionId).synonyms(), lowered));
        }

        // ---- grain, years, filter and top N ----
        String grain = "raw";
        for (Map.Entry<String, String> entry : GRAINS.entrySet()) {
            if (lowered.contains(entry.getKey())) {
                grain = entry.getValue();
                break;
            }
        }
        boolean dateDimension = dimensionId != null && "order_date".equals(SemanticModel.shortId(dimensionId));
        if (grain.equals("raw") && dateDimension) {
            grain = "year"; // a date dimension without a grain is a year here: the fact graph has no raw date
            warnings.add("no grain was asked for, so the date dimension is grouped by year");
        }
        // "1996 年" is a year, not a request to group by year. A bare 年/月/季 sitting next to a four-digit year
        // is the *filter* the caller wrote, while 年度/月度/季度 is an explicit grain request; treating the former
        // as a grain refused questions the model can answer perfectly well (that is how this was found). Only a
        // non-date dimension is affected: on a date dimension the year is both the grain and the filter.
        boolean explicitGrainWord = lowered.contains("年度") || lowered.contains("月度") || lowered.contains("季度")
                || lowered.contains("yearly") || lowered.contains("monthly") || lowered.contains("quarterly");
        if (!explicitGrainWord && !dateDimension && lowered.matches(".*\\d{4}\\s*[年月季].*")) {
            grain = "raw";
        }
        if (!"raw".equals(grain) && !dateDimension) {
            return refusal(model, question, kindOf(lowered),
                    "grain '" + grain + "' was asked for but the question groups by "
                            + (dimensionId == null ? "no dimension" : dimensionId)
                            + ", which is not a date; ask for a date dimension or drop the grain");
        }

        List<Integer> years = new ArrayList<>();
        for (String token : lowered.split("[^0-9]+")) {
            if (token.length() == 4) {
                try {
                    int year = Integer.parseInt(token);
                    if (year >= 1900 && year <= 2100) {
                        years.add(year);
                    }
                } catch (NumberFormatException ignore) {
                    // a four-digit token that is not a number is not a year
                }
            }
        }
        Integer fromYear = years.isEmpty() ? null : years.get(0);
        Integer toYear = years.size() > 1 ? years.get(years.size() - 1) : null;
        if (!years.isEmpty() && !dateDimension && dimensionId != null) {
            warnings.add("the question names a year but groups by " + dimensionId
                    + "; the year filter is applied to the period dimension when there is one");
        }
        if (years.isEmpty() && dateDimension) {
            return refusal(model, question, kindOf(lowered),
                    "the date dimension needs an explicit year (1995, 1996, ...): this deployment has no clock "
                            + "relative to the data, and 'this year' would be a guess");
        }

        Integer topN = null;
        for (String token : lowered.split("[^0-9]+")) {
            if (token.length() <= 2) {
                try {
                    int candidate = Integer.parseInt(token);
                    if (candidate > 0 && candidate <= 50 && (lowered.contains("top") || lowered.contains("前")
                            || lowered.contains("最高") || lowered.contains("最大的"))) {
                        topN = candidate;
                    }
                } catch (NumberFormatException ignore) {
                    // not a count
                }
            }
        }
        boolean descending = !lowered.contains("最低") && !lowered.contains("最少") && !lowered.contains("asc");

        // ---- a value filter: dimension=value, which is how a caller pins one slice ----
        String filterProperty = null;
        String filterValue = null;
        int equals = text.indexOf('=');
        if (equals > 0) {
            String left = text.substring(0, equals).trim();
            String right = text.substring(equals + 1).trim().split("[\\s,，]")[0];
            for (SemanticModel.Dimension dimension : model.dimensions().values()) {
                if (left.equalsIgnoreCase(SemanticModel.shortId(dimension.id()))
                        || dimension.synonyms().stream().anyMatch(left::equalsIgnoreCase)) {
                    filterProperty = dimension.id();
                    filterValue = right;
                    break;
                }
            }
            if (filterProperty == null) {
                warnings.add("the filter '" + left + "=" + right + "' does not name a dimension the model holds");
            }
        }

        Kind kind = kindOf(lowered);
        if (kind == Kind.ATTRIBUTE && (years.size() < 2 || dimensionId == null)) {
            return refusal(model, question, kind,
                    "an attribution needs two years and a dimension to decompose over, e.g. "
                            + "'1996 年各地区营收为什么变化' (from, to, dimension)");
        }
        double confidence = 0.6d + (matched.size() >= 2 ? 0.25d : 0d) + (warnings.isEmpty() ? 0.15d : 0d);
        if (topN != null && dimensionId == null) {
            return refusal(model, question, kind,
                    "'top " + topN + "' needs a dimension to rank (" + names(metric.dimensions(), 8) + ")");
        }
        return new Interpretation(kind, metricId, dimensionId, grain, fromYear, toYear, filterProperty, filterValue,
                topN, descending, Math.min(1d, Math.round(confidence * 100d) / 100d), List.copyOf(matched),
                List.copyOf(warnings), null, question);
    }

    private static Kind kindOf(String lowered) {
        for (String word : ATTRIBUTION_WORDS) {
            if (lowered.contains(word)) {
                return Kind.ATTRIBUTE;
            }
        }
        for (String word : ANALYSIS_WORDS) {
            if (lowered.contains(word)) {
                return Kind.ANALYZE;
            }
        }
        return Kind.ASK;
    }

    /** The terms a question may use for a metric or dimension: its short name, its id and its synonyms. */
    static List<String> termsOf(String id, List<String> synonyms) {
        List<String> terms = new ArrayList<>();
        terms.add(id);
        terms.add(SemanticModel.shortId(id));
        terms.addAll(synonyms);
        return terms;
    }

    /**
     * The words a question may use, from the binding file, merged with the model's own aliases.
     *
     * <p>The synonyms now live on the graph's own nodes ({@code alias}, written by the dataset), and this
     * returns empty for a re-seeded graph. The file path is kept as a fallback for a graph written before the
     * migration (docs/DATA-AGENT.md §3).
     */
    static Map<String, List<String>> synonymsFromBinding(String graph) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        String resource = "semantic-bindings/" + graph + ".json";
        try (java.io.InputStream in = QuestionInterpreter.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                return out;
            }
            Map<String, Object> raw = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(in, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
            if (raw.get("synonyms") instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    List<String> words = new ArrayList<>();
                    if (entry.getValue() instanceof List<?> list) {
                        for (Object word : list) {
                            words.add(String.valueOf(word).toLowerCase(Locale.ROOT));
                        }
                    }
                    out.put(String.valueOf(entry.getKey()), List.copyOf(words));
                }
            }
        } catch (java.io.IOException | RuntimeException ignored) {
            // a smaller vocabulary is not a failure: the model's own terms still work
        }
        return out;
    }

    /** The model's aliases plus the binding's, without duplicates. */
    static List<String> merged(List<String> fromModel, List<String> fromBinding) {
        List<String> all = new ArrayList<>(fromModel == null ? List.of() : fromModel);
        if (fromBinding != null) {
            for (String word : fromBinding) {
                if (!all.contains(word)) {
                    all.add(word);
                }
            }
        }
        return all;
    }

    /**
     * True when the question asks for a breakdown rather than a single number.
     *
     * <p>"各/按/每个/分别/by/breakdown/前 N" are the words that say "split this by something". A bare 分 is not
     * one of them: it is inside 分析, and refusing every question that contains 分析 for lack of a dimension would
     * turn a refinement into a wall.
     */
    static boolean mentionsBreakdown(String lowered) {
        return lowered.contains("各") || lowered.contains("按") || lowered.contains("每个")
                || lowered.contains("分别") || lowered.contains("breakdown") || lowered.contains(" by ")
                || lowered.contains("前 ") || lowered.contains("排名");
    }

    /** The term that actually matched, for the answer's "why this plan" record. */
    private static String shortestTerm(String id, List<String> synonyms, String lowered) {
        String best = SemanticModel.shortId(id);
        for (String term : termsOf(id, synonyms)) {
            if (lowered.contains(term.toLowerCase(Locale.ROOT)) && term.length() < best.length()) {
                best = term;
            }
        }
        return best;
    }

    private static Interpretation refusal(SemanticModel model, String question, Kind kind, String reason) {
        return new Interpretation(kind, null, null, "raw", null, null, null, null, null, true, 0d, List.of(), List.of(),
                reason, question);
    }

    private static String names(Iterable<String> ids, int limit) {
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (String id : ids) {
            if (count >= limit) {
                out.append(", ...");
                break;
            }
            out.append(count == 0 ? "" : ", ").append(SemanticModel.shortId(id));
            count += 1;
        }
        return out.toString();
    }

    /** The plan a client confirms: everything the run will use, in one hashable map. */
    public static Map<String, Object> planOf(Interpretation interpretation, String modelGraph, String dataGraph) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("kind", interpretation.kind().name());
        plan.put("graph", modelGraph);
        plan.put("dataGraph", dataGraph);
        plan.put("metric", interpretation.metricId());
        plan.put("dimension", interpretation.dimensionId());
        plan.put("grain", interpretation.grain());
        plan.put("fromYear", interpretation.fromYear());
        plan.put("toYear", interpretation.toYear());
        plan.put("filter", interpretation.filterProperty() == null ? null
                : interpretation.filterProperty() + "=" + interpretation.filterValue());
        plan.put("topN", interpretation.topN());
        plan.put("descending", interpretation.descending());
        return plan;
    }
}
